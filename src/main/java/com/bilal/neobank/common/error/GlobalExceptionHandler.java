package com.bilal.neobank.common.error;

import com.bilal.neobank.account.AccountLimitReachedException;
import com.bilal.neobank.account.AccountNotActiveException;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.account.AccountTypeNotAllowedException;
import com.bilal.neobank.account.IdempotencyKeyReusedException;
import com.bilal.neobank.account.InvalidIdempotencyKeyException;
import com.bilal.neobank.ledger.InsufficientFundsException;
import com.bilal.neobank.auth.EmailAlreadyRegisteredException;
import com.bilal.neobank.auth.InvalidCredentialsException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps exceptions to RFC 7807 problem details (application/problem+json). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail handleEmailTaken(EmailAlreadyRegisteredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email is already registered.");
        problem.setTitle("Email already registered");
        problem.setProperty("code", "EMAIL_ALREADY_REGISTERED");
        return problem;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        problem.setTitle("Invalid credentials");
        problem.setProperty("code", "INVALID_CREDENTIALS");
        return problem;
    }

    @ExceptionHandler(AccountLimitReachedException.class)
    ProblemDetail handleAccountLimit(AccountLimitReachedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Account limit reached");
        problem.setProperty("code", "ACCOUNT_LIMIT_REACHED");
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Account not found.");
        problem.setTitle("Account not found");
        problem.setProperty("code", "ACCOUNT_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(AccountNotActiveException.class)
    ProblemDetail handleAccountNotActive(AccountNotActiveException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Account not active", "This account is not active.",
                "ACCOUNT_NOT_ACTIVE");
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds",
                "The account does not have enough funds.", "INSUFFICIENT_FUNDS");
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused",
                "This Idempotency-Key was already used for a different request.", "IDEMPOTENCY_KEY_REUSED");
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail handleInvalidIdempotencyKey(InvalidIdempotencyKeyException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Idempotency key required", ex.getMessage(),
                "IDEMPOTENCY_KEY_REQUIRED");
    }

    @ExceptionHandler(AccountTypeNotAllowedException.class)
    ProblemDetail handleAccountTypeNotAllowed(AccountTypeNotAllowedException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Account type not allowed", ex.getMessage(),
                "ACCOUNT_TYPE_NOT_ALLOWED");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        problem.setTitle("Validation failed");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("fields", fields);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
