package com.kista.domain.port.out;

import com.kista.domain.model.user.User;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface UserPort {
    Optional<User> findById(UUID id);
    Optional<User> findByKakaoId(String kakaoId);
    Optional<User> findByTelegramChatId(String chatId); // 텔레그램 봇 명령 발신자 식별용

    // AccountPort.findByIdOrThrow 패턴과 동일
    default User findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + id));
    }
    User save(User user);
    List<User> findAll(); // 전체 사용자 목록 (관리자용)
    List<User> findAllByStatus(User.UserStatus status); // 상태별 사용자 목록 (관리자용)
    long countAll(); // 전체 사용자 수 (관리자 통계용)
    long countByStatus(User.UserStatus status); // 상태별 사용자 수 (관리자 통계용)
    long countByRole(User.UserRole role); // 역할별 사용자 수 (관리자 최소 1명 검증용)
    void delete(UUID id); // 사용자 삭제 (관리자용)
}
