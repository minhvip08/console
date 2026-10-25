package com.simi.backoffice.feature.shopee;

import com.simi.common.shopee.ShopeeMockPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Verifies real migrations/JPA/provisioning when infrastructure is available. */
@SpringBootTest(properties={"shopee.mock.enabled=true","shopee.mock.admin-users=admin"})
@Testcontainers(disabledWithoutDocker=true)
class ShopeePersistenceIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:15-alpine")
            .withCommand("postgres","-c","wal_level=logical")
            .withLabel("ao.session",System.getenv().getOrDefault("AO_SESSION_ID","local-test"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",postgres::getJdbcUrl);
        properties.add("spring.datasource.username",postgres::getUsername);
        properties.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired ShopeeManagementService service;
    @Autowired JdbcTemplate jdbc;
    @Test void migratesPersistsAndProvisionsBothKindsWithoutDuplicateScenarios() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '7' and success",Long.class)).isEqualTo(1L);
        for(String kind:List.of("SHOPEE","SHOPEE_ADS")) {
            var source=service.saveSource("admin",null,new ShopeeManagementController.SourceRequest(kind,"Fixture source","Synthetic",true,List.of("operator")));
            service.authorize("admin",source.getId(),new ShopeeManagementController.AuthorizationRequest("SHOP_ACCOUNT","fixture_shop","GRANTED"));
            var connection=service.saveConnection("admin",null,new ShopeeManagementController.ConnectionRequest(source.getId(),"Fixture connection","Synthetic",ShopeeMockPolicy.dataTypes(kind),LocalDate.now(ZoneOffset.UTC).minusDays(7),7,60,"MANUAL"));
            Long scenarioId=service.provisionScenario("admin",connection.getId()).getScenarioDefinitionId();
            assertThat(service.provisionScenario("admin",connection.getId()).getScenarioDefinitionId()).isEqualTo(scenarioId);
            assertThat(jdbc.queryForObject("select count(*) from step_definition where scenario_definition_id = ?",Long.class,scenarioId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select adapter_type from action_definition where id = (select action_definition_id from shopee_connection where id = ?)",String.class,connection.getId())).isEqualTo("mock");
            service.deleteConnection("admin",connection.getId());service.deleteSource("admin",source.getId());
        }
    }
}
