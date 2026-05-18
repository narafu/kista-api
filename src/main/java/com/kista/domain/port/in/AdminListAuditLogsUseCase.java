package com.kista.domain.port.in;

import com.kista.domain.model.AuditLog;
import java.util.List;

public interface AdminListAuditLogsUseCase {
    List<AuditLog> listAll();
}
