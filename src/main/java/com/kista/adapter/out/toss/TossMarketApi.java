package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossMarketSession.SessionHours;
import com.kista.domain.port.out.TossAccountListPort;
import com.kista.domain.port.out.TossMarketCalendarPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

// Toss market-calendar API 스펙 (openapi.json 검증):
// - GET /api/v1/market-calendar/US?date={YYYY-MM-DD} — 단건 조회 (date 미지정 시 오늘)
// - response: { "result": { today: { date, dayMarket, preMarket, regularMarket, afterMarket } } } — Toss 공통 {"result": {...}} 래퍼
//   각 세션: { startTime(ISO8601), endTime(ISO8601) }
// - isOpen boolean 없음 — regularMarket != null 이면 개장일로 판단
// 범위 조회(from~to)는 날짜별 루프로 처리, 최대 30일 제한
@Slf4j
@Component
@RequiredArgsConstructor
public class TossMarketApi implements TossMarketCalendarPort, TossAccountListPort {

    private static final String MARKET_CALENDAR_PATH = "/api/v1/market-calendar/US";
    private static final String ACCOUNTS_PATH = "/api/v1/accounts";
    // 범위 조회 최대 일수 — 초과 시 IllegalArgumentException(→ 400)
    private static final int MAX_RANGE_DAYS = 30;

    private final TossHttpClient tossHttpClient;

    // ── TossMarketCalendarPort ─────────────────────────────────────────────────

    @Override
    public List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to) {
        long days = from.until(to).getDays() + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("market-calendar 조회는 최대 " + MAX_RANGE_DAYS + "일 범위만 지원합니다");
        }
        List<TossMarketSession> sessions = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            sessions.add(fetchSession(date));
        }
        return sessions;
    }

    private TossMarketSession fetchSession(LocalDate date) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("date", date.toString()); // YYYY-MM-DD

        // 공통 API — 관리자 토큰 사용, 응답 {"result": {"today": {...}}} 래퍼 구조
        MarketCalendarResponseWrapper wrapper = tossHttpClient.getCommon(
                MARKET_CALENDAR_PATH, params, MarketCalendarResponseWrapper.class);
        MarketCalendarResponse response = wrapper != null ? wrapper.result() : null;

        if (response == null || response.today() == null) {
            log.warn("Toss market-calendar 응답 없음: date={}", date);
            // 응답 없으면 해당 날짜 휴장으로 처리
            return new TossMarketSession(date, null, null, null);
        }
        MarketDay today = response.today();
        return new TossMarketSession(
                date,
                toSessionHours(today.preMarket()),
                toSessionHours(today.regularMarket()),
                toSessionHours(today.afterMarket())
        );
    }

    private SessionHours toSessionHours(SessionWindow w) {
        if (w == null || w.startTime() == null) return null;
        return new SessionHours(
                OffsetDateTime.parse(w.startTime()),
                OffsetDateTime.parse(w.endTime())
        );
    }

    // ── TossAccountListPort ────────────────────────────────────────────────────

    @Override
    public List<TossAccountInfo> getAccountList(Account account) {
        AccountsResponse response = tossHttpClient.getNoAccountHeader(
                ACCOUNTS_PATH, account, new LinkedMultiValueMap<>(), AccountsResponse.class);

        if (response == null || response.result() == null) {
            log.warn("Toss 계좌 목록 응답 없음");
            return List.of();
        }
        return response.result().stream()
                .map(a -> new TossAccountInfo(a.accountSeq(), a.accountNo()))
                .toList();
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

    // GET /api/v1/market-calendar/US 응답 최상위 래퍼 — { "result": { today: {...} } }
    record MarketCalendarResponseWrapper(
        @JsonProperty("result") MarketCalendarResponse result
    ) {}

    record MarketCalendarResponse(
        @JsonProperty("today") MarketDay today
    ) {}

    record MarketDay(
        @JsonProperty("date")          String        date,
        @JsonProperty("preMarket")     SessionWindow preMarket,
        @JsonProperty("regularMarket") SessionWindow regularMarket,
        @JsonProperty("afterMarket")   SessionWindow afterMarket
    ) {}

    record SessionWindow(
        @JsonProperty("startTime") String startTime,  // ISO 8601
        @JsonProperty("endTime")   String endTime     // ISO 8601
    ) {}

    // GET /api/v1/accounts 응답 래퍼
    record AccountsResponse(
        @JsonProperty("result") List<AccountItem> result
    ) {}

    record AccountItem(
        @JsonProperty("accountSeq") int    accountSeq,
        @JsonProperty("accountNo")  String accountNo
    ) {}
}
