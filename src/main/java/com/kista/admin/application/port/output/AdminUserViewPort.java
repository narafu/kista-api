package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminUserView;
import com.kista.domain.model.user.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserViewPort {
    List<AdminUserView> findAll();
    List<AdminUserView> findAllByStatus(User.UserStatus status);
    Optional<AdminUserView> findById(UUID userId);
}
