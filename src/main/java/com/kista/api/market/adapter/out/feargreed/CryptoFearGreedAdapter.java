package com.kista.api.market.adapter.out.feargreed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.api.market.application.port.output.CryptoFearGreedPort;
import com.kista.api.market.domain.model.CryptoFearGreedHistory;
import com.kista.api.market.domain.model.FearGreedRating;
import com.kista.api.market.domain.model.FearGreedSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

// https://api.alternative.me/fng/ — Crypto Fear & Greed Index (일 1회 UTC 00:00 갱신, 2018-02-01부터 이력 제공)
@Slf4j
@Component
@RequiredArgsConstructor
class CryptoFearGreedAdapter implements CryptoFearGreedPort {

    private static final String URL = "https://api.alternative.me/fng/?limit={limit}"; // limit=N이면 최신 N건(최신순)
    private static final String SOURCE = "CRYPTO";

    private final RestClient fearGreedRestClient;

    @Override
    public CryptoFearGreedHistory fetchHistory(int limit) {
        FngResponse response = fearGreedRestClient.get().uri(URL, limit).retrieve().body(FngResponse.class);
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("Crypto Fear & Greed Index API 응답이 비어있음");
        }
        // 응답은 최신순 — 오름차순으로 뒤집어 반환
        List<FearGreedSnapshot> history = response.data().reversed().stream()
                .map(e -> FearGreedSnapshot.of(SOURCE,
                        Instant.ofEpochSecond(Long.parseLong(e.timestamp())), // UTC 00:00 = KST 같은 날 09:00이라 KST 일자 그대로
                        Integer.parseInt(e.value()),
                        FearGreedRating.fromLabel(e.valueClassification())))
                .toList();
        log.debug("Crypto F&G 이력 {}건 (최신 {})", history.size(), history.getLast().snapshotDate());
        // time_until_update는 최신 항목(첫 원소)에만 있다
        return new CryptoFearGreedHistory(history, parseSeconds(response.data().getFirst().timeUntilUpdate()));
    }

    // 초 단위 문자열 → Duration, 없거나 해석 불가면 null (이 필드 때문에 조회가 실패하면 안 된다)
    private static Duration parseSeconds(String seconds) {
        if (seconds == null || seconds.isBlank()) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(seconds.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // alternative.me API 응답 구조
    record FngResponse(@JsonProperty("data") List<FngEntry> data) {}

    record FngEntry(
            @JsonProperty("value") String value,
            @JsonProperty("value_classification") String valueClassification,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("time_until_update") String timeUntilUpdate // 다음 갱신까지 남은 초 — 최신 항목에만 존재
    ) {}
}
