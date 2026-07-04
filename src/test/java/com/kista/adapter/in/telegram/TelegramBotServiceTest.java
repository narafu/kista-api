package com.kista.adapter.in.telegram;

import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.PortfolioUseCase;
import com.kista.domain.port.in.UserUseCase;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramBotServiceTest {

    @Mock TelegramApiClient apiClient;
    @Mock PortfolioUseCase portfolioUseCase;
    @Mock UserUseCase userUseCase;

    TelegramBotService sut;
    static final long CHAT_ID = 12345L;
    static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sut = new TelegramBotService(String.valueOf(CHAT_ID), apiClient, portfolioUseCase, userUseCase);
        // adminChatId로 userId 조회 — status/history 명령에서만 사용, 다른 테스트에서는 미호출
        lenient().when(userUseCase.findUserIdByTelegramChatId(String.valueOf(CHAT_ID))).thenReturn(Optional.of(USER_ID));
    }

    private TelegramUpdate update(String text) {
        return new TelegramUpdate(1L,
                new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(CHAT_ID), text),
                null);
    }

    @Test
    void help_command_returns_command_list() {
        sut.handle(update("/help"));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(eq(String.valueOf(CHAT_ID)), captor.capture());
        assertThat(captor.getValue()).contains("/status").contains("/history");
    }

    @Test
    void status_command_returns_portfolio_info() {
        CyclePositionHistoryEntry snap = new CyclePositionHistoryEntry(
                UUID.randomUUID(), Ticker.SOXL,
                new BigDecimal("1000.00"), new BigDecimal("26.00"),
                new BigDecimal("25.0000"), 100, Instant.now());
        when(portfolioUseCase.getCurrent(any())).thenReturn(snap);

        sut.handle(update("/status"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("100주");
    }

    @Test
    void status_when_no_snapshot_returns_fallback_message() {
        when(portfolioUseCase.getCurrent(any())).thenThrow(new NoSuchElementException());

        sut.handle(update("/status"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("데이터가 없습니다");
    }

    @Test
    void history_command_with_days_delegates_to_usecase() {
        when(portfolioUseCase.getHistory(any(), any(), any(), eq(Ticker.SOXL))).thenReturn(List.of());

        sut.handle(update("/history 14"));

        verify(portfolioUseCase).getHistory(
                eq(USER_ID), eq(LocalDate.now().minusDays(14)), eq(LocalDate.now()), eq(Ticker.SOXL));
    }

    @Test
    void run_command_returns_v2_info_immediately() {
        sut.handle(update("/run"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        // V2에서는 확인 절차 없이 스케쥴러 안내 메시지 즉시 반환
        assertThat(captor.getValue()).contains("스케줄러");
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

}
