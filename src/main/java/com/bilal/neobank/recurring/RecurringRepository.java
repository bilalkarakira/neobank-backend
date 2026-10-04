package com.bilal.neobank.recurring;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RecurringRepository {

    public record Row(UUID id, UUID userId, UUID sourceAccountId, String destinationIban, BigDecimal amount,
            String description, UUID categoryId, Frequency frequency, LocalDate startDate, LocalDate endDate,
            int occurrence, LocalDate nextRunDate, RecurringStatus status, boolean autoPaused,
            int consecutiveFailures, OffsetDateTime createdAt) {
    }

    public record RunRow(LocalDate runDate, String status, String failureCode, String failureMessage,
            UUID transactionId, OffsetDateTime executedAt) {
    }

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(
            rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getObject("source_account_id", UUID.class), rs.getString("destination_iban"),
            rs.getBigDecimal("amount"), rs.getString("description"), rs.getObject("category_id", UUID.class),
            Frequency.valueOf(rs.getString("frequency")), rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class), rs.getInt("occurrence"),
            rs.getObject("next_run_date", LocalDate.class), RecurringStatus.valueOf(rs.getString("status")),
            rs.getBoolean("auto_paused"), rs.getInt("consecutive_failures"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public RecurringRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Row r) {
        jdbc.sql("""
                insert into recurring_payments (id, user_id, source_account_id, destination_iban, amount, description,
                    category_id, frequency, start_date, end_date, occurrence, next_run_date, status, auto_paused,
                    consecutive_failures, created_at)
                values (:id, :user, :source, :iban, :amount, :description, :category, :frequency, :start, :end,
                    :occurrence, :next, :status, :auto, :failures, :created)
                """)
                .param("id", r.id()).param("user", r.userId()).param("source", r.sourceAccountId())
                .param("iban", r.destinationIban()).param("amount", r.amount()).param("description", r.description())
                .param("category", r.categoryId()).param("frequency", r.frequency().name())
                .param("start", r.startDate()).param("end", r.endDate()).param("occurrence", r.occurrence())
                .param("next", r.nextRunDate()).param("status", r.status().name()).param("auto", r.autoPaused())
                .param("failures", r.consecutiveFailures()).param("created", r.createdAt())
                .update();
    }

    /** Owner-scoped read: cancelled orders and other people's orders are simply absent. */
    public Optional<Row> findForUser(UUID userId, UUID id) {
        return jdbc.sql("select * from recurring_payments where id = :id and user_id = :user and status <> 'CANCELLED'")
                .param("id", id).param("user", userId).query(ROW).optional();
    }

    public Optional<Row> findForUserForUpdate(UUID userId, UUID id) {
        return jdbc.sql("""
                select * from recurring_payments where id = :id and user_id = :user and status <> 'CANCELLED'
                for update
                """).param("id", id).param("user", userId).query(ROW).optional();
    }

    public Optional<Row> findForUpdate(UUID id) {
        return jdbc.sql("select * from recurring_payments where id = :id for update")
                .param("id", id).query(ROW).optional();
    }

    public List<Row> listForUser(UUID userId) {
        return jdbc.sql("""
                select * from recurring_payments where user_id = :user and status <> 'CANCELLED'
                order by created_at desc, id
                """).param("user", userId).query(ROW).list();
    }

    /** Orders that count against the per-user limit. */
    public long countOpen(UUID userId) {
        return jdbc.sql("select count(*) from recurring_payments where user_id = :user and status in ('ACTIVE', 'PAUSED')")
                .param("user", userId).query(Long.class).single();
    }

    public List<UUID> dueIds(LocalDate today) {
        return jdbc.sql("""
                select id from recurring_payments
                where status = 'ACTIVE' and next_run_date <= :today order by next_run_date, created_at
                """).param("today", today).query(UUID.class).list();
    }

    public void updateDetails(UUID id, BigDecimal amount, String description, UUID categoryId) {
        jdbc.sql("update recurring_payments set amount = :amount, description = :description, category_id = :category where id = :id")
                .param("amount", amount).param("description", description).param("category", categoryId)
                .param("id", id).update();
    }

    public void updateState(UUID id, int occurrence, LocalDate nextRunDate, RecurringStatus status,
            boolean autoPaused, int consecutiveFailures) {
        jdbc.sql("""
                update recurring_payments set occurrence = :occurrence, next_run_date = :next, status = :status,
                    auto_paused = :auto, consecutive_failures = :failures where id = :id
                """)
                .param("occurrence", occurrence).param("next", nextRunDate).param("status", status.name())
                .param("auto", autoPaused).param("failures", consecutiveFailures).param("id", id).update();
    }

    public void insertRun(UUID paymentId, LocalDate runDate, String status, String failureCode,
            String failureMessage, UUID transactionId, OffsetDateTime executedAt) {
        jdbc.sql("""
                insert into recurring_runs (payment_id, run_date, status, failure_code, failure_message,
                    transaction_id, executed_at)
                values (:payment, :date, :status, :code, :message, :tx, :at)
                """)
                .param("payment", paymentId).param("date", runDate).param("status", status)
                .param("code", failureCode).param("message", failureMessage).param("tx", transactionId)
                .param("at", executedAt).update();
    }

    public List<RunRow> listRuns(UUID paymentId, int limit) {
        return jdbc.sql("""
                select run_date, status, failure_code, failure_message, transaction_id, executed_at
                from recurring_runs where payment_id = :id order by run_date desc limit :limit
                """).param("id", paymentId).param("limit", limit)
                .query((rs, i) -> new RunRow(rs.getObject("run_date", LocalDate.class), rs.getString("status"),
                        rs.getString("failure_code"), rs.getString("failure_message"),
                        rs.getObject("transaction_id", UUID.class), rs.getObject("executed_at", OffsetDateTime.class)))
                .list();
    }
}
