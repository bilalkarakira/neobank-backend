package com.bilal.neobank.ledger;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Makes money-moving requests safe to retry. If the same (user, key) was already processed, the stored result is
 * returned instead of moving money again; if the key was used for a different request, that is rejected.
 *
 * Deliberately NOT @Transactional: when two identical requests race, the loser's database transaction rolls back
 * on the unique key, and only after that can the winner's committed result be read here.
 */
@Component
public class IdempotentTransactions {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    public record Outcome(LedgerTransaction transaction, boolean replayed) {
    }

    private final LedgerTransactionRepository transactionRepository;

    public IdempotentTransactions(LedgerTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public static void requireValidKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    /**
     * @param action performs the money movement in its own database transaction and returns the recorded transaction
     */
    public Outcome execute(UUID userId, String key, String requestHash, Supplier<LedgerTransaction> action) {
        Optional<LedgerTransaction> existing = transactionRepository.findByInitiatedByAndIdempotencyKey(userId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        try {
            return new Outcome(action.get(), false);
        } catch (DataIntegrityViolationException e) {
            // An identical request committed first (unique key violated): answer with its result instead.
            return transactionRepository.findByInitiatedByAndIdempotencyKey(userId, key)
                    .map(winner -> replay(winner, requestHash))
                    .orElseThrow(() -> e);
        }
    }

    private static Outcome replay(LedgerTransaction original, String requestHash) {
        if (!original.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        return new Outcome(original, true);
    }
}
