package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PointServiceTest {

    private static final Logger log = LoggerFactory.getLogger(PointServiceTest.class);
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

    @Test
    @DisplayName("포인트 충전 시 충전 금액이 0 이하면 예외가 발생해야 한다")
    void 포인트충전_음수나제로일때_예외발생() {
        // 테스트 설명: 포인트 충전 시 유효하지 않은 금액에 대한 예외 처리를 검증하는 테스트입니다.
        // 포인트는 양수만 충전 가능하므로, 0이나 음수로 충전 시도할 경우 적절한 예외가 발생하는지 확인합니다.

        // given
        long userId = 1L;
        long invalidAmount = 0L;

        // when & then
        // 0 금액으로 충전 시도
        assertThatThrownBy(() -> pointService.chargePoint(userId, invalidAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Charge amount must be positive");

        // Negative amount (음수 금액 테스트)
        // 음수 금액으로 충전 시도
        assertThatThrownBy(() -> pointService.chargePoint(userId, -100L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Charge amount must be positive");
    }

    @Test
    @DisplayName("동시에 여러 요청이 들어와도 포인트가 정확하게 충전되어야 한다")
    void 포인트충전_동시요청_처리() throws Exception {
        // 테스트 설명: 동시에 여러 요청이 들어와도 포인트가 정확하게 충전되는지 검증하는 테스트입니다.
        // 여러 스레드에서 동시에 같은 사용자의 포인트를 충전할 때, 모든 충전 요청이 순차적으로 처리되어
        // 최종 포인트 금액이 모든 충전 금액의 합과 일치해야 합니다.

        // given
        long userId = 1L;
        long initialPoint = 1000L;
        int threadCount = 10;
        long chargeAmountPerThread = 100L;
        long expectedFinalPoint = initialPoint + (threadCount * chargeAmountPerThread);

        // 실제 테이블 구현체 사용 (목 대신)
        UserPointTable realUserPointTable = new UserPointTable();
        PointHistoryTable realPointHistoryTable = new PointHistoryTable();
        PointService realPointService = new PointService(realUserPointTable, realPointHistoryTable);

        // 초기 포인트 설정
        realUserPointTable.insertOrUpdate(userId, initialPoint);

        // 모든 스레드가 동시에 시작할 수 있도록 CountDownLatch 사용
        // 출발 신호를 기다림
        CountDownLatch latch = new CountDownLatch(1);
        // 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        // Future 객체를 저장할 리스트
        List<Future<?>> futures = new ArrayList<>();

        // when
        // 여러 스레드에서 동시에 포인트 충전 요청
        for (int i = 0; i < threadCount; i++) {
            futures.add(executorService.submit(() -> {
                try {
                    // 모든 스레드가 대기 (출발 신호 기다림)
                    latch.await();
                    // 포인트 충전 실행 (동시 요청 발생)
                    realPointService.chargePoint(userId, chargeAmountPerThread);
                    log.info("Thread {}: Charging {} points to user {}", Thread.currentThread().getName(), chargeAmountPerThread, userId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }

        // 모든 스레드 동시에 시작
        latch.countDown();

        // 모든 스레드 완료 대기
        for (Future<?> future : futures) {
            future.get();
        }

        // 스레드 풀 종료
        executorService.shutdown();

        // then
        // 최종 포인트가 예상한 값과 일치하는지 검증
        UserPoint finalUserPoint = realUserPointTable.selectById(userId);
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);
    }

    @Test
    @DisplayName("포인트 사용 시 사용자의 포인트가 정상적으로 차감되어야 한다")
    void 포인트사용_정상케이스() {
        // 테스트 설명: 포인트 사용 기능의 기본 동작을 검증하는 테스트입니다.
        // 사용자가 포인트를 사용하면 해당 금액만큼 포인트가 차감되어야 하고,
        // 포인트 사용 내역이 히스토리에 정확히 기록되어야 합니다.

        // given
        long userId = 1L;
        long initialPoint = 1000L;
        long useAmount = 500L;
        // 초기 포인트 설정
        UserPoint initialUserPoint = new UserPoint(userId, initialPoint, System.currentTimeMillis());
        // 사용 후 예상되는 포인트는 initialPoint - useAmount 입니다.
        UserPoint expectedUserPoint = new UserPoint(userId, initialPoint - useAmount, System.currentTimeMillis());

        // 1. 포인트 조회에 대한 가짜 응답
        when(userPointTable.selectById(userId)).thenReturn(initialUserPoint);
        // 2. 포인트 업데이트(사용)에 대한 가짜 응답
        when(userPointTable.insertOrUpdate(eq(userId), eq(initialPoint - useAmount))).thenReturn(expectedUserPoint);

        // when
        // 포인트 사용 메서드 호출
        UserPoint userPoint = pointService.usePoint(userId, useAmount);

        // then
        // 1. 포인트 사용 후 사용자 포인트가 예상한 값과 일치하는지 검증
        assertThat(userPoint.point()).isEqualTo(initialPoint - useAmount);
        // 2. 포인트 사용 후 사용자 ID가 예상한 값과 일치하는지 검증
        assertThat(userPoint.id()).isEqualTo(userId);
        // 3. 포인트 사용 내역이 올바르게 기록되었는지 검증
        verify(pointHistoryTable).insert(eq(userId), eq(useAmount), eq(TransactionType.USE), anyLong());
    }

    @Test
    @DisplayName("포인트 사용 시 사용 금액이 0 이하면 예외가 발생해야 한다")
    void 포인트사용_음수나제로일때_예외발생() {
        // 테스트 설명: 포인트 사용 시 유효하지 않은 금액에 대한 예외 처리를 검증하는 테스트입니다.
        // 포인트는 양수만 사용 가능하므로, 0이나 음수로 사용 시도할 경우 적절한 예외가 발생하는지 확인합니다.

        // given
        long userId = 1L;
        long invalidAmount = 0L;

        // when & then
        // 0 금액으로 사용 시도
        assertThatThrownBy(() -> pointService.usePoint(userId, invalidAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Use amount must be positive");

        // 음수 금액으로 사용 시도
        assertThatThrownBy(() -> pointService.usePoint(userId, -100L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Use amount must be positive");
    }

    @Test
    @DisplayName("포인트 사용 시 잔액이 부족하면 예외가 발생해야 한다")
    void 포인트사용_잔액부족_예외발생() {
        // 테스트 설명: 포인트 사용 시 잔액 부족에 대한 예외 처리를 검증하는 테스트입니다.
        // 사용자의 포인트 잔액보다 많은 금액을 사용하려고 할 때 적절한 예외가 발생하는지 확인합니다.

        // given
        long userId = 1L;
        long currentPoint = 500L;
        long useAmount = 1000L; // 현재 포인트보다 많은 금액
        UserPoint userPoint = new UserPoint(userId, currentPoint, System.currentTimeMillis());

        // 포인트 조회에 대한 가짜 응답
        when(userPointTable.selectById(userId)).thenReturn(userPoint);

        // when & then
        // 잔액보다 많은 금액으로 사용 시도
        assertThatThrownBy(() -> pointService.usePoint(userId, useAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Insufficient point balance");
    }

    @Test
    @DisplayName("동시에 여러 요청이 들어와도 포인트가 정확하게 사용되어야 한다")
    void 포인트사용_동시요청_처리() throws Exception {
        // 테스트 설명: 동시에 여러 요청이 들어와도 포인트가 정확하게 사용되는지 검증하는 테스트입니다.
        // 여러 스레드에서 동시에 같은 사용자의 포인트를 사용할 때, 모든 사용 요청이 순차적으로 처리되어
        // 최종 포인트 금액이 정확해야 하며, 잔액 부족으로 인한 오류가 발생하지 않아야 합니다.

        // given
        long userId = 1L;
        long initialPoint = 1000L;
        int threadCount = 5;
        long useAmountPerThread = 100L;
        long expectedFinalPoint = initialPoint - (threadCount * useAmountPerThread);

        // 실제 테이블 구현체 사용 (목 대신)
        UserPointTable realUserPointTable = new UserPointTable();
        PointHistoryTable realPointHistoryTable = new PointHistoryTable();
        PointService realPointService = new PointService(realUserPointTable, realPointHistoryTable);

        // 초기 포인트 설정
        realUserPointTable.insertOrUpdate(userId, initialPoint);

        // 모든 스레드가 동시에 시작할 수 있도록 CountDownLatch 사용
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        // when
        // 여러 스레드에서 동시에 포인트 사용 요청
        for (int i = 0; i < threadCount; i++) {
            futures.add(executorService.submit(() -> {
                try {
                    // 모든 스레드가 대기
                    latch.await();
                    // 포인트 사용 실행
                    realPointService.usePoint(userId, useAmountPerThread);
                    log.info("Thread {}: Using {} points from user {}", Thread.currentThread().getName(), useAmountPerThread, userId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }

        // 모든 스레드 동시에 시작
        latch.countDown();

        // 모든 스레드 완료 대기
        for (Future<?> future : futures) {
            future.get();
        }

        executorService.shutdown();

        // then
        // 최종 포인트가 예상한 값과 일치하는지 검증
        UserPoint finalUserPoint = realUserPointTable.selectById(userId);
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);
    }

    @Test
    @DisplayName("포인트 조회 시 사용자의 포인트 정보가 정확히 반환되어야 한다")
    void 포인트조회_정상케이스() {
        // 테스트 설명: 포인트 조회 기능의 기본 동작을 검증하는 테스트입니다.
        // 사용자 ID로 포인트를 조회하면 해당 사용자의 정확한 포인트 정보가 반환되어야 합니다.

        // given
        long userId = 1L;
        long pointAmount = 1000L;
        long updateMillis = System.currentTimeMillis();
        UserPoint expectedUserPoint = new UserPoint(userId, pointAmount, updateMillis);

        // 포인트 조회에 대한 가짜 응답
        when(userPointTable.selectById(userId)).thenReturn(expectedUserPoint);

        // when
        // 포인트 조회 메서드 호출
        UserPoint userPoint = pointService.getPoint(userId);

        // then
        // 1. 조회된 사용자 ID가 예상한 값과 일치하는지 검증
        assertThat(userPoint.id()).isEqualTo(userId);
        // 2. 조회된 포인트 금액이 예상한 값과 일치하는지 검증
        assertThat(userPoint.point()).isEqualTo(pointAmount);
        // 3. 조회된 업데이트 시간이 예상한 값과 일치하는지 검증
        assertThat(userPoint.updateMillis()).isEqualTo(updateMillis);
    }
}
