package com.kista.api.admin.adapter.in.web;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.Broker;
import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.shared.contract.trading.TradeCorrectionResponse;
import com.kista.shared.contract.trading.OrderResponse;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.api.admin.application.usecase.AdminQueryUseCase;
import com.kista.api.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.api.admin.application.usecase.AdminUserUseCase;
import com.kista.shared.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;

import static com.kista.shared.support.WebMvcTestSupport.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.api.admin.application.port.output.AppErrorLogPort;
import com.kista.shared.kernel.UserRole;
import com.kista.shared.kernel.UserStatus;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.api.web.RootSecurityPolicy;

@WebMvcTest(AdminTradeController.class)
@Import({SecurityConfig.class, RootSecurityPolicy.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminTradeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AdminQueryUseCase adminQuery;
    @MockitoBean AdminUserUseCase adminUser;
    @MockitoBean AdminTradeCorrectionUseCase adminTradeCorrection;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID ORDER_STRATEGY_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

    @Test
    void listTrades_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/trades"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listTrades_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/trades")
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listTrades_adminRole_returns200() throws Exception {
        when(adminQuery.listTrades(null, null)).thenReturn(List.of());
        when(adminQuery.listAccounts(null, null)).thenReturn(List.of());
        when(adminUser.listAll(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/trades")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listTrades_accountNotInMap_currencyNull() throws Exception {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID cycleId = UUID.fromString("00000000-0000-0000-0000-000000000040");
        // 계좌 목록이 비어 accountMap에 없음 → 통화를 알 수 없어 null
        when(adminQuery.listAccounts(null, null)).thenReturn(List.of());
        when(adminUser.listAll(null, null)).thenReturn(List.of());
        when(adminQuery.listTrades(null, null)).thenReturn(List.of(
                new OrderResponse(UUID.fromString("00000000-0000-0000-0000-000000000050"), accountId, cycleId,
                        LocalDate.of(2026, 7, 1), StrategyTicker.SOXL, OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL,
                        "SELL_01", BigDecimal.valueOf(2), new BigDecimal("267.37"), OrderStatus.PLACED, "BROKER-1", null, null)));
        when(adminQuery.getStrategySummariesByCycleIds(java.util.Set.of(cycleId))).thenReturn(java.util.Map.of());

        mockMvc.perform(get("/api/admin/trades").with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ticker").value("SOXL"))
                .andExpect(jsonPath("$[0].currency").doesNotExist());
    }

    @Test
    void listStrategyTradeDates_adminRole_returns200() throws Exception {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        when(adminQuery.listStrategyTradeDates(accountId, strategyId))
                .thenReturn(List.of(LocalDate.of(2026, 7, 1)));

        mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/trade-dates",
                        accountId, strategyId)
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("2026-07-01"));
    }

    @Test
    void listStrategyTradeDates_다른_계좌의_전략이면_404() throws Exception {
        UUID wrongAccountId = UUID.randomUUID();
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        // 서비스가 accountId 불일치 시 NoSuchElementException을 던지도록 stub — ProblemDetailExceptionHandler가 404로 매핑
        when(adminQuery.listStrategyTradeDates(wrongAccountId, strategyId))
                .thenThrow(new java.util.NoSuchElementException("전략이 해당 계좌에 속하지 않습니다"));

        mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/trade-dates",
                        wrongAccountId, strategyId)
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void correctManualFills_adminRole_returns200() throws Exception {
        String body = """
                {
                  "userId": "00000000-0000-0000-0000-000000000010",
                  "accountId": "00000000-0000-0000-0000-000000000020",
                  "strategyId": "00000000-0000-0000-0000-000000000030",
                  "fills": [
                    {
                      "tradeDate": "2026-07-01",
                      "direction": "SELL",
                      "quantity": 2,
                      "price": 267.37,
                      "externalOrderId": "MANUAL-1"
                    }
                  ]
                }
                """;
        when(adminTradeCorrection.correctManualFills(org.mockito.ArgumentMatchers.eq(ADMIN_UUID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TradeCorrectionResponse(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        UUID.fromString("00000000-0000-0000-0000-000000000020"),
                        UUID.fromString("00000000-0000-0000-0000-000000000030"),
                        1,
                        java.math.BigDecimal.ZERO,
                        null,
                        new java.math.BigDecimal("7200.05"),
                        StrategyStatus.PAUSED,
                        true,
                        java.time.LocalDate.of(2026, 7, 1)
                ));

        mockMvc.perform(post("/api/admin/trades/manual-fills")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(adminTradeCorrection).correctManualFills(org.mockito.ArgumentMatchers.eq(ADMIN_UUID), org.mockito.ArgumentMatchers.any());
    }
}
