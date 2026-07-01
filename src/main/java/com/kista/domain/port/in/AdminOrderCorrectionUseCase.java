package com.kista.domain.port.in;

import com.kista.domain.model.admin.AdminOrderCorrectionCommand;
import com.kista.domain.model.admin.AdminOrderCorrectionResult;

import java.util.UUID;

public interface AdminOrderCorrectionUseCase {
    AdminOrderCorrectionResult correctOrder(UUID adminId, AdminOrderCorrectionCommand command);
}
