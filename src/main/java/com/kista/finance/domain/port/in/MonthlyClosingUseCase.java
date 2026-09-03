package com.kista.finance.domain.port.in;

import com.kista.finance.domain.model.MonthlyClosing;

import java.util.List;
import java.util.UUID;

public interface MonthlyClosingUseCase {
    List<MonthlyClosing> list(UUID userId, UUID requestedGroupId);
    MonthlyClosing setCompleted(UUID userId, UUID requestedGroupId, String month, boolean completed);
}
