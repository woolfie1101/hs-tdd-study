package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

@Service
public class PointService {

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;

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
        if (amount <= 0) {
            throw new IllegalArgumentException("Charge amount must be positive");
        }

        // 사용자 포인트 조회
        UserPoint userPoint = userPointTable.selectById(userId);

        // 새 포인트 계산
        long newPointAmount = userPoint.point() + amount;

        // 사용자 포인트 업데이트
        long currentTimeMillis = System.currentTimeMillis();
        UserPoint updatedUserPoint = userPointTable.insertOrUpdate(userId, newPointAmount);

        // 포인트 내역 추가
        pointHistoryTable.insert(userId, amount, TransactionType.CHARGE, currentTimeMillis);

        return updatedUserPoint;
    }
}
