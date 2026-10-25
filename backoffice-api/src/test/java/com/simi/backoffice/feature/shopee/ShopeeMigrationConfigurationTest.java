package com.simi.backoffice.feature.shopee;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ShopeeMigrationConfigurationTest {
    @Test void autoConfigurationCreatesFlywayAndInvokesMigrationStrategy() {
        var invoked=new AtomicBoolean();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withBean(DataSource.class,() -> mock(DataSource.class))
                .withBean(FlywayMigrationStrategy.class,() -> flyway -> invoked.set(true))
                .run(context -> {
                    assertThat(context).hasSingleBean(Flyway.class);
                    assertThat(invoked).isTrue();
                });
    }
}
