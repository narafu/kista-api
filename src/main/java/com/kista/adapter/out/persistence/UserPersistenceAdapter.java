package com.kista.adapter.out.persistence;

import com.kista.domain.model.User;
import com.kista.domain.port.out.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // UserJpaRepository가 package-private
public class UserPersistenceAdapter implements UserRepository {

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
    public User save(User user) {
        return jpaRepository.save(UserEntity.fromModel(user)).toModel();
    }
}
