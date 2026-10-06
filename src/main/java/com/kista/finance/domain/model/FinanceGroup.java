package com.kista.finance.domain.model;

import java.time.Instant;
import java.util.UUID;

// 그룹은 오직 초대로만 생성된다(1인 1그룹) — personal 자동생성 그룹 개념은 폐기됨.
// 데이터 소유는 개별 리소스의 (userId, groupId) 이중축이 담당하고, 이 record는 순수 멤버십 단위다.
public record FinanceGroup(
        UUID id,           // PK
        UUID ownerUserId,  // FK → users.id, 그룹 생성자
        Instant createdAt  // DB created_at, 신규 등록 시 null
) {
    public enum MemberRole {
        OWNER, MEMBER
    }

    // 1인1그룹·단일 그룹 공유 규칙과 충돌 — 이미 다른 그룹 소속이거나 다른 그룹에 공유된 항목, GlobalExceptionHandler 409 매핑
    public static class MembershipConflictException extends RuntimeException {
        public MembershipConflictException(String message) {
            super(message);
        }

        public MembershipConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
