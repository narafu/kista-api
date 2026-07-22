package com.kista.adapter.in.web;

import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.order.CancelResult;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.StrategySeedPreview;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.StrategyUseCase;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TradingCycleController.class)
@Execution(ExecutionMode.SAME_THREAD)
class TradingCycleControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean StrategyUseCase tradingCycle;
    @MockitoBean AccountStatisticsUseCase accountStatistics;
    @MockitoBean TradingExecutionUseCase tradingExecution;

    private static final UUID CYCLE_ID   = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID USER_ID    = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Test
    void executeManually_success_returns200WithOrders() throws Exception {
        when(tradingExecution.executeManually(any(), any())).thenReturn(List.of());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk()) // 200
                .andExpect(jsonPath("$.orders").isArray());
    }

    @Test
    void executeManually_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()))
                .andExpect(status().isUnauthorized()); // 401
    }

    @Test
    void executeManually_notOwner_returns403() throws Exception {
        doThrow(new SecurityException("소유권 불일치"))
                .when(tradingExecution).executeManually(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden()); // 403
    }

    @Test
    void executeManually_privacyCycle_returns400() throws Exception {
        doThrow(new IllegalArgumentException("INFINITE 사이클만 수동 실행 가능합니다"))
                .when(tradingExecution).executeManually(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest()); // 400
    }

    @Test
    void executeManually_alreadyExecutedToday_returns409() throws Exception {
        doThrow(new ManualTradingException("오늘 이미 실행된 사이클입니다"))
                .when(tradingExecution).executeManually(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isConflict()); // 409
    }

    @Test
    void executeManually_cycleNotFound_returns404() throws Exception {
        doThrow(new NoSuchElementException("사이클 없음"))
                .when(tradingExecution).executeManually(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNotFound()); // 404 — GlobalExceptionHandler 처리
    }

    @Test
    void executeManually_kisApiError_returns503() throws Exception {
        doThrow(new KisApiException("KIS API 연결 오류", null))
                .when(tradingExecution).executeManually(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isServiceUnavailable()); // 503
    }

    @Test
    void cancelExecute_success_returnsCancelResult() throws Exception {
        when(tradingExecution.cancelByCycle(any(), any()))
                .thenReturn(new CancelResult(2, 1));

        mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(1));
    }

    @Test
    void cancelExecute_notOwner_returns403() throws Exception {
        doThrow(new SecurityException("소유권 불일치"))
                .when(tradingExecution).cancelByCycle(any(), any());

        mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelExecute_notFound_returns404() throws Exception {
        doThrow(new NoSuchElementException("사이클 없음"))
                .when(tradingExecution).cancelByCycle(any(), any());

        mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void strategyHistory_returns_page_with_date_params() throws Exception {
        var page = new CycleHistoryPage(List.of(), null, false);
        when(accountStatistics.getByStrategy(eq(CYCLE_ID), any(), any(), any(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void strategyHistory_returns_page_without_date_params() throws Exception {
        var page = new CycleHistoryPage(List.of(), null, false);
        when(accountStatistics.getByStrategy(eq(CYCLE_ID), any(), isNull(), isNull(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void update_withSeed_returns200WithUpdatedInitialUsdDeposit() throws Exception {
        Strategy strategy = new Strategy(CYCLE_ID, UUID.randomUUID(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyDetail detail = new StrategyDetail(strategy, new BigDecimal("5000.00"), 20, false, null, 0, null);
        when(tradingCycle.update(eq(CYCLE_ID), any(), any())).thenReturn(detail);

        mockMvc.perform(put("/api/trading-cycles/{id}", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"INFINITE","cycleSeedType":"NONE","initialUsdDeposit":5000.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialUsdDeposit").value(5000.00));
    }

    @Test
    void update_seedBelowPurchaseAmount_returns400() throws Exception {
        doThrow(new IllegalArgumentException("시드는 이미 매수한 금액보다 적을 수 없습니다"))
                .when(tradingCycle).update(eq(CYCLE_ID), any(), any());

        mockMvc.perform(put("/api/trading-cycles/{id}", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"INFINITE","cycleSeedType":"NONE","initialUsdDeposit":100.00}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listMine_returns200_with_strategy_list() throws Exception {
        StrategyDetail detail = new StrategyDetail(
                new com.kista.domain.model.strategy.Strategy(
                        CYCLE_ID, UUID.randomUUID(),
                        com.kista.domain.model.strategy.Strategy.Type.INFINITE,
                        com.kista.domain.model.strategy.Strategy.Status.ACTIVE,
                        com.kista.domain.model.strategy.Strategy.Ticker.SOXL,
                        com.kista.domain.model.strategy.Strategy.CycleSeedType.NONE),
                new java.math.BigDecimal("1000"), 20, false, null, 0, null);
        when(tradingCycle.listByUserId(USER_ID)).thenReturn(List.of(detail));

        mockMvc.perform(get("/api/trading-cycles").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CYCLE_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void listMine_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/trading-cycles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void strategyHistory_returns_nextCursor_when_hasMore() throws Exception {
        Instant cursor = Instant.parse("2024-06-01T00:00:00Z");
        var page = new CycleHistoryPage(List.of(), cursor, true);
        when(accountStatistics.getByStrategy(eq(CYCLE_ID), any(), any(), any(), any(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2024-06-01T00:00:00Z"));
    }

    @Test
    void previewBatch_returns200_withMapKeyedByStrategyId() throws Exception {
        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        var preview = new com.kista.domain.model.order.NextOrdersPreview(
                java.time.LocalDate.of(2026, 1, 5), null, List.of(),
                null, List.of(), BigDecimal.ZERO, null);
        when(tradingExecution.previewBatch(eq(ACCOUNT_ID), any()))
                .thenReturn(java.util.Map.of(strategyId, preview));

        mockMvc.perform(get("/api/accounts/{accountId}/trading-cycles/previews", ACCOUNT_ID)
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + strategyId + "'].tradeDate").value("2026-01-05"))
                .andExpect(jsonPath("$['" + strategyId + "'].skipReason").doesNotExist());
    }

    @Test
    void previewBatch_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/accounts/{accountId}/trading-cycles/previews", ACCOUNT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void seedPreview_returns200_with_minSeed() throws Exception {
        var preview = new StrategySeedPreview("SOXL", new BigDecimal("30.00"), new BigDecimal("1320.00"), null);
        when(accountStatistics.strategySeedPreview(eq(ACCOUNT_ID), any(),
                eq(Strategy.Type.INFINITE), eq(Strategy.Ticker.SOXL), eq(20)))
                .thenReturn(preview);

        mockMvc.perform(get("/api/accounts/{accountId}/strategy-seed-preview", ACCOUNT_ID)
                        .param("type", "INFINITE").param("ticker", "SOXL").param("divisionCount", "20")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minSeed").value(1320.00))
                .andExpect(jsonPath("$.basePrice").value(30.00))
                .andExpect(jsonPath("$.skipReason").doesNotExist());
    }

    @Test
    void seedPreview_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/accounts/{accountId}/strategy-seed-preview", ACCOUNT_ID)
                        .param("type", "INFINITE").param("ticker", "SOXL").param("divisionCount", "20"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_vr_returns201_withVrField() throws Exception {
        // VR 전략 등록 201 응답 + vr 필드 포함 검증
        Strategy vrStrategy = new com.kista.domain.model.strategy.Strategy(
                UUID.randomUUID(), ACCOUNT_ID, Strategy.Type.VR, Strategy.Status.ACTIVE,
                Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyDetail.VrSummary vrSummary = new StrategyDetail.VrSummary(
                new BigDecimal("3000"), new BigDecimal("15.00"), 4, 0,
                new BigDecimal("1000.00"), 10);
        StrategyDetail detail = new StrategyDetail(vrStrategy, new BigDecimal("2000"), null, false, null, 0, vrSummary);
        when(tradingCycle.register(any(), eq(ACCOUNT_ID), any())).thenReturn(detail);

        mockMvc.perform(post("/api/accounts/{accountId}/trading-cycles", ACCOUNT_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "VR",
                                  "initialUsdDeposit": 2000.00,
                                  "initialValue": 3000.00,
                                  "intervalWeeks": 4,
                                  "bandWidth": 15.00,
                                  "recurringAmount": 0
                                }
                                """))
                .andExpect(status().isCreated())         // 201
                .andExpect(jsonPath("$.type").value("VR"))
                .andExpect(jsonPath("$.divisionCount").value((Integer) null))  // VR은 divisionCount=null
                .andExpect(jsonPath("$.vr").exists())
                .andExpect(jsonPath("$.vr.poolLimit").value(1000.00))
                .andExpect(jsonPath("$.vr.intervalWeeks").value(4));
    }

    @Test
    void register_omittedDivisionCount_passesRuntimeDefaultSentinel() throws Exception {
        Strategy strategy = new Strategy(UUID.randomUUID(), ACCOUNT_ID, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyDetail detail = new StrategyDetail(strategy, BigDecimal.ZERO, 30, false, 0.0, 0, null);
        when(tradingCycle.register(any(), eq(ACCOUNT_ID), any())).thenReturn(detail);

        mockMvc.perform(post("/api/accounts/{accountId}/trading-cycles", ACCOUNT_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"INFINITE","ticker":"SOXL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.divisionCount").value(30));

        verify(tradingCycle).register(eq(USER_ID), eq(ACCOUNT_ID),
                argThat((RegisterStrategyCommand command) -> command.divisionCount() == 0));
    }
}
