package com.kista.trading.domain.strategy;

import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import org.springframework.stereotype.Component;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Component
public class InfiniteCreationResolver implements StrategyCreationResolver {

    @Override
    public StrategyType type() {
        return StrategyType.INFINITE;
    }

    @Override
    public ResolvedCreation resolveTypeFields(RegisterStrategyCommand cmd, StrategyCreationSettings settings, StrategyTicker ticker) {
        // primitive 0은 요청 생략 sentinel이므로 설정 기본값으로 치환한다.
        Integer requestedDivisionCount = cmd.divisionCount() == 0 ? null : cmd.divisionCount();
        int divisionCount = settings.divisionCount().resolve(requestedDivisionCount);
        return new ResolvedCreation(ticker, divisionCount, null, null, null);
    }
}
