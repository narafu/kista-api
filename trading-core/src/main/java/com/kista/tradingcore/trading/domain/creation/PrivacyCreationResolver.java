package com.kista.tradingcore.trading.domain.creation;

import com.kista.shared.kernel.StrategyCreationSettings;
import com.kista.shared.kernel.StrategyDefaults;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyTicker;

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
