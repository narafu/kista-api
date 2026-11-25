package com.kista.tradingcore.broker.domain.model;

// 증권사 API 키에 필요한 권한(자산조회·주문하기)이 없을 때 — 자격증명 실패의 세부 사유(확정 거절·422)
public class BrokerPermissionDeniedException extends BrokerCredentialException {
    public BrokerPermissionDeniedException() {
        super("증권사 API 키에 필요한 권한이 없습니다.");
    }
}
