package com.bilal.neobank.history;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountService;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.history.dto.HistoryPage;
import com.bilal.neobank.history.dto.TransactionItem;
import com.bilal.neobank.history.dto.TransactionItem.Counterparty;
import com.bilal.neobank.ledger.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class HistoryService {

    /** The exported file, and whether the row cap cut it short. */
    public record Export(String filename, byte[] content, boolean truncated) {
    }

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AccountService accountService;
    private final TransactionHistoryRepository repository;
    private final HistoryProperties properties;

    public HistoryService(AccountService accountService, TransactionHistoryRepository repository,
            HistoryProperties properties) {
        this.accountService = accountService;
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public HistoryPage page(UUID userId, UUID accountId, HistoryFilter filter) {
        accountService.get(userId, accountId); // 404 unless the caller owns the account
        long total = repository.count(accountId, filter);
        List<TransactionItem> items = repository
                .find(accountId, filter, filter.size(), (long) filter.page() * filter.size()).stream()
                .map(row -> toItem(row, userId)).toList();
        int totalPages = (int) ((total + filter.size() - 1) / filter.size());
        return new HistoryPage(items, filter.page(), filter.size(), total, totalPages);
    }

    /** Capped at app.history.export-max-rows; the whole file is built in memory, which that cap keeps small. */
    @Transactional(readOnly = true)
    public Export export(UUID userId, UUID accountId, HistoryFilter filter) {
        Account account = accountService.get(userId, accountId);
        int cap = properties.exportMaxRows();
        List<HistoryRow> rows = repository.find(accountId, filter, cap + 1, 0);
        boolean truncated = rows.size() > cap;
        if (truncated) {
            rows = rows.subList(0, cap);
        }

        CsvWriter csv = new CsvWriter();
        csv.row(List.of("Date (UTC)", "Type", "Direction", "Amount", "Balance after", "Counterparty", "IBAN",
                "Category", "Note"));
        for (HistoryRow row : rows) {
            TransactionItem item = toItem(row, userId);
            csv.row(List.of(
                    TIMESTAMP.format(row.createdAt().atOffset(ZoneOffset.UTC)),
                    item.type().name(),
                    item.direction().name(),
                    item.amount(), // numbers we generated ourselves: not escaped, so "-250.00" stays a number
                    item.balanceAfter(),
                    CsvWriter.text(item.counterparty().label()),
                    item.counterparty().iban() == null ? "" : item.counterparty().iban(),
                    CsvWriter.text(item.category()),
                    CsvWriter.text(item.description())));
        }
        String filename = "neobank-%s-%s.csv".formatted(account.getIban(), LocalDate.now(ZoneOffset.UTC));
        // a BOM makes Excel read the file as UTF-8
        byte[] content = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        return new Export(filename, content, truncated);
    }

    private static TransactionItem toItem(HistoryRow row, UUID userId) {
        Direction direction = row.amount().signum() < 0 ? Direction.OUT : Direction.IN;
        // a category is the SENDER's label for their own spending, so the receiver never sees it
        String category = direction == Direction.OUT ? row.categoryName() : null;
        return new TransactionItem(row.transactionId(), row.type(), direction, money(row.amount()),
                money(row.balanceAfter()), row.description(), category, counterparty(row, userId), row.createdAt(), row.recurring());
    }

    private static Counterparty counterparty(HistoryRow row, UUID userId) {
        if (row.counterpartyType() == AccountType.SYSTEM) {
            // the bank itself: either paying interest or handing out (simulated) cash
            return new Counterparty(row.type() == TransactionType.INTEREST ? "Interest earned" : "Cash deposit", null, false);
        }
        if (userId.equals(row.counterpartyUserId())) {
            String label = row.counterpartyNickname() != null ? row.counterpartyNickname()
                    : row.counterpartyType() == AccountType.CHECKING ? "Checking account" : "Savings account";
            return new Counterparty(label, row.counterpartyIban(), true);
        }
        String last = row.counterpartyLastName();
        String initial = last == null || last.isEmpty() ? "" : " " + last.charAt(0) + ".";
        return new Counterparty(row.counterpartyFirstName() + initial, row.counterpartyIban(), false);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
