package com.kista.user.application.port.output;

import com.kista.user.domain.model.User;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

public interface UserPort {
    Optional<User> findById(UUID id);
    Optional<User> findByKakaoId(String kakaoId);
    Optional<User> findByTelegramChatId(String chatId); // 텔레그램 봇 명령 발신자 식별용

    // AccountPort.findByIdOrThrow 패턴과 동일
    default User findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + id));
    }
    User save(User user);
    List<User> findAllByStatus(UserStatus status); // 상태별 사용자 목록 (관리자용)
    List<UUID> findIdsByStatus(UserStatus status); // 상태별 사용자 id 목록 — 전 사용자 순회 후 개별 처리하는 소비자용(엔티티 전체 로드 회피)
    Map<UserStatus, Long> countGroupByStatus(); // 상태별 사용자 수 단일 GROUP BY 집계 (관리자 통계용)
    long countByRole(UserRole role); // 역할별 사용자 수 (관리자 최소 1명 검증용)
    void delete(UUID id); // 사용자 삭제 (관리자용)
}
