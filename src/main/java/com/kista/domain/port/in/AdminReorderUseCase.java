package com.kista.domain.port.in;

import com.kista.domain.model.admin.AdminReorderCommand;
import com.kista.domain.model.admin.AdminReorderResult;

import java.util.UUID;

public interface AdminReorderUseCase {
    AdminReorderResult reorder(UUID adminId, AdminReorderCommand command);
}
