package com.kista.adapter.in.telegram;

import com.kista.domain.model.Order;
import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramBotServiceTest {

    @Mock TelegramApiClient apiClient;
    @Mock GetTradeHistoryUseCase getTradeHistoryUseCase;
    @Mock GetPortfolioUseCase getPortfolioUseCase;
    @Mock ExecuteTradingUseCase executeTradingUseCase;
    @Mock ApproveUserUseCase approveUserUseCase;

    TelegramBotService sut;
    static final long CHAT_ID = 12345L;

    @BeforeEach
    void setUp() {
        sut = new TelegramBotService(String.valueOf(CHAT_ID), apiClient,
                getTradeHistoryUseCase, getPortfolioUseCase, executeTradingUseCase, approveUserUseCase);
    }

    // 일반 메시지 업데이트 생성 헬퍼
    private TelegramUpdate update(String text) {
        return new TelegramUpdate(1L,
                new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(CHAT_ID), text),
                null);
    }

    // callback_query 업데이트 생성 헬퍼
    private TelegramUpdate callbackUpdate(String callbackData) {
        TelegramUpdate.Message msg = new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(CHAT_ID), null);
        TelegramUpdate.CallbackQuery cq = new TelegramUpdate.CallbackQuery("cq-id-123", callbackData, msg);
        return new TelegramUpdate(1L, null, cq);
    }

    @Test
    void help_command_returns_command_list() {
        sut.handle(update("/help"));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(eq(String.valueOf(CHAT_ID)), captor.capture());
        assertThat(captor.getValue()).contains("/status").contains("/history").contains("/run");
    }

    @Test
    void status_command_returns_portfolio_info() {
        PortfolioSnapshot snap = new PortfolioSnapshot(UUID.randomUUID(), LocalDate.now(), "SOXL",
                100, new BigDecimal("25.0000"), new BigDecimal("26.0000"),
                new BigDecimal("2600.00"), new BigDecimal("1000.00"),
                new BigDecimal("3600.00"), Instant.now());
        when(getPortfolioUseCase.getCurrent()).thenReturn(snap);

        sut.handle(update("/status"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("100주");
    }

    @Test
    void status_when_no_snapshot_returns_fallback_message() {
        when(getPortfolioUseCase.getCurrent()).thenThrow(new NoSuchElementException());

        sut.handle(update("/status"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("데이터가 없습니다");
    }

    @Test
    void history_command_with_days_delegates_to_usecase() {
        when(getTradeHistoryUseCase.getHistory(any(), any(), eq("SOXL"))).thenReturn(List.of());

        sut.handle(update("/history 14"));

        verify(getTradeHistoryUseCase).getHistory(
                LocalDate.now().minusDays(14), LocalDate.now(), "SOXL");
    }

    @Test
    void run_command_transitions_to_awaiting_confirm() {
        sut.handle(update("/run"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("yes/no");
    }

    @Test
    void run_confirm_yes_starts_trading_execution() throws InterruptedException {
        sut.handle(update("/run"));
        reset(apiClient);

        sut.handle(update("yes"));

        Thread.sleep(200);
        verify(executeTradingUseCase, atLeastOnce()).execute();
    }

    @Test
    void run_confirm_no_cancels_and_returns_to_idle() {
        sut.handle(update("/run"));
        reset(apiClient);

        sut.handle(update("no"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("취소");
    }

    @Test
    void unauthorized_chatId_is_ignored() {
        TelegramUpdate badUpdate = new TelegramUpdate(1L,
                new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(99999L), "/help"),
                null);

        sut.handle(badUpdate);

        verifyNoInteractions(apiClient);
    }

    @Test
    void unknown_command_returns_help_hint() {
        sut.handle(update("/unknown"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("/help");
    }

    @Test
    void callback_approve_calls_approve_usecase_and_answers() {
        UUID userId = UUID.randomUUID();

        sut.handle(callbackUpdate("approve:" + userId));

        verify(approveUserUseCase).approve(userId);
        verify(apiClient).answerCallbackQuery("cq-id-123");
        verify(apiClient).sendMessage(eq(String.valueOf(CHAT_ID)), contains("승인"));
    }

    @Test
    void callback_reject_calls_reject_usecase_and_answers() {
        UUID userId = UUID.randomUUID();

        sut.handle(callbackUpdate("reject:" + userId));

        verify(approveUserUseCase).reject(userId);
        verify(apiClient).answerCallbackQuery("cq-id-123");
        verify(apiClient).sendMessage(eq(String.valueOf(CHAT_ID)), contains("거절"));
    }

    @Test
    void callback_always_answers_even_on_error() {
        doThrow(new RuntimeException("DB 오류")).when(approveUserUseCase).approve(any());

        sut.handle(callbackUpdate("approve:" + UUID.randomUUID()));

        // 오류가 나도 answerCallbackQuery는 반드시 호출
        verify(apiClient).answerCallbackQuery("cq-id-123");
    }
}
