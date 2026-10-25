package com.simi.backoffice.feature.management.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simi.backoffice.feature.management.action.ActionRepository;
import com.simi.backoffice.feature.management.service.ServiceRepository;
import com.simi.common.domain.entity.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopeeSwaggerImportTest {
    @Test void importsBothSyntheticSourcesAsMockAndPreservesBindingsOnReimport() {
        for (String catalogId : new String[]{"mock_shopee", "mock_shopee_ads"}) {
            var services = mock(ServiceRepository.class); var actions = mock(ActionRepository.class);
            var definitions = new ArrayList<ActionDefinition>();
            when(services.findByName(any())).thenReturn(Optional.empty());
            when(services.save(any())).thenAnswer(inv -> { Service s=inv.getArgument(0); s.setId(1L); return s; });
            when(actions.findByServiceId(1L)).thenAnswer(inv -> definitions);
            when(actions.save(any())).thenAnswer(inv -> { ActionDefinition a=inv.getArgument(0); a.setId(2L); definitions.add(a); return a; });
            var importer = new SwaggerImporter(new MockPlatformApiClient(), services, actions, new ObjectMapper());
            assertThat(importer.importFromPlatform(catalogId).actionIds()).containsExactly(2L);
            ActionDefinition first = definitions.getFirst();
            assertThat(first.getAdapterType()).isEqualTo("mock");
            assertThat(first.getParameters()).hasSize(2);
            assertThat(first.getParameters().get(0).path("varId").asText()).startsWith("VAR_");
            assertThat(first.getOutput().get(0).path("field").asText()).isEqualTo("preview");
            assertThat(importer.importFromPlatform(catalogId).actionIds()).containsExactly(2L);
            verify(actions, times(1)).save(any());
        }
    }
}
