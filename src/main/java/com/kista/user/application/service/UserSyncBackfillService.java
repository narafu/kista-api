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

// 일회성 드리프트 복구 — DB가 아직 공유(4b-1) 상태라 kista.user_notify_profile을 JdbcTemplate
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
        // 소프트 삭제됐지만 cascade 이벤트를 받은 적 없는 사용자 — UserDeletedEvent 재발행(idempotent)
        List<UUID> deletedUserIds = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE deleted_at IS NOT NULL", UUID.class);
        deletedUserIds.forEach(id -> eventPublisher.publishEvent(new UserDeletedEvent(id)));

        // 활성 사용자인데 trading-core 복제본이 없는 경우 — UserNotifyProfileChangedEvent 발행(upsert)
        List<UUID> driftUserIds = jdbcTemplate.queryForList(
                "SELECT id FROM users u WHERE u.deleted_at IS NULL AND NOT EXISTS " +
                        "(SELECT 1 FROM kista.user_notify_profile p WHERE p.user_id = u.id)", UUID.class);
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
