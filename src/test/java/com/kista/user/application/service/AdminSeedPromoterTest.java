package com.kista.user.application.service;

import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
import com.kista.support.DomainFixtures;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminSeedPromoter 단위 테스트")
class AdminSeedPromoterTest {

    @Mock UserPort userPort;
    @Mock UserNotifyProfilePublisher userNotifyProfilePublisher;

    @InjectMocks AdminSeedPromoter promoter;

    @Test
    @DisplayName("ACTIVE+ADMIN으로 저장하고 저장된 값으로 복제본 동기화를 발행한다")
    void promote_savesActiveAdminAndPublishesSyncedValue() {
        UUID userId = UUID.randomUUID();
        User plain = DomainFixtures.userWithStatus(userId, UserStatus.PENDING, (Instant) null);
        when(userPort.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = promoter.promote(plain);

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.role()).isEqualTo(UserRole.ADMIN);

        // 발행은 저장 전 원본이 아니라 저장 결과(ACTIVE)여야 한다 — 아니면 복제본이 is_active=false로 굳는다
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userNotifyProfilePublisher).publishStatusChanged(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(UserStatus.ACTIVE);
    }
}
