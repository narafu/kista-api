package com.kista.domain.model.finance;

import java.time.Instant;
import java.util.UUID;

public record FinanceGroupInvitation(
        UUID id,             // PK
        UUID groupId,        // FK → finance_groups.id
        UUID invitedBy,      // FK → users.id, 초대한 사람
        UUID inviteeUserId,  // FK → users.id, 코드 수락 전에는 null
        String code,         // 공유용 초대 코드 (URL-safe)
        Status status,
        Instant expiresAt,
        Instant createdAt    // DB created_at, 신규 등록 시 null
) {
    public enum Status {
        PENDING, ACCEPTED, DECLINED, EXPIRED
    }

    // 만료되었거나 이미 처리된 초대 수락/거절 시도 — 컨트롤러에서 409 매핑
    public static class InvalidInvitationStateException extends RuntimeException {
        public InvalidInvitationStateException(String message) {
            super(message);
        }
    }
}
