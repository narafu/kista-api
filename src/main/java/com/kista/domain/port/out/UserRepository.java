package com.kista.domain.port.out;

import com.kista.domain.model.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    Optional<User> findByKakaoId(String kakaoId);
    User save(User user);
}
