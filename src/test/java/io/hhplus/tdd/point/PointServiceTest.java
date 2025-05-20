package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PointServiceTest {

    @Mock
    private UserPointTable userPointTable;

    @Mock
    private PointHistoryTable pointHistoryTable;

    private PointService pointService;

    @BeforeEach
    void setUp() {
        pointService = new PointService(userPointTable, pointHistoryTable);
    }

    @Test
    @DisplayName("포인트 충전 시 사용자의 포인트가 정상적으로 증가해야 한다")
    void 포인트충전_정상케이스() {
        // 테스트 설명: 포인트 충전 기능의 기본 동작을 검증하는 테스트입니다.
        // 사용자가 포인트를 충전하면 해당 금액만큼 포인트가 증가해야 하고,
        // 포인트 충전 내역이 히스토리에 정확히 기록되어야 합니다.

        // given
        long userId = 1L;
        long chargeAmount = 1000L;
        // 초기 포인트는 0으로 설정합니다.
        UserPoint initialUserPoint = new UserPoint(userId, 0L, System.currentTimeMillis());
        // 충전 후 예상되는 포인트는 chargeAmount 만큼 증가한 값입니다.
        UserPoint expectedUserPoint = new UserPoint(userId, chargeAmount, System.currentTimeMillis());

        // 1. 포인트 조회에 대한 가짜 응답
        when(userPointTable.selectById(userId)).thenReturn(initialUserPoint);
        // 2. 포인트 업데이트(충전)에 대한 가짜 응답
        when(userPointTable.insertOrUpdate(eq(userId), eq(chargeAmount))).thenReturn(expectedUserPoint);

        // when
        // 포인트 충전 메서드 호출
        UserPoint userPoint = pointService.chargePoint(userId, chargeAmount);

        // then
        // 1. 포인트 충전 후 사용자 포인트가 예상한 값과 일치하는지 검증
        assertThat(userPoint.point()).isEqualTo(chargeAmount);
        // 2. 포인트 충전 후 사용자 ID가 예상한 값과 일치하는지 검증
        assertThat(userPoint.id()).isEqualTo(userId);
        // 3. 포인트 충전 내역이 올바르게 기록되었는지 검증
        verify(pointHistoryTable).insert(eq(userId), eq(chargeAmount), eq(TransactionType.CHARGE), anyLong());
    }
}