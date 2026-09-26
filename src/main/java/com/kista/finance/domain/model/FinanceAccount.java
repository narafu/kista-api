package com.kista.finance.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

public record FinanceAccount(
        UUID id,             // PK
        UUID groupId,        // FK → finance_groups.id, null이면 개인 계좌
        UUID userId,         // FK → users.id, 소유자
        Type accountType,
        String name,         // 계좌명 (예: 토스증권 일반계좌)
        String accountNo,    // 계좌번호(복호화된 값), null 허용
        String memo,         // null 허용
        Instant createdAt    // DB created_at, 신규 등록 시 null
) implements GroupShareable<FinanceAccount> {
    // 접근 불가 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyAccessibleBy(UUID requesterUserId, UUID requesterGroupId) {
        boolean owned = userId.equals(requesterUserId);
        boolean sharedInMyGroup = groupId != null && groupId.equals(requesterGroupId);
        if (!owned && !sharedInMyGroup) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
    }

    @Override
    public FinanceAccount withGroupId(UUID groupId) {
        return new FinanceAccount(id, groupId, userId, accountType, name, accountNo, memo, createdAt);
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

    // 계좌 삭제 요청 시 매핑된 자산 기록이 남아있으면 차단 — 먼저 자산 기록의 계좌를 미지정으로 바꿔야 한다.
    public static class LinkedAssetSnapshotsException extends RuntimeException {
        public LinkedAssetSnapshotsException() {
            super("이 계좌에 매핑된 자산 기록이 있어 삭제할 수 없습니다. 먼저 자산 기록의 계좌 매핑을 해제해주세요");
        }
    }

    // 전역 계좌번호 중복 등록 방지 (HMAC-SHA256 해시 기반 partial unique index, V20)
    public static class DuplicateAccountNoException extends RuntimeException {
        public DuplicateAccountNoException(String accountNo) {
            super("이미 등록된 계좌번호입니다: " + accountNo);
        }
    }
}
