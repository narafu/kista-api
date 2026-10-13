package com.kista.api.finance.application.usecase;

import com.kista.api.finance.domain.model.MonthlyClosing;

import java.util.List;
import java.util.UUID;

public interface MonthlyClosingUseCase {
    List<MonthlyClosing> list(UUID userId);
    MonthlyClosing setCompleted(UUID userId, String month, boolean completed);
}
