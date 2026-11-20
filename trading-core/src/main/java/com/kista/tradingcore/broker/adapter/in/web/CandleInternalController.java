package com.kista.tradingcore.broker.adapter.in.web;

import com.kista.tradingcore.broker.adapter.out.marketdata.MarketDataClient;
import com.kista.shared.contract.broker.DailyCandleResponse;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.DailyCandleQuery;
import com.kista.tradingcore.broker.domain.model.DailyCandleQuery.Adjustment;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

// root market 모듈이 broker 시세 클라이언트를 직접 참조하지 않도록 하는 내부 전용 엔드포인트 —
// com.kista.api.market.adapter.out.internal.CandleQueryHttpAdapter가 소비
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/broker/candles")
@RequiredArgsConstructor
public class CandleInternalController {

    private static final int MAX_LATEST_COUNT = 600; // 공개 엔드포인트가 부르는 일봉 개수 상한 — 캐시 키·Alpaca 호출 폭주 방지

    private final MarketDataClient marketData;

    @GetMapping("/latest")
    public List<DailyCandleResponse> latest(@RequestParam String symbol, @RequestParam int count) {
        return marketData.dailyCandles(symbol, DailyCandleQuery.latest(Math.clamp(count, 1, MAX_LATEST_COUNT), Adjustment.RAW)).stream()
                .map(CandleInternalController::toResponse)
                .toList();
    }

    // from~to(양끝 포함) 기간 조회 — 차트는 조정 없는 원시가(RAW). 일봉만 지원
    @GetMapping("/range")
    public List<DailyCandleResponse> range(@RequestParam String symbol,
                                           @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return marketData.dailyCandles(symbol, DailyCandleQuery.range(from, to, Adjustment.RAW)).stream()
                .map(CandleInternalController::toResponse)
                .toList();
    }

    // BrokerCandle(broker 도메인 타입) 직접 반환 금지 — contract 응답으로 명시 매핑
    private static DailyCandleResponse toResponse(BrokerCandle candle) {
        return new DailyCandleResponse(candle.date(), candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }
}
