package com.kista.api.admin.adapter.in.web;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.Broker;
import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.shared.contract.trading.ReorderResponse;
import com.kista.shared.contract.trading.TradeCorrectionResponse;
import com.kista.shared.contract.trading.OrderResponse;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.api.admin.application.usecase.AdminQueryUseCase;
import com.kista.api.admin.application.usecase.AdminReorderUseCase;
import com.kista.api.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.api.admin.application.usecase.AdminUserUseCase;
import com.kista.shared.platform.security.TokenBlacklistPort;
import com.kista.api.admin.application.port.output.TradingCommandPort;
import com.kista.shared.contract.trading.ReorderTimingAvailabilityResponse;
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
    @MockitoBean AdminReorderUseCase adminReorder;
    @MockitoBean TradingCommandPort tradingCommandPort;

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

    // 전략 주문 조회 공통 스텁 — 계좌(broker)·사용자·주문(ticker)·전략 요약
    private void stubStrategyOrders(Broker broker, StrategyTicker ticker) {
        UUID accountId = ORDER_ACCOUNT_ID;
        UUID strategyId = ORDER_STRATEGY_ID;
        UUID cycleId = UUID.fromString("00000000-0000-0000-0000-000000000040");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        // 단일 계좌/사용자 조회 — 전체 풀스캔 대신 findAccount/findUser 사용
        when(adminQuery.findAccount(accountId)).thenReturn(Optional.of(
                new com.kista.shared.contract.account.AccountSummaryResponse(
                        accountId, ownerId, "1234-56", broker, java.time.Instant.parse("2026-07-01T00:00:00Z"))));
        when(adminUser.findUser(ownerId)).thenReturn(Optional.of(
                new com.kista.api.user.domain.model.UserSummary(
                        ownerId, "privacy-user", UserStatus.ACTIVE, UserRole.USER, java.time.Instant.parse("2026-07-01T00:00:00Z"))));
        when(adminQuery.listStrategyOrders(accountId, strategyId, LocalDate.of(2026, 7, 1))).thenReturn(List.of(
                new OrderResponse(
                        UUID.fromString("00000000-0000-0000-0000-000000000050"), accountId, cycleId, LocalDate.of(2026, 7, 1),
                        ticker, OrderType.LIMIT, OrderTiming.AT_OPEN, OrderDirection.SELL, "SELL_01",
                        BigDecimal.valueOf(2), new BigDecimal("267.37"), OrderStatus.PLACED, "BROKER-1", null, null)));
        when(adminQuery.getStrategySummariesByCycleIds(java.util.Set.of(cycleId)))
                .thenReturn(java.util.Map.of(cycleId,
                        new com.kista.shared.contract.trading.StrategySummaryResponse(strategyId, StrategyType.PRIVACY)));
    }

    @Test
    void listStrategyOrders_adminRole_returns200() throws Exception {
        stubStrategyOrders(Broker.TOSS, StrategyTicker.SOXL);

        mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/orders", ORDER_ACCOUNT_ID, ORDER_STRATEGY_ID)
                        .param("tradeDate", "2026-07-01")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value(ORDER_ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$[0].strategyId").value(ORDER_STRATEGY_ID.toString()))
                .andExpect(jsonPath("$[0].strategyCycleId").value("00000000-0000-0000-0000-000000000040"))
                .andExpect(jsonPath("$[0].strategyType").value("PRIVACY"))
                .andExpect(jsonPath("$[0].ownerNickname").value("privacy-user"))
                .andExpect(jsonPath("$[0].ticker").value("SOXL"))
                .andExpect(jsonPath("$[0].direction").value("SELL"))
                .andExpect(jsonPath("$[0].price").value(267.37))
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    void listStrategyOrders_binanceAccount_currencyUsdt() throws Exception {
        stubStrategyOrders(Broker.BINANCE, StrategyTicker.BTC);

        mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/orders", ORDER_ACCOUNT_ID, ORDER_STRATEGY_ID)
                        .param("tradeDate", "2026-07-01")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("BTC"))
                .andExpect(jsonPath("$[0].currency").value("USDT"));
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
                .thenReturn(new ReorderResponse(
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
                .thenReturn(new ReorderTimingAvailabilityResponse(true, true, false));

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
                .thenReturn(new ReorderTimingAvailabilityResponse(false, false, false));

        mockMvc.perform(get("/api/admin/trades/reorder-timing")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atOpen").value(false))
                .andExpect(jsonPath("$.atClose").value(false))
                .andExpect(jsonPath("$.immediate").value(false));
    }

    @Test
    void getReorderBuyBudget_adminRole_returnsBudget() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(tradingCommandPort.reorderBuyBudget(List.of(orderId), java.time.LocalDate.of(2026, 10, 6)))
                .thenReturn(new com.kista.shared.contract.trading.ReorderBuyBudgetResponse(
                        Map.of(orderId, new BigDecimal("300")), Map.of(orderId, new BigDecimal("200"))));

        mockMvc.perform(get("/api/admin/trades/reorder-buy-budget")
                        .param("orderIds", orderId.toString()).param("tradeDate", "2026-10-06")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleRemaining['" + orderId + "']").value(300))
                .andExpect(jsonPath("$.sourceRefunds['" + orderId + "']").value(200));
    }
}
