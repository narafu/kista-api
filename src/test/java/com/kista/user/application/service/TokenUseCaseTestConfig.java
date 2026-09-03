package com.kista.user.application.service;

import com.kista.user.application.usecase.TokenUseCase;
import com.kista.user.application.port.output.BlacklistPort;
import com.kista.user.application.port.output.RefreshTokenPort;
import com.kista.user.application.port.output.UserPort;
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
