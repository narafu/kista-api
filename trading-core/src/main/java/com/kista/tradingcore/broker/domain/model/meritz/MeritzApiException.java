package com.kista.tradingcore.broker.domain.model.meritz;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// 메리츠 API 실패(자격증명·권한·한도 외) — 503 매핑. 메시지는 로그용(rsp_msg 원문 포함), 사용자 노출 금지
public class MeritzApiException extends BrokerApiException {

    private final String rspCd; // 응답 본문 rsp_cd (I/O·파싱 불가면 null)

    public MeritzApiException(String rspCd, String message, Throwable cause, boolean rejected) {
        this(rspCd, message, cause, rejected, false);
    }

    private MeritzApiException(String rspCd, String message, Throwable cause, boolean rejected, boolean insufficientFunds) {
        super("Meritz", message, cause, rejected, insufficientFunds);
        this.rspCd = rspCd;
    }

    // 증권사가 접수하지 않았음이 확실한 실패(송신 전 실패·업무 오류 응답)
    public static MeritzApiException rejected(String rspCd, String message) {
        return new MeritzApiException(rspCd, message, null, true);
    }

    // 확정 거절 중 잔고·수량 부족 코드로 식별된 경우(사용자 알림 대상)
    public static MeritzApiException rejectedInsufficientFunds(String rspCd, String message) {
        return new MeritzApiException(rspCd, message, null, true, true);
    }

    public String rspCd() {
        return rspCd;
    }
}
