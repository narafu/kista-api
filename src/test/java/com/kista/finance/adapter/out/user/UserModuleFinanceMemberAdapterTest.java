package com.kista.finance.adapter.out.user;

import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSummaryPort;
import com.kista.user.domain.model.UserSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserModuleFinanceMemberAdapterTest {

    @Mock UserPort userPort;
    @Mock UserSummaryPort userSummaryPort;

    private UserModuleFinanceMemberAdapter adapter() {
        return new UserModuleFinanceMemberAdapter(userPort, userSummaryPort);
    }

    @Test
    void ACTIVE_사용자_id만_위임_조회한다() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(userPort.findIdsByStatus(UserStatus.ACTIVE)).thenReturn(ids);

        assertThat(adapter().activeMemberIds()).isEqualTo(ids);
    }

    @Test
    void 닉네임은_사용자_요약의_nickname이다() {
        UUID userId = UUID.randomUUID();
        when(userSummaryPort.findById(userId)).thenReturn(Optional.of(
                new UserSummary(userId, "홍길동", UserStatus.ACTIVE, UserRole.USER, Instant.now())));

        assertThat(adapter().nicknameOf(userId)).contains("홍길동");
    }

    @Test
    void 사용자가_없으면_닉네임은_empty다() {
        UUID userId = UUID.randomUUID();
        when(userSummaryPort.findById(userId)).thenReturn(Optional.empty());

        assertThat(adapter().nicknameOf(userId)).isEmpty();
    }
}
