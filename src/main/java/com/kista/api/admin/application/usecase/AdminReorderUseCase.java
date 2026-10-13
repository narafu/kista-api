package com.kista.api.admin.application.usecase;

import com.kista.shared.contract.trading.ReorderRequest;
import com.kista.shared.contract.trading.ReorderResponse;

import java.util.UUID;

public interface AdminReorderUseCase {
    ReorderResponse reorder(UUID adminId, ReorderRequest command);
}
