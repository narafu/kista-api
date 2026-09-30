package com.kista.finance.adapter.out.user;

import com.kista.finance.application.port.output.FinanceMemberPort;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSummaryPort;
import com.kista.user.domain.model.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// finance → user 의존의 유일한 지점 — user 모듈의 사용자 조회 포트를 finance 소유 FinanceMemberPort로 변환한다
@Component
@RequiredArgsConstructor
class UserModuleFinanceMemberAdapter implements FinanceMemberPort {

    private final UserPort userPort;               // ACTIVE 사용자 id 조회
    private final UserSummaryPort userSummaryPort; // 닉네임 조회용 읽기 투영

    @Override
    public List<UUID> activeMemberIds() {
        return userPort.findIdsByStatus(UserStatus.ACTIVE);
    }

    @Override
    public Optional<String> nicknameOf(UUID userId) {
        return userSummaryPort.findById(userId).map(UserSummary::nickname);
    }
}
