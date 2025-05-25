package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포인트 시스템 통합 테스트
 * 여러 계층(컨트롤러, 서비스, 데이터베이스)이 상호작용하는 통합 테스트를 포함합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PointIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PointIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private UserPointTable userPointTable;

    @Autowired
    private PointHistoryTable pointHistoryTable;

    @Autowired
    private PointService pointService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private String baseUrl;
    private RestTemplate restTemplate;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/point";

        // HttpClient 5 버전에 맞게 코드 변경
        HttpClient httpClient = HttpClients.createDefault();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        restTemplate = new RestTemplate(requestFactory);

        // MockMvc 설정
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // 테스트 전에 데이터베이스 초기화
        // 실제 프로덕션 환경에서는 이렇게 하면 안되지만, 테스트 환경에서는 괜찮습니다.
        // 테이블 객체에 직접 접근하여 데이터를 초기화합니다.
        try {
            java.lang.reflect.Field tableField = UserPointTable.class.getDeclaredField("table");
            tableField.setAccessible(true);
            ((java.util.Map<?, ?>)tableField.get(userPointTable)).clear();

            tableField = PointHistoryTable.class.getDeclaredField("table");
            tableField.setAccessible(true);
            ((java.util.List<?>)tableField.get(pointHistoryTable)).clear();

            java.lang.reflect.Field cursorField = PointHistoryTable.class.getDeclaredField("cursor");
            cursorField.setAccessible(true);
            cursorField.set(pointHistoryTable, 1L);
        } catch (Exception e) {
            log.error("Failed to clear database tables", e);
        }
    }

    /**
     * 포인트 충전 동시성 통합 테스트 (컨트롤러 레벨)
     * 여러 스레드에서 동시에 같은 사용자의 포인트를 충전할 때,
     * 모든 충전 요청이 순차적으로 처리되어 최종 포인트 금액이 정확해야 합니다.
     */
    @Test
    @DisplayName("동시에 여러 충전 요청이 들어와도 포인트가 정확하게 충전되어야 한다 (컨트롤러)")
    void 포인트충전_동시요청_통합테스트() throws Exception {
        // given
        long userId = 1L;
        long initialPoint = 1000L;
        int threadCount = 10;
        long chargeAmountPerThread = 100L;
        long expectedFinalPoint = initialPoint + (threadCount * chargeAmountPerThread);

        // 초기 포인트 설정
        userPointTable.insertOrUpdate(userId, initialPoint);

        // 모든 스레드가 동시에 시작할 수 있도록 CountDownLatch 사용
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        // when
        // 여러 스레드에서 동시에 포인트 충전 요청
        for (int i = 0; i < threadCount; i++) {
            futures.add(executorService.submit(() -> {
                try {
                    // 모든 스레드가 대기
                    latch.await();

                    // MockMvc를 사용하여 포인트 충전 요청
                    try {
                        mockMvc.perform(patch("/point/{id}/charge", userId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.valueOf(chargeAmountPerThread)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(userId));

                        log.info("Thread {}: Charging {} points to user {}",
                            Thread.currentThread().getName(), chargeAmountPerThread, userId);
                    } catch (Exception e) {
                        log.error("Error in thread {}: {}", Thread.currentThread().getName(), e.getMessage());
                    }

                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
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
        UserPoint finalUserPoint = userPointTable.selectById(userId);
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);

        // 포인트 내역 조회
        List<PointHistory> histories = pointHistoryTable.selectAllByUserId(userId);

        // 충전 내역이 모두 기록되었는지 검증
        assertThat(histories).isNotNull();
        assertThat(histories.size()).isEqualTo(threadCount);
    }

    /**
     * 포인트 사용 동시성 통합 테스트 (컨트롤러 레벨)
     * 여러 스레드에서 동시에 같은 사용자의 포인트를 사용할 때,
     * 모든 사용 요청이 순차적으로 처리되어 최종 포인트 금액이 정확해야 합니다.
     */
    @Test
    @DisplayName("동시에 여러 사용 요청이 들어와도 포인트가 정확하게 사용되어야 한다 (컨트롤러)")
    void 포인트사용_동시요청_통합테스트() throws Exception {
        // given
        long userId = 1L;
        long initialPoint = 1000L;
        int threadCount = 5;
        long useAmountPerThread = 100L;
        long expectedFinalPoint = initialPoint - (threadCount * useAmountPerThread);

        // 초기 포인트 설정
        userPointTable.insertOrUpdate(userId, initialPoint);

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

                    // MockMvc를 사용하여 포인트 사용 요청
                    try {
                        mockMvc.perform(patch("/point/{id}/use", userId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.valueOf(useAmountPerThread)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(userId));

                        log.info("Thread {}: Using {} points from user {}",
                            Thread.currentThread().getName(), useAmountPerThread, userId);
                    } catch (Exception e) {
                        log.error("Error in thread {}: {}", Thread.currentThread().getName(), e.getMessage());
                    }

                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
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
        UserPoint finalUserPoint = userPointTable.selectById(userId);
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);

        // 포인트 내역 조회
        List<PointHistory> histories = pointHistoryTable.selectAllByUserId(userId);

        // 사용 내역이 모두 기록되었는지 검증
        assertThat(histories).isNotNull();
        assertThat(histories.size()).isEqualTo(threadCount);
    }

    /**
     * 포인트 충전과 사용 동시성 통합 테스트 (컨트롤러 레벨)
     * 여러 스레드에서 동시에 같은 사용자의 포인트를 충전하고 사용할 때,
     * 모든 요청이 순차적으로 처리되어 최종 포인트 금액이 정확해야 합니다.
     */
    @Test
    @DisplayName("동시에 여러 충전과 사용 요청이 들어와도 포인트가 정확하게 처리되어야 한다 (컨트롤러)")
    void 포인트충전과사용_동시요청_통합테스트() throws Exception {
        // given
        long userId = 1L;
        long initialPoint = 1000L;
        int chargeThreadCount = 10;
        int useThreadCount = 5;
        long chargeAmountPerThread = 100L;
        long useAmountPerThread = 100L;
        long expectedFinalPoint = initialPoint + (chargeThreadCount * chargeAmountPerThread) - (useThreadCount * useAmountPerThread);

        // 초기 포인트 설정
        userPointTable.insertOrUpdate(userId, initialPoint);

        // 모든 스레드가 동시에 시작할 수 있도록 CountDownLatch 사용
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(chargeThreadCount + useThreadCount);
        List<Future<?>> futures = new ArrayList<>();

        // when
        // 여러 스레드에서 동시에 포인트 충전 요청
        for (int i = 0; i < chargeThreadCount; i++) {
            futures.add(executorService.submit(() -> {
                try {
                    // 모든 스레드가 대기
                    latch.await();

                    // MockMvc를 사용하여 포인트 충전 요청
                    try {
                        mockMvc.perform(patch("/point/{id}/charge", userId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.valueOf(chargeAmountPerThread)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(userId));

                        log.info("Thread {}: Charging {} points to user {}",
                            Thread.currentThread().getName(), chargeAmountPerThread, userId);
                    } catch (Exception e) {
                        log.error("Error in thread {}: {}", Thread.currentThread().getName(), e.getMessage());
                    }

                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }));
        }

        // 여러 스레드에서 동시에 포인트 사용 요청
        for (int i = 0; i < useThreadCount; i++) {
            futures.add(executorService.submit(() -> {
                try {
                    // 모든 스레드가 대기
                    latch.await();

                    // MockMvc를 사용하여 포인트 사용 요청
                    try {
                        mockMvc.perform(patch("/point/{id}/use", userId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.valueOf(useAmountPerThread)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(userId));

                        log.info("Thread {}: Using {} points from user {}",
                            Thread.currentThread().getName(), useAmountPerThread, userId);
                    } catch (Exception e) {
                        log.error("Error in thread {}: {}", Thread.currentThread().getName(), e.getMessage());
                    }

                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
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
        UserPoint finalUserPoint = userPointTable.selectById(userId);
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);

        // 포인트 내역 조회
        List<PointHistory> histories = pointHistoryTable.selectAllByUserId(userId);

        // 충전 및 사용 내역이 모두 기록되었는지 검증
        assertThat(histories).isNotNull();
        // 충전 스레드 수 + 사용 스레드 수 만큼의 내역이 있어야 함 (초기 설정은 히스토리에 기록되지 않음)
        assertThat(histories.size()).isEqualTo(chargeThreadCount + useThreadCount);
    }

    /**
     * 포인트 충전 동시성 통합 테스트 (서비스 레벨)
     * 여러 스레드에서 동시에 같은 사용자의 포인트를 충전할 때,
     * 모든 충전 요청이 순차적으로 처리되어 최종 포인트 금액이 정확해야 합니다.
     */
    @Test
    @DisplayName("동시에 여러 충전 요청이 들어와도 포인트가 정확하게 충전되어야 한다 (서비스)")
    void 포인트충전_동시요청_서비스테스트() throws Exception {
        // given
        long userId = 1L;
        long initialPoint = 1000L;
        int threadCount = 10;
        long chargeAmountPerThread = 100L;
        long expectedFinalPoint = initialPoint + (threadCount * chargeAmountPerThread);

        // 초기 포인트 설정
        userPointTable.insertOrUpdate(userId, initialPoint);

        // 모든 스레드가 동시에 시작할 수 있도록 CountDownLatch 사용
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        // when
        // 여러 스레드에서 동시에 포인트 충전 요청
        for (int i = 0; i < threadCount; i++) {
            futures.add(executorService.submit(() -> {
                try {
                    // 모든 스레드가 대기 (출발 신호 기다림)
                    latch.await();
                    // 포인트 충전 실행 (동시 요청 발생)
                    pointService.chargePoint(userId, chargeAmountPerThread);
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
        UserPoint finalUserPoint = userPointTable.selectById(userId);
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);
    }

    /**
     * 포인트 사용 동시성 통합 테스트 (서비스 레벨)
     * 여러 스레드에서 동시에 같은 사용자의 포인트를 사용할 때,
     * 모든 사용 요청이 순차적으로 처리되어 최종 포인트 금액이 정확해야 합니다.
     */
    @Test
    @DisplayName("동시에 여러 사용 요청이 들어와도 포인트가 정확하게 사용되어야 한다 (서비스)")
    void 포인트사용_동시요청_서비스테스트() throws Exception {
        // given
        long userId = 1L;
        long initialPoint = 1000L;
        int threadCount = 5;
        long useAmountPerThread = 100L;
        long expectedFinalPoint = initialPoint - (threadCount * useAmountPerThread);

        // 초기 포인트 설정
        userPointTable.insertOrUpdate(userId, initialPoint);

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
                    pointService.usePoint(userId, useAmountPerThread);
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
        UserPoint finalUserPoint = userPointTable.selectById(userId);
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);
    }
}
