package com.kista.adapter.out.persistence.auth;

import com.kista.domain.port.out.RefreshTokenPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// TokenServiceRotationRollbackIT 전용 — RefreshTokenPersistenceAdapter(package-private)를 다른 패키지 테스트에 RefreshTokenPort로 노출
@TestConfiguration
public class RefreshTokenPortTestConfig {

    @Bean
    public RefreshTokenPort refreshTokenPort(RefreshTokenJpaRepository repository) {
        return new RefreshTokenPersistenceAdapter(repository);
    }
}
