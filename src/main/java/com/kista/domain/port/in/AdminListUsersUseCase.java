package com.kista.domain.port.in;

import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;

import java.util.List;

public interface AdminListUsersUseCase {
    List<AdminUserView> listAll();
    List<AdminUserView> listByStatus(User.UserStatus status);
}
