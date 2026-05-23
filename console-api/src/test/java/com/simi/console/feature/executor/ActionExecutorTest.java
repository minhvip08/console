package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.domain.entity.*;
import com.simi.console.feature.adapter.AdapterRegistry;
import com.simi.console.feature.adapter.AdapterResult;
import com.simi.console.feature.adapter.ServiceAdapter;
import com.simi.console.feature.compensation.SagaLogRepository;
import com.simi.console.feature.context.ContextStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test — tất cả dependencies đều mocked. Chỉ test business logic của ActionExecutor:
 * - 4 path idempotency (cache hit / first call / race condition)
 * - Status propagation (COMPLETED, FAILED)
 * - Saga log write khi COMPLETED + có compensation def
 */
@ExtendWith(MockitoExtension.class)
class ActionExecutorTest {

    @Mock ActionDefinitionRepository actionDefs;
    @Mock ServiceRepository services;
    @Mock RetryPolicyRepository retryPolicies;
    @Mock CompensationDefinitionRepository compensationDefs;
    @Mock ActionExecutionRepository actionExecutions;
    @Mock SagaLogRepository sagaLogs;
    @Mock ParameterResolver resolver;
    @Mock AdapterRegistry adapters;
    @Mock ServiceAdapter mockAdapter;
    @Mock ContextStore contextStore;
    // ObjectMapper là concrete class dùng thật — @Spy để @InjectMocks inject đúng
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks ActionExecutor actionExecutor;

    private final ObjectMapper json = new ObjectMapper();

    // ─── Tầng 4 Idempotency ───

    @Test
    void execute_whenIdempotencyKeyExists_returnsCachedWithoutCallingAdapter() {
        stubActionDef(1L, 99L);
        stubResolver();

        ActionExecution cached = execution(ActionExecutionStatus.COMPLETED);
        when(actionExecutions.findByIdempotencyKey(anyString())).thenReturn(Optional.of(cached));

        ActionExecution result = actionExecutor.execute(input(1L, 1L));

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(adapters, mockAdapter, sagaLogs);
    }

    @Test
    void execute_firstCall_callsAdapter_andReturns_COMPLETED() {
        ActionDefinition def = stubActionDef(1L, 99L);
        stubResolver();
        when(actionExecutions.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(actionExecutions.saveAndFlush(any())).thenAnswer(inv -> {
            ActionExecution row = inv.getArgument(0);
            row.setId(42L);
            return row;
        });
        when(services.findById(99L)).thenReturn(Optional.of(service(99L)));
        when(adapters.forType("mock")).thenReturn(mockAdapter);

        ObjectNode responseBody = json.createObjectNode().put("couponId", "C001");
        when(mockAdapter.execute(any())).thenReturn(AdapterResult.completed(200, responseBody, 100));
        when(actionExecutions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(compensationDefs.findByActionDefinitionId(anyLong())).thenReturn(Optional.empty());

        ActionExecution result = actionExecutor.execute(input(1L, 1L));

        assertThat(result.getStatus()).isEqualTo(ActionExecutionStatus.COMPLETED);
        assertThat(result.getResponseSnapshot()).isNotNull();
        // Output var "couponId" should be saved to context
        verify(contextStore, atLeastOnce()).appendItem(anyString(), any(ContextDataItem.class));
    }

    @Test
    void execute_whenAdapter_returns503_saves_FAILED_status() {
        stubActionDef(1L, 99L);
        stubResolver();
        when(actionExecutions.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(actionExecutions.saveAndFlush(any())).thenAnswer(inv -> {
            ((ActionExecution) inv.getArgument(0)).setId(42L);
            return inv.getArgument(0);
        });
        when(services.findById(99L)).thenReturn(Optional.of(service(99L)));
        when(adapters.forType("mock")).thenReturn(mockAdapter);
        when(mockAdapter.execute(any())).thenReturn(
                AdapterResult.failed(503, null, "Service Unavailable", 200));
        when(actionExecutions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActionExecution result = actionExecutor.execute(input(1L, 1L));

        assertThat(result.getStatus()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("Service Unavailable");
        // FAILED → không ghi saga log ở đây (CompensationManager trigger ở StepExecutor)
        verifyNoInteractions(sagaLogs);
    }

    @Test
    void execute_whenAdapter_timesOut_saves_TIMEOUT_status() {
        stubActionDef(1L, 99L);
        stubResolver();
        when(actionExecutions.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(actionExecutions.saveAndFlush(any())).thenAnswer(inv -> {
            ((ActionExecution) inv.getArgument(0)).setId(42L);
            return inv.getArgument(0);
        });
        when(services.findById(99L)).thenReturn(Optional.of(service(99L)));
        when(adapters.forType("mock")).thenReturn(mockAdapter);
        when(mockAdapter.execute(any())).thenReturn(
                AdapterResult.timeout("Connection timed out", 10000));
        when(actionExecutions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActionExecution result = actionExecutor.execute(input(1L, 1L));

        // TIMEOUT → Reconciler handle async, không phải Saga (docs/06, 07)
        assertThat(result.getStatus()).isEqualTo(ActionExecutionStatus.TIMEOUT);
        verifyNoInteractions(sagaLogs);
    }

    @Test
    void execute_whenRaceConditionOnInsert_fetchesCachedRow() {
        // Hai thread concurrently check → cả hai thấy Optional.empty() → race to INSERT
        // Thread thua nhận DataIntegrityViolationException → fetch winner's row
        stubActionDef(1L, 99L);
        stubResolver();

        ActionExecution winner = execution(ActionExecutionStatus.PROCESSING);
        when(actionExecutions.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty())                // first check: empty
                .thenReturn(Optional.of(winner));            // after race: winner row
        when(actionExecutions.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        ActionExecution result = actionExecutor.execute(input(1L, 1L));

        assertThat(result).isSameAs(winner);
        verifyNoInteractions(adapters, mockAdapter);
    }

    @Test
    void execute_whenCompleted_writesSagaLog_ifCompensationExists() {
        ActionDefinition def = stubActionDef(1L, 99L);
        stubResolver();
        when(actionExecutions.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(actionExecutions.saveAndFlush(any())).thenAnswer(inv -> {
            ((ActionExecution) inv.getArgument(0)).setId(42L);
            return inv.getArgument(0);
        });
        when(services.findById(99L)).thenReturn(Optional.of(service(99L)));
        when(adapters.forType("mock")).thenReturn(mockAdapter);
        when(mockAdapter.execute(any())).thenReturn(
                AdapterResult.completed(200, json.createObjectNode(), 50));
        when(actionExecutions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActionCompensation comp = new ActionCompensation();
        comp.setActionDefinitionId(1L);
        comp.setRequiredVarIds(new String[]{"VAR_COUPON_ID"});
        when(compensationDefs.findByActionDefinitionId(1L)).thenReturn(Optional.of(comp));
        when(contextStore.readPreviousSteps(anyString(), anyInt())).thenReturn(List.of());

        actionExecutor.execute(input(1L, 1L));

        verify(sagaLogs).save(any(SagaCompensationLog.class));
    }

    @Test
    void execute_whenCompleted_noCompensationDef_doesNotWriteSagaLog() {
        stubActionDef(1L, 99L);
        stubResolver();
        when(actionExecutions.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(actionExecutions.saveAndFlush(any())).thenAnswer(inv -> {
            ((ActionExecution) inv.getArgument(0)).setId(42L);
            return inv.getArgument(0);
        });
        when(services.findById(99L)).thenReturn(Optional.of(service(99L)));
        when(adapters.forType("mock")).thenReturn(mockAdapter);
        when(mockAdapter.execute(any())).thenReturn(
                AdapterResult.completed(200, json.createObjectNode(), 50));
        when(actionExecutions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(compensationDefs.findByActionDefinitionId(anyLong())).thenReturn(Optional.empty());

        actionExecutor.execute(input(1L, 1L));

        verifyNoInteractions(sagaLogs);
    }

    // ─── helpers ───

    private ActionDefinition stubActionDef(Long id, Long serviceId) {
        ActionDefinition def = new ActionDefinition();
        def.setId(id);
        def.setName("issue-coupon");
        def.setServiceId(serviceId);
        def.setAdapterType("mock");
        def.setParameters(json.createArrayNode());
        def.setOutput(json.createArrayNode().add(
                json.createObjectNode()
                        .put("field", "couponId")
                        .put("varId", "VAR_COUPON_01")
                        .put("type", "string")));
        when(actionDefs.findById(id)).thenReturn(Optional.of(def));
        return def;
    }

    private void stubResolver() {
        when(resolver.resolve(any(), any())).thenReturn(
                new ParameterResolver.Resolved(
                        Map.of(), Map.of(), Map.of(), json.createObjectNode(), Map.of()));
    }

    private Service service(Long id) {
        Service svc = new Service();
        svc.setId(id);
        svc.setBaseUrl("http://mock-service");
        return svc;
    }

    private ActionExecution execution(ActionExecutionStatus status) {
        ActionExecution exec = new ActionExecution();
        exec.setId(99L);
        exec.setStatus(status);
        exec.setIdempotencyKey("someKey");
        return exec;
    }

    private ActionExecutor.ExecuteInput input(Long executionId, Long actionDefinitionId) {
        return new ActionExecutor.ExecuteInput(
                executionId, 1L, "step1", 1, null, actionDefinitionId, List.of());
    }
}
