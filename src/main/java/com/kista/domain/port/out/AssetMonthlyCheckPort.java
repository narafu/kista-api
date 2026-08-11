package com.kista.domain.port.out;

import com.kista.domain.model.asset.AssetMonthlyCheck;

import java.util.List;
import java.util.UUID;

public interface AssetMonthlyCheckPort {
    List<AssetMonthlyCheck> findByUserId(UUID userId);

    // (user_id, month) 유니크 제약 위 upsert — 어댑터가 ON CONFLICT로 race 없이 처리
    AssetMonthlyCheck upsert(UUID userId, String month, boolean completed);

    void deleteByUserId(UUID userId); // 사용자 탈퇴 시 일괄 삭제
}
