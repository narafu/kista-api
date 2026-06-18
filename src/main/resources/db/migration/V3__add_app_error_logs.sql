-- app_error_logs: NotifyPort.notifyError() 호출 시 자동 저장되는 시스템 오류 이력
-- ============================================================
CREATE TABLE app_error_logs (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    error_type   VARCHAR(255) NOT NULL,  -- 예외 클래스 단순명 (예: KisApiException)
    message      TEXT,                   -- e.getMessage()
    stack_trace  TEXT,                   -- 전체 스택트레이스
    context      JSONB,                  -- 발생 위치 메타 { "caller": "TradingOpenScheduler" }
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_error_logs_created_at ON app_error_logs (created_at DESC);
