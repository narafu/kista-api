package com.kista.admin.domain.model;

import com.kista.sharedkernel.Broker;

import java.time.Instant;
import java.util.UUID;

// account.domain.model.Account의 admin own-type read model — AccountQueryHttpAdapter가
// trading-core 내부 API(/api/internal/accounts) 응답을 이 타입으로 역직렬화한다.
// Account의 전체 필드 중 admin이 실제로 소비하는 5개만 narrowing(AdminStrategyView와 동일 관례) —
// nickname/appKey/secretKey/brokerAccountCode는 admin 어디서도 읽지 않아 제외했다.
// accountNo는 마스킹 전 원본 값 — AdminAccountItem/AdminAccountResponse의 from() 팩토리에서
// AccountNumberMasker로 마스킹한다.
public record AdminAccountView(
        UUID id,           // PK
        UUID userId,       // FK -> users.id
        String accountNo,  // 계좌번호 (복호화된 원본, 마스킹 전)
        Broker broker,     // 증권사
        Instant createdAt  // DB created_at, 신규 등록 시 null
) {}
