package com.kista.user.application.port.output;

import com.kista.user.domain.model.AdminUserView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserStatus;

public interface AdminUserViewPort {
    List<AdminUserView> findAll();
    List<AdminUserView> findAllByStatus(UserStatus status);
    Optional<AdminUserView> findById(UUID userId);
}
