package com.kista.trading.application.port.output;

import com.kista.trading.domain.model.TradingUserProfile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TradingUserProfilePort {
    Optional<TradingUserProfile> findByUserId(UUID userId);
    Map<UUID, TradingUserProfile> findAllByUserIds(List<UUID> userIds); // 배치 조회(N+1 방지) — BatchContextFactory 전용
    List<TradingUserProfile> findAllActive(); // ACTIVE 상태 사용자 전체 — 장 이벤트(개장·마감) 브로드캐스트용
}
