package com.bilal.neobank.history;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs with a tiny row cap so the truncation behaviour can be tested with a handful of rows. */
@SpringBootTest(properties = "app.history.export-max-rows=3")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HistoryExportCapTest extends HistoryTestBase {

    @Test
    void exportIsCappedAndSaysSo() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);
        for (int i = 1; i <= 5; i++) {
            deposit(alice, acc, i + ".00", "n" + i);
        }

        MvcResult capped = export(alice, acc, "?sort=date,asc").andExpect(status().isOk())
                .andExpect(header().string("X-Export-Truncated", "true")).andReturn();
        String body = capped.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body.substring(1).split("\r\n")).hasSize(4); // header + the cap of 3 rows
        assertThat(body).contains("n1").contains("n3").doesNotContain("n4"); // the first rows of the chosen order

        export(alice, acc, "?q=n1").andExpect(header().doesNotExist("X-Export-Truncated")); // fits under the cap
        export(alice, acc, "?maxAmount=3").andExpect(header().doesNotExist("X-Export-Truncated")); // exactly 3 rows
    }
}
