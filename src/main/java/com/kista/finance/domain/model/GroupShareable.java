package com.kista.finance.domain.model;

import java.util.UUID;

// 그룹 공유(shareToGroup)/해제(unshare) 대상 애그리게이트 공통 계약.
// FinanceBudget/FinanceTransaction/AssetSnapshot이 구현 — application.service.finance.GroupShareSupport가 공용 로직에 사용한다.
public interface GroupShareable<T extends GroupShareable<T>> {
    UUID userId();
    UUID groupId();

    void verifyAccessibleBy(UUID requesterUserId, UUID requesterGroupId);

    // 다른 필드는 그대로 두고 groupId만 교체한 사본을 만든다.
    T withGroupId(UUID groupId);
}
