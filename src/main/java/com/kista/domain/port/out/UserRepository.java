package com.kista.domain.port.out;

import com.kista.domain.model.User;
import com.kista.domain.model.UserStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    Optional<User> findByKakaoId(String kakaoId);
    User save(User user);
    List<User> findAll(); // 전체 사용자 목록 (관리자용)
    List<User> findAllByStatus(UserStatus status); // 상태별 사용자 목록 (관리자용)
    void delete(UUID id); // 사용자 삭제 (관리자용)
}
