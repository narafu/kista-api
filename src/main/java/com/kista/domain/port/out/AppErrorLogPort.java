package com.kista.domain.port.out;

import com.kista.domain.model.admin.AppErrorLog;

import java.util.List;

public interface AppErrorLogPort {
    // 예외 발생 시 DB 저장 — caller는 호출 클래스 단순명
    void save(Exception e, String caller);
    // 최신순 limit건 조회
    List<AppErrorLog> findRecent(int limit);
}
