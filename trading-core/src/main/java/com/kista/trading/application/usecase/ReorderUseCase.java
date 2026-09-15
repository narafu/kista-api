package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;

public interface ReorderUseCase {
    ReorderResult reorder(ReorderCommand command);
}
