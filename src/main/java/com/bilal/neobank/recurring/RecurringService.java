package com.bilal.neobank.recurring;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountService;
import com.bilal.neobank.account.IbanGenerator;
import com.bilal.neobank.category.CategoryNotFoundException;
import com.bilal.neobank.category.CategoryRepository;
import com.bilal.neobank.recurring.RecurringExceptions.InvalidScheduleException;
import com.bilal.neobank.recurring.RecurringExceptions.InvalidStateException;
import com.bilal.neobank.recurring.RecurringExceptions.LimitReachedException;
import com.bilal.neobank.recurring.RecurringExceptions.NotFoundException;
import com.bilal.neobank.recurring.RecurringRepository.Row;
import com.bilal.neobank.recurring.dto.RecurringDtos.CreateRequest;
import com.bilal.neobank.recurring.dto.RecurringDtos.UpdateRequest;
import com.bilal.neobank.transfer.TransferExceptions.DailyLimitExceededException;
import com.bilal.neobank.transfer.TransferExceptions.SameAccountException;
import com.bilal.neobank.transfer.TransferProperties;
import com.bilal.neobank.transfer.TransferService;
import com.bilal.neobank.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Creating, changing and cancelling standing orders. Running them is RecurringExecutor's job. */
@Service
public class RecurringService {

    private final RecurringRepository repository;
    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final TransferService transferService;
    private final TransferProperties transferProperties;
    private final RecurringProperties properties;
    private final Clock clock;

    public RecurringService(RecurringRepository repository, AccountService accountService,
            AccountRepository accountRepository, UserRepository userRepository,
            CategoryRepository categoryRepository, TransferService transferService,
            TransferProperties transferProperties, RecurringProperties properties, Clock clock) {
        this.repository = repository;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.transferService = transferService;
        this.transferProperties = transferProperties;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Row create(UUID userId, CreateRequest request) {
        // serialises this user's creations, so the per-user limit cannot be beaten by two parallel requests
        userRepository.findByIdForUpdate(userId).orElseThrow(AccountNotFoundException::new);

        Account source = accountService.get(userId, request.sourceAccountId()); // 404 unless theirs
        String iban = IbanGenerator.normalize(request.destinationIban());
        transferService.lookup(userId, iban); // valid, exists, active and not a bank-internal account
        if (iban.equals(source.getIban())) {
            throw new SameAccountException();
        }
        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        requireAmountWithinLimit(userId, iban, amount);
        requireCategory(request.categoryId());

        LocalDate today = today();
        if (request.startDate().isBefore(today)) {
            throw new InvalidScheduleException("startDate", "must not be in the past");
        }
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new InvalidScheduleException("endDate", "must not be before the start date");
        }
        if (repository.countOpen(userId) >= properties.maxOpen()) {
            throw new LimitReachedException(properties.maxOpen());
        }

        Row row = new Row(UUID.randomUUID(), userId, source.getId(), iban, amount, clean(request.description()),
                request.categoryId(), request.frequency(), request.startDate(), request.endDate(), 0,
                request.startDate(), RecurringStatus.ACTIVE, false, 0, OffsetDateTime.now(clock));
        repository.insert(row);
        return row;
    }

    @Transactional(readOnly = true)
    public List<Row> list(UUID userId) {
        return repository.listForUser(userId);
    }

    @Transactional(readOnly = true)
    public Row get(UUID userId, UUID id) {
        return repository.findForUser(userId, id).orElseThrow(NotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<RecurringRepository.RunRow> runs(UUID userId, UUID id) {
        get(userId, id);
        return repository.listRuns(id, 50);
    }

    @Transactional
    public Row update(UUID userId, UUID id, UpdateRequest request) {
        Row row = lock(userId, id);
        if (row.status() != RecurringStatus.ACTIVE && row.status() != RecurringStatus.PAUSED) {
            throw new InvalidStateException("This recurring payment has ended.");
        }
        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        requireAmountWithinLimit(userId, row.destinationIban(), amount);
        requireCategory(request.categoryId());
        repository.updateDetails(id, amount, clean(request.description()), request.categoryId());
        return repository.findForUser(userId, id).orElseThrow(NotFoundException::new);
    }

    @Transactional
    public Row pause(UUID userId, UUID id) {
        Row row = lock(userId, id);
        if (row.status() != RecurringStatus.ACTIVE) {
            throw new InvalidStateException("Only an active recurring payment can be paused.");
        }
        repository.updateState(id, row.occurrence(), row.nextRunDate(), RecurringStatus.PAUSED, false, 0);
        return repository.findForUser(userId, id).orElseThrow(NotFoundException::new);
    }

    /** Resuming skips the dates that passed while paused: they are not paid retroactively. */
    @Transactional
    public Row resume(UUID userId, UUID id) {
        Row row = lock(userId, id);
        if (row.status() != RecurringStatus.PAUSED) {
            throw new InvalidStateException("Only a paused recurring payment can be resumed.");
        }
        LocalDate today = today();
        int n = row.occurrence();
        LocalDate next = row.frequency().occurrence(row.startDate(), n);
        while (next.isBefore(today)) {
            next = row.frequency().occurrence(row.startDate(), ++n);
        }
        if (row.endDate() != null && next.isAfter(row.endDate())) {
            repository.updateState(id, n, null, RecurringStatus.COMPLETED, false, 0);
        } else {
            repository.updateState(id, n, next, RecurringStatus.ACTIVE, false, 0);
        }
        return repository.findForUser(userId, id).orElseThrow(NotFoundException::new);
    }

    /** Cancelling is final: the order disappears from the API, its past runs stay in the ledger. */
    @Transactional
    public void cancel(UUID userId, UUID id) {
        Row row = lock(userId, id);
        repository.updateState(id, row.occurrence(), null, RecurringStatus.CANCELLED, row.autoPaused(),
                row.consecutiveFailures());
    }

    private Row lock(UUID userId, UUID id) {
        return repository.findForUserForUpdate(userId, id).orElseThrow(NotFoundException::new);
    }

    /** The daily limit applies to money sent to other customers, so a single run above it could never succeed. */
    private void requireAmountWithinLimit(UUID userId, String iban, BigDecimal amount) {
        boolean toSomeoneElse = accountRepository.findByIban(iban)
                .map(destination -> !userId.equals(destination.getUserId())).orElse(true);
        BigDecimal limit = transferProperties.dailyLimit();
        if (toSomeoneElse && amount.compareTo(limit) > 0) {
            throw new DailyLimitExceededException(limit.setScale(2), limit.setScale(2));
        }
    }

    private void requireCategory(UUID categoryId) {
        if (categoryId != null && !categoryRepository.existsById(categoryId)) {
            throw new CategoryNotFoundException();
        }
    }

    private static String clean(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
