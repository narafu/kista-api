package com.kista.tradingcore.trading.application.usecase;

import com.kista.tradingcore.trading.domain.model.ReorderCommand;
import com.kista.tradingcore.trading.domain.model.ReorderResult;

public interface ReorderUseCase {
    ReorderResult reorder(ReorderCommand command);
}
