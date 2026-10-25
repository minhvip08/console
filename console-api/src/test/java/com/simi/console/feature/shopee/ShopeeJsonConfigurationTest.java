package com.simi.console.feature.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShopeeJsonConfigurationTest {
    @Test void mvcSerializesDatesAndExistingJackson2NodesAsJson() {
        new WebApplicationContextRunner().withUserConfiguration(ShopeeConfiguration.class, TestMvc.class).run(context -> {
            assertThat(context).hasSingleBean(ObjectMapper.class);
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/fixture-json")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value("2026-10-25"))
                    .andExpect(jsonPath("$.payload.fixtureValue").value(42));
        });
    }
    @Configuration @EnableWebMvc
    static class TestMvc {
        @Bean Echo echo(ObjectMapper mapper) { return new Echo(mapper); }
    }
    @RestController
    static class Echo {
        private final ObjectMapper mapper;
        Echo(ObjectMapper mapper) { this.mapper=mapper; }
        @GetMapping("/fixture-json") Map<String,Object> echo() {
            return Map.of("date",LocalDate.of(2026,10,25),"payload",mapper.createObjectNode().put("fixtureValue",42));
        }
    }
}
