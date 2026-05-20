package com.kista.adapter.out.persistence.user;

import com.kista.domain.model.user.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByKakaoId(String kakaoId);
    List<UserEntity> findAllByStatus(UserStatus status); // 상태별 조회 (관리자용)
}
