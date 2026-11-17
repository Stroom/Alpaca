package com.example.alpaca.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Step 7: settlement. Every execution (fill / partial_fill trade event) creates a PENDING settlement with the
 * settle_date Alpaca reports (US equities settle T+1). The matching FILL activity from the activities stream
 * confirms the booking, and a scheduled job marks settlements SETTLED once their settle date has been reached.
 * Alpaca does not send a separate "settled" event per trade.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    public static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final JdbcClient db;

    public SettlementService(JdbcClient db) {
        this.db = db;
    }

    public record Execution(String tradeEventId, String executionId, UUID orderId, UUID accountId, String symbol,
                            String side, BigDecimal qty, BigDecimal price, LocalDate tradeDate, LocalDate settleDate) {
    }

    public void recordExecution(Execution e) {
        BigDecimal gross = e.qty().multiply(e.price()).setScale(2, RoundingMode.HALF_UP);
        if ("buy".equals(e.side())) {
            gross = gross.negate();
        }
        LocalDate settleDate = e.settleDate() != null ? e.settleDate() : nextBusinessDay(e.tradeDate());
        db.sql("""
                INSERT INTO settlement (execution_id, trade_event_id, order_id, account_id, symbol, side, qty, price,
                                        gross_amount, trade_date, settle_date, status)
                VALUES (:exec, :event, :order, :account, :symbol, :side, :qty, :price, :gross, :tradeDate, :settleDate, 'PENDING')
                ON CONFLICT (execution_id) DO NOTHING
                """)
                .param("exec", e.executionId())
                .param("event", e.tradeEventId())
                .param("order", e.orderId())
                .param("account", e.accountId())
                .param("symbol", e.symbol())
                .param("side", e.side())
                .param("qty", e.qty())
                .param("price", e.price())
                .param("gross", gross)
                .param("tradeDate", e.tradeDate())
                .param("settleDate", settleDate)
                .update();
        linkFillActivities(e.orderId());
    }

    /**
     * Pairs settlements with FILL activities of the same order (same qty and price). Runs from both sides because
     * the trades and activities streams are independent and either may arrive first.
     */
    public void linkFillActivities(UUID orderId) {
        int updated;
        do {
            updated = db.sql("""
                    WITH m AS (
                        SELECT s.id AS sid, a.event_id AS aid, a.status AS ast, a.settle_date AS asd
                          FROM settlement s
                          JOIN account_activity a ON a.order_id = s.order_id AND a.activity_type IN ('FILL', 'TRD')
                                                 AND a.qty = s.qty AND a.price = s.price
                         WHERE s.order_id = :order AND s.activity_event_id IS NULL
                           AND NOT EXISTS (SELECT 1 FROM settlement s2 WHERE s2.activity_event_id = a.event_id)
                         ORDER BY s.id, a.executed_at
                         LIMIT 1)
                    UPDATE settlement SET activity_event_id = m.aid, activity_status = m.ast,
                                          settle_date = COALESCE(m.asd, settlement.settle_date)
                      FROM m WHERE settlement.id = m.sid
                    """).param("order", orderId).update();
        } while (updated > 0);
    }

    /**
     * Retries activity linking for executions that are still unconfirmed, then marks executions whose
     * settle date has arrived (in New York time) as settled.
     */
    @Scheduled(initialDelay = 10_000, fixedDelay = 60_000)
    public void settleDue() {
        db.sql("SELECT DISTINCT order_id FROM settlement WHERE activity_event_id IS NULL")
                .query(UUID.class).list()
                .forEach(this::linkFillActivities);
        int settled = db.sql("""
                UPDATE settlement SET status = 'SETTLED', settled_at = now()
                 WHERE status = 'PENDING' AND settle_date <= :today
                """).param("today", LocalDate.now(MARKET_ZONE)).update();
        if (settled > 0) {
            log.info("Marked {} executions as settled", settled);
        }
    }

    static LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }
}
