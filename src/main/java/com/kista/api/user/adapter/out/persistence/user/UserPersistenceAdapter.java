package com.kista.api.user.adapter.out.persistence.user;

import com.kista.api.user.domain.model.User;
import com.kista.api.user.application.port.output.UserPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.kista.shared.kernel.UserRole;
import com.kista.shared.kernel.UserStatus;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class UserPersistenceAdapter implements UserPort {

    private final UserJpaRepository jpaRepository;

    @Override
    public Optional<User> findById(UUID id) {
        return jpaRepository.findById(id).map(UserEntity::toModel);
    }

    @Override
    public Optional<User> findByKakaoId(String kakaoId) {
        return jpaRepository.findByKakaoId(kakaoId).map(UserEntity::toModel);
    }

    @Override
    public Optional<User> findByTelegramChatId(String chatId) {
        return jpaRepository.findByTelegramChatId(chatId).map(UserEntity::toModel);
    }

    @Override
    public User save(User user) {
        return jpaRepository.save(UserEntity.fromModel(user)).toModel();
    }

    @Override
    public List<User> findAllByStatus(UserStatus status) {
        return jpaRepository.findAllByStatus(status).stream().map(UserEntity::toModel).toList();
    }

    @Override
    public List<UUID> findIdsByStatus(UserStatus status) {
        return jpaRepository.findIdsByStatus(status);
    }

    @Override
    public Map<UserStatus, Long> countGroupByStatus() {
        // GROUP BY 프로젝션을 EnumMap으로 수집 — 결과에 없는 상태는 호출측 getOrDefault(0)로 처리
        Map<UserStatus, Long> result = new EnumMap<>(UserStatus.class);
        for (UserJpaRepository.StatusCountProjection p : jpaRepository.countGroupByStatus()) {
            result.put(p.getStatus(), p.getCount());
        }
        return result;
    }

    @Override
    public long countByRole(UserRole role) {
        return jpaRepository.countByRole(role);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }
}
