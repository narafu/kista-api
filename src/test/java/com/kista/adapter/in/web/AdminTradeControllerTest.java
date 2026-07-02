package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.admin.AdminTradeCorrectionResult;
import com.kista.domain.model.admin.AdminOrderCorrectionResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminOrderCorrectionUseCase;
import com.kista.domain.port.in.AdminTradeCorrectionUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(AdminTradeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminTradeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AdminQueryUseCase adminQuery;
    @MockitoBean AdminUserUseCase adminUser;
    @MockitoBean AdminTradeCorrectionUseCase adminTradeCorrection;
    @MockitoBean AdminOrderCorrectionUseCase adminOrderCorrection;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listTrades_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/trades"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listTrades_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/trades")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listTrades_adminRole_returns200() throws Exception {
        when(adminQuery.listTrades(null, null)).thenReturn(List.of());
        when(adminQuery.listAccounts(null, null)).thenReturn(List.of());
        when(adminUser.listAll(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/trades")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listStrategyOrders_adminRole_returns200() throws Exception {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        UUID cycleId = UUID.fromString("00000000-0000-0000-0000-000000000040");
        // 단일 계좌/사용자 조회 — 전체 풀스캔 대신 findAccount/findUser 사용
        when(adminQuery.findAccount(accountId)).thenReturn(Optional.of(
                new com.kista.domain.model.account.Account(
                        accountId,
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        "toss-main",
                        "1234-56",
                        null,
                        null,
                        null,
                        com.kista.domain.model.account.Account.Broker.TOSS,
                        java.time.Instant.parse("2026-07-01T00:00:00Z")
                )
        ));
        when(adminUser.findUser(UUID.fromString("00000000-0000-0000-0000-000000000010"))).thenReturn(Optional.of(
                new com.kista.domain.model.admin.AdminUserView(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        "privacy-user",
                        com.kista.domain.model.user.User.UserStatus.ACTIVE,
                        com.kista.domain.model.user.User.UserRole.USER,
                        java.time.Instant.parse("2026-07-01T00:00:00Z")
                )
        ));
        when(adminQuery.listStrategyOrders(strategyId, LocalDate.of(2026, 7, 1))).thenReturn(List.of(
                new Order(
                        UUID.fromString("00000000-0000-0000-0000-000000000050"),
                        accountId,
                        cycleId,
                        LocalDate.of(2026, 7, 1),
                        Strategy.Ticker.SOXL,
                        Order.OrderType.LIMIT,
                        Order.OrderTiming.AT_OPEN,
                        Order.OrderDirection.SELL,
                        2,
                        new BigDecimal("267.37"),
                        Order.OrderStatus.PLACED,
                        "BROKER-1",
                        null,
                        null
                )
        ));
        when(adminQuery.getStrategyTypesByCycleIds(java.util.Set.of(cycleId)))
                .thenReturn(java.util.Map.of(cycleId, Strategy.Type.PRIVACY));

        mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/orders", accountId, strategyId)
                        .param("tradeDate", "2026-07-01")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].strategyType").value("PRIVACY"))
                .andExpect(jsonPath("$[0].ownerNickname").value("privacy-user"))
                .andExpect(jsonPath("$[0].ticker").value("SOXL"))
                .andExpect(jsonPath("$[0].direction").value("SELL"))
                .andExpect(jsonPath("$[0].price").value(267.37));
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
                      "tradeDateKst": "2026-07-01",
                      "direction": "SELL",
                      "quantity": 2,
                      "price": 267.37,
                      "externalOrderId": "MANUAL-1"
                    }
                  ]
                }
                """;
        when(adminTradeCorrection.correctManualFills(org.mockito.ArgumentMatchers.eq(ADMIN_UUID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AdminTradeCorrectionResult(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        UUID.fromString("00000000-0000-0000-0000-000000000020"),
                        UUID.fromString("00000000-0000-0000-0000-000000000030"),
                        1,
                        0,
                        null,
                        new java.math.BigDecimal("7200.05"),
                        Strategy.Status.PAUSED,
                        true,
                        java.time.LocalDate.of(2026, 7, 1)
                ));

        mockMvc.perform(post("/api/admin/trades/manual-fills")
                        .with(csrf())
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN")))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(adminTradeCorrection).correctManualFills(org.mockito.ArgumentMatchers.eq(ADMIN_UUID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void correctOrder_adminRole_returns200() throws Exception {
        String body = """
                {
                  "userId": "00000000-0000-0000-0000-000000000010",
                  "accountId": "00000000-0000-0000-0000-000000000020",
                  "strategyId": "00000000-0000-0000-0000-000000000030",
                  "orderId": "00000000-0000-0000-0000-000000000050",
                  "mode": "PLANNED_EDIT",
                  "tradeDateKst": "2026-07-01",
                  "quantity": 3,
                  "price": 250.00,
                  "memo": "price fix"
                }
                """;
        when(adminOrderCorrection.correctOrder(org.mockito.ArgumentMatchers.eq(ADMIN_UUID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AdminOrderCorrectionResult(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        UUID.fromString("00000000-0000-0000-0000-000000000020"),
                        UUID.fromString("00000000-0000-0000-0000-000000000030"),
                        UUID.fromString("00000000-0000-0000-0000-000000000050"),
                        com.kista.domain.model.admin.AdminOrderCorrectionCommand.Mode.PLANNED_EDIT,
                        Order.OrderStatus.PLANNED,
                        Order.OrderStatus.PLANNED,
                        null,
                        0,
                        null,
                        new java.math.BigDecimal("6989.00"),
                        Strategy.Status.ACTIVE,
                        false,
                        null
                ));

        mockMvc.perform(post("/api/admin/trades/order-corrections")
                        .with(csrf())
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN")))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PLANNED_EDIT"));
    }

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
