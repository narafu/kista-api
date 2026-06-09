package com.kista.adapter.in.web;

import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.tradingcycle.CycleHistoryPage;
import com.kista.domain.port.in.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.kista.domain.model.order.CancelResult;
import com.kista.domain.port.in.CancelOrderUseCase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TradingCycleController.class)
@Execution(ExecutionMode.SAME_THREAD)
class TradingCycleControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean RegisterTradingCycleUseCase registerCycle;
    @MockitoBean UpdateTradingCycleUseCase updateCycle;
    @MockitoBean DeleteTradingCycleUseCase deleteCycle;
    @MockitoBean GetTradingCycleUseCase getCycle;
    @MockitoBean PauseTradingCycleUseCase pauseCycle;
    @MockitoBean ResumeTradingCycleUseCase resumeCycle;
    @MockitoBean GetCycleHistoryUseCase getCycleHistory;
    @MockitoBean ManualExecuteTradingUseCase manualExecute;
    @MockitoBean CancelOrderUseCase cancelOrder;

    private static final UUID CYCLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID USER_ID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // JwtAuthFilter와 동일하게 principal을 UUID로 설정
    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    void executeManually_success_returns200WithOrders() throws Exception {
        when(manualExecute.execute(any(), any())).thenReturn(List.of());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
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
                .when(manualExecute).execute(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isForbidden()); // 403
    }

    @Test
    void executeManually_privacyCycle_returns400() throws Exception {
        doThrow(new IllegalArgumentException("INFINITE 사이클만 수동 실행 가능합니다"))
                .when(manualExecute).execute(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isBadRequest()); // 400
    }

    @Test
    void executeManually_alreadyExecutedToday_returns409() throws Exception {
        doThrow(new ManualTradingException("오늘 이미 실행된 사이클입니다"))
                .when(manualExecute).execute(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isConflict()); // 409
    }

    @Test
    void executeManually_cycleNotFound_returns404() throws Exception {
        doThrow(new NoSuchElementException("사이클 없음"))
                .when(manualExecute).execute(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNotFound()); // 404 — GlobalExceptionHandler 처리
    }

    @Test
    void executeManually_kisApiError_returns503() throws Exception {
        doThrow(new KisApiException("KIS API 연결 오류", null))
                .when(manualExecute).execute(any(), any());

        mockMvc.perform(post("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isServiceUnavailable()); // 503
    }

    @Test
    void cancelExecute_success_returnsCancelResult() throws Exception {
        when(cancelOrder.cancelByCycle(any(), any()))
                .thenReturn(new CancelResult(2, 1));

        mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(1));
    }

    @Test
    void cancelExecute_notOwner_returns403() throws Exception {
        doThrow(new SecurityException("소유권 불일치"))
                .when(cancelOrder).cancelByCycle(any(), any());

        mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelExecute_notFound_returns404() throws Exception {
        doThrow(new NoSuchElementException("사이클 없음"))
                .when(cancelOrder).cancelByCycle(any(), any());

        mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void strategyHistory_returns_page_with_date_params() throws Exception {
        var page = new CycleHistoryPage(List.of(), null, false);
        when(getCycleHistory.getByStrategy(eq(CYCLE_ID), any(), any(), any(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void strategyHistory_returns_page_without_date_params() throws Exception {
        var page = new CycleHistoryPage(List.of(), null, false);
        when(getCycleHistory.getByStrategy(eq(CYCLE_ID), any(), isNull(), isNull(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void strategyHistory_returns_nextCursor_when_hasMore() throws Exception {
        Instant cursor = Instant.parse("2024-06-01T00:00:00Z");
        var page = new CycleHistoryPage(List.of(), cursor, true);
        when(getCycleHistory.getByStrategy(eq(CYCLE_ID), any(), any(), any(), any(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2024-06-01T00:00:00Z"));
    }
}
