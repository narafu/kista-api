package com.kista.sharedkernel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

// 애플리케이션 오류 1건의 보고 — root admin이 app_error_logs에 저장한다.
// root 안에서는 로컬 이벤트로, trading-core에서는 Redis Stream(stream:app.error)으로 전달되는 통합 메시지라
// Exception 객체 대신 저장에 필요한 문자열 필드만 담는다(JDK 타입만 참조 — outbound-zero).
public record AppErrorRaisedEvent(
        String errorType,            // 예외 클래스 simple name
        String message,              // 예외 메시지
        String stackTrace,           // 전체 스택트레이스 (30줄 truncate는 저장 측 책임)
        Map<String, String> context  // 발생 위치 메타 (caller 등)
) {
    // 예외 → 보고 이벤트 변환 헬퍼 — 호출 지점(caller)을 context에 남긴다
    public static AppErrorRaisedEvent of(Exception e, String caller) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return new AppErrorRaisedEvent(e.getClass().getSimpleName(), e.getMessage(), sw.toString(), Map.of("caller", caller));
    }
}
