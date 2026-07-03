package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;

import java.util.UUID;

// 계좌 등록 전 자격증명 검증 포트 — Account 저장 전 원시 자격증명만으로 호출되므로
// BrokerAdapterRegistry(account.broker 라우팅) 대신 별도 broker enum 라우터로 조회
public interface BrokerConnectionTestPort {
    // 이 구현체가 담당하는 증권사
    Account.Broker supports();

    // 자격증명 검증 (OAuth 토큰 발급 시도). accountId null 허용(등록 전 사전검증, 캐시 저장 생략).
    // 실패 시 Account.InvalidKisKeyException
    void verifyCredentials(String appKey, String secretKey, UUID accountId);

    // 자격증명+계좌 검증 후 brokerAccountCode 반환 (KIS: null, Toss: accountSeq). 실패 시 Account.InvalidKisKeyException
    String verifyAccount(String appKey, String secretKey, String accountNo);
}
