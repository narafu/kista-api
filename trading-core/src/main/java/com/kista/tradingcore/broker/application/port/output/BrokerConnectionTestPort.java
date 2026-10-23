package com.kista.tradingcore.broker.application.port.output;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;

// 계좌 등록 전 자격증명 검증 포트 — Account 저장 전 원시 자격증명만으로 호출되므로
// BrokerRouter(account.broker 라우팅) 대신 별도 broker enum 라우터로 조회
public interface BrokerConnectionTestPort {
    // 이 구현체가 담당하는 증권사
    Broker supports();

    // 자격증명 검증 (OAuth 토큰 발급 시도) — 어느 계좌의 토큰 캐시도 건드리지 않는다. 실패 시 BrokerCredentialException
    void verifyCredentials(String appKey, String secretKey);

    // 자격증명+계좌 검증 후 보조 식별자·키 만료일 반환 (KIS: 둘 다 null, Toss: accountSeq, 업비트: accountNo 무시·만료일). 실패 시 BrokerCredentialException
    BrokerAccountVerification verifyAccount(String appKey, String secretKey, String accountNo);
}
