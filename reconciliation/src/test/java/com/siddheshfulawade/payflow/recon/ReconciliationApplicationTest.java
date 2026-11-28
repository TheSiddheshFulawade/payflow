package com.siddheshfulawade.payflow.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.recon.support.ReconPostgres;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Needs the recon database (Flyway runs at start-up), so it is an integration test: -Pit. */
@Tag("it")
@SpringBootTest(classes = ReconciliationApplication.class)
class ReconciliationApplicationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
