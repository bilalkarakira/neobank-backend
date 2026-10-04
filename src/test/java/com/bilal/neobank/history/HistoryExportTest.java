package com.bilal.neobank.history;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HistoryExportTest extends HistoryTestBase {

    private String csv(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void exportHasHeaderBomFilenameAndOneRowPerEntry() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);
        deposit(alice, acc, "100.00", "Salary");
        Acc bobAcc = open(tokenFor("bob@example.com", "Bob", "Miller"), "CHECKING", null);
        transfer(alice, acc, bobAcc.iban(), "30.00", "Rent");

        MvcResult result = export(alice, acc, "?sort=date,asc").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.matchesPattern("attachment; filename=\"neobank-" + acc.iban()
                                + "-\\d{4}-\\d{2}-\\d{2}\\.csv\"")))
                .andExpect(header().doesNotExist("X-Export-Truncated"))
                .andReturn();

        String body = csv(result);
        assertThat(body).startsWith("﻿");
        List<String> lines = List.of(body.substring(1).split("\r\n"));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("Date (UTC),Type,Direction,Amount,Balance after,Counterparty,IBAN,Note");
        assertThat(lines.get(1)).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},DEPOSIT,IN,100\\.00,100\\.00,Cash deposit,,Salary");
        assertThat(lines.get(2)).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},TRANSFER,OUT,-30\\.00,70\\.00,Bob M\\.,"
                + bobAcc.iban() + ",Rent");
    }

    @Test
    void quotesSpecialCharactersAndNeutralisesSpreadsheetFormulas() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);
        Acc evilAcc = open(tokenFor("carol@example.com", "=SUM(1)", "Kent"), "CHECKING", null);
        deposit(alice, acc, "500.00", "=HYPERLINK(\"http://evil\",\"x\")");
        deposit(alice, acc, "1.00", "Rent, \"June\"\nsecond line");
        deposit(alice, acc, "2.00", "+cmd");
        deposit(alice, acc, "3.00", "@SUM(A1)");
        deposit(alice, acc, "4.00", "-dash note");
        transfer(alice, acc, evilAcc.iban(), "5.00", null);

        String body = csv(export(alice, acc, "?sort=date,asc").andReturn());

        // formulas are prefixed with an apostrophe, and (because of the quotes and comma) quoted per RFC 4180
        assertThat(body).contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"");
        assertThat(body).contains("'+cmd").contains("'@SUM(A1)").contains("'-dash note");
        // commas, quotes and newlines survive inside one quoted cell
        assertThat(body).contains("\"Rent, \"\"June\"\"\nsecond line\"");
        // a counterparty NAME (user-controlled too) is neutralised as well
        assertThat(body).contains(",'=SUM(1) K.,");
        // our own signed numbers are NOT touched: they must remain numbers
        assertThat(body).contains(",OUT,-5.00,");
    }

    @Test
    void exportAppliesTheSameFiltersAsTheList() throws Exception {
        String alice = tokenFor("alice@example.com", "Alice", "Kent");
        Acc acc = open(alice, "CHECKING", null);
        deposit(alice, acc, "10.00", "keep me");
        deposit(alice, acc, "20.00", "other");

        String body = csv(export(alice, acc, "?q=keep").andReturn());

        assertThat(body).contains("keep me").doesNotContain("other");
    }

}
