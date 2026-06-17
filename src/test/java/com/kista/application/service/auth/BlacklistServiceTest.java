package com.kista.application.service.auth;

import com.kista.domain.port.out.BlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BlacklistServiceTest {

    @Mock BlacklistPort blacklistPort;
    @InjectMocks BlacklistService blacklistService;

    @Test
    void isBlacklisted_delegatesToPort() {
        UUID userId = UUID.randomUUID();
        given(blacklistPort.isBlacklisted(userId)).willReturn(true);
        assertThat(blacklistService.isBlacklisted(userId)).isTrue();
    }
}
