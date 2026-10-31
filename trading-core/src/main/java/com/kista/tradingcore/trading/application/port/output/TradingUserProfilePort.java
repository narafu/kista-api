package com.kista.tradingcore.trading.application.port.output;

import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TradingUserProfilePort {
    Optional<TradingUserProfile> findByUserId(UUID userId);
    Map<UUID, TradingUserProfile> findAllByUserIds(List<UUID> userIds); // 배치 조회(N+1 방지) — BatchContextFactory 전용
    List<TradingUserProfile> findAllActive(); // ACTIVE 상태 사용자 전체 — 장 이벤트(개장·마감) 브로드캐스트용
    void applyChange(UserNotifyProfileChangedEvent event); // 프로필 변경 스냅샷 upsert(호출자 트랜잭션 합류) — occurredAt 버전 가드로 옛 스냅샷은 건너뛴다
    void markDeleted(UUID userId); // 탈퇴 정리 — 복제본을 비활성 tombstone으로(토큰·chatId 제거, 호출자 트랜잭션 합류). 행을 지우면 늦게 도착한 프로필 이벤트가 활성 행을 되살린다
}
