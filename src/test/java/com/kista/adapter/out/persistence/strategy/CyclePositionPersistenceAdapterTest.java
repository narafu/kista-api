package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.CyclePositionInfiniteDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CyclePositionPersistenceAdapterTest {

    @Mock CyclePositionJpaRepository cyclePositionRepository;
    @Mock StrategyCycleJpaRepository strategyCycleRepository;
    @Mock StrategyJpaRepository strategyRepository;
    @Mock CyclePositionInfiniteJpaRepository cyclePositionInfiniteRepository;

    private CyclePositionPersistenceAdapter cyclePositionAdapter;
    private CyclePositionInfiniteDetailPersistenceAdapter cyclePositionInfiniteDetailAdapter;

    private final UUID cycleId = UUID.randomUUID();
    private final UUID cyclePositionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cyclePositionAdapter = new CyclePositionPersistenceAdapter(
                cyclePositionRepository,
                strategyCycleRepository,
                strategyRepository
        );
        cyclePositionInfiniteDetailAdapter = new CyclePositionInfiniteDetailPersistenceAdapter(
                cyclePositionInfiniteRepository
        );
    }

    @Test
    void save_infinitePosition_persistsCommonAndDetailRows() {
        CyclePosition position = CyclePosition.initialSnapshot(cycleId, new BigDecimal("1000.00"));
        CyclePositionEntity savedPositionEntity = new CyclePositionEntity();
        savedPositionEntity.setId(cyclePositionId);
        savedPositionEntity.setStrategyCycleId(cycleId);
        savedPositionEntity.setUsdDeposit(new BigDecimal("1000.00"));
        savedPositionEntity.setHoldings(0);
        when(cyclePositionRepository.save(any(CyclePositionEntity.class))).thenReturn(savedPositionEntity);

        CyclePosition saved = cyclePositionAdapter.save(position);

        CyclePositionInfiniteEntity savedDetailEntity = new CyclePositionInfiniteEntity();
        savedDetailEntity.setCyclePositionId(cyclePositionId);
        savedDetailEntity.setReverseMode(true);
        when(cyclePositionInfiniteRepository.save(any(CyclePositionInfiniteEntity.class))).thenReturn(savedDetailEntity);
        when(cyclePositionInfiniteRepository.findTopNByStrategyCycleIdOrderByCreatedAtDesc(eq(cycleId), any()))
                .thenReturn(List.of(savedDetailEntity));

        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(saved.id(), true));

        assertThat(saved.id()).isEqualTo(cyclePositionId);
        assertThat(cyclePositionInfiniteDetailAdapter.findLatestByCycleId(cycleId, 1))
                .extracting(CyclePositionInfiniteDetail::isReverseMode)
                .containsExactly(true);
        verify(cyclePositionRepository).save(any(CyclePositionEntity.class));
        verify(cyclePositionInfiniteRepository).save(any(CyclePositionInfiniteEntity.class));
    }

    @Test
    void deleteByStrategyId_delegatesToDetailRepository() {
        UUID strategyId = UUID.randomUUID();

        cyclePositionInfiniteDetailAdapter.deleteByStrategyId(strategyId);

        verify(cyclePositionInfiniteRepository).deleteByStrategyId(eq(strategyId));
    }
}
