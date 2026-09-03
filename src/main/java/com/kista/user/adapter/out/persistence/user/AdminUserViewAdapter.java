package com.kista.user.adapter.out.persistence.user;

import com.kista.user.domain.model.AdminUserView;
import com.kista.user.application.port.output.AdminUserViewPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserStatus;

@Component
@RequiredArgsConstructor
class AdminUserViewAdapter implements AdminUserViewPort {

    private final UserJpaRepository jpaRepository;

    @Override
    public List<AdminUserView> findAll() {
        return jpaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public List<AdminUserView> findAllByStatus(UserStatus status) {
        return jpaRepository.findAllByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public Optional<AdminUserView> findById(UUID userId) {
        return jpaRepository.findById(userId).map(this::toView);
    }

    // UserEntity에서 직접 읽어 createdAt 손실 없이 AdminUserView 생성
    private AdminUserView toView(UserEntity e) {
        return new AdminUserView(e.getId(), e.getNickname(), e.getStatus(), e.getRole(), e.getCreatedAt());
    }
}
