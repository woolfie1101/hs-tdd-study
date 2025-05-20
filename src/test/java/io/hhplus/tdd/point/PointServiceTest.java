package io.hhplus.tdd.point;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PointServiceTest {

    @Test
    @DisplayName("포인트 충전 시 사용자의 포인트가 정상적으로 증가해야 한다")
    void chargePoint_ShouldIncreaseUserPoint() {
        // given
        long userId = 1L;
        long chargeAmount = 1000L;

        // when
        UserPoint userPoint = pointService.chargePoint(userId, chargeAmount);

        // then
        assertThat(userPoint.point()).isEqualTo(1000L);
        assertThat(userPoint.id()).isEqualTo(userId);
    }

}