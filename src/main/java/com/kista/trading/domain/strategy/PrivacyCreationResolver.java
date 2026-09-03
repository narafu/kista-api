package com.kista.trading.domain.strategy;

import com.kista.sharedkernel.StrategyDefaults;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

public class PrivacyCreationResolver implements StrategyCreationResolver {

    @Override
    public StrategyType type() {
        return StrategyType.PRIVACY;
    }

    @Override
    public ResolvedCreation resolveTypeFields(StrategyCreationRequest request, StrategyCreationSettings settings, StrategyTicker ticker) {
        // PRIVACY는 전략 고유 설정 필드가 없다 — 고정 분할 수만 적용한다.
        return new ResolvedCreation(ticker, StrategyDefaults.DEFAULT_DIVISION_COUNT, null, null, null);
    }
}
