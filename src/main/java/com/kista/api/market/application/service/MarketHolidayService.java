package com.kista.api.market.application.service;

import com.kista.api.market.application.port.output.CandleQueryPort;
import com.kista.api.market.application.port.output.MarketCalendarQueryPort;
import com.kista.shared.contract.broker.DailyCandleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

// 미국 시장 캘린더·시세 조회 (계좌 무관 공용)
@Service
@RequiredArgsConstructor
public class MarketHolidayService {

    private static final Pattern SYMBOL_FORMAT = Pattern.compile("^[A-Z][A-Z0-9.]{0,9}$"); // 단일 종목 코드 — trading-core MarketDataClient와 같은 형식
    private static final int MAX_RANGE_YEARS = 5; // 기간 캔들 조회 최대 기간

    private final MarketCalendarQueryPort marketCalendarQueryPort; // 휴장일 조회 (marketcalendar 내부API)
    private final CandleQueryPort candleQueryPort;                 // Toss 캔들 조회 (broker 내부API)

    public List<LocalDate> getMonthlyHolidays(int year, int month) {
        return marketCalendarQueryPort.findHolidaysForMonth(year, month);
    }

    // 일봉만 지원 — count는 1~600으로 clamp (trading-core가 200봉 단위 페이징)
    public List<DailyCandleResponse> getDailyCandles(String symbol, int count) {
        requireSymbol(symbol);
        return candleQueryPort.latestDailyCandles(symbol, count);
    }

    // from~to(양끝 포함) 일봉 — 200봉 한도 없음
    public List<DailyCandleResponse> getDailyCandles(String symbol, LocalDate from, LocalDate to) {
        requireSymbol(symbol);
        // 역전 기간은 400 (ProblemDetailExceptionHandler의 IllegalArgumentException 매핑)
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        // 공개 엔드포인트 — 기간 상한으로 요청당 Toss 페이징 호출 수를 묶는다(5년 ≈ 7회)
        if (from.isBefore(to.minusYears(MAX_RANGE_YEARS)))
            throw new IllegalArgumentException("조회 기간은 최대 " + MAX_RANGE_YEARS + "년입니다.");
        return candleQueryPort.dailyCandles(symbol, from, to);
    }

    // 공개 엔드포인트 — 다종목 주입(콤마 등)을 trading-core로 넘기기 전에 400으로 막는다
    private static void requireSymbol(String symbol) {
        if (symbol == null || !SYMBOL_FORMAT.matcher(symbol).matches()) throw new IllegalArgumentException("종목 코드 형식이 올바르지 않습니다.");
    }
}
