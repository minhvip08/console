package com.simi.console.feature.shopee;

import com.simi.common.shopee.ShopeeMockPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
class ShopeeConfiguration {
    // Existing definitions/context use Jackson 2 JsonNode. Boot 4 defaults to Jackson 3.
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(com.fasterxml.jackson.databind.ObjectMapper.class)
    com.fasterxml.jackson.databind.ObjectMapper definitionObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    @Bean
    org.springframework.web.servlet.config.annotation.WebMvcConfigurer definitionJsonMessages(
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
            @Override
            public void configureMessageConverters(org.springframework.http.converter.HttpMessageConverters.ServerBuilder converters) {
                converters.withJsonConverter(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper));
            }
        };
    }
    @Bean
    ShopeeMockPolicy shopeeMockPolicy(@Value("${shopee.mock.enabled:false}") boolean enabled,
                                      @Value("${shopee.mock.admin-users:}") String admins) {
        return new ShopeeMockPolicy(enabled, admins, Clock.systemUTC());
    }
}
