package com.kista.domain.port.out;

import com.kista.domain.model.admin.AppErrorLog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AppErrorLogPort {
    // 예외 발생 시 DB 저장 — caller는 호출 클래스 단순명
    void save(Exception e, String caller);
    // 클라이언트(UI) 오류 리포트 저장 — 서버 Exception이 없는 브라우저 오류 전용
    void save(String errorType, String message, String stackTrace, Map<String, String> context);
    // 최신순 limit건 조회
    List<AppErrorLog> findRecent(int limit);
    // 기간 범위 조회 (최신순, limit건)
    List<AppErrorLog> findRecent(int limit, Instant from, Instant to);
    // 소프트 삭제 — deleted_at 설정, 없으면 NoSuchElementException
    void softDelete(UUID id);
}
