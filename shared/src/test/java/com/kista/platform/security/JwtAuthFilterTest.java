package com.kista.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JwtAuthFilterTest {

    @Test
    void shouldNotFilterAsyncDispatch_returnsFalse() {
        JwtAuthFilter filter = new JwtAuthFilter(mock(JwtDecoder.class), mock(TokenBlacklistPort.class));

        assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();
    }
}
