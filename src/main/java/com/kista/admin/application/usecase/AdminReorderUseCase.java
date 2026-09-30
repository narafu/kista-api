package com.kista.admin.application.usecase;

import com.kista.contract.trading.ReorderRequest;
import com.kista.contract.trading.ReorderResponse;

import java.util.UUID;

public interface AdminReorderUseCase {
    ReorderResponse reorder(UUID adminId, ReorderRequest command);
}
