package com.kista.domain.model.finance;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

public record FinanceCategory(
        UUID id,           // PK
        UUID groupId,      // FK → finance_groups.id, null이면 시스템 전역 카테고리
        UUID parentId,     // FK → 자기참조, L1이면 null
        UUID createdBy,    // FK → users.id, 시스템 카테고리는 null
        Type type,         // 생성 후 불변
        String name,
        int sortOrder,
        Instant createdAt  // DB created_at, 신규 등록 시 null
) {
    // 대출 L1 고정 UUID (V13 시드값과 동일) — 순자산/총부채 집계의 유일한 부채 판별자.
    // 구 AssetCategory.LOAN의 후계값.
    public static final UUID SYSTEM_LOAN_ID = UUID.fromString("f1000000-0000-4000-8000-000000000404");

    public boolean isSystem() {
        return groupId == null;
    }

    // 시스템 카테고리는 전 그룹 공용 읽기 허용, 그룹 카테고리는 소유 그룹만 접근 가능.
    // 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterGroupId) {
        if (isSystem()) {
            return;
        }
        if (!groupId.equals(requesterGroupId)) {
            throw new SecurityException("카테고리에 대한 접근 권한이 없습니다");
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Type {
        INCOME("수입", 1),
        EXPENSE("소비", -1),
        SAVING("저축", -1), // 가처분 현금에서 나가지만 소비는 아니므로 별도 타입
        ASSET("자산", 0);   // 거래 부호 미사용 — finance_transactions이 아닌 finance_asset_snapshots 전용 타입

        private final String label; // 한국어 표시명
        private final int sign;     // finance_transactions.amount 부호 SSOT (순현금흐름 = Σ amount × type.sign)
    }

    // 같은 그룹·같은 부모 아래 카테고리명 중복 — uq_finance_categories_group_parent_name 위반을 어댑터가 이 예외로 변환
    public static class DuplicateNameException extends RuntimeException {
        public DuplicateNameException(String name) {
            super("이미 같은 이름의 카테고리가 있습니다: " + name);
        }
    }
}
