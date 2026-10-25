package com.simi.console.feature.shopee;

import com.simi.common.domain.context.ContextDataItem;
import com.simi.common.shopee.ShopeeException;
import com.simi.console.feature.context.*;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopeePreviewControllerTest {
    @Test void returnsOnlyPreviewOutputsAndChecksAccessBeforeLoadingSavedValues() {
        var guard=mock(ShopeeExecutionGuard.class);var runs=mock(ShopeeRunRepository.class);var contexts=mock(ContextStore.class);
        var controller=new ShopeePreviewController(guard,runs,contexts);
        when(runs.existsById(10L)).thenReturn(true);
        var input=new ContextDataItem("input","VAR_INPUT","INITIAL","string","1.0",
                new ContextDataItem.Origin(ContextDataItem.SourceType.UI_INPUT,"3","4",1,"widget","input",null),OffsetDateTime.now());
        var output=new ContextDataItem("output","VAR_PREVIEW",Map.of("mode","mock-only"),"object","1.0",
                new ContextDataItem.Origin(ContextDataItem.SourceType.ACTION_OUTPUT,"3","4",1,"20","preview","preview"),OffsetDateTime.now());
        when(contexts.loadSnapshotFromDb("10")).thenReturn(new ContextSnapshot(List.of(input,output)));
        assertThat(controller.preview("operator",10L).outputs()).containsExactly(output);
        clearInvocations(contexts);
        doThrow(new ShopeeException(403,"Source access denied")).when(guard).checkExecution("revoked",10L);
        assertThatThrownBy(() -> controller.preview("revoked",10L)).hasMessageContaining("denied");
        verifyNoInteractions(contexts);
        assertThatThrownBy(() -> controller.preview("operator",99L)).hasMessageContaining("not found");
    }
}
