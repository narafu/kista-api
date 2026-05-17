package com.kista.domain.port.in;

import com.kista.domain.model.User;
import com.kista.domain.model.UserStatus;

import java.util.List;

public interface AdminListUsersUseCase {
    List<User> listAll();
    List<User> listByStatus(UserStatus status);
}
