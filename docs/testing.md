# Testing Guide — Scenario Console

> Hướng dẫn viết test cho toàn bộ Scenario Console project: 3 service (console-api, backoffice-api, reconciliation-service) trên Spring Boot 4.0.6 / Java 25. Áp dụng Testing Pyramid để phân bổ effort đúng chỗ — fast unit test nhiều, slow integration test ít nhưng đủ bao phủ critical invariants.

---

## Mục lục

1. [Testing Pyramid — khi nào dùng loại nào](#1-testing-pyramid--khi-nào-dùng-loại-nào)
2. [Tool Stack](#2-tool-stack)
3. [Naming Convention](#3-naming-convention)
4. [Concrete Test Examples](#4-concrete-test-examples)
5. [Integration Test Pattern với Testcontainers](#5-integration-test-pattern-với-testcontainers)
6. [Coverage Target](#6-coverage-target)
7. [What NOT to Test](#7-what-not-to-test)
8. [Chạy Tests](#8-chạy-tests)
9. [CI Integration](#9-ci-integration)
10. [Interview Q&A](#10-interview-qa)

---

## 1. Testing Pyramid — khi nào dùng loại nào

```
           ┌──────────────────────────┐
           │   Integration Tests      │  ← ít nhất, chậm nhất, dùng Testcontainers
           │   (Testcontainers)       │
           ├──────────────────────────┤
           │   Slice Tests            │  ← trung bình, test 1 layer riêng biệt
           │   (@WebMvcTest /         │
           │    @DataJpaTest)         │
           ├──────────────────────────┤
           │   Unit Tests (Mockito)   │  ← nhiều hơn, fast, mock dependencies
           ├──────────────────────────┤
           │   Pure Unit Tests        │  ← nhiều nhất, nhanh nhất, zero Spring
           │   (no Spring context)    │
           └──────────────────────────┘
```

### Tầng 1 — Pure Unit Test (không Spring, không Mockito)

Dùng khi: class không có external dependency. Logic thuần túy: tính toán, validation, domain rule.

**Ví dụ phù hợp:**
- `IdempotencyKeyGenerator` — SHA256 từ các primitive inputs
- `ParameterSorter` — sort params trước khi hash
- `VarIdParser` — parse `VAR_{ULID}` format
- `StepOrderComparator` — so sánh thứ tự step

**Không dùng cho:** class cần Spring bean, DB, Redis, HTTP client.

### Tầng 2 — Unit Test with Mockito

Dùng khi: class có dependencies nhưng logic business quan trọng hơn infra. Mock dependencies bằng Mockito, không spin up Spring context.

**Ví dụ phù hợp:**
- `ActionExecutorUseCase` — mock `ActionExecutionRepository`, `ServiceAdapter`, `SagaManager`
- `TriggerUseCaseImpl` — mock `TriggerExecutionRepository`, `RedisContextRepository`
- `ParameterResolverService` — mock `ContextRepository`
- `CompensationManager` — mock `SagaCompensationLogRepository`

**Lý do không dùng @SpringBootTest ở đây:** khởi động toàn bộ Spring context cho 1 class mất 3–10 giây. Với 50 class, test suite sẽ chậm hơn 10 phút. Mockito unit test chạy trong milliseconds.

### Tầng 3 — Slice Test

Dùng khi: cần test 1 layer cụ thể của Spring mà không cần toàn bộ context.

#### @WebMvcTest — test Controller layer

- Load MVC layer (DispatcherServlet, Controller, ExceptionHandler, Filter)
- Không load Service, Repository, DB
- Dùng `@MockBean` cho dependencies của Controller
- Nhanh hơn `@SpringBootTest` khoảng 5x

```java
@WebMvcTest(TriggerController.class)
class TriggerControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean TriggerUseCase triggerUseCase;
    // ...
}
```

**Nên test gì với @WebMvcTest:**
- HTTP status code cho mọi response path (200, 202, 400, 409, 500)
- Request validation (missing field → 400)
- Response body structure (deserialization đúng không)
- Header handling (X-Request-Id presence check)

#### @DataJpaTest — test Repository layer

- Load JPA layer (EntityManager, Spring Data repositories)
- Dùng embedded DB hoặc Testcontainers PostgreSQL (khuyến nghị)
- Không load Controller, Service

**Nên test gì với @DataJpaTest:**
- Custom `@Query` methods
- JPQL / native query phức tạp
- Constraint violation behavior

**Lưu ý:** Spring Data default methods (`findById`, `save`, `delete`) không cần test — xem mục 7.

### Tầng 4 — Integration Test with Testcontainers

Dùng khi: cần verify hành vi của toàn bộ system với real PostgreSQL + Redis. Đây là lớp test đảm bảo **system-level invariants** — quan trọng nhất là idempotency.

**Ví dụ phù hợp:**
- Idempotency guarantee: 2 request concurrent với cùng key → chỉ 1 action_execution được INSERT
- Context save/restore cycle: ghi Redis → restart → đọc lại đúng giá trị
- Saga trigger on FAILED: action fail → saga_compensation_log được ghi đúng
- 2PC behavior: PostgreSQL ACID là source of truth khi Redis lẫn lộn

**Chi phí:** mỗi lần test phải spin up Docker container (PostgreSQL, Redis). Đặt ở separate Gradle sourceSet `integrationTest` để không block unit test thông thường.

---

## 2. Tool Stack

| Tool | Version | Mục đích |
|---|---|---|
| JUnit 5 (Jupiter) | bundled với Spring Boot 4 | Test runner, `@Test`, `@ParameterizedTest`, `@BeforeEach` |
| Mockito 5 | bundled với Spring Boot 4 | Mock/Stub/Spy dependencies |
| AssertJ | bundled | Fluent assertion API |
| Testcontainers | 1.20+ | Real PostgreSQL + Redis trong Docker |
| Spring Boot Test | 4.0.6 | `@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`, `MockMvc` |
| MockMvc | bundled | HTTP layer test không cần server |

### build.gradle — test dependencies

```groovy
// common/build.gradle và mỗi service build.gradle
dependencies {
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    // Đã include: JUnit 5, Mockito 5, AssertJ, MockMvc, Spring Boot Test

    testImplementation 'org.testcontainers:junit-jupiter:1.20.1'
    testImplementation 'org.testcontainers:postgresql:1.20.1'

    // Redis via GenericContainer (không có official Testcontainers module)
    testImplementation 'org.testcontainers:testcontainers:1.20.1'
}
```

### Tại sao AssertJ thay vì JUnit assertions?

```java
// JUnit assertion — ít readable, error message mơ hồ
assertEquals(expected, actual);

// AssertJ — fluent, error message rõ hơn
assertThat(actual)
    .isEqualTo(expected)
    .isNotNull();

// Collection assertions — không thể với JUnit assertions
assertThat(actions)
    .hasSize(3)
    .extracting(ActionExecution::getStatus)
    .containsExactly(COMPLETED, COMPLETED, FAILED);
```

---

## 3. Naming Convention

### Test class names

| Loại test | Naming pattern | Ví dụ |
|---|---|---|
| Pure unit test | `<ClassUnderTest>Test` | `IdempotencyKeyGeneratorTest` |
| Mockito unit test | `<ClassUnderTest>Test` | `ActionExecutorUseCaseTest` |
| Slice test | `<ClassUnderTest>Test` | `TriggerControllerTest` |
| Integration test | `<ClassUnderTest>IntegrationTest` | `ActionExecutorIntegrationTest` |

### Test method names

Pattern: `methodName_scenario_expectedOutcome()`

```java
// Pure unit
@Test
void generateKey_withSameParamsDifferentOrder_returnsSameHash()

@Test
void generateKey_differentActionDefinitionId_returnsDifferentHash()

// Use case
@Test
void execute_whenIdempotencyKeyExists_returnsCachedResponse()

@Test
void execute_whenAdapterReturns503_savesFailedStatusAndTriggersSaga()

@Test
void execute_whenConcurrentDuplicateInsert_onlyOneRowCommits()

// Controller
@Test
void trigger_whenMissingRequestIdHeader_returns400()

@Test
void trigger_whenExecutionAlreadyRunning_returns202()

@Test
void trigger_whenActionCompletes_returns200WithResponse()
```

**Quy tắc:**
- Một `@Test` per behavior, không phải per method. Nếu 1 method có 5 branches → viết 5 test.
- Không đặt tên `test_` prefix — JUnit 5 không cần.
- `_scenario_` mô tả state/precondition, `_expectedOutcome` mô tả kết quả muốn verify.

---

## 4. Concrete Test Examples

### 4.1 `common/src/test/java/com/simi/common/idempotency/IdempotencyKeyGeneratorTest.java`

Test class này verify tính determinism và correctness của SHA256 key generation — critical vì toàn bộ Tầng 4 idempotency phụ thuộc vào đây.

**Behaviors cần test:**

- `generateKey_withSameInputs_returnsSameHash()` — gọi 2 lần với cùng inputs phải cho cùng kết quả (determinism)
- `generateKey_withParamsDifferentOrder_returnsSameHash()` — `{a: 1, b: 2}` và `{b: 2, a: 1}` phải cho cùng hash. Đây là invariant quan trọng nhất — nếu params không được sort trước khi hash, cùng 1 action sẽ tạo ra 2 key khác nhau tùy ordering của Map, dẫn đến duplicate action chạy.
- `generateKey_withDifferentExecutionId_returnsDifferentHash()` — 2 execution khác nhau không share idempotency space
- `generateKey_withDifferentActionDefinitionId_returnsDifferentHash()` — 2 action khác nhau trong cùng execution phải có key khác
- `generateKey_withDifferentParamValues_returnsDifferentHash()` — cùng action nhưng params khác → key khác (không nhầm lẫn user A với user B)
- `generateKey_returnsHex64Chars()` — SHA256 output luôn là 64 hex chars, không nullable

**Test pattern:**

```java
class IdempotencyKeyGeneratorTest {

    private final IdempotencyKeyGenerator generator = new IdempotencyKeyGenerator();

    @Test
    void generateKey_withParamsDifferentOrder_returnsSameHash() {
        Map<String, String> paramsAB = new LinkedHashMap<>();
        paramsAB.put("userId", "user_01JW");
        paramsAB.put("amount", "1000");

        Map<String, String> paramsBA = new LinkedHashMap<>();
        paramsBA.put("amount", "1000");
        paramsBA.put("userId", "user_01JW");

        String keyAB = generator.generate(1L, 100L, paramsAB);
        String keyBA = generator.generate(1L, 100L, paramsBA);

        assertThat(keyAB).isEqualTo(keyBA);
    }

    @Test
    void generateKey_withDifferentExecutionId_returnsDifferentHash() {
        Map<String, String> params = Map.of("userId", "user_01JW");

        String key1 = generator.generate(1L, 100L, params);
        String key2 = generator.generate(2L, 100L, params);

        assertThat(key1).isNotEqualTo(key2);
    }
}
```

---

### 4.2 `console-api/src/test/java/com/simi/console/feature/executor/action_execute/ParameterResolverTest.java`

Test class này verify logic resolve parameters từ Execution Context cho mỗi action — bao gồm path param, query param, request body. Critical vì nếu resolver sai, action gọi 3rd party với data sai.

**Behaviors cần test:**

- `resolve_pathParam_extractedFromContext()` — varId `VAR_01JWTXN` được map đúng vào `{transactionId}` trong path
- `resolve_queryParam_appendedToUrl()` — query param được append vào URL đúng format
- `resolve_bodyParam_includesInRequestBody()` — body params được wrap vào JSON object đúng
- `resolve_whenRequiredParamMissing_throwsMissingRequiredParameterException()` — nếu required param không có trong context, throw exception; không gọi 3rd party với data thiếu
- `resolve_whenOptionalParamMissing_paramIsSkipped()` — optional param không có trong context → không include trong request, không throw exception
- `resolve_whenVarIdValueIsNull_requiredThrows_optionalSkips()` — phân biệt null value với missing key

---

### 4.3 `console-api/src/test/java/com/simi/console/feature/executor/action_execute/ActionExecutorUseCaseTest.java`

Test class này verify business logic của ActionExecutorUseCase — trái tim của execution engine. Tất cả dependencies được mock.

**Setup:**

```java
@ExtendWith(MockitoExtension.class)
class ActionExecutorUseCaseTest {

    @Mock ActionExecutionRepository actionExecutionRepository;
    @Mock ServiceAdapterFactory adapterFactory;
    @Mock ServiceAdapter mockAdapter;
    @Mock SagaCompensationManager sagaManager;
    @Mock ContextRepository contextRepository;

    @InjectMocks ActionExecutorUseCase useCase;
}
```

**Behaviors cần test:**

- `execute_whenIdempotencyKeyExists_returnsCachedResponse()` — khi DB đã có row với key đó và status COMPLETED, không gọi adapter, trả về `response_snapshot` ngay. Mock `actionExecutionRepository.findByIdempotencyKey()` trả về Optional với COMPLETED row.

- `execute_firstTime_callsAdapterAndSavesResult()` — khi không có row nào, INSERT PROCESSING, gọi adapter, nhận response, UPDATE COMPLETED, save output vars vào context. Verify `adapter.call()` được gọi đúng 1 lần và `contextRepository.save()` được gọi với đúng varIds.

- `execute_whenAdapterReturnsHttpError_savesFailedStatus()` — khi adapter throw `AdapterHttpException` (4xx/5xx), UPDATE action_execution status → FAILED, không throw exception ra ngoài (Saga sẽ xử lý).

- `execute_whenAdapterTimesOut_savesTimeoutStatus()` — khi adapter throw `AdapterTimeoutException`, UPDATE → TIMEOUT, không trigger Saga (Reconciler xử lý). Verify `sagaManager.triggerCompensation()` KHÔNG được gọi.

- `execute_whenDataIntegrityViolation_logsRaceConditionAndReturnsCached()` — khi INSERT throw `DataIntegrityViolationException` (concurrent duplicate), query lại DB, trả về existing row. Đây là race condition handler — 2 thread cùng INSERT, chỉ 1 thắng, cái kia phải gracefully fallback.

- `execute_whenActionCompletes_writesSagaLogWithContextSnapshot()` — khi action COMPLETED, saga_compensation_log được ghi với đúng `context_snapshot` chứa output varIds. Verify `sagaManager.recordSuccess()` được gọi với đúng params.

---

### 4.4 `console-api/src/test/java/com/simi/console/feature/executor/step_execute/TriggerControllerTest.java`

Test class này verify HTTP contract của TriggerController — mọi response code đều phải được test.

**Setup:**

```java
@WebMvcTest(TriggerController.class)
class TriggerControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean TriggerUseCase triggerUseCase;
}
```

**Behaviors cần test:**

- `trigger_whenMissingXRequestIdHeader_returns400()` — request không có `X-Request-Id` header → HTTP 400. Backend không tự generate requestId — đây là contract với frontend.

  ```java
  @Test
  void trigger_whenMissingXRequestIdHeader_returns400() throws Exception {
      mockMvc.perform(post("/executions/1/triggers/10/execute")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isBadRequest());
  }
  ```

- `trigger_whenRequestIsInFlight_returns202()` — mock `triggerUseCase.execute()` throw `ExecutionAlreadyRunningException` → HTTP 202 với body `{"status": "processing"}`. Frontend dùng status này để keep spinner.

- `trigger_whenDeduplicatedRequest_returns200WithCachedResponse()` — mock useCase trả về cached response → HTTP 200 với body đầy đủ.

- `trigger_whenActionCompletes_returns200()` — happy path, mock trả về thành công → HTTP 200.

- `trigger_whenExecutionNotFound_returns404()` — mock throw `ExecutionNotFoundException` → HTTP 404.

- `trigger_whenInternalError_returns500()` — mock throw unchecked exception → HTTP 500, không leak stack trace ra response body.

---

### 4.5 `console-api/src/test/java/com/simi/console/feature/executor/action_execute/ActionExecutorIntegrationTest.java`

Full integration test với real PostgreSQL + Redis. Test này verify system-level invariants — đặc biệt là idempotency guarantee tại DB level.

**Behaviors cần test:**

- `execute_fullFlow_actionExecutionPersistedInDb()` — gọi use case với mock HTTP adapter, verify `action_execution` row được INSERT với đúng status, idempotency_key, scenario_execution_id.

- `execute_idempotencyGuarantee_secondCallReturnsCachedResult()` — gọi use case 2 lần với cùng inputs, verify chỉ 1 `action_execution` row tồn tại trong DB (UNIQUE constraint), lần 2 trả về cùng kết quả mà không gọi adapter lần nữa.

- `execute_concurrentRequests_onlyOneActionExecutionInserted()` — spawn 2 thread cùng gọi execute với cùng key, verify DB chỉ có đúng 1 row. Đây là race condition test quan trọng nhất của hệ thống.

- `execute_outputVarsWrittenToRedis()` — sau khi action COMPLETED, verify Redis có đúng key `exec:{execId}:{sourceType}:{srcId}:{varId}` với value đúng.

- `execute_whenFailed_sagaLogCreatedInDb()` — khi adapter trả về lỗi, verify `saga_compensation_log` row được tạo với status PENDING và `context_snapshot` chứa đúng dữ liệu.

---

## 5. Integration Test Pattern với Testcontainers

### Setup class — AbstractIntegrationTest

Tất cả integration test extend từ abstract base class này để tái sử dụng container lifecycle:

```java
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // PostgreSQL container — reuse across test class để tránh restart mỗi @Test
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("console_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);  // Testcontainers Reuse: giữ container giữa các run

    // Redis via GenericContainer (không có official module)
    @Container
    static final GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withReuse(true);

    // Override Spring datasource config với container's dynamic port
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
            () -> redis.getMappedPort(6379));
    }
}
```

**Lý do `@DynamicPropertySource`:** Testcontainers bind port ngẫu nhiên trên host, không biết trước lúc compile. `@DynamicPropertySource` inject đúng port vào Spring context sau khi container đã start.

### application-test.yaml — Test profile overrides

Tạo file `src/test/resources/application-test.yaml` (cho mỗi service):

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop  # recreate schema mỗi test run
    show-sql: true           # xem query khi debug

  # Tắt CloudWatch trong test — không có AWS credentials
  cloud:
    aws:
      cloudwatch:
        enabled: false
      s3:
        enabled: false

# Tắt Kafka auto-configuration — integration test dùng @MockBean cho consumer/producer
# trừ khi test cụ thể cần real Kafka (thêm KafkaContainer riêng)
spring.autoconfigure.exclude:
  - org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration

logging:
  level:
    com.simi: DEBUG          # verbose log khi test fail
    org.testcontainers: INFO
```

### Concrete integration test example

```java
class ActionExecutorIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActionExecutorUseCase useCase;
    @Autowired ActionExecutionRepository actionExecutionRepository;
    @Autowired RedisContextRepository redisContextRepository;

    // Mock HTTP adapter để không gọi real external service trong test
    @MockBean ServiceAdapterFactory adapterFactory;

    @BeforeEach
    void setUp() {
        // Mock adapter trả về success response
        ServiceAdapter mockAdapter = mock(ServiceAdapter.class);
        when(adapterFactory.getAdapter(any())).thenReturn(mockAdapter);
        when(mockAdapter.call(any())).thenReturn(
            AdapterResponse.success(Map.of("transactionId", "txn_01JWTEST"))
        );
    }

    @Test
    void execute_idempotencyGuarantee_secondCallReturnsCachedResult() {
        // Arrange
        ActionExecuteCommand command = buildTestCommand(
            executionId: 1L,
            actionDefinitionId: 100L,
            params: Map.of("userId", "user_01JW", "amount", "1000")
        );

        // Act — gọi 2 lần với cùng command
        ActionExecuteResult first = useCase.execute(command);
        ActionExecuteResult second = useCase.execute(command);

        // Assert — chỉ 1 row trong DB
        List<ActionExecution> rows = actionExecutionRepository
            .findByScenarioExecutionId(1L);
        assertThat(rows).hasSize(1);

        // Assert — 2 kết quả giống nhau
        assertThat(first.getTransactionId()).isEqualTo(second.getTransactionId());

        // Assert — adapter chỉ được gọi 1 lần (lần 2 return từ DB)
        verify(adapterFactory.getAdapter(any()), times(1)).call(any());
    }

    @Test
    void execute_concurrentRequests_onlyOneActionExecutionInserted()
            throws InterruptedException {
        ActionExecuteCommand command = buildTestCommand(...);

        // 2 thread cùng execute
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Future<ActionExecuteResult> f1 = pool.submit(() -> {
            latch.await();
            return useCase.execute(command);
        });
        Future<ActionExecuteResult> f2 = pool.submit(() -> {
            latch.await();
            return useCase.execute(command);
        });

        latch.countDown();  // release cả 2 thread cùng lúc
        f1.get();
        f2.get();

        // Assert — chỉ 1 row dù 2 thread cùng INSERT
        long count = actionExecutionRepository.countByScenarioExecutionId(1L);
        assertThat(count).isEqualTo(1);

        pool.shutdown();
    }
}
```

---

## 6. Coverage Target

### Target theo layer

| Layer | Tool | Target | Lý do |
|---|---|---|---|
| Use case / business logic | Mockito unit test | 80%+ | Core của application — phần thay đổi nhiều nhất |
| Idempotency paths (Tầng 2, 3, 4) | Integration test + Mockito | **100%** | Critical invariant — duplicate action = charge 2 lần |
| Controller layer | @WebMvcTest | 100% response codes | HTTP contract phải chính xác |
| Repository (custom queries) | @DataJpaTest | 100% custom @Query | Default Spring Data methods không cần |
| Domain entities / value objects | Pure unit test | 100% | Không có external deps, dễ test |
| Infrastructure adapters | Mockito + 1 integration test | 70%+ | Verify wiring đúng, không test 3rd party behavior |

### Idempotency là critical invariant — 100% coverage bắt buộc

Idempotency failure dẫn đến duplicate charge, duplicate coupon, duplicate order. Không phải bug gây lỗi giao diện — mà là bug gây **thiệt hại thực tế về tiền**. Do đó:

```
Mọi code path liên quan đến idempotency key generation, duplicate check,
race condition handling PHẢI có test cover.

Bao gồm:
- SHA256 determinism (param ordering)
- DB UNIQUE constraint violation handler
- Redis SETNX path (dedup:requestId)
- trigger_execution UNIQUE handler
- Concurrent INSERT race condition
```

### Đo coverage

```bash
# Gradle jacoco report
./gradlew :console-api:jacocoTestReport

# Report tại: console-api/build/reports/jacoco/test/html/index.html
```

Cấu hình minimum threshold trong `build.gradle`:

```groovy
jacocoCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = 'LINE'
                minimum = 0.80
            }
        }
        // Idempotency package — enforce 100%
        rule {
            element = 'PACKAGE'
            includes = ['com.simi.*.feature.executor.action_execute']
            limit {
                counter = 'BRANCH'
                minimum = 1.00
            }
        }
    }
}
```

---

## 7. What NOT to Test

### 7.1 Spring Data default methods

```java
// KHÔNG cần test:
actionExecutionRepository.findById(id);     // Spring Data cung cấp
actionExecutionRepository.save(entity);     // Spring Data cung cấp
actionExecutionRepository.deleteById(id);   // Spring Data cung cấp
```

Spring đã test những method này. Test chúng là test framework, không phải code của bạn.

### 7.2 Framework behavior

```java
// KHÔNG cần test:
// - Spring @Valid validation (Jackson + Bean Validation đã test)
// - Jackson serialization/deserialization (trừ custom serializer/deserializer)
// - Spring Security filter chain (trừ custom filter)
// - Hibernate mapping (đã verify qua @DataJpaTest nếu cần)
```

### 7.3 Mock behavior

```java
// KHÔNG cần test:
when(mockRepo.findById(1L)).thenReturn(Optional.of(entity));
// ... rồi assert rằng mock trả về entity đó

// Đây là test Mockito, không phải test code của bạn.
```

**Nguyên tắc:** Nếu test chỉ verify rằng mock trả về những gì bạn đã stub, test đó vô nghĩa.

### 7.4 Trivial getters/setters

```java
// KHÔNG cần test (trừ khi có logic trong getter):
entity.getUserId();
entity.setStatus(COMPLETED);
```

### 7.5 Tóm tắt — câu hỏi để tự check

> "Nếu implementation thay đổi theo cách sai, test này có fail không?"

Nếu câu trả lời là "không", test đó không có giá trị.

---

## 8. Chạy Tests

### Chạy từng module

```bash
# Common module (pure unit tests, domain logic)
./gradlew :common:test

# Console API (unit + slice tests)
./gradlew :console-api:test

# Backoffice API
./gradlew :backoffice-api:test

# Reconciliation service
./gradlew :reconciliation-service:test

# Tất cả modules
./gradlew test
```

### Chạy integration tests riêng (cần Docker)

```bash
# Integration tests được đặt trong sourceSet riêng
./gradlew :console-api:integrationTest

# Toàn bộ integration tests
./gradlew integrationTest
```

### Chạy 1 test class / method cụ thể

```bash
# Chạy 1 class
./gradlew :console-api:test --tests "com.simi.console.feature.executor.action_execute.ActionExecutorUseCaseTest"

# Chạy 1 method
./gradlew :console-api:test --tests "*.ActionExecutorUseCaseTest.execute_whenIdempotencyKeyExists_returnsCachedResponse"
```

### Coverage report

```bash
# Generate và verify coverage
./gradlew :console-api:test jacocoTestReport jacocoTestCoverageVerification

# Report HTML
open console-api/build/reports/jacoco/test/html/index.html
```

### Chạy nhanh không dừng khi có fail (xem toàn bộ lỗi)

```bash
./gradlew test --continue
```

---

## 9. CI Integration

### Yêu cầu môi trường CI

Testcontainers cần **Docker daemon** chạy trên CI agent. Nếu dùng:
- **GitHub Actions**: `ubuntu-latest` runner đã có Docker sẵn.
- **GitLab CI**: cần `docker:dind` service hoặc runner với Docker-in-Docker.
- **Jenkins**: agent phải mount `/var/run/docker.sock`.

### Ví dụ GitHub Actions workflow

```yaml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: ~/.gradle/caches
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*') }}

      - name: Run unit tests
        run: ./gradlew test

      - name: Run integration tests
        run: ./gradlew integrationTest
        # Docker đã available trên ubuntu-latest — Testcontainers sẽ pull image tự động

      - name: Upload coverage report
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: coverage-report
          path: '**/build/reports/jacoco/**'
```

### Testcontainers trong CI — lưu ý

**Image pull time:** Lần đầu chạy, Testcontainers pull `postgres:15-alpine` và `redis:7-alpine` (~50MB mỗi cái). Cache Docker layers trong CI để tăng tốc:

```yaml
- name: Cache Docker images
  uses: ScribeMD/docker-cache@0.5.0
  with:
    key: docker-${{ runner.os }}-${{ hashFiles('**/AbstractIntegrationTest.java') }}
```

**Container reuse:** Testcontainers Reuse (`withReuse(true)`) không hoạt động trong CI ephemeral environments (container bị xóa sau mỗi run). Chỉ dùng reuse trong local dev.

**Ryuk (resource reaper):** Testcontainers tự dọn container sau khi test xong qua Ryuk daemon. Không cần explicit cleanup trong `@AfterAll` khi dùng `@Container` annotation.

---

## 10. Interview Q&A

### Q1: Testing Pyramid là gì? Khi nào dùng integration test?

**A:** Testing Pyramid là model phân tầng test theo tốc độ và chi phí:

```
Nhiều test → Unit (ms, zero infra)
Vừa       → Integration / E2E (giây, cần infra)
Ít test   → Manual / UI test (phút, tốn người)
```

Dùng integration test khi:
1. **System-level invariant** cần verify với real infra — ví dụ: idempotency guarantee phải dùng real PostgreSQL UNIQUE constraint, không thể mock được
2. **Cross-layer behavior** cần kiểm tra — ví dụ: Lua script chạy atomic trên real Redis, không thể test với mock
3. **Data migration / schema** — verify ORM mapping đúng với actual DB engine

Không dùng integration test cho business logic thuần — Mockito unit test nhanh hơn 100x và cho kết quả tương đương.

---

### Q2: Tại sao không mock database trong integration test?

**A:** Mock database (H2 in-memory hoặc Mockito repo mock) cho false confidence:

1. **SQL dialect khác nhau:** H2 không support PostgreSQL-specific syntax (`ON CONFLICT DO NOTHING`, `RETURNING id`, `JSONB`, array operators). Test pass với H2 nhưng fail trên production PostgreSQL.

2. **Constraint behavior khác:** `UNIQUE` constraint violation trên H2 và PostgreSQL có thể throw exception khác nhau, ở thời điểm khác nhau trong transaction.

3. **Lua script atomicity:** Redis mock không thể verify Lua script atomic — đây là điểm core của 3-key model. Chỉ real Redis mới verify được.

4. **ACID isolation:** Race condition test (`execute_concurrentRequests_onlyOneActionExecutionInserted`) chỉ có nghĩa với real transaction isolation. H2 và mock không simulate concurrent transaction behavior chính xác.

**Kết luận:** Testcontainers với real PostgreSQL + Redis tốn thêm 5–10 giây per test class, nhưng đảm bảo test phản ánh đúng production behavior.

---

### Q3: Làm sao test race condition trong idempotency?

**A:** Race condition trong idempotency xảy ra khi 2 request concurrent cùng INSERT `action_execution` với cùng `idempotency_key`. Cách test:

**Bước 1:** Dùng `CountDownLatch` để release 2 thread cùng lúc:

```java
CountDownLatch startLatch = new CountDownLatch(1);
CountDownLatch doneLatch = new CountDownLatch(2);
List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

for (int i = 0; i < 2; i++) {
    new Thread(() -> {
        try {
            startLatch.await();  // chờ signal
            useCase.execute(command);
        } catch (DataIntegrityViolationException e) {
            exceptions.add(e);  // expected — một trong 2 sẽ throw
        } finally {
            doneLatch.countDown();
        }
    }).start();
}

startLatch.countDown();  // release cả 2 thread cùng lúc
doneLatch.await();
```

**Bước 2:** Assert DB state sau:

```java
long count = actionExecutionRepository.countByIdempotencyKey(key);
assertThat(count).isEqualTo(1);  // chỉ 1 row, dù 2 thread cùng INSERT
```

**Bước 3:** Assert exception handling — thread "thua" phải gracefully fallback về query kết quả của thread "thắng", không throw exception ra user.

**Lưu ý:** Test này chỉ có ý nghĩa với real PostgreSQL. Mock không simulate concurrent transaction isolation.

---

### Q4: @WebMvcTest vs @SpringBootTest — trade-offs?

**A:**

| | @WebMvcTest | @SpringBootTest |
|---|---|---|
| Scope | MVC layer only (Controller, Filter, ExceptionHandler) | Toàn bộ application context |
| Startup time | ~1 giây | ~5–15 giây |
| DB, Redis | Không load (phải @MockBean) | Load nếu có config |
| Dùng khi | Test HTTP contract (status code, request/response format) | Test full application flow, integration |
| Nhược điểm | Phải @MockBean mọi dependency → test isolate, không catch wiring bug | Chậm, cần infra nếu không mock |

**Quyết định:**
- `@WebMvcTest` cho tất cả Controller tests — verify HTTP contract nhanh, không cần DB
- `@SpringBootTest` chỉ cho integration test thực sự cần full context — kết hợp với Testcontainers

**Pitfall hay gặp:** dùng `@SpringBootTest` cho mọi test vì "đơn giản hơn" → test suite chậm 10x không cần thiết.

---

### Q5: Testcontainers vs H2 — khi nào dùng cái nào?

**A:**

| | H2 In-Memory | Testcontainers PostgreSQL |
|---|---|---|
| Tốc độ startup | ~0.1 giây | ~3–5 giây (lần đầu pull image) |
| SQL compatibility | H2 dialect (khác PostgreSQL) | 100% PostgreSQL |
| PostgreSQL features | Không support JSONB, arrays, ON CONFLICT | Full support |
| Thích hợp cho | Test không cần DB-specific feature | Test cần PostgreSQL behavior chính xác |
| CI friendly | Không cần Docker | Cần Docker daemon |

**Khuyến nghị cho project này:**

Dùng **Testcontainers PostgreSQL** cho toàn bộ integration test — lý do:
1. Schema dùng JSONB, `ON CONFLICT DO NOTHING RETURNING id`, array types — H2 không support
2. Race condition test cần real transaction isolation
3. Testcontainers với `withReuse(true)` trong local dev giảm overhead startup xuống gần bằng H2 sau lần đầu

H2 chỉ chấp nhận cho project đơn giản không dùng DB-specific feature. Console project không thuộc category đó.

---

### Q6: Phân biệt Mock, Stub, Spy, Fake, Dummy — 5 loại Test Double

**A:** Thuật ngữ của Gerard Meszaros — mỗi loại có mục đích khác nhau:

| Test Double | Mục đích | Mockito tương đương |
|---|---|---|
| **Dummy** | Truyền vào để fulfill signature, không bao giờ được dùng | `null`, `mock()` không stub gì |
| **Stub** | Trả về giá trị cố định, không assert interaction | `when(...).thenReturn(...)` |
| **Mock** | Verify interaction — assert method được gọi đúng số lần, đúng args | `verify(mock).method(args)` |
| **Spy** | Wrap real object, gọi real method trừ những method bị stub | `@Spy` / `spy(realObject)` |
| **Fake** | Implementation thật nhưng đơn giản hóa — ví dụ in-memory repository | Custom class, không có Mockito builtin |

**Ví dụ phân biệt Stub vs Mock:**

```java
// STUB — chỉ cần nó trả về giá trị đúng, không quan tâm bị gọi bao nhiêu lần
when(actionExecutions.findByIdempotencyKey(anyString()))
    .thenReturn(Optional.of(cached));

// MOCK — verify hành vi: adapter phải được gọi đúng 1 lần, không hơn
verify(mockAdapter, times(1)).execute(any());
verify(sagaLogs, never()).save(any());  // assert NOT called
```

**Khi nào dùng Spy thay vì Mock:**

```java
// Spy khi cần test 1 method của class nhưng muốn gọi real implementation của method khác
@Spy ObjectMapper objectMapper = new ObjectMapper();  // real ObjectMapper
// → @InjectMocks inject real ObjectMapper vào class under test
// → Không phải mock toàn bộ — chỉ partial mock những gì cần
```

**Fake dùng khi nào:** Khi behavior của Stub quá phức tạp để stub (nhiều case khác nhau tùy input) và Testcontainers quá nặng. Ví dụ: `InMemoryContextStore` implement `ContextStore` interface, dùng `HashMap` thay Redis.

---

### Q7: @Mock vs @Spy vs @InjectMocks — khi nào dùng cái nào?

**A:**

```java
@ExtendWith(MockitoExtension.class)
class ActionExecutorTest {

    @Mock ActionExecutionRepository actionExecutions;
    // → Toàn bộ methods return default: null/0/false/Optional.empty()
    // → Phải stub tất cả những gì bạn muốn nó trả về

    @Spy ObjectMapper objectMapper = new ObjectMapper();
    // → Gọi REAL methods của ObjectMapper
    // → Chỉ override những method bạn muốn via doReturn()
    // → Phải khởi tạo ngay (new ObjectMapper()) — không thể spy interface

    @InjectMocks ActionExecutor actionExecutor;
    // → Mockito tạo instance ActionExecutor
    // → Inject tất cả @Mock và @Spy vào fields (constructor injection hoặc field injection)
    // → Thứ tự inject: constructor → setter → field
}
```

**Pitfall với @InjectMocks:**

```java
// ❌ WRONG — @InjectMocks KHÔNG fail nếu inject thiếu field
// Nếu ActionExecutor cần SagaManager nhưng không có @Mock SagaManager,
// field đó sẽ là null → NullPointerException ở runtime, không phải compile time

// ✅ Verify constructor injection bằng cách khai báo explicit constructor trong production code
// → Mockito sẽ dùng constructor, thiếu dependency sẽ fail ngay khi tạo @InjectMocks
```

**Khi nào KHÔNG dùng @InjectMocks:**

Khi class cần được tạo với specific configuration phức tạp — dùng `new` trực tiếp trong `@BeforeEach`:

```java
@BeforeEach
void setUp() {
    actionExecutor = new ActionExecutor(
        actionExecutions, adapters, sagaLogs,
        resolver, contextStore,
        new ObjectMapper()  // real mapper
    );
}
```

---

### Q8: ArgumentCaptor — khi nào dùng, khi nào KHÔNG nên dùng?

**A:** `ArgumentCaptor` capture argument được truyền vào mock để assert nội dung bên trong.

**Dùng khi:** cần verify giá trị bên trong object phức tạp được truyền vào mock.

```java
@Test
void execute_whenCompleted_savesCorrectSagaLog() {
    // ... setup ...

    actionExecutor.execute(input);

    // Capture SagaCompensationLog được truyền vào sagaLogs.save()
    ArgumentCaptor<SagaCompensationLog> captor =
        ArgumentCaptor.forClass(SagaCompensationLog.class);
    verify(sagaLogs).save(captor.capture());

    SagaCompensationLog saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(CompensationStatus.PENDING);
    assertThat(saved.getStepOrder()).isEqualTo(1);
    assertThat(saved.getContextSnapshot()).isNotNull();
    // Verify context_snapshot chứa đúng output varIds
    assertThat(saved.getContextSnapshot().get("VAR_COUPON_01")).isNotNull();
}
```

**KHÔNG dùng khi:** bạn chỉ muốn verify method được gọi, không cần check argument bên trong. `verify(sagaLogs).save(any())` đơn giản hơn và đủ.

**Pitfall:** `ArgumentCaptor` đánh bại mục đích của type system — nếu bạn thấy mình capture rồi assert quá nhiều field, đó là dấu hiệu test đang làm quá nhiều việc. Tách thành nhiều test nhỏ hơn hoặc đổi sang test behavior thay vì implementation detail.

---

### Q9: @MockBean vs @Mock — khác nhau gì?

**A:**

| | `@Mock` (Mockito) | `@MockBean` (Spring Boot Test) |
|---|---|---|
| Context | Không cần Spring context | Cần Spring context (`@SpringBootTest`, `@WebMvcTest`) |
| Cách hoạt động | Tạo Mockito mock thuần, inject vào `@InjectMocks` | Tạo Mockito mock VÀ đăng ký vào Spring ApplicationContext, replace bean thật |
| Reset | Mockito reset tự động sau mỗi test (với `MockitoExtension`) | Spring context được reload nếu context configuration thay đổi |
| Tốc độ | Rất nhanh (không spin context) | Chậm hơn (cần context startup) |

```java
// @Mock — dùng với @ExtendWith(MockitoExtension.class), không cần Spring
@ExtendWith(MockitoExtension.class)
class ActionExecutorTest {
    @Mock ActionExecutionRepository repo;  // Mockito mock, không phải Spring bean
    @InjectMocks ActionExecutor useCase;
}

// @MockBean — dùng với @WebMvcTest / @SpringBootTest
@WebMvcTest(TriggerController.class)
class TriggerControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean TriggerUseCase triggerUseCase;  // replace Spring bean TriggerUseCase bằng mock
}
```

**Quan trọng:** `@MockBean` làm Spring context bị reset và rebuild nếu bạn dùng nó trong nhiều test class khác nhau với configuration khác nhau. Đây là nguyên nhân phổ biến làm test suite chậm không rõ lý do.

---

### Q10: @Transactional trong test — có nên dùng không?

**A:** `@Transactional` trên test method tự động rollback transaction sau mỗi test — data không persist sang test tiếp theo.

**Khi dùng @Transactional trong test CÓ lợi:**

```java
@DataJpaTest  // auto-applies @Transactional
class ActionExecutionRepositoryTest {
    @Test
    void findByScenarioExecutionId_returnsCorrectRows() {
        // INSERT → query → assert → AUTO ROLLBACK
        // Không cần cleanup trong @AfterEach
    }
}
```

**Khi @Transactional trong test GÂY VẤN ĐỀ:**

```java
// ❌ Sai — test này verify COMMITTED data trong DB
// Nhưng @Transactional trên test bọc cả use case execution trong 1 transaction lớn hơn
// → behavior không giống production (nơi use case commit độc lập)
@Test
@Transactional  // ← KHÔNG nên ở đây
void execute_idempotencyGuarantee_secondCallReturnsCachedResult() {
    // use case call 1 → INSERT action_execution
    // use case call 2 → findByIdempotencyKey
    // Nhưng vì cùng transaction, "visibility" của INSERT khác với production
}
```

**Race condition test + @Transactional = sai:**

Concurrent test dùng nhiều thread → nhiều transaction → `@Transactional` trên test thread không wrap các transaction của worker threads. Test chạy nhưng không phản ánh đúng production isolation.

**Quy tắc:**
- `@DataJpaTest`: dùng `@Transactional` — fine vì test simple repository behavior
- `@SpringBootTest` integration test với Testcontainers: KHÔNG dùng `@Transactional` — cleanup bằng `@BeforeEach` xóa data, hoặc dùng `@Sql` annotation

---

### Q11: FIRST principles — unit test tốt cần gì?

**A:** FIRST là 5 tiêu chí của unit test chất lượng cao:

| Chữ | Nguyên tắc | Dấu hiệu vi phạm |
|---|---|---|
| **F**ast | Chạy trong milliseconds | Đọc file, sleep(), gọi HTTP, spin Spring context |
| **I**solated | Không phụ thuộc vào test khác, chạy bất kỳ thứ tự nào | Test fail khi chạy đơn lẻ nhưng pass khi chạy cùng suite |
| **R**epeatable | Cùng kết quả mọi lúc, bất kể môi trường | Dùng `new Date()` / `Math.random()` trực tiếp trong production code mà không inject |
| **S**elf-validating | Pass hoặc Fail rõ ràng, không cần manual check | Test print ra output rồi người phải tự nhìn |
| **T**imely | Viết cùng lúc với code, không phải sau | "Sẽ viết test sau" — thường không bao giờ viết |

**Repeatable — cách fix non-determinism:**

```java
// ❌ Non-deterministic — test có thể fail tùy timezone, time của máy
class ActionExecutorTest {
    void execute_setsStartedAt_toCurrentTime() {
        actionExecutor.execute(input);
        // assertion về LocalDateTime.now() — flaky!
    }
}

// ✅ Inject Clock — deterministic trong test
class ActionExecutor {
    private final Clock clock;  // inject

    void execute(...) {
        execution.setStartedAt(LocalDateTime.now(clock));  // testable
    }
}

// Test dùng fixed clock
Clock fixedClock = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);
ActionExecutor useCase = new ActionExecutor(..., fixedClock);
```

---

### Q12: Flaky test là gì? Nguyên nhân phổ biến và cách fix?

**A:** Flaky test là test đôi khi pass, đôi khi fail mà không thay đổi code — giảm trust vào test suite, thường bị skip hoặc ignore dẫn đến mất giá trị.

**Nguyên nhân phổ biến:**

**1. Shared state giữa các test:**
```java
// ❌ Flaky — test A modify static field, test B đọc field đó
static Map<String, String> sharedCache = new HashMap<>();

@Test void testA() { sharedCache.put("key", "val"); }
@Test void testB() { assertThat(sharedCache).isEmpty(); }  // fail nếu A chạy trước
```

**2. Thứ tự test không đảm bảo:**
```java
// ❌ Flaky — testB cần data từ testA
@Test void testA() { repository.save(entity); }
@Test void testB() { assertThat(repository.findAll()).hasSize(1); }  // fail nếu B chạy trước A
```

**3. Non-determinism (time, random):** (đã cover ở Q11)

**4. Race condition trong concurrent test:**
```java
// ❌ Flaky — sleep không đủ reliable
CompletableFuture.runAsync(() -> useCase.execute(cmd));
Thread.sleep(100);  // không đảm bảo async task đã finish
assertThat(repository.count()).isEqualTo(1);  // sometimes fails
```

**Fix:**
```java
// ✅ Dùng CountDownLatch hoặc CompletableFuture.get() để wait properly
CompletableFuture<Void> future = CompletableFuture.runAsync(() -> useCase.execute(cmd));
future.get(5, TimeUnit.SECONDS);  // deterministic wait
assertThat(repository.count()).isEqualTo(1);
```

**5. Port conflict trong integration test:**
```java
// ❌ Flaky nếu port 8080 đã bị occupied
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)  // hardcoded port

// ✅ Dùng RANDOM_PORT
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
```

**Cách phát hiện:** Chạy test 10 lần liên tục: `./gradlew test --rerun-tasks` (hoặc trong CI, repeat: 3). Nếu fail rate > 0 mà không thay đổi code → flaky.

---

### Q13: @ParameterizedTest — khi nào dùng?

**A:** Dùng khi cùng 1 behavior cần verify với nhiều input khác nhau — tránh copy-paste test.

**Ví dụ phù hợp — test idempotency key với nhiều param combinations:**

```java
@ParameterizedTest
@MethodSource("paramVariants")
void generateKey_withSameInputsSorted_returnsSameHash(Map<String, String> params1,
                                                       Map<String, String> params2) {
    String key1 = generator.generate(1L, 100L, params1);
    String key2 = generator.generate(1L, 100L, params2);
    assertThat(key1).isEqualTo(key2);
}

static Stream<Arguments> paramVariants() {
    return Stream.of(
        Arguments.of(
            Map.of("userId", "u1", "amount", "1000"),
            Map.of("amount", "1000", "userId", "u1")   // same, different order
        ),
        Arguments.of(
            Map.of("a", "1", "b", "2", "c", "3"),
            Map.of("c", "3", "a", "1", "b", "2")       // same, different order
        )
    );
}
```

**Test HTTP status codes với @CsvSource:**

```java
@ParameterizedTest
@CsvSource({
    "COMPLETED, 200",
    "PROCESSING, 202",
    "FAILED, 500"
})
void trigger_mapsUseCaseStatusToHttpStatus(String status, int expectedHttpStatus) throws Exception {
    when(triggerUseCase.execute(any())).thenReturn(new TriggerResult(Status.valueOf(status)));
    mockMvc.perform(post("/executions/1/triggers/1/execute")
            .header("X-Request-Id", "req-123"))
        .andExpect(status().is(expectedHttpStatus));
}
```

**Khi KHÔNG dùng:** mỗi input cần setup/assert khác nhau đáng kể — lúc này các test method riêng biệt rõ ràng hơn.

---

### Q14: Test coverage 100% có đảm bảo code không bug không?

**A:** Không. Coverage 100% chỉ có nghĩa là mỗi line đã được execute ít nhất 1 lần — không có nghĩa là tất cả behaviors đã được verify.

**Ví dụ code 100% line coverage nhưng vẫn bug:**

```java
// Production code
int divide(int a, int b) {
    return a / b;
}

// Test — 100% line coverage nhưng thiếu test case
@Test
void divide_twoPositives_returnsQuotient() {
    assertThat(divide(10, 2)).isEqualTo(5);  // line được execute → 100% coverage
    // Nhưng divide(10, 0) → ArithmeticException chưa được test!
}
```

**Coverage là necessary, không phải sufficient:**

- **Line coverage**: mọi line được execute (dễ đạt, ít giá trị)
- **Branch coverage**: mọi if/else branch (tốt hơn — 100% branch coverage cho idempotency code)
- **Mutation coverage**: test có fail khi code bị mutate không? (tốt nhất, tốn compute)

**Thực tế tốt hơn coverage %:**

Thay vì "đạt 80% coverage", hỏi: *"Nếu business invariant X bị vi phạm, có test nào fail không?"*

Với project này:
- Duplicate action execution (idempotency fail) → có test? ✓
- TIMEOUT trigger Saga (sai design) → có test verify sagaManager KHÔNG được gọi? ✓
- `null` varId trong context → có test? ✓

---

### Q15: TDD (Test-Driven Development) — có nên áp dụng không?

**A:** TDD là cycle: viết test fail → viết code minimal để pass → refactor. Không phải silver bullet — có trade-offs cụ thể.

**Khi TDD có giá trị cao:**

1. **Domain logic phức tạp** — IdempotencyKeyGenerator, ParameterResolver: viết test trước force bạn suy nghĩ về edge cases trước khi code
2. **Bug fix** — luôn viết test reproduce bug trước khi fix: đảm bảo bug không quay lại (regression test)
3. **Refactor safety** — có test suite đầy đủ → refactor tự tin

**Khi TDD ít có giá trị:**

1. **Exploratory code** — lúc bạn chưa biết API sẽ trông như thế nào (Spike): viết code trước để khám phá, sau đó viết test
2. **Infrastructure wiring** — `AbstractIntegrationTest`, Docker setup: không có domain logic để TDD
3. **UI layer** — Controller test: viết controller trước thường tự nhiên hơn

**Approach thực tế cho project này:**

```
Use Case / Business logic (ActionExecutor, ParameterResolver) → TDD
Controller HTTP contract                                       → Test sau (contract rõ ràng từ spec)
Integration test (idempotency)                                 → Test sau (cần infra setup trước)
Repository custom queries                                      → Viết query → test query → iterate
```

**Red-Green-Refactor trong practice:**

```java
// RED: viết test fail
@Test
void generateKey_withParamsDifferentOrder_returnsSameHash() {
    // Test này fail vì IdempotencyKeyGenerator chưa sort params
    assertThat(generator.generate(1L, 1L, Map.of("b","2","a","1")))
        .isEqualTo(generator.generate(1L, 1L, Map.of("a","1","b","2")));
}

// GREEN: thêm sort trong implementation
// params.entrySet().stream()
//   .sorted(Map.Entry.comparingByKey())
//   .collect(...)

// REFACTOR: extract method, improve naming — test vẫn xanh
```

---

## Tham khảo thêm

- [05-idempotency.md](05-idempotency.md) — 4 tầng idempotency cần test đầy đủ
- [03-execution-engine.md](03-execution-engine.md) — Execution flows, business logic cần cover
- [04-context-redis.md](04-context-redis.md) — Redis 3-key model, Lua script atomicity
- [06-saga-compensation.md](06-saga-compensation.md) — Saga trigger conditions cần test
- [08-database.md](08-database.md) — Schema, constraints, custom queries cần test
