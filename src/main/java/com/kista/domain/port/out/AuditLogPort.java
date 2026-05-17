package com.kista.domain.port.out;

import com.kista.domain.model.AuditLog;

import java.util.Map;
import java.util.UUID;

public interface AuditLogPort {
    // 감사 로그 기록 (adminId 기준, payload는 action 관련 메타데이터)
    void log(UUID adminId, String action, String targetType, UUID targetId, Map<String, Object> payload);
    // id로 단건 조회 (없으면 NoSuchElementException)
    AuditLog findById(UUID id);
}
