package com.kista.broker.adapter.in.web;

import com.kista.broker.application.port.output.CandlePort;
import com.kista.broker.domain.model.toss.TossCandle;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// root market 모듈이 broker.CandlePort를 직접 참조하지 않도록 하는 내부 전용 엔드포인트 —
// com.kista.market.adapter.out.internal.CandleQueryHttpAdapter가 소비
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/broker/candles")
@RequiredArgsConstructor
public class CandleInternalController {

    private final CandlePort candlePort;

    @GetMapping("/latest")
    public List<CandleResponse> latest(@RequestParam String symbol, @RequestParam String interval, @RequestParam int count) {
        return candlePort.getLatestCandles(symbol, interval, count).stream()
                .map(CandleResponse::from)
                .toList();
    }

    // TossCandle(broker 도메인 타입) 직접 반환 금지 — 전용 Response DTO, 필드 shape byte-identical
    record CandleResponse(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        static CandleResponse from(TossCandle candle) {
            return new CandleResponse(candle.date(), candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
        }
    }
}
