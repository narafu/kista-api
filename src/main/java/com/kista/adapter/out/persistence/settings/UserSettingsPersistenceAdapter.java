package com.kista.adapter.out.persistence.settings;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.LoadUserSettingsPort;
import com.kista.domain.port.out.SaveUserSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserSettingsPersistenceAdapter implements LoadUserSettingsPort, SaveUserSettingsPort {

    private final UserSettingsJpaRepository settingsRepo;
    private final UserNotificationPrefJpaRepository prefRepo;

    @Override
    public Optional<UserSettings> loadByUserId(UUID userId) {
        // user_settings 행이 없으면 empty 반환 — 서비스 레이어에서 defaultFor() 분기 처리
        return settingsRepo.findById(userId).map(entity -> {
            // 알림 선호도 맵 조립 — 없으면 빈 Map (isNotificationEnabled가 기본값 true 반환)
            Map<NotificationType, Boolean> prefs = prefRepo.findByUserId(userId).stream()
                    .collect(Collectors.toMap(
                            p -> NotificationType.valueOf(p.getType()),
                            UserNotificationPrefJpaEntity::isEnabled
                    ));
            return new UserSettings(userId, entity.isBalanceCheckEnabled(), prefs);
        });
    }

    @Override
    public void save(UserSettings settings) {
        // user_settings 행 저장 (없으면 INSERT, 있으면 UPDATE)
        settingsRepo.save(new UserSettingsJpaEntity(settings.userId(), settings.balanceCheckEnabled()));
        // 알림 선호도 각 타입별로 저장
        settings.notificationPrefs().forEach((type, enabled) ->
                prefRepo.save(new UserNotificationPrefJpaEntity(settings.userId(), type.name(), enabled))
        );
    }
}
