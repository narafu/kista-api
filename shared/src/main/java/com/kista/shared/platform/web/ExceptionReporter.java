package com.kista.shared.platform.web;

// 응답으로 처리하지 못한 예외(500)·증권사 장애를 app_error_logs로 보고하는 프로세스별 빈 —
// platform은 shared.kernel(AppErrorRaisedEvent)을 참조할 수 없어 각 앱의 web 모듈이 구현한다
@FunctionalInterface
public interface ExceptionReporter {

    void report(Exception ex);
}
