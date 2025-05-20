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
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포인트 컨트롤러 통합 테스트
 * 실제 HTTP 요청을 통해 동시성 제어가 올바르게 동작하는지 검증합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PointControllerTest {

    private static final Logger log = LoggerFactory.getLogger(PointControllerTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private UserPointTable userPointTable;

    @Autowired
    private PointHistoryTable pointHistoryTable;

    private String baseUrl;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/point";

        // HttpClient 5 버전에 맞게 코드 변경
        HttpClient httpClient = HttpClients.createDefault();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        restTemplate = new RestTemplate(requestFactory);
    }

    /**
     * 포인트 충전 동시성 통합 테스트
     * 여러 스레드에서 동시에 같은 사용자의 포인트를 충전할 때,
     * 모든 충전 요청이 순차적으로 처리되어 최종 포인트 금액이 정확해야 합니다.
     */
    @Test
    @DisplayName("동시에 여러 충전 요청이 들어와도 포인트가 정확하게 충전되어야 한다")
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

                    // HTTP 요청으로 포인트 충전 실행
                    String url = baseUrl + "/" + userId + "/charge";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    // RequestBody를 JSON 형식의 문자열로 변경
                    String requestBody = String.valueOf(chargeAmountPerThread);
                    HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

                    ResponseEntity<UserPoint> response = restTemplate.exchange(url, HttpMethod.PATCH, requestEntity, UserPoint.class);

                    log.info("Thread {}: Charging {} points to user {}, Response: {}",
                        Thread.currentThread().getName(), chargeAmountPerThread, userId, response.getBody());

                    return response;
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
        String url = baseUrl + "/" + userId;
        ResponseEntity<UserPoint> response = testRestTemplate.getForEntity(url, UserPoint.class);
        UserPoint finalUserPoint = response.getBody();

        assertThat(finalUserPoint).isNotNull();
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);

        // 포인트 내역 조회
        String historyUrl = baseUrl + "/" + userId + "/histories";
        ResponseEntity<PointHistory[]> historyResponse = testRestTemplate.getForEntity(historyUrl, PointHistory[].class);
        PointHistory[] histories = historyResponse.getBody();

        // 충전 내역이 모두 기록되었는지 검증
        assertThat(histories).isNotNull();
        // threadCount 만큼의 충전 내역이 있어야 함 (초기 설정은 히스토리에 기록되지 않음)
        assertThat(histories.length).isEqualTo(threadCount);
    }

    /**
     * 포인트 사용 동시성 통합 테스트
     * 여러 스레드에서 동시에 같은 사용자의 포인트를 사용할 때,
     * 모든 사용 요청이 순차적으로 처리되어 최종 포인트 금액이 정확해야 합니다.
     */
    @Test
    @DisplayName("동시에 여러 사용 요청이 들어와도 포인트가 정확하게 차감되어야 한다")
    void 포인트사용_동시요청_통합테스트() throws Exception {
        // given
        long userId = 2L;
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

                    // HTTP 요청으로 포인트 사용 실행
                    String url = baseUrl + "/" + userId + "/use";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    // RequestBody를 JSON 형식의 문자열로 변경
                    String requestBody = String.valueOf(useAmountPerThread);
                    HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

                    ResponseEntity<UserPoint> response = restTemplate.exchange(url, HttpMethod.PATCH, requestEntity, UserPoint.class);

                    log.info("Thread {}: Using {} points from user {}, Response: {}",
                        Thread.currentThread().getName(), useAmountPerThread, userId, response.getBody());

                    return response;
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
        String url = baseUrl + "/" + userId;
        ResponseEntity<UserPoint> response = testRestTemplate.getForEntity(url, UserPoint.class);
        UserPoint finalUserPoint = response.getBody();

        assertThat(finalUserPoint).isNotNull();
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);

        // 포인트 내역 조회
        String historyUrl = baseUrl + "/" + userId + "/histories";
        ResponseEntity<PointHistory[]> historyResponse = testRestTemplate.getForEntity(historyUrl, PointHistory[].class);
        PointHistory[] histories = historyResponse.getBody();

        // 사용 내역이 모두 기록되었는지 검증
        assertThat(histories).isNotNull();
        // threadCount 만큼의 사용 내역이 있어야 함 (초기 설정은 히스토리에 기록되지 않음)
        assertThat(histories.length).isEqualTo(threadCount);
    }

    /**
     * 포인트 충전과 사용 혼합 동시성 통합 테스트
     * 여러 스레드에서 동시에 같은 사용자의 포인트를 충전하고 사용할 때,
     * 모든 요청이 순차적으로 처리되어 최종 포인트 금액이 정확해야 합니다.
     */
    @Test
    @DisplayName("동시에 여러 충전과 사용 요청이 들어와도 포인트가 정확하게 처리되어야 한다")
    void 포인트충전과사용_동시요청_통합테스트() throws Exception {
        // given
        long userId = 3L;
        long initialPoint = 500L;
        int chargeThreadCount = 8;
        int useThreadCount = 4;
        long chargeAmountPerThread = 100L;
        long useAmountPerThread = 100L;

        // 예상되는 최종 포인트 = 초기값 + (충전 스레드 수 * 충전량) - (사용 스레드 수 * 사용량)
        long expectedFinalPoint = initialPoint +
            (chargeThreadCount * chargeAmountPerThread) -
            (useThreadCount * useAmountPerThread);

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

                    // HTTP 요청으로 포인트 충전 실행
                    String url = baseUrl + "/" + userId + "/charge";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    // RequestBody를 JSON 형식의 문자열로 변경
                    String requestBody = String.valueOf(chargeAmountPerThread);
                    HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

                    ResponseEntity<UserPoint> response = restTemplate.exchange(url, HttpMethod.PATCH, requestEntity, UserPoint.class);

                    log.info("Thread {}: Charging {} points to user {}, Response: {}",
                        Thread.currentThread().getName(), chargeAmountPerThread, userId, response.getBody());

                    return response;
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

                    // HTTP 요청으로 포인트 사용 실행
                    String url = baseUrl + "/" + userId + "/use";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    // RequestBody를 JSON 형식의 문자열로 변경
                    String requestBody = String.valueOf(useAmountPerThread);
                    HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

                    ResponseEntity<UserPoint> response = restTemplate.exchange(url, HttpMethod.PATCH, requestEntity, UserPoint.class);

                    log.info("Thread {}: Using {} points from user {}, Response: {}",
                        Thread.currentThread().getName(), useAmountPerThread, userId, response.getBody());

                    return response;
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
        String url = baseUrl + "/" + userId;
        ResponseEntity<UserPoint> response = testRestTemplate.getForEntity(url, UserPoint.class);
        UserPoint finalUserPoint = response.getBody();

        assertThat(finalUserPoint).isNotNull();
        assertThat(finalUserPoint.point()).isEqualTo(expectedFinalPoint);

        // 포인트 내역 조회
        String historyUrl = baseUrl + "/" + userId + "/histories";
        ResponseEntity<PointHistory[]> historyResponse = testRestTemplate.getForEntity(historyUrl, PointHistory[].class);
        PointHistory[] histories = historyResponse.getBody();

        // 충전 및 사용 내역이 모두 기록되었는지 검증
        assertThat(histories).isNotNull();
        // 충전 스레드 수 + 사용 스레드 수 만큼의 내역이 있어야 함 (초기 설정은 히스토리에 기록되지 않음)
        assertThat(histories.length).isEqualTo(chargeThreadCount + useThreadCount);
    }
}