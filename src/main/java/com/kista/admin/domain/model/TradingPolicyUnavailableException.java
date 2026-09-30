package com.kista.admin.domain.model;

// trading-core 정책 API에 닿지 못했을 때(연결 거부·타임아웃·5xx) — 전송 계층 예외를 admin 어휘로 바꾼 표지 예외.
// GlobalExceptionHandler가 503으로 매핑하고, 공개 설정 조회(RuntimeConfigService)는 기본 정책으로 강등한다.
public class TradingPolicyUnavailableException extends RuntimeException {
    public TradingPolicyUnavailableException(String message, Throwable cause) {
        super("매매 런타임 정책을 조회할 수 없습니다: " + message, cause);
    }
}
