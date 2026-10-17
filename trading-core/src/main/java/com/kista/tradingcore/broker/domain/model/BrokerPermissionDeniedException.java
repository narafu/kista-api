package com.kista.tradingcore.broker.domain.model;

// 거래소 API 키에 필요한 권한(자산조회·주문하기)이 없을 때(업비트 out_of_scope) — 자격증명 실패의 세부 사유(확정 거절·422)
public class BrokerPermissionDeniedException extends BrokerCredentialException {
    public BrokerPermissionDeniedException() {
        super("거래소 API 키에 필요한 권한이 없습니다.");
    }
}
