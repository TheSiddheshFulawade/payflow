package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plain unit test: ids that reach SQL or JSON of the harness cannot carry quotes or other syntax (REQ-PF-051). */
class SafeIdsTest {

    @Test
    @DisplayName("REQ-PF-051 a quote in an id is refused, never put into SQL")
    void quoteIsRefused() {
        assertThatThrownBy(() -> SafeIds.sqlList(List.of("ok-1", "x') or ('1'='1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("REQ-PF-051 empty, null, space, comma, backslash and double quote are refused")
    void otherSyntaxIsRefused() {
        for (String bad : new String[] {"", null, "a b", "a,b", "a\\b", "a\"b", "a;b", "a_b"}) {
            assertThatThrownBy(() -> SafeIds.require(bad))
                    .as("id %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // TODO-ME: SafeIds.sqlList(List.of()) returns "", so a query becomes `where payer in ()`, which is invalid SQL.
    // Decide what an empty list should do (refusing with IllegalArgumentException is the simplest), assert it here,
    // remove @Disabled, see the test fail, then change SafeIds.sqlList until it passes.
    // Check: ./mvnw.cmd -q -B test -pl system-tests -Dtest=SafeIdsTest
    @Test
    @org.junit.jupiter.api.Disabled("TODO-ME: empty list behaviour")
    @DisplayName("REQ-PF-051 an empty id list has no SQL form and is refused")
    void emptyListHasNoSafeSqlForm() {
        // your assertion here
    }

    @Test
    @DisplayName("REQ-PF-051 an id is lower-cased and a list is quoted per id")
    void validIdsAreLowerCasedAndQuoted() {
        assertThat(SafeIds.require("Alice-01")).isEqualTo("alice-01");
        assertThat(SafeIds.sqlList(List.of("a-1", "B2"))).isEqualTo("'a-1','b2'");
    }
}
