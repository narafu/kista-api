package com.kista.trading.adapter.out.persistence;

import com.kista.support.DataJpaTestBase;
import com.kista.support.TradingCoreJpaTestConfig;
import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 체크포인트 upsert·마커 멱등 insert 실 DB 왕복 검증
@Import(TradingBatchRunPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
@ContextConfiguration(classes = TradingCoreJpaTestConfig.class)
class TradingBatchRunPersistenceAdapterDbTest extends DataJpaTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    @Autowired TradingBatchRunPersistenceAdapter adapter;

    @Test
    void findPhase_noRow_empty() {
        assertThat(adapter.findPhase(TradingBatchJob.CLOSE, DAY)).isEmpty();
    }

    @Test
    void recordPhase_upsertsLatestPhasePerJob() {
        adapter.recordPhase(TradingBatchJob.CLOSE, DAY, TradingBatchPhase.PLANNED);
        adapter.recordPhase(TradingBatchJob.CLOSE, DAY, TradingBatchPhase.PLACED);
        adapter.recordPhase(TradingBatchJob.OPEN, DAY, TradingBatchPhase.DONE);

        assertThat(adapter.findPhase(TradingBatchJob.CLOSE, DAY)).contains(TradingBatchPhase.PLACED);
        assertThat(adapter.findPhase(TradingBatchJob.OPEN, DAY)).contains(TradingBatchPhase.DONE);
        assertThat(adapter.findPhase(TradingBatchJob.CLOSE, DAY.plusDays(1))).isEmpty();
    }

    @Test
    void markReported_idempotent_andScopedByDateAndStrategy() {
        UUID strategyId = UUID.randomUUID();

        adapter.markReported(DAY, strategyId);
        adapter.markReported(DAY, strategyId); // 중복 호출 무해

        assertThat(adapter.isReported(DAY, strategyId)).isTrue();
        assertThat(adapter.isReported(DAY.plusDays(1), strategyId)).isFalse();
        assertThat(adapter.isReported(DAY, UUID.randomUUID())).isFalse();
    }
}
