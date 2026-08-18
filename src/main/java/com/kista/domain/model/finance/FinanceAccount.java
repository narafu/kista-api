package com.kista.domain.model.finance;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

public record FinanceAccount(
        UUID id,             // PK
        UUID groupId,        // FK → finance_groups.id
        UUID createdBy,      // FK → users.id
        Type accountType,
        String name,         // 계좌명 (예: 토스증권 일반계좌)
        String accountNo,    // 계좌번호(복호화된 값), null 허용
        String memo,         // null 허용
        Instant createdAt    // DB created_at, 신규 등록 시 null
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterGroupId) {
        if (!groupId.equals(requesterGroupId)) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Type {
        SECURITIES("증권사"),
        BANK("은행"),
        INSURANCE("보험"),
        EXCHANGE("거래소");

        private final String label;
    }

    // 같은 그룹 안 계좌명 중복 — uq_finance_accounts_group_name 위반을 어댑터가 이 예외로 변환
    public static class DuplicateNameException extends RuntimeException {
        public DuplicateNameException(String name) {
            super("이미 같은 이름의 계좌가 있습니다: " + name);
        }
    }
}
