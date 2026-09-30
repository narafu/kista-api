package com.kista.contract.account;

import com.kista.sharedkernel.Broker;

import java.time.Instant;
import java.util.UUID;

// 계좌 요약 — trading-core 내부 API(/api/internal/accounts) 응답.
// appKey/secretKey(복호화된 브로커 자격증명)·nickname·brokerAccountCode는 의도적으로 싣지 않는다 — 내부망으로도 평문 비밀값을 직렬화하지 않기 위한 narrowing
public record AccountSummaryResponse(
        UUID id,           // PK
        UUID userId,       // FK -> users.id
        String accountNo,  // 계좌번호 (복호화된 원본, 마스킹 전)
        Broker broker,     // 증권사
        Instant createdAt  // DB created_at
) {}
