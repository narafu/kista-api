package com.kista.domain.port.out;

import com.kista.domain.model.toss.TossCandle;

import java.time.LocalDate;
import java.util.List;

// 공통 API — 개별 계좌 토큰 불필요, 관리자 자격증명으로 조회. 종목코드만 받으므로 Strategy 도메인과 무관
public interface TosCandlePort {
    // GET /api/v1/candles — interval: "1d"(일봉)만 openapi.json 검증됨. "1w" 등 미지원
    List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to);

    // 최신 캔들 N개 조회 — count는 토스 API 1회 호출 최대치인 200으로 clamp
    List<TossCandle> getLatestCandles(String symbol, String interval, int count);
}
