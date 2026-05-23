package com.kista.adapter.out.kis;

import com.kista.domain.model.order.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

// KIS API 응답 문자열 파싱 공용 헬퍼 — 패키지 내부 전용
final class KisResponseParser {

    private KisResponseParser() {}

    // KIS 응답 숫자 문자열 → BigDecimal, 빈 값·오류 시 ZERO
    static BigDecimal parseBd(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    // KIS 응답 정수 문자열 → int, "5.0" 같은 소수 형식도 수용 (Double 경유)
    static int parseIntSafe(String s) {
        try { return s == null || s.isBlank() ? 0 : (int) Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    // sll_buy_dvsn_cd: 01=매도, 02=매수
    static Order.OrderDirection parseDirection(String sllBuyDvsnCd) {
        return "01".equals(sllBuyDvsnCd) ? Order.OrderDirection.SELL : Order.OrderDirection.BUY;
    }

    // KIS YYYYMMDD 문자열 → LocalDate, 파싱 실패 시 fallback 반환
    static LocalDate parseDate(String yyyymmdd, LocalDate fallback) {
        if (yyyymmdd == null || yyyymmdd.isBlank()) return fallback;
        try { return LocalDate.parse(yyyymmdd.trim(), DateTimeFormatter.BASIC_ISO_DATE); }
        catch (DateTimeParseException e) { return fallback; }
    }
}
