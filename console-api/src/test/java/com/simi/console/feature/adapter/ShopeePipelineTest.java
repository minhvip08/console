package com.simi.console.feature.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.simi.common.domain.*;
import com.simi.common.domain.entity.*;
import com.simi.common.shopee.*;
import com.simi.console.feature.compensation.*;
import com.simi.console.feature.context.*;
import com.simi.console.feature.executor.*;
import com.simi.console.feature.rbac.PermissionService;
import com.simi.console.feature.shopee.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real guard/resolver/adapter/executor/context pipeline with in-memory mocked infrastructure. */
class ShopeePipelineTest {
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    final Clock clock=Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"),ZoneOffset.UTC);
    final ShopeeMockPolicy policy=new ShopeeMockPolicy(true,"admin",clock);
    final ShopeeSourceRepository sources=mock(ShopeeSourceRepository.class);
    final ShopeeConnectionRepository connections=mock(ShopeeConnectionRepository.class);
    final ShopeeRunRepository runs=mock(ShopeeRunRepository.class);
    final ScenarioExecutionRepository executions=mock(ScenarioExecutionRepository.class);
    final TriggerRepository triggers=mock(TriggerRepository.class);
    final StepDefinitionRepository steps=mock(StepDefinitionRepository.class);
    final TriggerActionRepository links=mock(TriggerActionRepository.class);
    final ActionDefinitionRepository definitions=mock(ActionDefinitionRepository.class);
    final ServiceRepository services=mock(ServiceRepository.class);
    final PermissionService permissions=mock(PermissionService.class);
    final ActionExecutionRepository actionRows=mock(ActionExecutionRepository.class);
    final TriggerExecutionRepository triggerRows=mock(TriggerExecutionRepository.class);
    final CompensationDefinitionRepository compensations=mock(CompensationDefinitionRepository.class);
    final CompensationManager compensationManager=mock(CompensationManager.class);
    final RedisContextWriter writer=mock(RedisContextWriter.class);
    final RedisContextReader reader=mock(RedisContextReader.class);
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final ValueOperations<String,String> values=mock(ValueOperations.class);
    final Map<String,String> redisState=new HashMap<>();
    final Map<String,ActionExecution> actionState=new HashMap<>();
    final Map<Long,ShopeeRun> runState=new HashMap<>();
    final Map<Long,TriggerExecution> triggerState=new HashMap<>();
    ShopeeSource source; ShopeeConnection connection; ScenarioExecution execution;
    StepDefinition step; StepTrigger trigger; ActionDefinition action; Service service;
    ShopeeExecutionGuard guard; StepExecutor executor; ContextStore contexts;

    @BeforeEach void setup() throws Exception {
        source=new ShopeeSource();source.setId(1L);source.setKind("SHOPEE");source.setEnabled(true);
        source.setShopAccountId("fixture_shop");source.setAuthorizedUntil(clock.instant().plusSeconds(3600));source.setAllowedUserIds(List.of("operator"));
        connection=new ShopeeConnection();connection.setId(2L);connection.setSourceId(1L);connection.setScenarioDefinitionId(3L);
        connection.setActionDefinitionId(6L);connection.setDataTypes(List.of("fixture_sales"));
        connection.setFirstLoadDate(LocalDate.of(2026,10,1));connection.setLookbackDays(7);
        execution=new ScenarioExecution();execution.setId(10L);execution.setScenarioDefinitionId(3L);
        execution.setCreatedBy("operator");execution.setStatus(ScenarioExecutionStatus.EXECUTING);execution.setContext(json.createObjectNode());
        step=new StepDefinition();step.setId(4L);step.setScenarioDefinitionId(3L);step.setStepOrder(1);
        trigger=new StepTrigger();trigger.setId(5L);trigger.setStepDefinitionId(4L);
        action=new ActionDefinition();action.setId(6L);action.setServiceId(7L);action.setName("fixture_preview");
        action.setAdapterType("mock");action.setEndpoint("/fixture/preview");action.setHttpMethod("POST");
        action.setParameters(json.readTree("[{\"name\":\"load_mode\",\"in\":\"body\",\"varId\":\"VAR_MODE\"},{\"name\":\"outcome\",\"in\":\"body\",\"varId\":\"VAR_OUTCOME\"}]"));
        action.setOutput(json.readTree("[{\"field\":\"preview\",\"varId\":\"VAR_PREVIEW\",\"type\":\"object\"}]"));
        service=new Service();service.setId(7L);service.setName("shopee-mock");service.setBaseUrl("https://shopee-mock.invalid");
        var link=new StepTriggerAction();link.setActionDefinitionId(6L);link.setStepTriggerId(5L);link.setActionOrder(1);
        when(sources.findById(1L)).thenAnswer(inv -> Optional.of(source));
        when(connections.findByScenarioDefinitionId(3L)).thenReturn(Optional.of(connection));
        when(executions.findById(10L)).thenAnswer(inv -> Optional.of(execution));
        when(executions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runs.save(any())).thenAnswer(inv -> {ShopeeRun r=inv.getArgument(0);runState.put(r.getId(),r);return r;});
        when(runs.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(runState.get(inv.getArgument(0))));
        when(triggers.findById(5L)).thenReturn(Optional.of(trigger));when(steps.findById(4L)).thenReturn(Optional.of(step));
        when(links.findByStepTriggerIdOrderByActionOrderAsc(5L)).thenReturn(List.of(link));
        when(definitions.findById(6L)).thenReturn(Optional.of(action));when(services.findById(7L)).thenReturn(Optional.of(service));
        when(writer.save(anyString(),any())).thenReturn(true);
        when(reader.previousStepsData(anyString(),anyInt())).thenReturn(List.of());
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenAnswer(inv -> redisState.putIfAbsent(inv.getArgument(0),inv.getArgument(1))==null);
        when(values.get(anyString())).thenAnswer(inv -> redisState.get(inv.getArgument(0)));
        doAnswer(inv -> {redisState.put(inv.getArgument(0),inv.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
        when(actionRows.findByIdempotencyKey(anyString())).thenAnswer(inv -> Optional.ofNullable(actionState.get(inv.getArgument(0))));
        when(actionRows.saveAndFlush(any())).thenAnswer(inv -> {ActionExecution r=inv.getArgument(0);r.setId(20L);actionState.put(r.getIdempotencyKey(),r);return r;});
        when(triggerRows.findByScenarioExecutionIdAndStepTriggerId(10L,5L)).thenAnswer(inv -> Optional.ofNullable(triggerState.get(10L)));
        when(triggerRows.saveAndFlush(any())).thenAnswer(inv -> {TriggerExecution r=inv.getArgument(0);r.setId(30L);triggerState.put(10L,r);return r;});
        guard=new ShopeeExecutionGuard(connections,sources,runs,executions,triggers,steps,links,definitions,services,permissions,policy);
        contexts=new ContextStore(executions,writer,reader,json);
        var preview=new ShopeeMockPreview(runs,guard,json);
        var registry=new AdapterRegistry(List.of(new MockAdapter(json,preview)));
        var actionExecutor=new ActionExecutor(definitions,services,mock(RetryPolicyRepository.class),compensations,actionRows,
                mock(SagaLogRepository.class),new ParameterResolver(json),registry,contexts,json,guard);
        executor=new StepExecutor(redis,triggerRows,triggers,links,executions,steps,contexts,writer,actionExecutor,compensationManager,json);
    }
    @ParameterizedTest @ValueSource(strings={"SHOPEE","SHOPEE_ADS"})
    void retrievesNonemptyFixturesRestoresContextAndDeduplicates(String kind) {
        source.setKind(kind);service.setName(ShopeeMockPolicy.serviceName(kind));connection.setDataTypes(ShopeeMockPolicy.dataTypes(kind));
        guard.snapshot("operator",execution);
        var result=run("INITIAL","SUCCESS","same-request");
        assertThat(result.status()).isEqualTo(TriggerExecutionStatus.COMPLETED);
        var preview=result.actionResults().getFirst().getResponseSnapshot().path("preview");
        assertThat(preview.path("sourceKind").asText()).isEqualTo(kind);
        assertThat(preview.path("records")).hasSize(4);
        assertThat(preview.path("liveData").asBoolean()).isFalse();
        assertThat(preview.path("complete").asBoolean()).isFalse();
        assertThat(preview.path("windowFrom").asText()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(preview.path("windowTo").asText()).isEqualTo("2026-10-25T12:00:00Z");
        assertThat(contexts.loadSnapshotFromDb("10").items()).anyMatch(item -> item.varId().equals("VAR_PREVIEW"));
        contexts.restoreToRedis("10",contexts.loadSnapshotFromDb("10"));
        assertThat(run("INITIAL","SUCCESS","same-request").cached()).isTrue();
        verify(actionRows,times(1)).saveAndFlush(any());
        assertThat(redisState.keySet()).contains("result:10:5:same-request");
        verifyNoInteractions(compensationManager);
    }
    @Test void incrementalWindowUsesFrozenConfigurationAndRunClock() {
        guard.snapshot("operator",execution);
        connection.setLookbackDays(1);connection.setDataTypes(List.of("fixture_sales_summary"));
        var result=run("INCREMENTAL","SUCCESS","incremental");
        var preview=result.actionResults().getFirst().getResponseSnapshot().path("preview");
        assertThat(preview.path("windowFrom").asText()).isEqualTo("2026-10-18T12:00:00Z");
        assertThat(preview.path("records").get(0).path("fixtureType").asText()).isEqualTo("fixture_sales");
    }
    @Test void revokedDisabledExpiredAndChangedAccountsCannotReadCachedRuns() {
        guard.snapshot("operator",execution);run("INITIAL","SUCCESS","cached");
        source.setEnabled(false);
        assertThatThrownBy(() -> run("INITIAL","SUCCESS","cached")).hasMessageContaining("disabled");
        source.setEnabled(true);source.setAllowedUserIds(List.of());
        assertThatThrownBy(() -> guard.checkExecution("operator",10L)).hasMessageContaining("denied");
        source.setAllowedUserIds(List.of("operator"));source.setAuthorizedUntil(clock.instant());
        assertThatThrownBy(() -> guard.checkExecution("operator",10L)).hasMessageContaining("reauthorization");
        source.setAuthorizedUntil(clock.instant().plusSeconds(100));source.setShopAccountId("fixture_another");
        assertThatThrownBy(() -> guard.checkExecution("operator",10L)).hasMessageContaining("account changed");
        verify(actionRows,times(1)).saveAndFlush(any());
    }
    @Test void rejectsMissingIdentityUnknownInputsCrossScenarioTriggersAndHttpAdapter() {
        assertThatThrownBy(() -> guard.checkScenario(null,3L)).hasMessageContaining("identity");
        guard.snapshot("operator",execution);
        assertThatThrownBy(() -> guard.checkTrigger("operator",10L,5L,Map.of("secret","must-not-persist"))).hasMessageContaining("declared");
        assertThat(execution.getContext()).isEmpty();
        step.setScenarioDefinitionId(99L);
        assertThatThrownBy(() -> run("INITIAL","SUCCESS","x")).hasMessageContaining("does not belong");
        step.setScenarioDefinitionId(3L);action.setAdapterType("http");
        assertThatThrownBy(() -> run("INITIAL","SUCCESS","x")).hasMessageContaining("synthetic preview");
        verifyNoInteractions(actionRows);
    }
    @Test void sourceGrantDoesNotBypassCurrentScenarioPermission() {
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(permissions).checkScenarioPermission("operator",3L);
        assertThatThrownBy(() -> guard.checkScenario("operator",3L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(actionRows);
    }
    @Test void timeoutSurvivesNullResponseSummaryWithoutCompensation() {
        guard.snapshot("operator",execution);
        var result=run("INITIAL","TIMEOUT","timeout");
        assertThat(result.failedActionStatus()).isEqualTo(ActionExecutionStatus.TIMEOUT);
        assertThat(result.summary()).contains("TIMEOUT").contains("\"response\":null");
        verifyNoInteractions(compensationManager);
        var cached=run("INITIAL","TIMEOUT","timeout");
        assertThat(cached.cached()).isTrue();
        assertThat(cached.status()).isEqualTo(TriggerExecutionStatus.FAILED);
        assertThat(cached.summary()).contains("TIMEOUT");
    }
    @Test void knownFailurePreservesExistingSagaRouting() {
        guard.snapshot("operator",execution);
        var result=run("INITIAL","FAILED","failed");
        assertThat(result.failedActionStatus()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(result.summary()).contains("mock-only");
        verify(compensationManager).compensate(10L);
        var cached=run("INITIAL","FAILED","failed");
        assertThat(cached.cached()).isTrue();
        assertThat(cached.status()).isEqualTo(TriggerExecutionStatus.FAILED);
    }
    private StepExecutor.TriggerResult run(String mode,String outcome,String requestId) {
        Map<String,Object> inputs=Map.of("VAR_MODE",mode,"VAR_OUTCOME",outcome);
        guard.checkTrigger("operator",10L,5L,inputs);
        return executor.execute(new StepExecutor.TriggerInput(10L,5L,requestId,inputs,"operator"));
    }
}
