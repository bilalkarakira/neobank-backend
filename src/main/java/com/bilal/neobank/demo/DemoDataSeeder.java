package com.bilal.neobank.demo;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.account.IbanGenerator;
import com.bilal.neobank.category.Category;
import com.bilal.neobank.category.CategoryRepository;
import com.bilal.neobank.interest.InterestCalculator;
import com.bilal.neobank.interest.InterestProperties;
import com.bilal.neobank.interest.InterestService;
import com.bilal.neobank.ledger.LedgerReconciliationService;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Development only (profile "demo"): creates a demo customer with about six months of believable activity so the
 * dashboard has something to show. The ledger service cannot backdate, so rows are inserted directly, keeping a
 * consistent running balance, and the reconciliation check proves the books balance before the data is committed.
 * Idempotent: does nothing when the demo user already exists. Fixed random seed, so the data is reproducible.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_EMAIL = "demo@neobank.local";
    public static final String DEMO_PASSWORD = "DemoPassw0rd";
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** How one month of interest was computed (kept in interest_accruals next to its transaction). */
    private record Accrual(String period, BigDecimal averageBalance, BigDecimal monthlyRate) {
    }

    private record Event(Instant at, UUID initiatedBy, UUID source, UUID destination, BigDecimal amount,
            String description, UUID categoryId, TransactionType type, Accrual accrual) {

        Event(Instant at, UUID initiatedBy, UUID source, UUID destination, BigDecimal amount, String description,
                UUID categoryId, TransactionType type) {
            this(at, initiatedBy, source, destination, amount, description, categoryId, type, null);
        }
    }

    private record Merchant(String first, String last, String email, String category) {
    }

    private static final List<Merchant> MERCHANTS = List.of(
            new Merchant("Maple", "Properties", "maple@neobank.local", "Housing"),
            new Merchant("Fresh", "Market", "fresh@neobank.local", "Groceries"),
            new Merchant("Pizza", "Corner", "pizza@neobank.local", "Dining"),
            new Merchant("City", "Transit", "transit@neobank.local", "Transport"),
            new Merchant("Power", "Water", "power@neobank.local", "Bills & utilities"),
            new Merchant("Style", "Shop", "style@neobank.local", "Shopping"),
            new Merchant("Cinema", "Plus", "cinema@neobank.local", "Entertainment"),
            new Merchant("Care", "Pharmacy", "care@neobank.local", "Health"),
            new Merchant("Sky", "Airlines", "sky@neobank.local", "Travel"));

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final LedgerReconciliationService reconciliation;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final boolean seedOnStartup;
    private final InterestProperties interestProperties;

    public DemoDataSeeder(UserRepository userRepository, AccountRepository accountRepository,
            CategoryRepository categoryRepository, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
            LedgerReconciliationService reconciliation, Clock clock, PlatformTransactionManager transactionManager,
            InterestProperties interestProperties, @Value("${app.demo.seed-on-startup:true}") boolean seedOnStartup) {
        this.seedOnStartup = seedOnStartup;
        this.interestProperties = interestProperties;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedOnStartup) {
            seed();
        }
    }

    /** @return true when data was created, false when the demo user already existed */
    public boolean seed() {
        Boolean created = transactions.execute(status -> {
            if (userRepository.existsByEmail(DEMO_EMAIL)) {
                return false;
            }
            doSeed();
            return true;
        });
        if (Boolean.TRUE.equals(created)) {
            log.info("Demo data created. Log in as {} / {}", DEMO_EMAIL, DEMO_PASSWORD);
        } else {
            log.info("Demo data already present ({}); nothing to do", DEMO_EMAIL);
        }
        return Boolean.TRUE.equals(created);
    }

    private void doSeed() {
        User demo = createUser(DEMO_EMAIL, "Demo", "Customer");
        User sam = createUser("sam@neobank.local", "Sam", "Rivera");
        UUID checking = createAccount(demo, AccountType.CHECKING, "Main");
        UUID savings = createAccount(demo, AccountType.SAVINGS, "Rainy day");
        UUID samChecking = createAccount(sam, AccountType.CHECKING, "Sam main");

        Map<String, UUID> categories = new HashMap<>();
        for (Category category : categoryRepository.findAll()) {
            categories.put(category.getName(), category.getId());
        }
        Map<String, UUID> merchantAccounts = new HashMap<>();
        for (Merchant merchant : MERCHANTS) {
            User owner = createUser(merchant.email(), merchant.first(), merchant.last());
            merchantAccounts.put(merchant.category(), createAccount(owner, AccountType.CHECKING, null));
        }
        accountRepository.flush();

        List<Event> events = generateEvents(demo.getId(), sam.getId(), checking, savings, samChecking, categories,
                merchantAccounts);
        events.addAll(interestEvents(events, savings));
        events.sort(Comparator.comparing(Event::at));
        insert(events);

        var report = reconciliation.check();
        if (!report.isHealthy()) {
            throw new IllegalStateException("Demo data left the ledger unbalanced: " + report);
        }
    }

    private List<Event> generateEvents(UUID demoId, UUID samId, UUID checking, UUID savings, UUID samChecking,
            Map<String, UUID> categories, Map<String, UUID> merchants) {
        Random random = new Random(42);
        Instant now = clock.instant();
        LocalDate start = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1).minusMonths(5);
        List<Event> events = new ArrayList<>();

        events.add(deposit(at(start, 8, 0), samId, samChecking, "3000.00", "Initial funding"));
        for (int m = 0; m < 6; m++) {
            LocalDate month = start.plusMonths(m);
            events.add(deposit(at(month, 9, 0), demoId, checking, "3200.00", "Salary"));
            events.add(spend(at(month.withDayOfMonth(2), 10, 30), demoId, checking, merchants.get("Housing"), "950.00",
                    "Rent", categories.get("Housing")));
            events.add(new Event(at(month.withDayOfMonth(3), 12, 0), demoId, checking, savings, money("300.00"),
                    "Monthly savings", null, TransactionType.TRANSFER));
            events.add(spend(at(month.withDayOfMonth(5), 18, 15), demoId, checking, merchants.get("Bills & utilities"),
                    cents(55 + random.nextInt(40), random), "Electricity & water", categories.get("Bills & utilities")));
            events.add(spend(at(month.withDayOfMonth(8), 18, 20), demoId, checking, merchants.get("Bills & utilities"),
                    "45.00", "Internet", categories.get("Bills & utilities")));
            for (int day : new int[] { 4, 11, 18, 25 }) {
                events.add(spend(at(month.withDayOfMonth(day), 17, 40), demoId, checking, merchants.get("Groceries"),
                        cents(48 + random.nextInt(75), random), "Weekly shop", categories.get("Groceries")));
            }
            scatter(events, random, month, 3 + random.nextInt(4), 11, 35, new String[] { "Lunch", "Dinner out",
                    "Coffee & cake", "Takeaway" }, demoId, checking, merchants.get("Dining"), categories.get("Dining"));
            scatter(events, random, month, 4 + random.nextInt(3), 3, 22, new String[] { "Transit pass", "Taxi",
                    "Bike share" }, demoId, checking, merchants.get("Transport"), categories.get("Transport"));
            scatter(events, random, month, 1 + random.nextInt(2), 25, 115, new String[] { "Clothes", "Books",
                    "Home goods" }, demoId, checking, merchants.get("Shopping"), categories.get("Shopping"));
            scatter(events, random, month, 1 + random.nextInt(2), 9, 29, new String[] { "Cinema", "Streaming" },
                    demoId, checking, merchants.get("Entertainment"), categories.get("Entertainment"));
            if (random.nextInt(3) == 0) {
                scatter(events, random, month, 1, 18, 47, new String[] { "Pharmacy" }, demoId, checking,
                        merchants.get("Health"), categories.get("Health"));
            }
            if (m == 2) {
                events.add(spend(at(month.withDayOfMonth(16), 11, 0), demoId, checking, merchants.get("Travel"),
                        "360.00", "Flights", categories.get("Travel")));
            }
            // money received from a friend is income; money sent to a friend is spending ("Other")
            events.add(new Event(at(month.withDayOfMonth(10 + random.nextInt(10)), 20, 10), samId, samChecking,
                    checking, money(cents(40 + random.nextInt(80), random)), "Dinner split", null,
                    TransactionType.TRANSFER));
            if (m % 2 == 1) {
                events.add(spend(at(month.withDayOfMonth(20), 14, 0), demoId, checking, samChecking, "40.00",
                        "Birthday gift", categories.get("Other")));
            }
        }
        return events.stream().filter(e -> !e.at().isAfter(now)).collect(java.util.stream.Collectors.toList());
    }

    /**
     * One interest credit per completed month for the savings account, computed with the SAME formulas as the real
     * job (average of the end-of-day balances, which include the interest of the earlier months) and dated like the
     * real job would have dated it: the first minutes of the following month.
     */
    private List<Event> interestEvents(List<Event> events, UUID savings) {
        List<Event> inflows = new ArrayList<>(events.stream().filter(e -> e.destination().equals(savings)).toList());
        if (inflows.isEmpty()) {
            return List.of();
        }
        inflows.sort(Comparator.comparing(Event::at));
        Instant now = clock.instant();
        BigDecimal rate = InterestCalculator.monthlyRate(interestProperties.apy());
        YearMonth lastCompleted = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
        List<Event> created = new ArrayList<>();

        for (YearMonth period = YearMonth.from(inflows.get(0).at().atOffset(ZoneOffset.UTC)); !period.isAfter(lastCompleted);
                period = period.plusMonths(1)) {
            Instant creditedAt = at(period.plusMonths(1).atDay(1), 0, 30);
            if (creditedAt.isAfter(now)) {
                break;
            }
            List<BigDecimal> endOfDay = new ArrayList<>();
            for (LocalDate day = period.atDay(1); !day.isAfter(period.atEndOfMonth()); day = day.plusDays(1)) {
                Instant dayEnd = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                endOfDay.add(inflows.stream().filter(e -> e.at().isBefore(dayEnd)).map(Event::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
            BigDecimal average = InterestCalculator.averageBalance(endOfDay);
            BigDecimal amount = InterestCalculator.interest(average, rate);
            if (amount.signum() > 0) {
                Event interest = new Event(creditedAt, null, SystemAccounts.INTEREST, savings, amount,
                        InterestService.description(period), null, TransactionType.INTEREST,
                        new Accrual(period.toString(), average, rate));
                created.add(interest);
                inflows.add(interest); // later months earn interest on this interest
            }
        }
        return created;
    }

    private void scatter(List<Event> events, Random random, LocalDate month, int count, int min, int max,
            String[] notes, UUID demoId, UUID checking, UUID merchantAccount, UUID categoryId) {
        for (int i = 0; i < count; i++) {
            LocalDate day = month.withDayOfMonth(1 + random.nextInt(28));
            events.add(spend(at(day, 9 + random.nextInt(12), random.nextInt(60)), demoId, checking, merchantAccount,
                    cents(min + random.nextInt(max - min + 1), random), notes[random.nextInt(notes.length)],
                    categoryId));
        }
    }

    private static final Set<UUID> SYSTEM_ACCOUNTS = Set.of(SystemAccounts.FUNDING, SystemAccounts.INTEREST);

    /** Writes the events in time order with a consistent running balance (the ledger service cannot backdate). */
    private void insert(List<Event> events) {
        // running balances; the bank accounts start from what they already hold, new customer accounts from zero
        Map<UUID, BigDecimal> balances = new HashMap<>();
        for (UUID system : SYSTEM_ACCOUNTS) {
            balances.put(system, jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, system));
        }
        int n = 0;
        for (Event event : events) {
            n++;
            BigDecimal sourceBefore = balances.getOrDefault(event.source(), BigDecimal.ZERO);
            if (!SYSTEM_ACCOUNTS.contains(event.source()) && sourceBefore.compareTo(event.amount()) < 0) {
                throw new IllegalStateException("Demo data would overdraw an account at " + event.at());
            }
            BigDecimal sourceAfter = sourceBefore.subtract(event.amount());
            BigDecimal destinationAfter = balances.getOrDefault(event.destination(), BigDecimal.ZERO).add(event.amount());
            balances.put(event.source(), sourceAfter);
            balances.put(event.destination(), destinationAfter);

            UUID transactionId = UUID.randomUUID();
            OffsetDateTime when = OffsetDateTime.ofInstant(event.at(), ZoneOffset.UTC);
            jdbc.update("""
                    insert into transactions (id, type, initiated_by, idempotency_key, request_hash, description,
                                              source_account_id, destination_account_id, amount, category_id, created_at)
                    values (?, ?, ?, ?, 'demo-seed', ?, ?, ?, ?, ?, ?)
                    """, transactionId, event.type().name(), event.initiatedBy(), "demo-seed-" + n, event.description(),
                    event.source(), event.destination(), event.amount(), event.categoryId(), when);
            entry(transactionId, event.source(), event.amount().negate(), sourceAfter, when);
            entry(transactionId, event.destination(), event.amount(), destinationAfter, when);

            if (event.accrual() != null) {
                jdbc.update("""
                        insert into interest_accruals (account_id, period, status, average_balance, monthly_rate, amount,
                                                       transaction_id, processed_at)
                        values (?, ?, 'CREDITED', ?, ?, ?, ?, ?)
                        """, event.destination(), event.accrual().period(), event.accrual().averageBalance(),
                        event.accrual().monthlyRate(), event.amount(), transactionId, when);
            }
        }
        for (Map.Entry<UUID, BigDecimal> balance : balances.entrySet()) {
            jdbc.update("update accounts set balance = ? where id = ?", balance.getValue(), balance.getKey());
        }
    }

    private void entry(UUID transactionId, UUID accountId, BigDecimal amount, BigDecimal balanceAfter,
            OffsetDateTime when) {
        jdbc.update("""
                insert into ledger_entries (transaction_id, account_id, amount, balance_after, created_at)
                values (?, ?, ?, ?, ?)
                """, transactionId, accountId, amount, balanceAfter, when);
    }

    private User createUser(String email, String first, String last) {
        return userRepository.save(new User(email, passwordEncoder.encode(DEMO_PASSWORD), first, last, Role.CUSTOMER));
    }

    private UUID createAccount(User owner, AccountType type, String nickname) {
        String iban = IbanGenerator.generate(accountRepository.nextAccountNumber());
        return accountRepository.save(new Account(owner.getId(), iban, type, nickname)).getId();
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static Event deposit(Instant at, UUID initiatedBy, UUID account, String amount, String description) {
        return new Event(at, initiatedBy, SystemAccounts.FUNDING, account, money(amount), description, null,
                TransactionType.DEPOSIT);
    }

    private static Event spend(Instant at, UUID initiatedBy, UUID source, UUID destination, String amount,
            String description, UUID categoryId) {
        return new Event(at, initiatedBy, source, destination, money(amount), description, categoryId,
                TransactionType.TRANSFER);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    /** A whole-dollar base plus random cents, as a decimal string. */
    private static String cents(int dollars, Random random) {
        return "%d.%02d".formatted(dollars, random.nextInt(100));
    }
}
