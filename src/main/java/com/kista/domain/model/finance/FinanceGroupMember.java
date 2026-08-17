package com.kista.domain.model.finance;

import java.time.Instant;
import java.util.UUID;

public record FinanceGroupMember(
        UUID id,           // PK
        UUID groupId,      // FK → finance_groups.id
        UUID userId,       // FK → users.id
        FinanceGroup.MemberRole role,
        Instant joinedAt,
        Instant createdAt  // DB created_at, 신규 등록 시 null
) {}
