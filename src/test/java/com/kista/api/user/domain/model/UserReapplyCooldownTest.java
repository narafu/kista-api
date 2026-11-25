package com.kista.api.user.domain.model;

import com.kista.api.support.DomainFixtures;
import com.kista.shared.kernel.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserReapplyCooldownTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");

    private static User user(UserStatus status, Instant lastReappliedAt) {
        return DomainFixtures.userWithStatus(UUID.randomUUID(), status).withStatus(status, lastReappliedAt);
    }

    @Test
    void PENDING은_1시간_경계까지_쿨다운이고_정각에_풀린다() {
        User u = user(UserStatus.PENDING, T0);
        assertThat(u.reapplyAvailableAt(true, T0.plus(Duration.ofMinutes(59)))).isEqualTo(T0.plus(Duration.ofHours(1)));
        assertThat(u.reapplyAvailableAt(true, T0.plus(Duration.ofHours(1)))).isNull();
    }

    @Test
    void REJECTED는_24시간_경계까지_쿨다운이고_승인설정과_무관하다() {
        User u = user(UserStatus.REJECTED, T0);
        assertThat(u.reapplyAvailableAt(false, T0.plus(Duration.ofHours(23)))).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(u.reapplyAvailableAt(true, T0.plus(Duration.ofHours(24)))).isNull();
    }

    @Test
    void 승인불필요_PENDING_ACTIVE_기록없음은_null() {
        assertThat(user(UserStatus.PENDING, T0).reapplyAvailableAt(false, T0)).isNull();
        assertThat(user(UserStatus.ACTIVE, T0).reapplyAvailableAt(true, T0)).isNull();
        assertThat(user(UserStatus.REJECTED, null).reapplyAvailableAt(true, T0)).isNull();
    }

    @Test
    void requireReapplyAllowed는_쿨다운_중이면_해제시각과_함께_예외() {
        User u = user(UserStatus.REJECTED, T0);
        assertThatThrownBy(() -> u.requireReapplyAllowed(true, T0.plusSeconds(1)))
                .isInstanceOfSatisfying(User.CooldownException.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo(T0.plus(Duration.ofHours(24))));
    }
}
