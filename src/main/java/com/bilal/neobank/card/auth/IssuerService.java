package com.bilal.neobank.card.auth;

import com.bilal.neobank.card.auth.AuthorizationEngine.AdviceInput;
import com.bilal.neobank.card.auth.AuthorizationEngine.AuthInput;
import com.bilal.neobank.card.auth.AuthorizationEngine.Decision;
import com.bilal.neobank.card.auth.AuthorizationEngine.ReversalInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Not transactional itself: each call to the engine is one transaction, and this wrapper turns whatever goes wrong
 * into an answer the terminal can use. Two simultaneous requests with the same duplicate key can both pass the lookup
 * for an unknown card; the unique index stops the second, and a second attempt then simply finds the first answer.
 * Anything else unexpected is a system error (96) and, since the transaction rolled back, leaves no hold.
 */
@Service
public class IssuerService {

    private static final Logger log = LoggerFactory.getLogger(IssuerService.class);

    private final AuthorizationEngine engine;

    public IssuerService(AuthorizationEngine engine) {
        this.engine = engine;
    }

    public Decision authorize(AuthInput input) {
        return guarded(() -> engine.authorize(input));
    }

    public Decision advise(AdviceInput input) {
        return guarded(() -> engine.advise(input));
    }

    public Decision reverse(ReversalInput input) {
        return guarded(() -> engine.reverse(input));
    }

    private Decision guarded(Supplier<Decision> call) {
        try {
            try {
                return call.get();
            } catch (DataIntegrityViolationException raced) {
                return call.get();
            }
        } catch (RuntimeException e) {
            // only the class: the message of a database error can quote the values (a hash, never a clear PAN, but still)
            log.warn("Issuer processing failed ({}), answering 96", e.getClass().getSimpleName());
            return new Decision(ResponseCode.SYSTEM_ERROR, null);
        }
    }
}
