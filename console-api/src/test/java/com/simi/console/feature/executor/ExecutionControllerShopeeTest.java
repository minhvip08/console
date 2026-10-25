package com.simi.console.feature.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.common.shopee.ShopeeException;
import com.simi.console.feature.context.*;
import com.simi.console.feature.rbac.PermissionService;
import com.simi.console.feature.shopee.ShopeeExecutionGuard;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionControllerShopeeTest {
    @Test void revokedSourceCannotReadOrRestoreContextAndBodyIdentityCannotStartRun() {
        var guard=mock(ShopeeExecutionGuard.class);
        var contexts=mock(ContextStore.class);
        var executions=mock(ScenarioExecutionRepository.class);
        var controller=new ExecutionController(mock(ScenarioDefinitionRepository.class),mock(StepDefinitionRepository.class),
                executions,contexts,mock(PermissionService.class),new ObjectMapper(),guard);
        doThrow(new ShopeeException(403,"Source access denied")).when(guard).checkExecution("revoked",10L);
        assertThatThrownBy(() -> controller.get(10L,"revoked")).hasMessageContaining("denied");
        assertThatThrownBy(() -> controller.resume(10L,"revoked")).hasMessageContaining("denied");
        doThrow(new ShopeeException(403,"Trusted user identity required")).when(guard).checkScenario(null,3L);
        assertThatThrownBy(() -> controller.start(null,new ExecutionController.StartRequest(3L,"spoofed-admin")))
                .hasMessageContaining("identity");
        verifyNoInteractions(contexts,executions);
    }
}
