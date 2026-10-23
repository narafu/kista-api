package com.kista.tradingcore.broker.domain.model;

// 거래소 API 키에 출금 권한이 켜져 있을 때(바이낸스 enableWithdrawals) — 자격증명 실패의 세부 사유(확정 거절·422)
public class BrokerWithdrawalEnabledException extends BrokerCredentialException {
    public BrokerWithdrawalEnabledException() {
        super("출금 권한이 켜진 API 키는 등록할 수 없습니다.");
    }
}
