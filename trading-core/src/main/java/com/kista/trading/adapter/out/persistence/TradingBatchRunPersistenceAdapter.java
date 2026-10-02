package com.kista.trading.adapter.out.persistence;

import com.kista.trading.application.port.output.TradingBatchRunPort;
import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

// 매매 배치 체크포인트 — 단순 upsert/exists라 JPA 엔티티 대신 JdbcTemplate (search_path=trading)
@Component
@RequiredArgsConstructor
class TradingBatchRunPersistenceAdapter implements TradingBatchRunPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<TradingBatchPhase> findPhase(TradingBatchJob job, LocalDate tradeDate) {
        return jdbcTemplate.queryForList(
                        "SELECT phase FROM trading_batch_run WHERE job_name = ? AND trade_date = ?",
                        String.class, job.lockName(), tradeDate)
                .stream().findFirst().map(TradingBatchPhase::valueOf);
    }

    @Override
    public void recordPhase(TradingBatchJob job, LocalDate tradeDate, TradingBatchPhase phase) {
        jdbcTemplate.update("""
                INSERT INTO trading_batch_run (job_name, trade_date, phase, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (job_name, trade_date) DO UPDATE
                   SET phase = EXCLUDED.phase,
                       updated_at = now()
                """, job.lockName(), tradeDate, phase.name());
    }

    @Override
    public boolean isReported(LocalDate tradeDate, UUID strategyId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM trading_batch_report WHERE trade_date = ? AND strategy_id = ?)",
                Boolean.class, tradeDate, strategyId));
    }

    @Override
    public void markReported(LocalDate tradeDate, UUID strategyId) {
        jdbcTemplate.update("""
                INSERT INTO trading_batch_report (trade_date, strategy_id)
                VALUES (?, ?)
                ON CONFLICT (trade_date, strategy_id) DO NOTHING
                """, tradeDate, strategyId);
    }
}
