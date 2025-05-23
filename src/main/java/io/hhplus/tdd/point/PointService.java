package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Service
public class PointService {

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;
    private final ConcurrentHashMap<Long, Lock> userLocks = new ConcurrentHashMap<>();

    public PointService(UserPointTable userPointTable, PointHistoryTable pointHistoryTable) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
    }

    /**
     * 포인트 충전
     * 
     * @param userId 유저 아이디
     * @param amount 충전할 포인트
     * @return the updated UserPoint
     */
    public UserPoint chargePoint(long userId, long amount) {
        // 유효성 검사(충전 금액이 0 이하인 경우)
        if (amount <= 0) {
            throw new IllegalArgumentException("Charge amount must be positive");
        }

        // 헬퍼 메소드를 사용하여 락 로직 처리
        return executeWithLock(
            userId,
            userPoint -> userPoint.point() + amount, // 충전 작업: 현재 포인트 + 충전 금액
            TransactionType.CHARGE,
            amount
        );
    }


    /**
     * 포인트 사용
     *
     * @param userId 유저 아이디
     * @param amount 사용할 포인트
     * @return the updated UserPoint
     */
    public UserPoint usePoint(long userId, long amount) {
        // 유효성 검사(사용 금액이 0 이하인 경우)
        if (amount <= 0) {
            throw new IllegalArgumentException("Use amount must be positive");
        }

        // 헬퍼 메소드를 사용하여 락 로직 처리
        // 잔액 부족 검사는 executeWithLock 내부에서 수행됨
        return executeWithLock(
            userId,
            userPoint -> userPoint.point() - amount, // 사용 작업: 현재 포인트 - 사용 금액
            TransactionType.USE,
            amount
        );
    }

    /**
     * 포인트 조회
     *
     * @param userId 유저 아이디
     * @return the UserPoint
     */
    public UserPoint getPoint(long userId) {
        return userPointTable.selectById(userId);
    }

    /**
     * 포인트 내역 조회
     *
     * @param userId 유저 아이디
     * @return 유저의 포인트 충전/사용 내역 목록
     */
    public List<PointHistory> getPointHistory(long userId) {
        return pointHistoryTable.selectAllByUserId(userId);
    }

    /**
     * 락을 사용하여 포인트 관련 작업을 수행하는 헬퍼 메소드
     * 
     * @param userId 유저 아이디
     * @param operation 락 내부에서 실행할 작업
     * @param transactionType 트랜잭션 타입 (충전 또는 사용)
     * @param amount 포인트 양
     * @return 업데이트된 UserPoint
     * @throws RuntimeException 락 획득 타임아웃 또는 작업 실패 시
     */
    private UserPoint executeWithLock(long userId, Function<UserPoint, Long> operation, 
                                     TransactionType transactionType, long amount) {
        // 사용자 포인트 미리 조회 (락 밖에서)
        UserPoint userPoint = userPointTable.selectById(userId);

        // 작업 수행 가능 여부 미리 확인 (락 밖에서)
        // 포인트 사용 시 잔액 부족 여부 확인
        if (transactionType == TransactionType.USE && userPoint.point() < amount) {
            throw new IllegalArgumentException("Insufficient point balance");
        }

        // 사용자별 락 획득 (없으면 새로 생성)
        Lock userLock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());

        UserPoint updatedUserPoint = null;
        long currentTimeMillis = System.currentTimeMillis();
        boolean lockAcquired = false;

        try {
            // 락 획득 시도 (1초 타임아웃)
            lockAcquired = userLock.tryLock(1, TimeUnit.SECONDS);
            if (!lockAcquired) {
                throw new RuntimeException("Failed to acquire lock for user " + userId + " after timeout");
            }

            // 락 획득 후 최신 상태 다시 조회
            userPoint = userPointTable.selectById(userId);

            // 작업 수행 및 새 포인트 계산
            long newPointAmount = operation.apply(userPoint);

            // 사용자 포인트 업데이트
            currentTimeMillis = System.currentTimeMillis();
            updatedUserPoint = userPointTable.insertOrUpdate(userId, newPointAmount);

            // 락 맵에서 불필요한 락 제거 (사용자당 최대 1개의 락만 유지)
            if (userLocks.size() > 1000) { // 임의의 임계값
                userLocks.clear();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock acquisition was interrupted", e);
        } finally {
            // 락 해제 (finally 블록에서 항상 실행되도록)
            if (lockAcquired) {
                userLock.unlock();
            }

            // 포인트 내역 추가 (락 밖에서 수행 - 동시성 이슈 없음)
            if (updatedUserPoint != null) {
                pointHistoryTable.insert(userId, amount, transactionType, currentTimeMillis);
            }
        }

        if (updatedUserPoint == null) {
            throw new RuntimeException("Failed to update user point");
        }

        return updatedUserPoint;
    }
}
