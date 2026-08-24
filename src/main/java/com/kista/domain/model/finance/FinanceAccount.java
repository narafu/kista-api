package com.kista.domain.model.finance;

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

    // 과거 uq_finance_accounts_group_name(V13) 위반을 어댑터가 이 예외로 변환했다 — save()의 그룹 내 이름
    // 중복, reassignGroup()의 이관 대상 그룹 이름 충돌 모두 이 하나의 제약에 의존했다. V16에서 해당 제약을
    // DROP해(계좌 이름 중복 허용) 두 경로 모두 더 이상 이 예외로 이어지지 않는다 — 어댑터 코드·클래스는
    // GlobalExceptionHandler 매핑 호환을 위해 유지.
    public static class DuplicateNameException extends RuntimeException {
        public DuplicateNameException(String name) {
            super("이미 같은 이름의 계좌가 있습니다: " + name);
        }
    }

    // 계좌 삭제 요청 시 매핑된 자산 기록이 남아있으면 차단 — 먼저 자산 기록의 계좌를 미지정으로 바꿔야 한다.
    public static class LinkedAssetSnapshotsException extends RuntimeException {
        public LinkedAssetSnapshotsException() {
            super("이 계좌에 매핑된 자산 기록이 있어 삭제할 수 없습니다. 먼저 자산 기록의 계좌 매핑을 해제해주세요");
        }
    }
}
