package com.siddheshfulawade.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The URL default carries logServerErrorDetail=false, but PAYMENT_DB_URL replaces the whole URL. The driver property
 * must therefore be set on its own, so an overridden URL still keeps row values out of exceptions and the
 * dead-letter topic.
 */
class DatasourceHidesRowValuesTest {

    private static final String PROPERTY = "spring.datasource.hikari.data-source-properties.logServerErrorDetail";

    private static StandardEnvironment environment(Map<String, Object> overrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        for (PropertySource<?> source :
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }

    @Test
    @DisplayName("REQ-PF-004 logServerErrorDetail=false is set as a driver property, not only in the default URL")
    void driverPropertyIsSetIndependentlyOfTheUrl() throws IOException {
        assertThat(environment(Map.of()).getProperty(PROPERTY)).isEqualTo("false");
    }

    @Test
    @DisplayName("REQ-PF-004 the driver property is still there when PAYMENT_DB_URL replaces the url")
    void overriddenUrlStillHidesRowValues() throws IOException {
        StandardEnvironment env = environment(Map.of("PAYMENT_DB_URL", "jdbc:postgresql://db.internal:5432/payment"));

        assertThat(env.getProperty("spring.datasource.url")).doesNotContain("logServerErrorDetail");
        assertThat(env.getProperty(PROPERTY)).isEqualTo("false");
    }
}
