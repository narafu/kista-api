package com.kista.platform.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramHttpClientTest {

    MockRestServiceServer server;
    TelegramHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TelegramHttpClient(builder.build());
    }

    @Test
    void sendMessage_텔레그램_sendMessage_엔드포인트로_전송한다() {
        server.expect(requestTo("https://api.telegram.org/bottok/sendMessage"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"chat_id\":\"c1\"")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.sendMessage("c1", "hi", "tok");

        server.verify();
    }

    @Test
    void sendMessage_빈_토큰이면_호출하지_않는다() {
        client.sendMessage("c1", "hi", " ");

        server.verify(); // 기대 요청 0건 — 호출이 있었다면 실패
    }

    @Test
    void sendWithInlineKeyboard_reply_markup을_포함한다() {
        server.expect(requestTo("https://api.telegram.org/bottok/sendMessage"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("inline_keyboard")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.sendWithInlineKeyboard("c1", "hi", "tok", List.of(Map.of("text", "ok", "callback_data", "a:b")));

        server.verify();
    }

    @Test
    void 전송_실패는_예외를_삼킨다() {
        server.expect(requestTo("https://api.telegram.org/bottok/sendMessage")).andRespond(withServerError());

        client.sendMessage("c1", "hi", "tok"); // 예외 전파 없음

        server.verify();
    }

    @Test
    void getBotUsername_성공_시_username을_반환한다() {
        server.expect(requestTo("https://api.telegram.org/bottok/getMe"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"username\":\"kista_bot\"}}", MediaType.APPLICATION_JSON));

        assertThat(client.getBotUsername("tok")).isEqualTo("kista_bot");
    }

    @Test
    void getBotUsername_ok가_아니면_IllegalArgumentException() {
        server.expect(requestTo("https://api.telegram.org/bottok/getMe"))
                .andRespond(withSuccess("{\"ok\":false}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getBotUsername("tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("유효하지 않은 Bot Token입니다");
    }

    @Test
    void getBotUsername_HTTP_오류는_IllegalArgumentException으로_변환한다() {
        server.expect(requestTo("https://api.telegram.org/bottok/getMe")).andRespond(withServerError());

        assertThatThrownBy(() -> client.getBotUsername("tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("유효하지 않은 Bot Token입니다: ");
    }

    @Test
    void getBotUsername_예외_메시지의_봇_토큰을_마스킹한다() {
        String token = "123456:SECRET-token";
        // 전송 계층 I/O 오류 — Spring이 요청 URL(토큰 포함)을 예외 메시지에 넣는 상황을 재현
        server.expect(requestTo("https://api.telegram.org/bot" + token + "/getMe"))
                .andRespond(request -> {
                    throw new java.io.IOException("connect failed: https://api.telegram.org/bot" + token + "/getMe");
                });

        assertThatThrownBy(() -> client.getBotUsername(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("유효하지 않은 Bot Token입니다: ")
                .hasMessageContaining("/bot***")
                .hasMessageNotContaining(token);
    }

    @Test
    void getBotUsername_result가_없으면_IllegalArgumentException() {
        server.expect(requestTo("https://api.telegram.org/bottok/getMe"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getBotUsername("tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("봇 username을 가져올 수 없습니다");
    }
}
