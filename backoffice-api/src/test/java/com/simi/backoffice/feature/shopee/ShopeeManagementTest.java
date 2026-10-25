package com.simi.backoffice.feature.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.simi.backoffice.feature.management.action.ActionRepository;
import com.simi.backoffice.feature.management.platform.SwaggerImporter;
import com.simi.backoffice.feature.management.scenario.*;
import com.simi.backoffice.feature.management.step.*;
import com.simi.common.domain.entity.*;
import com.simi.common.shopee.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShopeeManagementTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    final ShopeeMockPolicy policy=new ShopeeMockPolicy(true,"admin",clock);
    final ShopeeSourceRepository sources=mock(ShopeeSourceRepository.class);
    final ShopeeConnectionRepository connections=mock(ShopeeConnectionRepository.class);
    final SwaggerImporter importer=mock(SwaggerImporter.class);
    final ActionRepository actions=mock(ActionRepository.class);
    final ScenarioRepository scenarios=mock(ScenarioRepository.class);
    final StepRepository steps=mock(StepRepository.class);
    final StepTriggerRepository triggers=mock(StepTriggerRepository.class);
    final StepTriggerActionRepository links=mock(StepTriggerActionRepository.class);
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    final Map<Long,ShopeeSource> sourceState=new HashMap<>();
    final Map<Long,ShopeeConnection> connectionState=new HashMap<>();
    ShopeeManagementService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        when(sources.save(any())).thenAnswer(inv -> { ShopeeSource s=inv.getArgument(0); if(s.getId()==null)s.setId((long)sourceState.size()+1); sourceState.put(s.getId(),s);return s;});
        when(sources.findById(any())).thenAnswer(inv -> Optional.ofNullable(sourceState.get(inv.getArgument(0))));
        when(sources.lockById(any())).thenAnswer(inv -> Optional.ofNullable(sourceState.get(inv.getArgument(0))));
        when(sources.findAll()).thenAnswer(inv -> new ArrayList<>(sourceState.values()));
        when(connections.save(any())).thenAnswer(inv -> { ShopeeConnection c=inv.getArgument(0); if(c.getId()==null)c.setId((long)connectionState.size()+1);connectionState.put(c.getId(),c);return c;});
        when(connections.findById(any())).thenAnswer(inv -> Optional.ofNullable(connectionState.get(inv.getArgument(0))));
        when(connections.lockById(any())).thenAnswer(inv -> Optional.ofNullable(connectionState.get(inv.getArgument(0))));
        when(connections.existsBySourceIdAndDeletedFalse(any())).thenAnswer(inv -> connectionState.values().stream().anyMatch(c -> !c.getDeleted() && c.getSourceId().equals(inv.getArgument(0))));
        service=new ShopeeManagementService(sources,connections,policy,importer,actions,scenarios,steps,triggers,links,json);
        mvc=MockMvcBuilders.standaloneSetup(new ShopeeManagementController(service,policy))
                .setControllerAdvice(new ShopeeExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @Test void lifecycleSupportsBothSourcesAndRequiresAuthorization() {
        for(String kind:List.of("SHOPEE","SHOPEE_ADS")) {
            ShopeeSource s=service.saveSource("admin",null,sourceRequest(kind,true));
            assertThatThrownBy(() -> service.saveConnection("admin",null,connectionRequest(s.getId(),kind,"MANUAL"))).hasMessageContaining("authorization");
            service.authorize("admin",s.getId(),new ShopeeManagementController.AuthorizationRequest("SHOP_ACCOUNT",null,"CANCELLED"));
            assertThat(s.getAuthorizedUntil()).isNull();
            assertThatThrownBy(() -> service.authorize("admin",s.getId(),new ShopeeManagementController.AuthorizationRequest("MAIN_ACCOUNT","fixture_shop","GRANTED"))).hasMessageContaining("Shop Account");
            service.authorize("admin",s.getId(),new ShopeeManagementController.AuthorizationRequest("SHOP_ACCOUNT","fixture_shop","GRANTED"));
            ShopeeConnection c=service.saveConnection("admin",null,connectionRequest(s.getId(),kind,"MANUAL"));
            assertThat(c.getFrequencyMinutes()).isEqualTo(60);
            assertThatThrownBy(() -> service.deleteSource("admin",s.getId())).hasMessageContaining("dependent");
            service.saveSource("admin",s.getId(),sourceRequest(kind,false));
            assertThatThrownBy(() -> service.saveConnection("admin",c.getId(),connectionRequest(s.getId(),kind,"MANUAL"))).hasMessageContaining("disabled");
            service.saveSource("admin",s.getId(),sourceRequest(kind,true));
            service.authorize("admin",s.getId(),new ShopeeManagementController.AuthorizationRequest("SHOP_ACCOUNT","fixture_shop","GRANTED"));
            service.deleteConnection("admin",c.getId()); service.deleteSource("admin",s.getId());
            assertThat(s.getDeleted()).isTrue(); assertThat(c.getDeleted()).isTrue();
        }
    }
    @Test void deniesNonAdminsRejectsRealCredentialsAndScheduledMode() {
        assertThatThrownBy(() -> service.saveSource("operator",null,sourceRequest("SHOPEE",true))).hasMessageContaining("administrator");
        ShopeeSource s=service.saveSource("admin",null,sourceRequest("SHOPEE",true));
        assertThatThrownBy(() -> service.authorize("admin",s.getId(),new ShopeeManagementController.AuthorizationRequest("SHOP_ACCOUNT","real-token","GRANTED"))).hasMessageContaining("synthetic");
        assertThatThrownBy(() -> service.saveConnection("admin",null,connectionRequest(s.getId(),"SHOPEE","SCHEDULED"))).hasMessageContaining("Scheduled");
        assertThat(service.listSources("stranger")).isEmpty();
    }
    @Test void provisionsRealDefinitionsOnceWithVariableBoundWidgets() throws Exception {
        var source=service.saveSource("admin",null,sourceRequest("SHOPEE",true));
        service.authorize("admin",source.getId(),new ShopeeManagementController.AuthorizationRequest("SHOP_ACCOUNT","fixture_shop","GRANTED"));
        var connection=service.saveConnection("admin",null,connectionRequest(source.getId(),"SHOPEE","MANUAL"));
        ActionDefinition action=new ActionDefinition();action.setId(9L);
        action.setParameters(json.readTree("[{\"name\":\"load_mode\",\"varId\":\"VAR_MODE\"},{\"name\":\"outcome\",\"varId\":\"VAR_OUTCOME\"}]"));
        action.setOutput(json.readTree("[{\"field\":\"preview\",\"varId\":\"VAR_PREVIEW\"}]"));
        when(importer.importFromPlatform("mock_shopee")).thenReturn(new SwaggerImporter.ImportResult(8L,"shopee-mock",List.of(9L)));
        when(actions.findById(9L)).thenReturn(Optional.of(action));
        when(scenarios.save(any())).thenAnswer(inv -> {ScenarioDefinition s=inv.getArgument(0);s.setId(10L);return s;});
        when(steps.save(any())).thenAnswer(inv -> {StepDefinition s=inv.getArgument(0);s.setId(11L);return s;});
        when(triggers.save(any())).thenAnswer(inv -> {StepTrigger t=inv.getArgument(0);t.setId(12L);return t;});
        service.provisionScenario("admin",connection.getId()); service.provisionScenario("admin",connection.getId());
        assertThat(connection.getScenarioDefinitionId()).isEqualTo(10L);
        verify(scenarios,times(1)).save(any());
        verify(links).save(argThat(link -> link.getActionDefinitionId().equals(9L) && link.getStepTriggerId().equals(12L)));
        verify(steps,times(2)).save(argThat(step -> step.getUi().toString().contains("VAR_PREVIEW")));
    }
    @Test void httpContractsValidateInputAndExposeInactiveFrequency() throws Exception {
        mvc.perform(post("/api/shopee/sources").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sourceRequest("SHOPEE",true))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/shopee/sources").header("X-User-Id","admin").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/shopee/sources").header("X-User-Id","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sourceRequest("SHOPEE",true))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AUTH_REQUIRED"));
        mvc.perform(post("/api/shopee/sources/1/authorize").header("X-User-Id","admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"SHOP_ACCOUNT\",\"shopAccountId\":\"fixture_shop\",\"outcome\":\"GRANTED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("mock-only"));
        mvc.perform(post("/api/shopee/connections").header("X-User-Id","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(connectionRequest(1L,"SHOPEE","MANUAL"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.frequencyActive").value(false)).andExpect(jsonPath("$.executionMode").value("MANUAL"));
    }
    private ShopeeManagementController.SourceRequest sourceRequest(String kind,boolean enabled) {
        return new ShopeeManagementController.SourceRequest(kind,"Fixture source","Synthetic",enabled,List.of("operator"));
    }
    private ShopeeManagementController.ConnectionRequest connectionRequest(Long sourceId,String kind,String mode) {
        return new ShopeeManagementController.ConnectionRequest(sourceId,"Fixture connection","Synthetic",ShopeeMockPolicy.dataTypes(kind),LocalDate.of(2026,10,1),7,60,mode);
    }
}
