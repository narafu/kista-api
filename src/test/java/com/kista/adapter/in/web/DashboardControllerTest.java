package com.kista.adapter.in.web;

import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.PortfolioUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Execution(ExecutionMode.SAME_THREAD)
class DashboardControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean PortfolioUseCase portfolioUseCase;
    @MockitoBean AccountStatisticsUseCase accountStatistics;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    // JwtAuthFilter와 동일하게 principal을 UUID로 설정
    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    void getPortfolioSnapshots_returns_200() throws Exception {
        when(portfolioUseCase.getSnapshots(any(), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/portfolio/snapshots?from=2026-01-01&to=2026-01-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk());
    }

    @Test
    void cycleHistory_returns_page_with_date_params() throws Exception {
        var page = new CycleHistoryPage(List.of(), null, false);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), any(), any(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void cycleHistory_returns_page_without_date_params() throws Exception {
        // '전체' 선택 시 from/to 없어도 200 반환
        var page = new CycleHistoryPage(List.of(), null, false);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), isNull(), isNull(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void cycleHistory_returns_nextCursor_when_hasMore() throws Exception {
        Instant cursor = Instant.parse("2024-06-01T00:00:00Z");
        var page = new CycleHistoryPage(List.of(), cursor, true);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), any(), any(), any(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2024-06-01T00:00:00Z"));
    }
}
