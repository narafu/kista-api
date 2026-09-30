package com.kista.user.application.port.output;

import com.kista.user.domain.model.UserSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserStatus;

public interface UserSummaryPort {
    List<UserSummary> findAll();
    List<UserSummary> findAllByStatus(UserStatus status);
    Optional<UserSummary> findById(UUID userId);
}
