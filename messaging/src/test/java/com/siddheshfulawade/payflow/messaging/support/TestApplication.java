package com.siddheshfulawade.payflow.messaging.support;

import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only app: the messaging auto-configuration plus one wallet-like listener. */
@SpringBootApplication
public class TestApplication {

    @Bean
    SwitchableRelayHook relayHook() {
        return new SwitchableRelayHook();
    }

    @Bean
    TestEffect testEffect(JdbcTemplate jdbc) {
        return new TestEffect(jdbc);
    }

    @Bean
    WalletCommandListener walletCommandListener(IdempotentConsumer consumer, TestEffect effect) {
        return new WalletCommandListener(consumer, effect);
    }
}
