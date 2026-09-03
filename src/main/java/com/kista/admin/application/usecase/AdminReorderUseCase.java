package com.kista.admin.application.usecase;

import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;

import java.util.UUID;

public interface AdminReorderUseCase {
    AdminReorderResult reorder(UUID adminId, AdminReorderCommand command);
}
