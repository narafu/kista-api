package com.kista.user.adapter.out.persistence.user;

import com.kista.user.domain.model.UserSummary;
import com.kista.user.application.port.output.UserSummaryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserStatus;

@Component
@RequiredArgsConstructor
class UserSummaryAdapter implements UserSummaryPort {

    private final UserJpaRepository jpaRepository;

    @Override
    public List<UserSummary> findAll() {
        return jpaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public List<UserSummary> findAllByStatus(UserStatus status) {
        return jpaRepository.findAllByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public Optional<UserSummary> findById(UUID userId) {
        return jpaRepository.findById(userId).map(this::toView);
    }

    // UserEntity에서 직접 읽어 createdAt 손실 없이 UserSummary 생성
    private UserSummary toView(UserEntity e) {
        return new UserSummary(e.getId(), e.getNickname(), e.getStatus(), e.getRole(), e.getCreatedAt());
    }
}
