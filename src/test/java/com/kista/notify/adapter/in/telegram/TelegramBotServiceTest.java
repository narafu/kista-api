package com.kista.notify.adapter.in.telegram;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.notify.adapter.out.gateway.TelegramHttpClient;
import com.kista.notify.adapter.out.gateway.TelegramProperties;
import com.kista.notify.application.port.output.PortfolioQueryPort;
import com.kista.user.application.usecase.UserUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

    @Mock TelegramHttpClient telegramHttpClient;
    @Mock PortfolioQueryPort portfolioQueryPort;
    @Mock UserUseCase userUseCase;

    static final TelegramProperties PROPS = new TelegramProperties("admin-token", String.valueOf(12345L));

    TelegramBotService sut;
    static final long CHAT_ID = 12345L;
    static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sut = new TelegramBotService(String.valueOf(CHAT_ID), telegramHttpClient, PROPS, portfolioQueryPort, userUseCase);
        // adminChatId로 userId 조회 — status/history 명령에서만 사용, 다른 테스트에서는 미호출
        lenient().when(userUseCase.findUserIdByTelegramChatId(String.valueOf(CHAT_ID))).thenReturn(Optional.of(USER_ID));
    }

    private TelegramUpdate update(String text) {
        return new TelegramUpdate(1L,
                new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(CHAT_ID), text),
                null);
    }

    private TelegramUpdate callbackUpdate(String data) {
        return new TelegramUpdate(1L, null,
                new TelegramUpdate.CallbackQuery("cb-1", data,
                        new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(CHAT_ID), null)));
    }

    @Test
    void help_command_returns_command_list() {
        sut.handle(update("/help"));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        // 관리자 봇 토큰(PROPS.botToken())이 실제로 전달되는지까지 검증 — any()로만 받으면 토큰 누락/오전달을 못 잡는다
        verify(telegramHttpClient).sendMessage(eq(String.valueOf(CHAT_ID)), captor.capture(), eq("admin-token"));
        assertThat(captor.getValue()).contains("/status").contains("/history");
    }

    @Test
    void status_command_returns_portfolio_info() {
        PortfolioQueryPort.PortfolioCurrentView snap = new PortfolioQueryPort.PortfolioCurrentView(
                StrategyTicker.SOXL, 100, new BigDecimal("25.0000"),
                new BigDecimal("1000.00"), new BigDecimal("26.00"));
        when(portfolioQueryPort.getCurrent(any())).thenReturn(snap);

        sut.handle(update("/status"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramHttpClient).sendMessage(any(), captor.capture(), any());
        assertThat(captor.getValue()).contains("100주");
    }

    @Test
    void status_when_no_snapshot_returns_fallback_message() {
        when(portfolioQueryPort.getCurrent(any())).thenThrow(new NoSuchElementException());

        sut.handle(update("/status"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramHttpClient).sendMessage(any(), captor.capture(), any());
        assertThat(captor.getValue()).contains("데이터가 없습니다");
    }

    @Test
    void history_command_with_days_delegates_to_usecase() {
        when(portfolioQueryPort.getHistory(any(), any(), any(), eq(StrategyTicker.SOXL))).thenReturn(List.of());

        sut.handle(update("/history 14"));

        verify(portfolioQueryPort).getHistory(
                eq(USER_ID), eq(LocalDate.now().minusDays(14)), eq(LocalDate.now()), eq(StrategyTicker.SOXL));
    }

    @Test
    void run_command_returns_v2_info_immediately() {
        sut.handle(update("/run"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramHttpClient).sendMessage(any(), captor.capture(), any());
        // V2에서는 확인 절차 없이 스케쥴러 안내 메시지 즉시 반환
        assertThat(captor.getValue()).contains("스케줄러");
    }

    @Test
    void unauthorized_chatId_is_ignored() {
        TelegramUpdate badUpdate = new TelegramUpdate(1L,
                new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(99999L), "/help"),
                null);

        sut.handle(badUpdate);

        verifyNoInteractions(telegramHttpClient);
    }

    @Test
    void unknown_command_returns_help_hint() {
        sut.handle(update("/unknown"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramHttpClient).sendMessage(any(), captor.capture(), any());
        assertThat(captor.getValue()).contains("/help");
    }

    @Test
    void approve_callback_delegates_to_telegram_approval_usecase() {
        sut.handle(callbackUpdate("approve:" + USER_ID));

        verify(userUseCase).approve(USER_ID);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramHttpClient).sendMessage(eq(String.valueOf(CHAT_ID)), captor.capture(), eq("admin-token"));
        assertThat(captor.getValue()).contains("승인 완료").contains(USER_ID.toString());
        verify(telegramHttpClient).answerCallbackQuery("cb-1", "admin-token");
    }

    @Test
    void reject_callback_delegates_to_telegram_approval_usecase() {
        sut.handle(callbackUpdate("reject:" + USER_ID));

        verify(userUseCase).reject(USER_ID, null);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramHttpClient).sendMessage(eq(String.valueOf(CHAT_ID)), captor.capture(), eq("admin-token"));
        assertThat(captor.getValue()).contains("거절 완료").contains(USER_ID.toString());
        verify(telegramHttpClient).answerCallbackQuery("cb-1", "admin-token");
    }

    @Test
    void unknown_callback_action_is_ignored() {
        sut.handle(callbackUpdate("unknown:" + USER_ID));

        verify(userUseCase, never()).approve(any());
        verify(userUseCase, never()).reject(any(), any());
        verify(telegramHttpClient, never()).sendMessage(any(), any(), any());
    }

}
