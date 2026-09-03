package com.kista.user.application.service;

import com.kista.user.application.event.UserDeletedEvent;
import com.kista.account.application.port.output.AccountPort;
import com.kista.user.application.port.output.BlacklistPort;
import com.kista.user.application.port.output.RefreshTokenPort;
import com.kista.application.port.output.StrategyPort;
import com.kista.user.application.port.output.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserCascadeDeleter 단위 테스트")
class UserCascadeDeleterTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock UserPort userPort;
    @Mock RefreshTokenPort refreshTokenPort;
    @Mock BlacklistPort blacklistPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks UserCascadeDeleter deleter;

    @Test
    @DisplayName("account/strategy는 직접 소프트 삭제하고, 나머지 cascade는 UserDeletedEvent로 위임한다")
    void deleteCascade_softDeletesAndPublishesEvent() {
        UUID userId = UUID.randomUUID();

        deleter.deleteCascade(userId);

        verify(strategyPort).deleteByUserId(userId);
        verify(accountPort).deleteByUserId(userId);
        verify(userPort).delete(userId);
        verify(refreshTokenPort).deleteAllByUserId(userId);
        verify(blacklistPort).add(eq(userId), any(Duration.class));
        verify(eventPublisher).publishEvent(new UserDeletedEvent(userId));
    }
}
