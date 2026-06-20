package com.kista.application.service.user;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.in.UpdateBalanceCheckUseCase.UpdateBalanceCheckCommand;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase.UpdateNotificationPrefCommand;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.LoadUserSettingsPort;
import com.kista.domain.port.out.SaveUserSettingsPort;
import com.kista.domain.port.out.StrategyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    @Mock LoadUserSettingsPort loadPort;
    @Mock SaveUserSettingsPort savePort;
    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @InjectMocks UserSettingsService service;

    private final UUID USER_ID = UUID.randomUUID();

    @Test
    void getByUserId_returns_defaults_when_no_record() {
        when(loadPort.loadByUserId(USER_ID)).thenReturn(Optional.empty());
        UserSettings result = service.getByUserId(USER_ID);
        assertThat(result.balanceCheckEnabled()).isTrue();
        assertThat(result.isNotificationEnabled(NotificationType.TRADING_ALERT)).isTrue();
    }

    @Test
    void getByUserId_returns_stored_settings() {
        UserSettings stored = new UserSettings(USER_ID, false, Map.of(NotificationType.TRADING_ALERT, false));
        when(loadPort.loadByUserId(USER_ID)).thenReturn(Optional.of(stored));
        assertThat(service.getByUserId(USER_ID)).isSameAs(stored);
    }

    @Test
    void updateNotificationPref_saves_updated_pref() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of());
        when(loadPort.loadByUserId(USER_ID)).thenReturn(Optional.of(existing));

        service.update(new UpdateNotificationPrefCommand(USER_ID, NotificationType.TRADING_ALERT, false));

        verify(savePort).save(argThat(s ->
                !s.isNotificationEnabled(NotificationType.TRADING_ALERT)));
    }

    @Test
    void updateBalanceCheck_saves_updated_value() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of());
        when(loadPort.loadByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of());

        service.update(new UpdateBalanceCheckCommand(USER_ID, false));

        verify(savePort).save(argThat(s -> !s.balanceCheckEnabled()));
    }
}
