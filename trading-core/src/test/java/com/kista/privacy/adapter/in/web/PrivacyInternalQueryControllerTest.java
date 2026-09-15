package com.kista.privacy.adapter.in.web;

import com.kista.privacy.application.usecase.PrivacyUseCase;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import com.kista.trading.TradingApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PrivacyInternalQueryController.class)
@ContextConfiguration(classes = TradingApplication.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class PrivacyInternalQueryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean PrivacyUseCase privacy;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void listTradeBases_인증없이_요청하면_401() throws Exception {
        mockMvc.perform(get("/api/internal/privacy/trade-bases")
                        .param("fromReleaseDate", "2026-01-01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listTradeBases_인증되면_200() throws Exception {
        given(privacy.findBasesFromTradeDate(LocalDate.of(2026, 1, 1))).willReturn(List.of());

        mockMvc.perform(get("/api/internal/privacy/trade-bases")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("fromReleaseDate", "2026-01-01"))
                .andExpect(status().isOk());
    }
}
