package com.kista.strategyconfig.application.service;

import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.application.port.output.StrategyLookupPort;
import com.kista.trading.application.port.output.StrategyPausePort;
import com.kista.trading.domain.model.StrategyRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// trading의 StrategyLookupPort/StrategyPausePort 구현 — strategy-config 내부 StrategyPort를 감싸
// Strategy를 StrategyRef로 매핑해 반환한다. trading은 strategy-config를 전혀 참조하지 않는다.
@Component
@RequiredArgsConstructor
class StrategyLookupAdapter implements StrategyLookupPort, StrategyPausePort {

    private final StrategyPort strategyPort;

    @Override
    public List<StrategyRef> findAllActive() {
        return strategyPort.findAllActive().stream().map(StrategyLookupAdapter::toRef).toList();
    }

    @Override
    public List<StrategyRef> findByAccountId(UUID accountId) {
        return strategyPort.findByAccountId(accountId).stream().map(StrategyLookupAdapter::toRef).toList();
    }

    @Override
    public Optional<StrategyRef> findById(UUID id) {
        return strategyPort.findById(id).map(StrategyLookupAdapter::toRef);
    }

    @Override
    public StrategyTicker findTickerById(UUID id) {
        return strategyPort.findById(id).map(Strategy::ticker).orElse(null);
    }

    @Override
    public Map<UUID, StrategyTicker> findTickersByIds(Collection<UUID> ids) {
        return strategyPort.findTickersByIds(ids);
    }

    @Override
    public void pause(UUID strategyId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        strategyPort.save(strategy.withStatus(StrategyStatus.PAUSED));
    }

    private static StrategyRef toRef(Strategy s) {
        return new StrategyRef(s.id(), s.accountId(), s.type(), s.status(), s.ticker(), s.cycleSeedType());
    }
}
