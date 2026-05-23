package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.domain.entity.ActionDefinition;
import com.simi.common.domain.entity.ActionExecution;
import com.simi.common.domain.entity.ScenarioExecution;
import com.simi.common.domain.entity.Service;
import com.simi.console.feature.context.ScenarioExecutionRepository;
import com.simi.console.observability.S3ClaimCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Spring Boot context đầy đủ với PostgreSQL + Redis thật (Testcontainers).
 *
 * Mục tiêu: verify idempotency guarantee tại DB level. Unit test (@ExtendWith Mockito)
 * không thể catch bug ở tầng này vì UNIQUE constraint và concurrent insert là DB behavior,
 * không phải Java logic.
 *
 * Convention: @BeforeEach clean DB state để test độc lập nhau.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class ActionExecutorIntegrationTest {

    // Dùng static container để reuse giữa các test method trong cùng class — nhanh hơn.
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("console_test")
            .withUsername("console")
            .withPassword("console");

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    // S3ClaimCheckService sử dụng AWS SDK — mock để tránh connect LocalStack trong CI
    @MockitoBean S3ClaimCheckService s3ClaimCheckService;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host",     redis::getHost);
        registry.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
    }

    @Autowired ActionExecutor actionExecutor;
    @Autowired ActionExecutionRepository actionExecutionRepo;
    @Autowired ActionDefinitionRepository actionDefinitionRepo;
    @Autowired ServiceRepository serviceRepo;
    @Autowired ScenarioExecutionRepository scenarioExecutionRepo;
    @Autowired ObjectMapper json;

    private Long serviceId;
    private Long actionDefinitionId;
    private Long executionId;

    @BeforeEach
    void setUp() {
        actionExecutionRepo.deleteAll();
        // Lưu thứ tự: Service → ActionDefinition → ScenarioExecution
        Service svc = new Service();
        svc.setName("coupon-service-" + System.nanoTime()); // unique per test
        svc.setBaseUrl("http://coupon-service");
        serviceId = serviceRepo.save(svc).getId();

        ActionDefinition def = new ActionDefinition();
        def.setName("issue-coupon");
        def.setServiceId(serviceId);
        def.setAdapterType("mock"); // dùng MockAdapter — không gọi HTTP thật
        def.setParameters(json.createArrayNode());
        def.setOutput(json.createArrayNode().add(
                json.createObjectNode()
                        .put("field", "couponId")
                        .put("varId", "VAR_COUPON_OUT_01")
                        .put("type", "string")));
        actionDefinitionId = actionDefinitionRepo.save(def).getId();

        ScenarioExecution exec = new ScenarioExecution();
        exec.setScenarioDefinitionId(1L);
        exec.setCreatedBy("test-user");
        exec.setStatus(com.simi.common.domain.ScenarioExecutionStatus.EXECUTING);
        exec.setContext(json.createObjectNode());
        executionId = scenarioExecutionRepo.save(exec).getId();
    }

    @Test
    void execute_firstCall_createsActionExecution_withCOMPLETED() {
        ActionExecution result = actionExecutor.execute(input(executionId, actionDefinitionId));

        assertThat(result.getStatus()).isEqualTo(ActionExecutionStatus.COMPLETED);
        assertThat(result.getId()).isNotNull();
        assertThat(result.getIdempotencyKey()).hasSize(64); // SHA256 hex

        // Verify DB row exists
        assertThat(actionExecutionRepo.findById(result.getId())).isPresent();
    }

    @Test
    void execute_calledTwice_withSameInput_returnsExactSameRow() {
        // Đây là invariant cốt lõi của Tầng 4 idempotency:
        // dù gọi bao nhiêu lần với cùng executionId + actionDefinitionId + params
        // → chỉ 1 action_execution row trong DB
        ActionExecution first  = actionExecutor.execute(input(executionId, actionDefinitionId));
        ActionExecution second = actionExecutor.execute(input(executionId, actionDefinitionId));

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getIdempotencyKey()).isEqualTo(second.getIdempotencyKey());

        long count = actionExecutionRepo.findAll().stream()
                .filter(r -> r.getScenarioExecutionId().equals(executionId))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void execute_differentExecutionIds_createSeparateRows() {
        // Hai execution khác nhau → hai action_execution rows riêng, không nhầm lẫn
        ScenarioExecution exec2 = new ScenarioExecution();
        exec2.setScenarioDefinitionId(1L);
        exec2.setCreatedBy("test-user");
        exec2.setStatus(com.simi.common.domain.ScenarioExecutionStatus.EXECUTING);
        exec2.setContext(json.createObjectNode());
        Long executionId2 = scenarioExecutionRepo.save(exec2).getId();

        ActionExecution r1 = actionExecutor.execute(input(executionId, actionDefinitionId));
        ActionExecution r2 = actionExecutor.execute(input(executionId2, actionDefinitionId));

        assertThat(r1.getId()).isNotEqualTo(r2.getId());
        assertThat(r1.getIdempotencyKey()).isNotEqualTo(r2.getIdempotencyKey());
    }

    @Test
    void execute_outputVars_areAvailable_inContext() {
        // MockAdapter returns fake values for each output field;
        // ActionExecutor saves them to ContextStore (Redis + DB).
        // Verify response_snapshot contains the output.
        ActionExecution result = actionExecutor.execute(input(executionId, actionDefinitionId));

        assertThat(result.getStatus()).isEqualTo(ActionExecutionStatus.COMPLETED);
        assertThat(result.getResponseSnapshot()).isNotNull();
        assertThat(result.getResponseSnapshot().has("couponId")).isTrue();
    }

    // ─── helpers ───

    private ActionExecutor.ExecuteInput input(Long execId, Long actDefId) {
        return new ActionExecutor.ExecuteInput(
                execId, 1L, "step1", 1, null, actDefId, List.of());
    }
}
