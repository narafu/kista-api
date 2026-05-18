package com.kista.domain.port.out;

import com.kista.domain.model.AuditLog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AuditLogPort {
    // 감사 로그 기록 (adminId 기준, payload는 action 관련 메타데이터)
    void log(UUID adminId, String action, String targetType, UUID targetId, Map<String, Object> payload);
    // id로 단건 조회
    AuditLog findById(UUID id);
    // 감사 로그 전체 조회 (최신순, 최대 100건) — 관리자 목록 화면용
    List<AuditLog> findAll();
}
