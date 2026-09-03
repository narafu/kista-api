package com.kista.finance.application.service;

import com.kista.finance.domain.model.GroupShareable;

import java.util.Optional;
import java.util.UUID;

// shareToGroup/unshare 공용 로직 — FinanceBudget/FinanceTransaction/AssetSnapshot 세 서비스가 재사용한다.
// Optional.empty() 반환은 멱등(이미 목표 상태)이라는 뜻 — 호출부는 save를 건너뛰고 기존 값을 그대로 반환하면 된다.
final class GroupShareSupport {

    private GroupShareSupport() {
    }

    static <T extends GroupShareable<T>> Optional<T> shareToGroup(
            T existing, UUID userId, Optional<UUID> currentGroupId, String notOwnerMessage) {
        if (!existing.userId().equals(userId)) {
            throw new SecurityException(notOwnerMessage);
        }
        UUID groupId = currentGroupId.orElseThrow(() -> new IllegalStateException("소속된 그룹이 없습니다"));
        if (groupId.equals(existing.groupId())) {
            return Optional.empty(); // 이미 같은 그룹에 공유된 상태
        }
        if (existing.groupId() != null) {
            throw new IllegalStateException("이미 다른 그룹에 공유된 항목입니다");
        }
        return Optional.of(existing.withGroupId(groupId));
    }

    // 그룹 멤버 누구나 가능(소유자 한정 아님) — verifyAccessibleBy가 owned/sharedInMyGroup 둘 다 허용.
    static <T extends GroupShareable<T>> Optional<T> unshare(T existing, UUID userId, UUID currentGroupId) {
        existing.verifyAccessibleBy(userId, currentGroupId);
        return existing.groupId() == null ? Optional.empty() : Optional.of(existing.withGroupId(null));
    }
}
