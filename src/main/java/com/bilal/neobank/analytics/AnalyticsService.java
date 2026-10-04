package com.bilal.neobank.analytics;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountService;
import com.bilal.neobank.analytics.AnalyticsRepository.CategorySpend;
import com.bilal.neobank.analytics.AnalyticsRepository.MonthFlow;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.BalancePoint;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.CashflowMonth;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.CategoryShare;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.Changes;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.MonthTotals;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.SpendingByCategory;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.Summary;
import com.bilal.neobank.history.InvalidFilterException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Everything is UTC. "All accounts" means all of the caller's accounts; one account id narrows it (404 if not theirs). */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    public static final int DEFAULT_DAYS = 90;
    public static final int DEFAULT_MONTHS = 6;
    private static final int MAX_RANGE_DAYS = 366;

    private final AnalyticsRepository repository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final Clock clock;

    public AnalyticsService(AnalyticsRepository repository, AccountRepository accountRepository,
            AccountService accountService, Clock clock) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.clock = clock;
    }

    public Summary summary(UUID userId, UUID accountId) {
        List<UUID> ids = scope(userId, accountId);
        YearMonth current = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        YearMonth previous = current.minusMonths(1);

        Map<String, MonthFlow> flows = ids.isEmpty() ? Map.of()
                : repository.monthlyFlow(ids, userId, start(previous), start(current.plusMonths(1))).stream()
                        .collect(Collectors.toMap(MonthFlow::month, f -> f));
        MonthTotals now = totals(current, flows.get(current.toString()));
        MonthTotals before = totals(previous, flows.get(previous.toString()));

        BigDecimal balance = ids.isEmpty() ? BigDecimal.ZERO : repository.totalBalance(ids);
        Changes changes = new Changes(change(now.income(), before.income()), change(now.spending(), before.spending()),
                change(now.net(), before.net()));
        return new Summary("USD", money(balance), now, before, changes);
    }

    public List<BalancePoint> balanceHistory(UUID userId, UUID accountId, Integer days) {
        int count = days == null ? DEFAULT_DAYS : days;
        if (count < 7 || count > 365) {
            throw new InvalidFilterException(Map.of("days", "must be between 7 and 365"));
        }
        List<UUID> ids = scope(userId, accountId);
        if (ids.isEmpty()) {
            return List.of();
        }
        LocalDate end = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return repository.dailyBalances(ids, end.minusDays(count - 1L), end).stream()
                .map(p -> new BalancePoint(p.date(), money(p.balance()))).toList();
    }

    public List<CashflowMonth> cashflow(UUID userId, UUID accountId, Integer months) {
        int count = months == null ? DEFAULT_MONTHS : months;
        if (count < 1 || count > 12) {
            throw new InvalidFilterException(Map.of("months", "must be between 1 and 12"));
        }
        List<UUID> ids = scope(userId, accountId);
        YearMonth last = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        YearMonth first = last.minusMonths(count - 1L);

        Map<String, MonthFlow> flows = ids.isEmpty() ? Map.of()
                : repository.monthlyFlow(ids, userId, start(first), start(last.plusMonths(1))).stream()
                        .collect(Collectors.toMap(MonthFlow::month, f -> f));
        // months without any activity still get a (zero) entry, so the chart has no gaps
        return IntStream.range(0, count).mapToObj(i -> first.plusMonths(i)).map(month -> {
            MonthFlow flow = flows.get(month.toString());
            return new CashflowMonth(month.toString(), money(flow == null ? BigDecimal.ZERO : flow.income()),
                    money(flow == null ? BigDecimal.ZERO : flow.spending()));
        }).toList();
    }

    public SpendingByCategory spendingByCategory(UUID userId, UUID accountId, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate begin = from != null ? from : end.minusDays(29);
        if (begin.isAfter(end)) {
            throw new InvalidFilterException(Map.of("from", "must not be after 'to'"));
        }
        if (begin.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new InvalidFilterException(Map.of("from", "the range may span at most " + MAX_RANGE_DAYS + " days"));
        }
        List<UUID> ids = scope(userId, accountId);
        List<CategorySpend> rows = ids.isEmpty() ? List.of()
                : repository.spendingByCategory(ids, userId, begin.atStartOfDay().toInstant(ZoneOffset.UTC),
                        end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        BigDecimal total = rows.stream().map(CategorySpend::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> shares = shares(rows.stream().map(CategorySpend::total).toList(), total);
        List<CategoryShare> categories = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            categories.add(new CategoryShare(rows.get(i).name(), money(rows.get(i).total()), shares.get(i)));
        }
        return new SpendingByCategory(begin, end, money(total), categories);
    }

    /**
     * Percentages with one decimal that add up to exactly 100.0 (largest-remainder method): every slice gets the
     * rounded-down share, and the leftover tenths go to the slices with the biggest remainders.
     */
    static List<BigDecimal> shares(List<BigDecimal> values, BigDecimal total) {
        if (values.isEmpty() || total.signum() == 0) {
            return values.stream().map(v -> BigDecimal.ZERO.setScale(1)).toList();
        }
        int n = values.size();
        long[] tenths = new long[n];
        BigDecimal[] remainders = new BigDecimal[n];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            BigDecimal exact = values.get(i).multiply(BigDecimal.valueOf(1000)).divide(total, 10, RoundingMode.HALF_UP);
            tenths[i] = exact.setScale(0, RoundingMode.FLOOR).longValueExact();
            remainders[i] = exact.subtract(BigDecimal.valueOf(tenths[i]));
            assigned += tenths[i];
        }
        long leftover = 1000 - assigned;
        List<Integer> order = IntStream.range(0, n).boxed()
                .sorted(Comparator.<Integer, BigDecimal>comparing(i -> remainders[i]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        for (int k = 0; k < leftover; k++) {
            tenths[order.get(k % n)]++;
        }
        return IntStream.range(0, n).mapToObj(i -> BigDecimal.valueOf(tenths[i], 1)).toList();
    }

    /** The ids to aggregate over; empty when the user has no accounts. */
    private List<UUID> scope(UUID userId, UUID accountId) {
        if (accountId != null) {
            Account account = accountService.get(userId, accountId);
            return List.of(account.getId());
        }
        return accountRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(Account::getId).toList();
    }

    private static MonthTotals totals(YearMonth month, MonthFlow flow) {
        BigDecimal income = flow == null ? BigDecimal.ZERO : flow.income();
        BigDecimal spending = flow == null ? BigDecimal.ZERO : flow.spending();
        return new MonthTotals(month.toString(), money(income), money(spending), money(income.subtract(spending)));
    }

    /** (current - previous) / |previous| in percent with one decimal; null when previous is zero. */
    private static BigDecimal change(String current, String previous) {
        BigDecimal before = new BigDecimal(previous);
        if (before.signum() == 0) {
            return null;
        }
        return new BigDecimal(current).subtract(before).multiply(BigDecimal.valueOf(100))
                .divide(before.abs(), 1, RoundingMode.HALF_UP);
    }

    private static Instant start(YearMonth month) {
        return month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
