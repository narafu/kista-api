package com.kista.api.market.adapter.in.web.dto;

import com.kista.shared.contract.broker.DailyCandleResponse;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.tradingcore.matching.domain.model.DcaIndicators;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record TossCandleResponse(
    @Schema(description = "기준일")
    LocalDate date,    // 기준일
    @Schema(description = "시가")
    BigDecimal open,   // 시가
    @Schema(description = "고가")
    BigDecimal high,   // 고가
    @Schema(description = "저가")
    BigDecimal low,    // 저가
    @Schema(description = "종가")
    BigDecimal close,  // 종가
    @Schema(description = "거래량")
    long volume,       // 거래량
    @Schema(description = "단순이동평균 20일 (해당 캔들까지 종가 평균, 부족 시 null, 소수 둘째 자리). 이 응답의 RAW 종가로 DCA 판정과 같은 코드(DcaIndicators)로 계산 — 분할·배당 조정 종가를 쓰는 DCA 판정값과 다를 수 있음")
    @Nullable BigDecimal sma20,
    @Schema(description = "단순이동평균 50일 (부족 시 null). 이 응답의 RAW 종가로 DCA 판정과 같은 코드(DcaIndicators)로 계산 — 분할·배당 조정 종가를 쓰는 DCA 판정값과 다를 수 있음")
    @Nullable BigDecimal sma50,
    @Schema(description = "단순이동평균 120일 (부족 시 null). 이 응답의 RAW 종가로 DCA 판정과 같은 코드(DcaIndicators)로 계산 — 분할·배당 조정 종가를 쓰는 DCA 판정값과 다를 수 있음")
    @Nullable BigDecimal sma120,
    @Schema(description = "단순이동평균 200일 (부족 시 null). 이 응답의 RAW 종가로 DCA 판정과 같은 코드(DcaIndicators)로 계산 — 분할·배당 조정 종가를 쓰는 DCA 판정값과 다를 수 있음")
    @Nullable BigDecimal sma200,
    @Schema(description = "RSI(14, Wilder) — 해당 캔들 포함 최근 250개 종가 창 기준 (부족 시 null). 이 응답의 RAW 종가로 DCA 판정과 같은 코드(DcaIndicators)로 계산 — 분할·배당 조정 종가를 쓰는 DCA 판정값과 다를 수 있음")
    @Nullable BigDecimal rsi14
) {
    // 일봉 목록 → 지표 포함 응답 — SMA·RSI는 DCA 판정이 쓰는 DcaIndicators 그대로 호출
    // ponytail: 캔들마다 부분 리스트로 재계산하는 O(n·창) — 최대 600개라 충분, 느려지면 증분 계산으로
    public static List<TossCandleResponse> fromList(List<DailyCandleResponse> candles) {
        List<BigDecimal> closes = candles.stream().map(DailyCandleResponse::close).toList();
        List<Integer> periods = DcaIndicator.SMA_PERIODS; // [20, 50, 120, 200] — sma20..sma200 필드 순서와 대응
        List<TossCandleResponse> result = new ArrayList<>(candles.size());
        for (int i = 0; i < candles.size(); i++) {
            DailyCandleResponse c = candles.get(i);
            List<BigDecimal> upTo = closes.subList(0, i + 1);
            // RSI는 시작점에 민감 — DCA 판정과 같은 길이의 최근 창으로 계산
            List<BigDecimal> window = closes.subList(Math.max(0, i + 1 - DcaIndicators.CLOSE_WINDOW), i + 1);
            result.add(new TossCandleResponse(c.date(), c.open(), c.high(), c.low(), c.close(), c.volume(),
                    sma(upTo, periods.get(0)), sma(upTo, periods.get(1)), sma(upTo, periods.get(2)), sma(upTo, periods.get(3)),
                    DcaIndicators.rsiWilder(window, DcaIndicators.RSI_PERIOD).orElse(null)));
        }
        return result;
    }

    private static @Nullable BigDecimal sma(List<BigDecimal> closes, int period) {
        return DcaIndicators.sma(closes, period).orElse(null);
    }
}
