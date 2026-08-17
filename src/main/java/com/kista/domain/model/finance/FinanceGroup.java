package com.kista.domain.model.finance;

import java.time.Instant;
import java.util.UUID;

public record FinanceGroup(
        UUID id,           // PK
        UUID ownerUserId,  // FK → users.id, 그룹 생성자(개인 그룹은 본인)
        String name,       // 표시명, 개인 그룹 기본값 '개인'
        boolean personal,  // true면 가입 시 자동 생성된 1인 그룹 (삭제·탈퇴 불가)
        Instant createdAt  // DB created_at, 신규 등록 시 null
) {
    public enum MemberRole {
        OWNER, MEMBER
    }

    // 개인 그룹 탈퇴 시도 — 컨트롤러에서 409 매핑
    public static class CannotLeavePersonalGroupException extends RuntimeException {
        public CannotLeavePersonalGroupException() {
            super("개인 그룹은 탈퇴할 수 없습니다");
        }
    }
}
