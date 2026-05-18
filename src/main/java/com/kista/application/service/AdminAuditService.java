package com.kista.application.service;

import com.kista.domain.model.AuditLog;
import com.kista.domain.port.in.AdminListAuditLogsUseCase;
import com.kista.domain.port.out.AuditLogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAuditService implements AdminListAuditLogsUseCase {

    private final AuditLogPort auditLogPort;

    @Override
    public List<AuditLog> listAll() {
        return auditLogPort.findAll(); // 최신순 100건
    }
}
