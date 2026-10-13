package com.kista.tradingcore.trading.domain.strategy;

import com.kista.shared.kernel.StrategyCreationSettings;
import com.kista.shared.kernel.StrategyDefaults;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

// DCA 생성 정책 해석 — 런타임 정책은 ticker만 관리하고 DCA 고유 값(주기·금액)은 사용자 입력이라 여기서 다루지 않는다
public class DcaCreationResolver implements StrategyCreationResolver {

    @Override
    public StrategyType type() {
        return StrategyType.DCA;
    }

    @Override
    public ResolvedCreation resolveTypeFields(StrategyCreationRequest request, StrategyCreationSettings settings, StrategyTicker ticker) {
        return new ResolvedCreation(ticker, StrategyDefaults.DEFAULT_DIVISION_COUNT, null, null, null);
    }
}
