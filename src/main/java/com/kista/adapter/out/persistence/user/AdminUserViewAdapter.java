package com.kista.adapter.out.persistence.user;

import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.AdminUserViewPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

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
    public List<AdminUserView> findAllByStatus(User.UserStatus status) {
        return jpaRepository.findAllByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::toView)
                .toList();
    }

    // UserEntity에서 직접 읽어 createdAt 손실 없이 AdminUserView 생성
    private AdminUserView toView(UserEntity e) {
        return new AdminUserView(e.getId(), e.getNickname(), e.getStatus(), e.getRole(), e.getCreatedAt());
    }
}
