package com.kista.notify.adapter.out.gateway;

import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.platform.telegram.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

// HTTP 전송 상세(URL·빈 토큰 가드·오류 삼킴)는 platform TelegramHttpClientTest가 검증 — 여기선 어댑터의 위임 인자만 확인한다
@ExtendWith(MockitoExtension.class)
class TelegramAdapterTest {

    @Mock TelegramHttpClient telegramHttpClient;

    TelegramAdapter adapter;

    static final TelegramProperties PROPS =
            new TelegramProperties("test-token", "chat-123");

    @BeforeEach
    void setUp() {
        adapter = new TelegramAdapter(telegramHttpClient, PROPS);
    }

    @Test
    void notifyInfo_관리자_채팅방과_봇_토큰으로_메시지를_그대로_전송한다() {
        adapter.notifyInfo("스케쥴러 시작");

        verify(telegramHttpClient).sendMessage("chat-123", "스케쥴러 시작", "test-token");
    }

    @Test
    void notifyError_예외_메시지를_관리자_알림_문구로_감싸_전송한다() {
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        adapter.notifyError(new RuntimeException("KIS API 호출 실패"));

        verify(telegramHttpClient).sendMessage(org.mockito.ArgumentMatchers.eq("chat-123"), textCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("test-token"));
        assertThat(textCaptor.getValue())
                .contains("⚠️ 관리자 알림")
                .contains("KIS API 호출 실패");
    }
}
