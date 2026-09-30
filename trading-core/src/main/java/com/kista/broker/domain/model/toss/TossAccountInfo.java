package com.kista.broker.domain.model.toss;

import com.kista.broker.domain.model.BrokerAccountInfo;

// Toss 계좌 정보 — GET /api/v1/accounts 응답 단위
public record TossAccountInfo(
    int    accountSeq, // 계좌 일련번호 — brokerAccountCode에 저장되는 값
    String accountNo   // 계좌번호 (마스킹 포함 가능)
) {
    // 벤더 중립 계좌 정보로 변환
    public BrokerAccountInfo toBrokerAccountInfo() {
        return new BrokerAccountInfo(accountSeq, accountNo);
    }
}
