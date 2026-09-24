package com.kista.finance.application.service;

import com.kista.finance.domain.model.GroupShareable;

import java.util.Optional;
import java.util.UUID;

// shareToGroup/unshare 공용 로직 — FinanceAccount/FinanceBudget/FinanceTransaction/AssetSnapshot/FinanceCategory
// 5개 서비스가 재사용한다. 그룹 필수 검증(requireCurrentGroup/requireGroupIfSharing)은 BulkFinanceRegisterService도 재사용.
// Optional.empty() 반환은 멱등(이미 목표 상태)이라는 뜻 — 호출부는 save를 건너뛰고 기존 값을 그대로 반환하면 된다.
final class GroupShareSupport {

    private GroupShareSupport() {
    }

    // "소속된 그룹이 없습니다" 메시지의 단일 소유자 — 그룹 필수 검증이 필요한 모든 호출부가 이 메서드를 거친다.
    static UUID requireCurrentGroup(Optional<UUID> currentGroupId) {
        return currentGroupId.orElseThrow(() -> new IllegalStateException("소속된 그룹이 없습니다"));
    }

    // shareToGroup=true로 신규 생성할 때만 그룹이 필수 — false면 개인 소유라 그룹 없어도 무방.
    static void requireGroupIfSharing(boolean shareToGroup, UUID currentGroupId) {
        if (shareToGroup) {
            requireCurrentGroup(Optional.ofNullable(currentGroupId));
        }
    }

    static <T extends GroupShareable<T>> Optional<T> shareToGroup(
            T existing, UUID userId, Optional<UUID> currentGroupId, String notOwnerMessage) {
        if (!existing.userId().equals(userId)) {
            throw new SecurityException(notOwnerMessage);
        }
        UUID groupId = requireCurrentGroup(currentGroupId);
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
