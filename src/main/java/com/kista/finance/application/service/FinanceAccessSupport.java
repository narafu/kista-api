package com.kista.finance.application.service;

import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.domain.model.GroupShareable;

import java.util.UUID;
import java.util.function.Function;

// 조회 → 현재 그룹 조회 → 접근권한 검증 3단 반복 공용 로직 — FinanceAccount/AssetSnapshot/FinanceBudget/
// FinanceTransaction/FinanceCategory 5개 서비스의 update/delete가 재사용한다.
final class FinanceAccessSupport {

    private FinanceAccessSupport() {
    }

    // finder로 엔티티를 조회한 뒤 곧바로 접근권한을 검증한다 — find와 검증 사이에 다른 조건이 끼지 않고
    // 호출부가 currentGroupId를 이어서 쓸 필요도 없는 호출부용.
    static <T extends GroupShareable<T>> T loadAccessible(
            Function<UUID, T> finder, UUID id, UUID userId, FinanceGroupPort financeGroupPort) {
        T existing = finder.apply(id);
        verifyAccessible(existing, userId, financeGroupPort);
        return existing;
    }

    // 이미 조회해 둔 엔티티를 검증한다 — find와 검증 사이에 다른 조건(시스템 카테고리 등)을 끼워 넣어야 하거나,
    // 검증에 쓰인 currentGroupId를 호출부가 이어서(카테고리/마감월 재검증 등) 재사용해야 하는 경우 사용한다.
    static <T extends GroupShareable<T>> UUID verifyAccessible(T existing, UUID userId, FinanceGroupPort financeGroupPort) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        return currentGroupId;
    }
}
