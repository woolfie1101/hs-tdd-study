# TDD 및 동시성 이슈 관리

## TDD(Test-Driven Development)에 대한 고찰

### TDD란 무엇인가?

TDD(Test-Driven Development)는 소프트웨어 개발 방법론 중 하나로, 테스트 코드를 먼저 작성한 후 실제 코드를 구현하는 방식입니다. 이는 전통적인 개발 방식(코드 작성 → 테스트)과는 반대되는 접근법입니다.

### TDD 개발 사이클: Red-Green-Refactor

TDD는 다음과 같은 3단계 사이클로 진행됩니다:

1. **Red**: 실패하는 테스트 작성
   - 구현하고자 하는 기능에 대한 테스트 코드를 먼저 작성합니다.
   - 이 시점에서는 테스트가 실패합니다(Red).

2. **Green**: 테스트를 통과하는 최소한의 코드 작성
   - 테스트를 통과할 수 있는 가장 간단한 코드를 작성합니다.
   - 이 단계에서는 코드의 품질보다 테스트 통과에 집중합니다.

3. **Refactor**: 코드 개선
   - 테스트를 통과한 코드를 리팩토링하여 품질을 개선합니다.
   - 중복 제거, 가독성 향상, 성능 최적화 등을 수행합니다.
   - 리팩토링 후에도 모든 테스트가 통과해야 합니다.

### TDD의 장점

1. **높은 코드 품질**
   - 테스트 가능한 코드는 일반적으로 더 모듈화되고 결합도가 낮습니다.
   - 버그를 조기에 발견하고 수정할 수 있습니다.

2. **안전한 리팩토링**
   - 테스트 스위트가 있으면 코드 변경 시 기존 기능이 손상되지 않았는지 즉시 확인할 수 있습니다.

3. **문서화 효과**
   - 테스트 코드는 코드의 사용 방법과 기대 동작을 보여주는 살아있는 문서 역할을 합니다.

4. **설계 개선**
   - 테스트를 먼저 작성함으로써 API와 인터페이스에 대해 더 깊이 생각하게 됩니다.
   - 사용자 관점에서 코드를 설계하게 됩니다.

### 프로젝트에서의 TDD 적용 예시

이 프로젝트에서는 포인트 시스템에 대한 다양한 테스트를 구현했습니다:

1. **단위 테스트 (PointControllerTest, PointServiceTest)**
   - 각 컴포넌트를 독립적으로 테스트하여 기본 기능이 올바르게 작동하는지 확인합니다.
   - 모킹(Mocking)을 사용하여 외부 의존성을 격리합니다.

2. **통합 테스트 (PointIntegrationTest)**
   - 여러 컴포넌트가 함께 작동할 때의 동작을 검증합니다.
   - 실제 환경과 유사한 조건에서 테스트합니다.

## 동시성 이슈에 대한 고찰

### 동시성 문제란?

동시성 문제는 여러 스레드나 프로세스가 동시에 같은 자원에 접근할 때 발생하는 문제입니다. 특히 금융 시스템이나 포인트 시스템과 같이 데이터 일관성이 중요한 애플리케이션에서 심각한 문제를 일으킬 수 있습니다.

### 주요 동시성 문제

1. **경쟁 상태(Race Condition)**
   - 두 개 이상의 스레드가 공유 데이터에 동시에 접근하여 결과가 접근 순서에 따라 달라지는 상황입니다.
   - 예: 두 사용자가 동시에 포인트를 충전할 때, 최종 포인트 금액이 부정확해질 수 있습니다.

2. **데드락(Deadlock)**
   - 두 개 이상의 스레드가 서로 상대방이 점유한 자원을 기다리며 무한정 대기하는 상황입니다.

3. **라이브락(Livelock)**
   - 스레드가 계속 동작하지만 작업을 진행하지 못하는 상황입니다.

4. **기아 상태(Starvation)**
   - 특정 스레드가 필요한 자원을 계속 얻지 못하는 상황입니다.

### 동시성 문제 해결 전략

1. **락(Lock) 사용**
   - 공유 자원에 대한 접근을 동기화하여 한 번에 하나의 스레드만 접근할 수 있도록 합니다.
   - 예: `ReentrantLock`, `synchronized` 키워드 등

2. **원자적 연산(Atomic Operations)**
   - 분할할 수 없는 단일 단위로 실행되는 연산을 사용합니다.
   - 예: `AtomicInteger`, `AtomicReference` 등

3. **불변 객체(Immutable Objects)**
   - 생성 후 상태가 변경되지 않는 객체를 사용하여 동시성 문제를 방지합니다.

4. **스레드 로컬 변수(Thread-Local Variables)**
   - 각 스레드가 자신만의 변수 복사본을 가지도록 하여 공유 상태를 최소화합니다.

### 프로젝트에서의 동시성 관리 예시

이 프로젝트에서는 포인트 충전 및 사용 시 동시성 문제를 해결하기 위해 다음과 같은 전략을 사용했습니다:

1. **사용자별 락(Lock) 사용**

   ```
   // PointService.java에서 사용자별 락 관리
   private final ConcurrentHashMap<Long, Lock> userLocks = new ConcurrentHashMap<>();

   // 사용자별 락 획득
   Lock userLock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
   ```

2. **타임아웃을 통한 데드락 방지**

   ```
   // 락 획득 시도 (10초 타임아웃)
   lockAcquired = userLock.tryLock(10, TimeUnit.SECONDS);
   if (!lockAcquired) {
       throw new RuntimeException("Failed to acquire lock for user " + userId + " after timeout");
   }
   ```

3. **트랜잭션 일관성 보장**

   ```
   // 락 내부에서 최신 상태 조회 및 검증
   UserPoint userPoint = userPointTable.selectById(userId);

   // 포인트 사용 시 잔액 부족 여부 확인 (락 안에서)
   if (transactionType == TransactionType.USE && userPoint.point() < amount) {
       throw new IllegalArgumentException("Insufficient point balance");
   }
   ```

4. **포인트 내역 추가를 락 내부에서 수행**

   ```
   // 포인트 내역 추가 (락 안에서 수행하여 동시성 이슈 방지)
   if (updatedUserPoint != null) {
       pointHistoryTable.insert(userId, amount, transactionType, currentTimeMillis);
   }
   ```

### 동시성 테스트

동시성 문제는 재현하기 어렵기 때문에 철저한 테스트가 필요합니다. 이 프로젝트에서는 다음과 같은 동시성 테스트를 구현했습니다:

```
// 여러 스레드에서 동시에 포인트 충전 요청
CountDownLatch latch = new CountDownLatch(1);
ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

for (int i = 0; i < threadCount; i++) {
    futures.add(executorService.submit(() -> {
        try {
            // 모든 스레드가 대기
            latch.await();
            // 포인트 충전 실행
            pointService.chargePoint(userId, chargeAmountPerThread);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }));
}

// 모든 스레드 동시에 시작
latch.countDown();
```

이러한 테스트를 통해 동시성 문제가 발생하지 않는지 검증할 수 있습니다.

## 결론

TDD와 동시성 관리는 현대 소프트웨어 개발에서 매우 중요한 요소입니다. TDD를 통해 코드의 품질과 신뢰성을 높이고, 적절한 동시성 관리 전략을 통해 다중 사용자 환경에서도 안정적으로 동작하는 애플리케이션을 구현할 수 있습니다. 이 프로젝트에서는 이러한 원칙들을 적용하여 안정적이고 확장 가능한 포인트 시스템을 구현했습니다.
