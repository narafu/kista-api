package com.kista.user.application.service;

import com.kista.sharedkernel.NotificationType;
import com.kista.user.domain.model.UserSettings;
import com.kista.user.application.port.output.ActiveStrategyCountPort;
import com.kista.user.application.port.output.UserSettingsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    @Mock UserSettingsPort userSettingsPort;
    @Mock ActiveStrategyCountPort activeStrategyCountPort;
    @Mock UserNotifyProfilePublisher userNotifyProfilePublisher;
    @InjectMocks UserSettingsService service;

    private final UUID USER_ID = UUID.randomUUID();

    @Test
    void getByUserId_returns_defaults_when_no_record() {
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
        UserSettings result = service.getByUserId(USER_ID);
        assertThat(result.balanceCheckEnabled()).isTrue();
        assertThat(result.isNotificationEnabled(NotificationType.TRADING_ALERT)).isTrue();
    }

    @Test
    void getByUserId_returns_stored_settings() {
        UserSettings stored = new UserSettings(USER_ID, false, Map.of(NotificationType.TRADING_ALERT, false),
                UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(stored);
        assertThat(service.getByUserId(USER_ID)).isSameAs(stored);
    }

    @Test
    void updateNotificationPref_saves_updated_pref() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of(), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(existing);

        service.updateNotificationPref(USER_ID, NotificationType.TRADING_ALERT, false);

        verify(userSettingsPort).save(argThat(s ->
                !s.isNotificationEnabled(NotificationType.TRADING_ALERT)));
    }

    @Test
    void updateBalanceCheck_saves_updated_value() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of(), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(existing);
        when(activeStrategyCountPort.countActiveByUserId(USER_ID)).thenReturn(0L);

        service.updateBalanceCheck(USER_ID, false);

        verify(userSettingsPort).save(argThat(s -> !s.balanceCheckEnabled()));
    }

    @Test
    void updateStrategySuggestions_saves_updated_list() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of(), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(existing);

        service.updateStrategySuggestions(USER_ID, List.of("커스텀전략"));

        verify(userSettingsPort).save(argThat(s -> s.strategySuggestions().equals(List.of("커스텀전략"))));
    }
    @Test
    void updateNotificationPref_변경_시_UserNotifyProfileChangedEvent_발행을_위임한다() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of(), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(existing);

        service.updateNotificationPref(USER_ID, NotificationType.TRADING_ALERT, false);

        // 저장된 값 그대로가 trading-core 복제본으로 전달돼야 한다
        verify(userNotifyProfilePublisher).publishSettingsChanged(argThat(s ->
                s.userId().equals(USER_ID) && !s.isNotificationEnabled(NotificationType.TRADING_ALERT)));
    }

    @Test
    void updateBalanceCheck_변경_시_UserNotifyProfileChangedEvent_발행을_위임한다() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of(), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(existing);
        when(activeStrategyCountPort.countActiveByUserId(USER_ID)).thenReturn(0L);

        service.updateBalanceCheck(USER_ID, false);

        verify(userNotifyProfilePublisher).publishSettingsChanged(argThat(s ->
                s.userId().equals(USER_ID) && !s.balanceCheckEnabled()));
    }

    @Test
    void updateStrategySuggestions는_프로필_대상_필드가_아니라_발행하지_않는다() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of(), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS);
        when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(existing);

        service.updateStrategySuggestions(USER_ID, List.of("커스텀전략"));

        verifyNoInteractions(userNotifyProfilePublisher);
    }
}
