package com.kista.adapter.in.web;

import com.kista.domain.model.backtest.BacktestCommand;
import com.kista.domain.model.backtest.BacktestPoint;
import com.kista.domain.model.backtest.BacktestResult;
import com.kista.domain.model.backtest.BacktestSummary;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.BacktestUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BacktestController.class)
@Execution(ExecutionMode.SAME_THREAD)
class BacktestControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort; // GlobalExceptionHandler 의존성
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean BacktestUseCase backtestUseCase;

    private static final UUID USER_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    private static BacktestResult result() {
        return new BacktestResult(
                List.of(new BacktestPoint(LocalDate.of(2024, 1, 2), new BigDecimal("1000.00"), new BigDecimal("1000.00")),
                        new BacktestPoint(LocalDate.of(2024, 1, 3), new BigDecimal("1100.00"), new BigDecimal("1000.00"))),
                new BacktestSummary(new BigDecimal("1100.00"), new BigDecimal("1000.00"),
                        new BigDecimal("0.1000000000"), new BigDecimal("2.5000000000"),
                        new BigDecimal("-0.0500000000"), 3, 1),
                List.of("체결은 일봉 고가/저가 터치 기준으로 판정됩니다"));
    }

    @Test
    void 백테스트_결과를_반환한다() throws Exception {
        when(backtestUseCase.run(any())).thenReturn(result());

        mockMvc.perform(get("/api/backtest")
                        .param("type", "VR").param("ticker", "TQQQ")
                        .param("from", "2024-01-01").param("to", "2024-01-05")
                        .param("seed", "1000")
                        .param("vrBandWidth", "15").param("vrIntervalWeeks", "4")
                        .param("vrRecurringAmount", "100").param("vrInitialValue", "500")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[1].date").value("2024-01-03"))
                .andExpect(jsonPath("$.points[1].totalAsset").value(1100.00))
                .andExpect(jsonPath("$.summary.finalAsset").value(1100.00))
                .andExpect(jsonPath("$.summary.totalReturnRate").value(0.1))
                .andExpect(jsonPath("$.summary.tradeCount").value(3))
                .andExpect(jsonPath("$.summary.cycleCount").value(1))
                .andExpect(jsonPath("$.warnings.length()").value(1));

        ArgumentCaptor<BacktestCommand> captor = ArgumentCaptor.forClass(BacktestCommand.class);
        verify(backtestUseCase).run(captor.capture());
        BacktestCommand command = captor.getValue();
        assertThat(command.type()).isEqualTo(Strategy.Type.VR);
        assertThat(command.ticker()).isEqualTo(Strategy.Ticker.TQQQ);
        assertThat(command.from()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(command.to()).isEqualTo(LocalDate.of(2024, 1, 5));
        assertThat(command.seed()).isEqualByComparingTo("1000");
        assertThat(command.vrRecurringAmount()).isEqualTo(100);
        assertThat(command.vrInitialValue()).isEqualByComparingTo("500");
        assertThat(command.divisionCount()).isNull();
    }

    @Test
    void vrRecurringAmount는_생략하면_0으로_기본값을_쓴다() throws Exception {
        when(backtestUseCase.run(any())).thenReturn(result());

        mockMvc.perform(get("/api/backtest")
                        .param("type", "INFINITE").param("ticker", "TQQQ")
                        .param("from", "2024-01-01").param("to", "2024-01-05")
                        .param("seed", "1000")
                        .with(authentication(auth())))
                .andExpect(status().isOk());

        ArgumentCaptor<BacktestCommand> captor = ArgumentCaptor.forClass(BacktestCommand.class);
        verify(backtestUseCase).run(captor.capture());
        assertThat(captor.getValue().vrRecurringAmount()).isZero();
    }

    @Test
    void 필수_파라미터가_없으면_400이다() throws Exception {
        mockMvc.perform(get("/api/backtest")
                        .param("ticker", "TQQQ")
                        .param("from", "2024-01-01").param("to", "2024-01-05")
                        .param("seed", "1000")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(backtestUseCase);
    }

    @Test
    void 서비스가_던진_IllegalArgumentException은_400으로_매핑된다() throws Exception {
        when(backtestUseCase.run(any())).thenThrow(new IllegalArgumentException("시드(seed)는 0보다 커야 합니다"));

        mockMvc.perform(get("/api/backtest")
                        .param("type", "INFINITE").param("ticker", "TQQQ")
                        .param("from", "2024-01-01").param("to", "2024-01-05")
                        .param("seed", "0")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/backtest")
                        .param("type", "INFINITE").param("ticker", "TQQQ")
                        .param("from", "2024-01-01").param("to", "2024-01-05")
                        .param("seed", "1000"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(backtestUseCase);
    }
}
