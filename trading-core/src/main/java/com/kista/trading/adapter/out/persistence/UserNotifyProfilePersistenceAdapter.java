package com.kista.trading.adapter.out.persistence;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.kista.platform.crypto.AesCryptoService;
import com.kista.sharedkernel.NotificationType;
import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// TradingUserProfilePort 구현 — user 모듈을 직접 참조하던 web의 TradingUserProfileAdapter를 대체한다.
// trading-core 소유 복제 테이블(kista.user_notify_profile)만 읽으므로 root(:api) 컴파일 의존이 0이다.
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
class UserNotifyProfilePersistenceAdapter implements TradingUserProfilePort {

    private static final TypeReference<Map<NotificationType, Boolean>> PREFS_TYPE = new TypeReference<>() {};

    private final UserNotifyProfileJpaRepository repository;
    private final ObjectMapper objectMapper;
    private final AesCryptoService crypto; // telegramBotToken 복호화 — persistence 경계에서만 사용

    @Override
    public Optional<TradingUserProfile> findByUserId(UUID userId) {
        return repository.findById(userId).map(this::toProfile);
    }

    @Override
    public Map<UUID, TradingUserProfile> findAllByUserIds(List<UUID> userIds) {
        return repository.findAllByUserIdIn(userIds).stream()
                .map(this::toProfile)
                .collect(Collectors.toMap(TradingUserProfile::userId, Function.identity()));
    }

    @Override
    public List<TradingUserProfile> findAllActive() {
        return repository.findAllByActiveTrue().stream().map(this::toProfile).toList();
    }

    private TradingUserProfile toProfile(UserNotifyProfileEntity entity) {
        // persistence 경계에서 telegramBotToken 복호화 — null이면 그대로 null 유지
        String telegramBotToken = entity.getTelegramBotToken() == null ? null : crypto.decrypt(entity.getTelegramBotToken());
        return new TradingUserProfile(entity.getUserId(), readPrefs(entity), entity.isBalanceCheckEnabled(),
                telegramBotToken, entity.getChatId());
    }

    // 복제본이 손상돼도 알림 판정만 기본값(전부 활성)으로 떨어지고 매매 자체는 계속되도록 격리한다
    private Map<NotificationType, Boolean> readPrefs(UserNotifyProfileEntity entity) {
        try {
            return objectMapper.readValue(entity.getNotificationPrefsJson(), PREFS_TYPE);
        } catch (Exception e) {
            log.warn("[userId={}] 알림 설정 복제본 역직렬화 실패 — 기본값(전부 활성)으로 대체: {}",
                    entity.getUserId(), e.getMessage());
            return Map.of();
        }
    }
}
