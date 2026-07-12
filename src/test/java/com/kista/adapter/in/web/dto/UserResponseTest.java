package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserResponseTest {

    @Test
    void from_rejectedUser_exposesRejectReason() {
        UUID userId = UUID.randomUUID();
        User user = DomainFixtures.userWithStatus(userId, User.UserStatus.REJECTED)
                .withRejection("허위 정보 기재");
        UserSettings settings = UserSettings.defaultFor(userId);

        UserResponse response = UserResponse.from(user, settings);

        assertThat(response.rejectReason()).isEqualTo("허위 정보 기재");
    }

    @Test
    void from_activeUserWithLingeringReason_masksRejectReasonAsNull() {
        // 과거 REJECTED -> 재신청 승인으로 ACTIVE 전환된 사용자 — reject_reason 컬럼에 값이 남아있어도 노출 금지
        UUID userId = UUID.randomUUID();
        User rejectedThenApproved = DomainFixtures.userWithStatus(userId, User.UserStatus.REJECTED)
                .withRejection("잔존 사유")
                .withStatus(User.UserStatus.ACTIVE);
        UserSettings settings = UserSettings.defaultFor(userId);

        UserResponse response = UserResponse.from(rejectedThenApproved, settings);

        assertThat(response.rejectReason()).isNull();
    }

    @Test
    void from_pendingUser_rejectReasonIsNull() {
        UUID userId = UUID.randomUUID();
        User user = DomainFixtures.userWithStatus(userId, User.UserStatus.PENDING);
        UserSettings settings = UserSettings.defaultFor(userId);

        UserResponse response = UserResponse.from(user, settings);

        assertThat(response.rejectReason()).isNull();
    }
}
