package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.domain.ActionExecutionStatus;
import com.simi.common.domain.TriggerExecutionStatus;
import com.simi.common.domain.entity.ActionExecution;
import com.simi.console.feature.rbac.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller slice test — dùng standaloneSetup thay @WebMvcTest (removed in SB4).
 * Mục đích: verify request mapping, header parsing, response code — không test business logic.
 * Dependencies đều mocked bằng Mockito.
 */
@ExtendWith(MockitoExtension.class)
class TriggerControllerTest {

    @Mock StepExecutor stepExecutor;
    @Mock PermissionService permissionService;
    @Mock com.simi.console.feature.shopee.ShopeeExecutionGuard shopee;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TriggerController(stepExecutor, permissionService, shopee))
                .build();
    }

    @Test
    void deniedShopeeAccessCannotReachCachedResult() throws Exception {
        org.mockito.Mockito.doThrow(new com.simi.common.shopee.ShopeeException(403, "Source access denied"))
                .when(shopee).checkTrigger(org.mockito.ArgumentMatchers.eq("revoked"),
                        org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.any());
        MockMvc protectedMvc = MockMvcBuilders.standaloneSetup(new TriggerController(stepExecutor, permissionService, shopee))
                .build();
        // Standalone setup has no application advice; assert the guarded call propagates before execution.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectedMvc.perform(post("/api/executions/100/trigger")
                        .header("X-User-Id", "revoked").header("X-Request-Id", "cached")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"triggerId\":1}")))
                .hasCauseInstanceOf(com.simi.common.shopee.ShopeeException.class);
        org.mockito.Mockito.verifyNoInteractions(stepExecutor);
    }

    @Test
    void trigger_returns400_whenNoRequestId() throws Exception {
        // Không có X-Request-Id header và body.requestId là null
        mockMvc.perform(post("/api/executions/100/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("triggerId", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void trigger_returns202_whenRequestAlreadyProcessing() throws Exception {
        when(stepExecutor.execute(any())).thenReturn(StepExecutor.TriggerResult.inFlight());

        mockMvc.perform(post("/api/executions/100/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-001")
                        .content(json.writeValueAsString(Map.of("triggerId", 1))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("processing"));
    }

    @Test
    void trigger_returns200_withCachedResponse_whenSameRequestIdRepeated() throws Exception {
        when(stepExecutor.execute(any())).thenReturn(
                StepExecutor.TriggerResult.fromCache("cached summary from prior run"));

        mockMvc.perform(post("/api/executions/100/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-dup-001")
                        .content(json.writeValueAsString(Map.of("triggerId", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("cached"))
                .andExpect(jsonPath("$.summary").value("cached summary from prior run"));
    }

    @Test
    void trigger_returns200_whenAllActionsCompleted() throws Exception {
        ActionExecution completedAction = new ActionExecution();
        completedAction.setId(42L);
        completedAction.setActionDefinitionId(1L);
        completedAction.setStatus(ActionExecutionStatus.COMPLETED);

        StepExecutor.TriggerResult success = new StepExecutor.TriggerResult(
                TriggerExecutionStatus.COMPLETED,
                List.of(completedAction),
                false, false, "{}", null);
        when(stepExecutor.execute(any())).thenReturn(success);

        mockMvc.perform(post("/api/executions/100/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-fresh-001")
                        .content(json.writeValueAsString(Map.of("triggerId", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("completed"))
                .andExpect(jsonPath("$.triggerStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.actions[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary").value("{}"));
    }

    @Test
    void trigger_acceptsRequestId_fromBodyFallback_whenHeaderAbsent() throws Exception {
        when(stepExecutor.execute(any())).thenReturn(StepExecutor.TriggerResult.inFlight());

        // requestId in body, không có header
        mockMvc.perform(post("/api/executions/100/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("triggerId", 1, "requestId", "body-req-001"))))
                .andExpect(status().isAccepted()); // 202 = executor picked it up, not 400
    }
}
