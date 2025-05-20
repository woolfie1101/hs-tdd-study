package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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

        // 사용자별 락 획득 (없으면 새로 생성)
        Lock userLock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());

        UserPoint updatedUserPoint;
        long currentTimeMillis;

        // 락 획득
        userLock.lock();
        try {
            // 사용자 포인트 조회
            UserPoint userPoint = userPointTable.selectById(userId);

            // 새 포인트 계산
            long newPointAmount = userPoint.point() + amount;

            // 사용자 포인트 업데이트
            currentTimeMillis = System.currentTimeMillis();
            updatedUserPoint = userPointTable.insertOrUpdate(userId, newPointAmount);
        } finally {
            // 락 해제 (finally 블록에서 항상 실행되도록)
            userLock.unlock();
        }

        // 포인트 내역 추가 (락 밖에서 수행 - 동시성 이슈 없음)
        pointHistoryTable.insert(userId, amount, TransactionType.CHARGE, currentTimeMillis);

        return updatedUserPoint;
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

        // 사용자별 락 획득 (없으면 새로 생성)
        Lock userLock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());

        UserPoint updatedUserPoint;
        long currentTimeMillis;

        // 락 획득
        userLock.lock();
        try {
            // 사용자 포인트 조회
            UserPoint userPoint = userPointTable.selectById(userId);

            // 잔액 부족 검사
            if (userPoint.point() < amount) {
                throw new IllegalArgumentException("Insufficient point balance");
            }

            // 새 포인트 계산
            long newPointAmount = userPoint.point() - amount;

            // 사용자 포인트 업데이트
            currentTimeMillis = System.currentTimeMillis();
            updatedUserPoint = userPointTable.insertOrUpdate(userId, newPointAmount);
        } finally {
            // 락 해제 (finally 블록에서 항상 실행되도록)
            userLock.unlock();
        }

        // 포인트 내역 추가 (락 밖에서 수행 - 동시성 이슈 없음)
        pointHistoryTable.insert(userId, amount, TransactionType.USE, currentTimeMillis);

        return updatedUserPoint;
    }
}
