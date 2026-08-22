package com.kista.adapter.out.persistence.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSettingsPersistenceAdapterTest {

    @Mock UserSettingsJpaRepository settingsRepo;
    @Mock UserNotificationPrefJpaRepository prefRepo;
    UserSettingsPersistenceAdapter adapter;

    private final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new UserSettingsPersistenceAdapter(settingsRepo, prefRepo, new ObjectMapper());
    }

    @Test
    void loadByUserId_returns_empty_when_no_settings_row() {
        when(settingsRepo.findById(USER_ID)).thenReturn(Optional.empty());
        assertThat(adapter.loadByUserId(USER_ID)).isEmpty();
    }

    @Test
    void loadByUserId_assembles_settings_with_prefs() {
        UserSettingsJpaEntity entity = new UserSettingsJpaEntity(USER_ID, false, "[\"VR\"]");
        UserNotificationPrefJpaEntity pref = new UserNotificationPrefJpaEntity(USER_ID, "TRADING_ALERT", false);
        when(settingsRepo.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(prefRepo.findByUserId(USER_ID)).thenReturn(List.of(pref));

        Optional<UserSettings> result = adapter.loadByUserId(USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get().balanceCheckEnabled()).isFalse();
        assertThat(result.get().isNotificationEnabled(NotificationType.TRADING_ALERT)).isFalse();
        assertThat(result.get().strategySuggestions()).containsExactly("VR");
    }

    @Test
    void loadByUserId_defaultsStrategySuggestions_whenColumnNull() {
        UserSettingsJpaEntity entity = new UserSettingsJpaEntity(USER_ID, false, null);
        when(settingsRepo.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(prefRepo.findByUserId(USER_ID)).thenReturn(List.of());

        Optional<UserSettings> result = adapter.loadByUserId(USER_ID);

        assertThat(result.get().strategySuggestions()).isEqualTo(UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
    }

    @Test
    void save_persists_settings_and_prefs() {
        UserSettings settings = new UserSettings(USER_ID, true,
                Map.of(NotificationType.TRADING_ALERT, false), List.of("VR"));

        adapter.save(settings);

        verify(settingsRepo).save(argThat(e -> e.getUserId().equals(USER_ID) && e.isBalanceCheckEnabled()
                && e.getStrategySuggestions().equals("[\"VR\"]")));
        verify(prefRepo).save(argThat(e -> e.getType().equals("TRADING_ALERT") && !e.isEnabled()));
    }
}
