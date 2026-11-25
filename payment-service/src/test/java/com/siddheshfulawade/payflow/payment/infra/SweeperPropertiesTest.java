package com.siddheshfulawade.payflow.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** payflow.payment.sweeper.{enabled,interval,batch-size,max-resends}: defaults, bounds, and the warning when off. */
class SweeperPropertiesTest {

    private static SweeperProperties bound(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("payflow.payment.sweeper", SweeperProperties.class);
    }

    private static SweeperProperties bound(String name, String value) {
        return bound(Map.of("payflow.payment.sweeper." + name, value));
    }

    @Test
    @DisplayName("REQ-PF-012 without settings the sweeper runs every 2 s over 50 payments and re-sends 3 times")
    void defaults() {
        SweeperProperties defaults = bound(Map.of());

        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.interval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.batchSize()).isEqualTo(50);
        assertThat(defaults.maxResends()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0} = {1} is accepted")
    @CsvSource({
        "interval, 100ms",
        "interval, 2s",
        "interval, 1m",
        "batch-size, 1",
        "batch-size, 1000",
        "max-resends, 0",
        "max-resends, 20",
    })
    @DisplayName("REQ-PF-012 the ends of each allowed range are accepted")
    void boundariesAreAccepted(String name, String value) {
        SweeperProperties properties = bound(name, value);

        assertThat(properties).isNotNull();
    }

    @ParameterizedTest(name = "{0} = {1} is refused")
    @CsvSource({
        "interval, 99ms",
        "interval, 0s",
        "interval, -1s",
        "interval, 61s",
        "interval, 1h",
        "batch-size, 0",
        "batch-size, -5",
        "batch-size, 1001",
        "max-resends, -1",
        "max-resends, 21",
        "max-resends, 1000000",
    })
    @DisplayName("REQ-PF-012 outside its range a setting stops the start, and the message names the setting")
    void outOfRangeIsRefused(String name, String value) {
        assertThatThrownBy(() -> bound(name, value)).hasStackTraceContaining(name);
    }

    @Test
    @DisplayName("REQ-PF-012 the values of a valid setting are bound as given")
    void valuesAreBound() {
        SweeperProperties properties = bound(Map.of(
                "payflow.payment.sweeper.enabled", "false",
                "payflow.payment.sweeper.interval", "250ms",
                "payflow.payment.sweeper.batch-size", "7",
                "payflow.payment.sweeper.max-resends", "0"));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.interval()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.batchSize()).isEqualTo(7);
        assertThat(properties.maxResends()).isZero();
    }

    // ---- the schedule ----

    private static List<ILoggingEvent> warningsWhenConfiguring(SweeperProperties settings) {
        Logger logger = (Logger) LoggerFactory.getLogger(SweeperSchedule.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // the sweeper is not used while configuring: with the schedule off it must never be needed
            new SweeperSchedule(null, settings).configureTasks(new ScheduledTaskRegistrar());
            return appender.list.stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                    .toList();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("REQ-PF-012 a disabled sweeper logs a WARN at start: payments get stuck without one")
    void disabledSweeperWarns() {
        var warnings = warningsWhenConfiguring(new SweeperProperties(false, Duration.ofSeconds(2), 50, 3));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).containsIgnoringCase("disabled");
    }

    @Test
    @DisplayName("REQ-PF-012 an enabled sweeper logs no warning")
    void enabledSweeperDoesNotWarn() {
        var warnings = warningsWhenConfiguring(new SweeperProperties(true, Duration.ofSeconds(2), 50, 3));

        assertThat(warnings).isEmpty();
    }
}
