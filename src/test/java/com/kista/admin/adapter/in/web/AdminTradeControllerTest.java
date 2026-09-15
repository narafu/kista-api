package com.kista.admin.adapter.in.web;

import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.Broker;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.admin.application.usecase.AdminQueryUseCase;
import com.kista.admin.application.usecase.AdminReorderUseCase;
import com.kista.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.admin.application.usecase.AdminUserUseCase;
import com.kista.platform.security.TokenBlacklistPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.domain.model.AdminReorderTimingAvailability;
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
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;

import static com.kista.support.WebMvcTestSupport.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;

@WebMvcTest(AdminTradeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminTradeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AdminQueryUseCase adminQuery;
    @MockitoBean AdminUserUseCase adminUser;
    @MockitoBean AdminTradeCorrectionUseCase adminTradeCorrection;
    @MockitoBean AdminReorderUseCase adminReorder;
    @MockitoBean TradingCommandPort tradingCommandPort;

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
    void listStrategyOrders_adminRole_returns200() throws Exception {
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        UUID cycleId = UUID.fromString("00000000-0000-0000-0000-000000000040");
        // 단일 계좌/사용자 조회 — 전체 풀스캔 대신 findAccount/findUser 사용
        when(adminQuery.findAccount(accountId)).thenReturn(Optional.of(
                new com.kista.admin.domain.model.AdminAccountView(
                        accountId,
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        "1234-56",
                        Broker.TOSS,
                        java.time.Instant.parse("2026-07-01T00:00:00Z")
                )
        ));
        when(adminUser.findUser(UUID.fromString("00000000-0000-0000-0000-000000000010"))).thenReturn(Optional.of(
                new com.kista.user.domain.model.AdminUserView(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        "privacy-user",
                        UserStatus.ACTIVE,
                        UserRole.USER,
                        java.time.Instant.parse("2026-07-01T00:00:00Z")
                )
        ));
        when(adminQuery.listStrategyOrders(accountId, strategyId, LocalDate.of(2026, 7, 1))).thenReturn(List.of(
                new AdminOrderView(
                        UUID.fromString("00000000-0000-0000-0000-000000000050"),
                        accountId,
                        cycleId,
                        LocalDate.of(2026, 7, 1),
                        StrategyTicker.SOXL,
                        OrderType.LIMIT,
                        OrderTiming.AT_OPEN,
                        OrderDirection.SELL,
                        "SELL_01",
                        2,
                        new BigDecimal("267.37"),
                        OrderStatus.PLACED,
                        "BROKER-1",
                        null,
                        null
                )
        ));
        when(adminQuery.getStrategySummariesByCycleIds(java.util.Set.of(cycleId)))
                .thenReturn(java.util.Map.of(
                        cycleId,
                        new com.kista.admin.domain.model.AdminStrategySummary(strategyId, StrategyType.PRIVACY)
                ));

        mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/orders", accountId, strategyId)
                        .param("tradeDate", "2026-07-01")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value(accountId.toString()))
                .andExpect(jsonPath("$[0].strategyId").value(strategyId.toString()))
                .andExpect(jsonPath("$[0].strategyType").value("PRIVACY"))
                .andExpect(jsonPath("$[0].ownerNickname").value("privacy-user"))
                .andExpect(jsonPath("$[0].ticker").value("SOXL"))
                .andExpect(jsonPath("$[0].direction").value("SELL"))
                .andExpect(jsonPath("$[0].price").value(267.37));
    }

    @Test
    void listStrategyOrders_다른_계좌의_전략이면_404() throws Exception {
        UUID wrongAccountId = UUID.randomUUID();
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        // 서비스가 accountId 불일치 시 NoSuchElementException을 던지도록 stub — GlobalExceptionHandler가 404로 매핑
        when(adminQuery.listStrategyOrders(wrongAccountId, strategyId, LocalDate.of(2026, 7, 1)))
                .thenThrow(new java.util.NoSuchElementException("전략이 해당 계좌에 속하지 않습니다"));

        mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/orders",
                        wrongAccountId, strategyId)
                        .param("tradeDate", "2026-07-01")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isNotFound());
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
        // 서비스가 accountId 불일치 시 NoSuchElementException을 던지도록 stub — GlobalExceptionHandler가 404로 매핑
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
                .thenReturn(new AdminTradeCorrectionResult(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        UUID.fromString("00000000-0000-0000-0000-000000000020"),
                        UUID.fromString("00000000-0000-0000-0000-000000000030"),
                        1,
                        0,
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

    @Test
    void reorder_adminRole_returns200() throws Exception {
        String body = """
                {
                  "userId": "00000000-0000-0000-0000-000000000010",
                  "accountId": "00000000-0000-0000-0000-000000000020",
                  "strategyId": "00000000-0000-0000-0000-000000000030",
                  "orderId": "00000000-0000-0000-0000-000000000050",
                  "timing": "AT_CLOSE",
                  "tradeDate": "2026-07-01",
                  "quantity": 3,
                  "price": 250.00,
                  "memo": "reorder memo"
                }
                """;
        when(adminReorder.reorder(org.mockito.ArgumentMatchers.eq(ADMIN_UUID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AdminReorderResult(
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        UUID.fromString("00000000-0000-0000-0000-000000000020"),
                        UUID.fromString("00000000-0000-0000-0000-000000000030"),
                        UUID.fromString("00000000-0000-0000-0000-000000000050"),
                        OrderStatus.PLANNED,
                        OrderStatus.PLANNED,
                        null,
                        new BigDecimal("236.54"),
                        1,
                        OrderDirection.SELL
                ));

        mockMvc.perform(post("/api/admin/trades/reorders")
                        .with(csrf())
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultingStatus").value("PLANNED"));
    }

    @Test
    void getReorderTiming_adminRole_tradingDay_returnsTimingFlags() throws Exception {
        // 개장 여부 판정은 trading-core 내부 API 구현부로 이관됨 — 컨트롤러는 포트 결과를 그대로 반환
        when(tradingCommandPort.reorderTimingAvailability())
                .thenReturn(new AdminReorderTimingAvailability(true, true, false));

        mockMvc.perform(get("/api/admin/trades/reorder-timing")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atOpen").isBoolean())
                .andExpect(jsonPath("$.atClose").isBoolean())
                .andExpect(jsonPath("$.immediate").isBoolean());
    }

    @Test
    void getReorderTiming_adminRole_holiday_returnsAllFalse() throws Exception {
        // 비개장일 판정도 trading-core 쪽에서 이미 반영된 결과를 그대로 전달
        when(tradingCommandPort.reorderTimingAvailability())
                .thenReturn(new AdminReorderTimingAvailability(false, false, false));

        mockMvc.perform(get("/api/admin/trades/reorder-timing")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atOpen").value(false))
                .andExpect(jsonPath("$.atClose").value(false))
                .andExpect(jsonPath("$.immediate").value(false));
    }
}

