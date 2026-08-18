package com.kista.domain.port.out;

import com.kista.domain.model.finance.MonthlyClosing;

import java.util.List;
import java.util.UUID;

public interface MonthlyClosingPort {
    List<MonthlyClosing> findByGroupId(UUID groupId);

    // (group_id, month) 유니크 제약 위 upsert — 어댑터가 ON CONFLICT로 race 없이 처리
    MonthlyClosing upsert(UUID groupId, UUID closedBy, String month, boolean completed);

    void deleteByGroupId(UUID groupId); // 그룹 소프트 삭제(탈퇴로 멤버 0) 시 함께 정리
}
