package com.kista.broker.domain.model;

// 벤더 중립 증권사 계좌 정보 — 증권사에 연결된 계좌 목록 1건
public record BrokerAccountInfo(
    int    accountSeq, // 계좌 일련번호 — brokerAccountCode에 저장되는 값
    String accountNo   // 계좌번호 (마스킹 포함 가능)
) {}
