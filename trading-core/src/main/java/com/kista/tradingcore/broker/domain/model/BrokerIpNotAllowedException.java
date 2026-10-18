package com.kista.tradingcore.broker.domain.model;

// 거래소 API 키의 허용 IP에 서버 IP가 없을 때(업비트 no_authorization_ip) — 자격증명 실패의 세부 사유라 상위 타입을 잇는다(확정 거절·422)
public class BrokerIpNotAllowedException extends BrokerCredentialException {
    public BrokerIpNotAllowedException() {
        super("거래소 API 키에 서버 IP가 허용되어 있지 않습니다.");
    }
}
