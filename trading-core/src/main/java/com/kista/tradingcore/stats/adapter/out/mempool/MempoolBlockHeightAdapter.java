package com.kista.tradingcore.stats.adapter.out.mempool;

import com.kista.tradingcore.stats.application.port.output.BlockHeightPort;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// mempool.space 공개 API — 무인증, 최신 블록 높이를 text 숫자 하나로 응답
@Component
class MempoolBlockHeightAdapter implements BlockHeightPort {

    private static final String BASE_URL = "https://mempool.space/api";

    private final RestClient restClient;

    MempoolBlockHeightAdapter(RestClient statsMempoolRestClient) {
        this.restClient = statsMempoolRestClient;
    }

    @Override
    public long currentBtcHeight() {
        String body = restClient.get().uri(BASE_URL + "/blocks/tip/height").retrieve().body(String.class);
        if (body == null || body.isBlank()) throw new IllegalStateException("mempool.space 블록 높이 응답이 비어있습니다");
        return Long.parseLong(body.trim());
    }
}
