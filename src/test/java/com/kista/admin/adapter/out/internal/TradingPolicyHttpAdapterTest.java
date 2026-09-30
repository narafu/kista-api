package com.kista.admin.adapter.out.internal;

import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TradingPolicySettings;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingPolicyHttpAdapterTest {

    private static final String POLICY_JSON = """
            {
              "brokers":{"KIS":{"enabled":true},"TOSS":{"enabled":false},"MOCK":{"enabled":true}},
              "strategies":{
                "INFINITE":{"enabled":true,"ticker":{"customizable":true,"allowedValues":["MAGX","USD","TQQQ","SOXL"],"defaultValue":"SOXL"},"divisionCount":{"customizable":true,"allowedValues":[20,30,40],"defaultValue":20}},
                "PRIVACY":{"enabled":false,"ticker":{"customizable":false,"allowedValues":["SOXL"],"defaultValue":"SOXL"}},
                "VR":{"enabled":true,"ticker":{"customizable":false,"allowedValues":["TQQQ"],"defaultValue":"TQQQ"},"recurringMode":{"customizable":true,"allowedValues":["DEPOSIT","HOLD","WITHDRAW"],"defaultValue":"HOLD"},"bandWidth":{"customizable":true,"allowedValues":[10,15,20],"defaultValue":15},"intervalWeeks":{"customizable":true,"allowedValues":[1,2,4],"defaultValue":2}}
              }
            }
            """;

    private MockWebServer server;
    private TradingPolicyHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).build();
        adapter = new TradingPolicyHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void load_역직렬화된_정책을_반환한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body(POLICY_JSON).build());

        TradingPolicySettings settings = adapter.load();

        assertThat(settings.brokerEnabled(Broker.TOSS)).isFalse();
        assertThat(settings.strategy(StrategyType.PRIVACY).enabled()).isFalse();
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getTarget()).isEqualTo("/api/internal/trading/policy-settings");
    }

    @Test
    void replace_PUT으로_전체_정책을_보내고_응답을_반환한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body(POLICY_JSON).build());

        TradingPolicySettings saved = adapter.replace(TradingPolicySettings.defaults());

        assertThat(saved.brokerEnabled(Broker.TOSS)).isFalse();
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("PUT");
        assertThat(recorded.getTarget()).isEqualTo("/api/internal/trading/policy-settings");
        assertThat(recorded.getBody().utf8()).contains("\"brokers\"").contains("\"strategies\"");
    }

    @Test
    void replace_400이면_IllegalArgumentException으로_되돌린다() {
        server.enqueue(new MockResponse.Builder()
                .code(400).addHeader("Content-Type", "application/problem+json")
                .body("{\"detail\":\"broker settings must contain every known key\"}").build());

        assertThatThrownBy(() -> adapter.replace(TradingPolicySettings.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every known key");
    }

    // 배포 전환기 — 옛 trading-core(엔드포인트 없음)면 조회는 기본 정책으로 내려간다
    @Test
    void load_404이면_기본_정책으로_대체한다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThat(adapter.load()).isEqualTo(TradingPolicySettings.defaults());
    }

    @Test
    void replace_404이면_명확한_메시지의_IllegalStateException을_던진다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> adapter.replace(TradingPolicySettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kista-trading 배포 후");
    }
}
