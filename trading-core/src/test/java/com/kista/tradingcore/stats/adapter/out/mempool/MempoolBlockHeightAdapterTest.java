package com.kista.tradingcore.stats.adapter.out.mempool;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MempoolBlockHeightAdapterTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final MempoolBlockHeightAdapter adapter = new MempoolBlockHeightAdapter(builder.build());

    @Test
    void text_숫자_응답을_trim해_블록_높이로_파싱한다() {
        server.expect(requestTo("https://mempool.space/api/blocks/tip/height"))
                .andRespond(withSuccess("972175\n", MediaType.TEXT_PLAIN));

        assertThat(adapter.currentBtcHeight()).isEqualTo(972_175L);
        server.verify();
    }

    @Test
    void 빈_응답이면_실패한다() {
        server.expect(requestTo("https://mempool.space/api/blocks/tip/height"))
                .andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        assertThatThrownBy(adapter::currentBtcHeight).isInstanceOf(IllegalStateException.class);
    }
}
