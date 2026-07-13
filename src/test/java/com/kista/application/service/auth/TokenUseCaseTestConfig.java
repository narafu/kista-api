package com.kista.application.service.auth;

import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.RefreshTokenPort;
import com.kista.domain.port.out.UserPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// TokenServiceRotationRollbackIT(adapter 패키지) 전용 — TokenService(package-private)를 TokenUseCase로 노출
@TestConfiguration
public class TokenUseCaseTestConfig {

    @Bean
    public TokenUseCase tokenUseCase(RefreshTokenPort refreshTokenPort, BlacklistPort blacklistPort, UserPort userPort) {
        return new TokenService(refreshTokenPort, blacklistPort, userPort);
    }
}
