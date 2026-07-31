package com.kista.domain.port.out;

import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserViewPort {
    List<AdminUserView> findAll();
    List<AdminUserView> findAllByStatus(User.UserStatus status);
    Optional<AdminUserView> findById(UUID userId);
}
