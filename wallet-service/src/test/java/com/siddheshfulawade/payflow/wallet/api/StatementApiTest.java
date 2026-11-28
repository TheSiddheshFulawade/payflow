package com.siddheshfulawade.payflow.wallet.api;

import static com.siddheshfulawade.payflow.wallet.support.Tokens.OPS;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.READ;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.WRITE;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.wallet.app.StatementLine;
import com.siddheshfulawade.payflow.wallet.support.SecuredWebTest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/** REQ-PF-040 and REQ-PF-003: the wallet statement is a CSV for ops only; deny by default for everyone else. */
class StatementApiTest extends SecuredWebTest {

    private static final String URL = "/api/v1/ops/statements";
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    @DisplayName("REQ-PF-040 ops get text/csv: header row, one line per payment, long minor units")
    void csv() throws Exception {
        LocalDate day = LocalDate.parse("2026-11-26");
        when(statements.forDate(day))
                .thenReturn(
                        List.of(new StatementLine(A, Long.MAX_VALUE, "CAPTURED"), new StatementLine(B, 0, "RELEASED")));

        MvcResult result = mvc.perform(get(URL).param("date", "2026-11-26").with(as("ops-1", OPS)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getContentAsString().replace("\r\n", "\n"))
                .isEqualTo("payment_id,captured_amount,status\n" + A + "," + Long.MAX_VALUE + ",CAPTURED\n" + B
                        + ",0,RELEASED\n");
        verify(statements).forDate(day);
    }

    @Test
    @DisplayName("REQ-PF-040 a date without movements is a header row only, not an error")
    void emptyDay() throws Exception {
        when(statements.forDate(LocalDate.parse("2026-01-01"))).thenReturn(List.of());

        MvcResult result = mvc.perform(get(URL).param("date", "2026-01-01").with(as("ops-1", OPS)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString().trim()).isEqualTo("payment_id,captured_amount,status");
    }

    @Test
    @DisplayName("REQ-PF-003 a customer (read and write) gets 403 and nothing is queried")
    void customerForbidden() throws Exception {
        mvc.perform(get(URL).param("date", "2026-11-26").with(as("alice", READ, WRITE)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(statements);
    }

    @Test
    @DisplayName("REQ-PF-003 no token is 401")
    void noToken() throws Exception {
        mvc.perform(get(URL).param("date", "2026-11-26")).andExpect(status().isUnauthorized());

        verifyNoInteractions(statements);
    }

    @Test
    @DisplayName("REQ-PF-003 ops may only GET statements: POST is refused")
    void onlyGet() throws Exception {
        mvc.perform(post(URL)
                        .param("date", "2026-11-26")
                        .contentType("application/json")
                        .content("{}")
                        .with(as("ops-1", OPS)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(statements);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13-01", "2026-02-30", "yesterday", "26-11-2026", "", "2026-11-26T10:00:00"})
    @DisplayName("REQ-PF-040 an invalid date is a 400 problem and nothing is queried")
    void invalidDate(String date) throws Exception {
        MvcResult result = mvc.perform(get(URL).param("date", date).with(as("ops-1", OPS)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception");
        verifyNoInteractions(statements);
    }

    @Test
    @DisplayName("REQ-PF-040 a date before 2000-01-01 or after tomorrow (UTC) is a 400 problem, not a 500")
    void dateOutsideBounds() throws Exception {
        LocalDate tooLate = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        for (String date : List.of("1999-12-31", "0001-01-01", "9999-12-31", tooLate.toString())) {
            MvcResult result = mvc.perform(get(URL).param("date", date).with(as("ops-1", OPS)))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception");
        }
        verifyNoInteractions(statements);
    }

    @Test
    @DisplayName("REQ-PF-040 the bounds themselves are accepted: 2000-01-01 and tomorrow (UTC)")
    void dateBoundsAreInclusive() throws Exception {
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        for (String date : List.of("2000-01-01", tomorrow.toString())) {
            mvc.perform(get(URL).param("date", date).with(as("ops-1", OPS))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("REQ-PF-040 a missing date is a 400 problem")
    void missingDate() throws Exception {
        MvcResult result = mvc.perform(get(URL).with(as("ops-1", OPS)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        verifyNoInteractions(statements);
    }
}
