package com.kista.domain.model.ledger;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

public record Category(
        UUID id,          // PK
        UUID userId,      // FK → users.id, 전역 카테고리 없음(전부 사용자별 생성)
        UUID parentId,    // FK → ledger_categories.id 자기참조, 최상위면 null
        Type type,        // 수입/소비/저축 — 생성 후 불변 (과거 집계 소급 반전 방지)
        String name,      // 자유 입력
        Instant createdAt // DB created_at, 신규 등록 시 null
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new SecurityException("카테고리에 대한 접근 권한이 없습니다");
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Type {
        INCOME("수입", 1),
        EXPENSE("소비", -1),
        SAVING("저축", -1); // 가처분 현금에서 나가지만 소비는 아니므로 별도 타입

        private final String label; // 한국어 표시명
        private final int sign;     // 순현금흐름 = Σ amount × type.sign
    }
}
