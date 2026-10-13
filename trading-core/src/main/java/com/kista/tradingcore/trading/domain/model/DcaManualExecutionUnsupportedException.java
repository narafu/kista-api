package com.kista.tradingcore.trading.domain.model;

// DCA 전략의 바로 주문·단건 미리보기 거부 — 409 DCA_MANUAL_EXECUTION_UNSUPPORTED
public class DcaManualExecutionUnsupportedException extends RuntimeException {
    public DcaManualExecutionUnsupportedException() {
        super("DCA 전략은 바로 주문과 미리보기를 지원하지 않습니다.");
    }
}
