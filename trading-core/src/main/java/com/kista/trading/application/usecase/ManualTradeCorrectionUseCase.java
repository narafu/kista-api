package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ManualTradeCorrectionResult;

public interface ManualTradeCorrectionUseCase {
    ManualTradeCorrectionResult correctManualFills(ManualTradeCorrectionCommand command);
}
