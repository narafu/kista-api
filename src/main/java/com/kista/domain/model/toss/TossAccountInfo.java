package com.kista.domain.model.toss;

// Toss 계좌 정보 — GET /api/v1/accounts 응답 단위
public record TossAccountInfo(
    int    accountSeq, // 계좌 일련번호 — brokerAccountCode에 저장되는 값
    String accountNo   // 계좌번호 (마스킹 포함 가능)
) {}
