package com.kista.tradingcore.stats.adapter.out.mempool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.stats.application.port.output.BlockHeightPort;
import com.kista.tradingcore.stats.application.port.output.HashrateHistoryPort;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeMap;

// mempool.space 공개 API — 무인증, 최신 블록 높이(text 숫자)와 해시레이트 이력
@Component
class MempoolBlockHeightAdapter implements BlockHeightPort, HashrateHistoryPort {

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

    @Override
    public List<DatedValue> dailyHashratesEh() {
        HashrateResponse res = restClient.get().uri(BASE_URL + "/v1/mining/hashrate/2y").retrieve().body(HashrateResponse.class);
        if (res == null || res.hashrates() == null || res.hashrates().isEmpty()) {
            throw new IllegalStateException("mempool.space 해시레이트 응답이 비어있습니다");
        }
        // H/s → EH/s(10^18), 소수 2자리
        // 같은 UTC 날짜는 마지막 점만 남긴다(포트의 하루 한 값 계약)
        TreeMap<LocalDate, BigDecimal> byDate = new TreeMap<>();
        for (HashratePoint h : res.hashrates()) {
            byDate.put(Instant.ofEpochSecond(h.timestamp()).atZone(ZoneOffset.UTC).toLocalDate(),
                    BigDecimal.valueOf(h.avgHashrate()).movePointLeft(18).setScale(2, RoundingMode.HALF_UP));
        }
        return byDate.entrySet().stream().map(e -> new DatedValue(e.getKey(), e.getValue())).toList();
    }

    record HashrateResponse(@JsonProperty("hashrates") List<HashratePoint> hashrates) {}

    record HashratePoint(@JsonProperty("timestamp") long timestamp, @JsonProperty("avgHashrate") double avgHashrate) {}
}
