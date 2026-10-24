package com.kista.api.user.domain.model;

import com.kista.shared.kernel.NotificationType;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class UserSettingsTest {

    @Test
    void isNotificationEnabled_returns_true_when_no_pref_record() {
        UserSettings settings = new UserSettings(UUID.randomUUID(), Map.of(), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        assertThat(settings.isNotificationEnabled(NotificationType.TRADING_ALERT)).isTrue();
    }

    @Test
    void isNotificationEnabled_returns_stored_value() {
        UserSettings settings = new UserSettings(UUID.randomUUID(),
                Map.of(NotificationType.TRADING_ALERT, false), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        assertThat(settings.isNotificationEnabled(NotificationType.TRADING_ALERT)).isFalse();
    }

    @Test
    void defaultFor_has_empty_prefs() {
        UUID userId = UUID.randomUUID();
        UserSettings settings = UserSettings.defaultFor(userId);
        assertThat(settings.isNotificationEnabled(NotificationType.TRADING_ALERT)).isTrue();
        assertThat(settings.strategySuggestions()).isEqualTo(UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
    }
}
