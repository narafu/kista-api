package com.kista.tradingcore.trading.application.usecase;

import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.tradingcore.trading.domain.model.ManualTradeCorrectionResult;

public interface ManualTradeCorrectionUseCase {
    ManualTradeCorrectionResult correctManualFills(ManualTradeCorrectionCommand command);
}
