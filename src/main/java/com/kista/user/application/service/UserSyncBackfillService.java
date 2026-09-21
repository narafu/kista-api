package com.kista.user.application.service;

import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.application.usecase.UserSyncBackfillUseCase;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// 일회성 드리프트 복구 — DB가 아직 공유(4b-1) 상태라 trading.user_notify_profile을 JdbcTemplate
// 원시 SQL로 직접 들여다본다(trading-core 소유 테이블이지만 물리적으로 같은 DB). 4b-2 컷오버
// 이후엔 이 쿼리가 더 이상 유효하지 않으므로 재사용하지 말 것 — 일회성 도구로 남긴다.
@Service
@RequiredArgsConstructor
class UserSyncBackfillService implements UserSyncBackfillUseCase {

    private final JdbcTemplate jdbcTemplate;
    private final UserPort userPort;
    private final UserSettingsPort userSettingsPort;
    private final ApplicationEventPublisher eventPublisher;

    // @Transactional — AFTER_COMMIT 리스너(신규 Stream 발행자 포함)가 메서드 종료 시 한 번에 발화하도록
    @Override
    @Transactional
    public BackfillResult runOnce() {
        // 소프트 삭제됐지만 cascade 이벤트를 받은 적 없는 사용자 — UserDeletedEvent 재발행(idempotent).
        // 4a 배포(2026-09-16 아침) 이후로 범위 제한 — 이전 탈퇴자는 이미 정상 처리됐으므로
        // 재발행 시 관리자 알림만 중복 발송됨(범위 제한 이후엔 매번 같은 대상만 재발행돼 idempotent 서술이 다시 참)
        List<UUID> deletedUserIds = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE deleted_at IS NOT NULL AND deleted_at >= '2026-09-16 07:00:00+09'", UUID.class);
        deletedUserIds.forEach(id -> eventPublisher.publishEvent(new UserDeletedEvent(id)));

        // 활성 사용자 전체 재동기화 — 프로필 행이 없는 경우뿐 아니라 4a 배포 이후 상태/설정이 바뀐
        // 사용자(승인, 알림 설정 변경 등)도 놓치지 않도록 NOT EXISTS 제한 없이 전체 대상. upsert라 재갱신 안전
        List<UUID> driftUserIds = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE deleted_at IS NULL", UUID.class);
        int backfilled = 0;
        for (UUID userId : driftUserIds) {
            User user = userPort.findById(userId).orElse(null);
            if (user == null) {
                continue;
            }
            UserSettings settings = userSettingsPort.findOrDefault(userId);
            eventPublisher.publishEvent(new UserNotifyProfileChangedEvent(
                    userId, settings.notificationPrefs(), settings.balanceCheckEnabled(),
                    user.status() == UserStatus.ACTIVE, user.telegramBotToken(), user.telegramChatId()));
            backfilled++;
        }
        return new BackfillResult(deletedUserIds.size(), backfilled);
    }
}
