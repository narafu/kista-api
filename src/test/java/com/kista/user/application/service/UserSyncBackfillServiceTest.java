package com.kista.user.application.service;

import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import com.kista.support.DomainFixtures;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.UserStatus;

@ExtendWith(MockitoExtension.class)
class UserSyncBackfillServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock UserPort userPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @Test
    void runOnce_republishesDeletedUsersAndBackfillsMissingProfiles() {
        UUID deletedUserId = UUID.randomUUID();
        UUID driftUserId = UUID.randomUUID();
        when(jdbcTemplate.queryForList("SELECT id FROM users WHERE deleted_at IS NOT NULL", UUID.class))
                .thenReturn(List.of(deletedUserId));
        when(jdbcTemplate.queryForList(
                "SELECT id FROM users u WHERE u.deleted_at IS NULL AND NOT EXISTS " +
                        "(SELECT 1 FROM kista.user_notify_profile p WHERE p.user_id = u.id)", UUID.class))
                .thenReturn(List.of(driftUserId));
        User driftUser = DomainFixtures.userWithStatus(driftUserId, UserStatus.ACTIVE);
        when(userPort.findById(driftUserId)).thenReturn(Optional.of(driftUser));
        when(userSettingsPort.findOrDefault(driftUserId)).thenReturn(UserSettings.defaultFor(driftUserId));

        UserSyncBackfillService service = new UserSyncBackfillService(
                jdbcTemplate, userPort, userSettingsPort, eventPublisher);
        var result = service.runOnce();

        assertThat(result.cascadeRepublished()).isEqualTo(1);
        assertThat(result.profileBackfilled()).isEqualTo(1);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(UserDeletedEvent.class);
            assertThat(((UserDeletedEvent) e).userId()).isEqualTo(deletedUserId);
        });
        assertThat(events.getAllValues()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(UserNotifyProfileChangedEvent.class);
            assertThat(((UserNotifyProfileChangedEvent) e).userId()).isEqualTo(driftUserId);
        });
    }
}
