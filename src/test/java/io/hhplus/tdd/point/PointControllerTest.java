package io.hhplus.tdd.point;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포인트 컨트롤러 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
public class PointControllerTest {

    @Mock
    private PointService pointService;

    @InjectMocks
    private PointController pointController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(pointController).build();
    }

    @Test
    @DisplayName("사용자 포인트 조회 테스트")
    void 사용자_포인트_조회() throws Exception {
        // given
        long userId = 1L;
        long pointAmount = 1000L;
        long updateMillis = System.currentTimeMillis();
        UserPoint userPoint = new UserPoint(userId, pointAmount, updateMillis);

        // when
        when(pointService.getPoint(userId)).thenReturn(userPoint);

        // then
        mockMvc.perform(get("/point/{id}", userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId))
            .andExpect(jsonPath("$.point").value(pointAmount))
            .andExpect(jsonPath("$.updateMillis").value(updateMillis));

        verify(pointService).getPoint(userId);
    }

    @Test
    @DisplayName("사용자 포인트 내역 조회 테스트")
    void 사용자_포인트_내역_조회() throws Exception {
        // given
        long userId = 1L;
        List<PointHistory> histories = Arrays.asList(
            new PointHistory(1L, userId, 500L, TransactionType.CHARGE, System.currentTimeMillis()),
            new PointHistory(2L, userId, 200L, TransactionType.USE, System.currentTimeMillis())
        );

        // when
        when(pointService.getPointHistory(userId)).thenReturn(histories);

        // then
        mockMvc.perform(get("/point/{id}/histories", userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1L))
            .andExpect(jsonPath("$[0].userId").value(userId))
            .andExpect(jsonPath("$[0].amount").value(500L))
            .andExpect(jsonPath("$[0].type").value("CHARGE"))
            .andExpect(jsonPath("$[1].id").value(2L))
            .andExpect(jsonPath("$[1].userId").value(userId))
            .andExpect(jsonPath("$[1].amount").value(200L))
            .andExpect(jsonPath("$[1].type").value("USE"));

        verify(pointService).getPointHistory(userId);
    }

    @Test
    @DisplayName("포인트 충전 테스트")
    void 포인트_충전() throws Exception {
        // given
        long userId = 1L;
        long chargeAmount = 500L;
        long updatedPoint = 1500L;
        long updateMillis = System.currentTimeMillis();
        UserPoint updatedUserPoint = new UserPoint(userId, updatedPoint, updateMillis);

        // when
        when(pointService.chargePoint(eq(userId), eq(chargeAmount))).thenReturn(updatedUserPoint);

        // then
        mockMvc.perform(patch("/point/{id}/charge", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.valueOf(chargeAmount)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId))
            .andExpect(jsonPath("$.point").value(updatedPoint))
            .andExpect(jsonPath("$.updateMillis").value(updateMillis));

        verify(pointService).chargePoint(userId, chargeAmount);
    }

    @Test
    @DisplayName("포인트 사용 테스트")
    void 포인트_사용() throws Exception {
        // given
        long userId = 1L;
        long useAmount = 300L;
        long updatedPoint = 700L;
        long updateMillis = System.currentTimeMillis();
        UserPoint updatedUserPoint = new UserPoint(userId, updatedPoint, updateMillis);

        // when
        when(pointService.usePoint(eq(userId), eq(useAmount))).thenReturn(updatedUserPoint);

        // then
        mockMvc.perform(patch("/point/{id}/use", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.valueOf(useAmount)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId))
            .andExpect(jsonPath("$.point").value(updatedPoint))
            .andExpect(jsonPath("$.updateMillis").value(updateMillis));

        verify(pointService).usePoint(userId, useAmount);
    }

}
