package com.kista.domain.strategy;

import com.kista.domain.model.settings.StrategyCreationSettings;
import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import org.springframework.stereotype.Component;

@Component
public class PrivacyCreationResolver implements StrategyCreationResolver {

    @Override
    public Strategy.Type type() {
        return Strategy.Type.PRIVACY;
    }

    @Override
    public ResolvedCreation resolveTypeFields(RegisterStrategyCommand cmd, StrategyCreationSettings settings, Strategy.Ticker ticker) {
        // PRIVACY는 전략 고유 설정 필드가 없다 — 고정 분할 수만 적용한다.
        return new ResolvedCreation(ticker, Strategy.DEFAULT_DIVISION_COUNT, null, null, null);
    }
}
