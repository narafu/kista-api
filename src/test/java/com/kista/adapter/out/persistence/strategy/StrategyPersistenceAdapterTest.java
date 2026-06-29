package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyInfiniteDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyPersistenceAdapterTest {

    @Mock StrategyJpaRepository strategyRepository;
    @Mock StrategyInfiniteJpaRepository strategyInfiniteRepository;

    private StrategyPersistenceAdapter strategyAdapter;
    private StrategyInfiniteDetailPersistenceAdapter strategyInfiniteDetailAdapter;

    private final UUID accountId = UUID.randomUUID();
    private final UUID strategyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        strategyAdapter = new StrategyPersistenceAdapter(strategyRepository);
        strategyInfiniteDetailAdapter = new StrategyInfiniteDetailPersistenceAdapter(strategyInfiniteRepository);
    }

    @Test
    void save_infiniteStrategy_persistsCommonAndDetailRows() {
        Strategy strategy = new Strategy(null, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyEntity savedStrategyEntity = new StrategyEntity();
        savedStrategyEntity.setId(strategyId);
        savedStrategyEntity.setAccountId(accountId);
        savedStrategyEntity.setType(Strategy.Type.INFINITE);
        savedStrategyEntity.setStatus(Strategy.Status.ACTIVE);
        savedStrategyEntity.setTicker(Strategy.Ticker.SOXL);
        savedStrategyEntity.setCycleSeedType(Strategy.CycleSeedType.NONE);
        when(strategyRepository.save(any(StrategyEntity.class))).thenReturn(savedStrategyEntity);

        Strategy saved = strategyAdapter.save(strategy);

        StrategyInfiniteEntity savedDetailEntity = new StrategyInfiniteEntity();
        savedDetailEntity.setStrategyId(strategyId);
        savedDetailEntity.setDivisionCount(20);
        when(strategyInfiniteRepository.save(any(StrategyInfiniteEntity.class))).thenReturn(savedDetailEntity);
        when(strategyInfiniteRepository.findById(strategyId)).thenReturn(Optional.of(savedDetailEntity));

        strategyInfiniteDetailAdapter.save(new StrategyInfiniteDetail(saved.id(), 20));

        assertThat(saved.id()).isEqualTo(strategyId);
        assertThat(strategyInfiniteDetailAdapter.findByStrategyId(saved.id()))
                .map(StrategyInfiniteDetail::divisionCount)
                .contains(20);
        verify(strategyRepository).save(any(StrategyEntity.class));
        verify(strategyInfiniteRepository).save(any(StrategyInfiniteEntity.class));
    }
}
