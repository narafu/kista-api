package com.kista.broker.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.toss.TossAccountInfo;
import com.kista.broker.domain.model.toss.TossMarketSession;
import com.kista.broker.domain.model.toss.TossMarketSession.SessionHours;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Toss market-calendar API 스펙 (openapi.json 검증):
// - GET /api/v1/market-calendar/US?date={YYYY-MM-DD} — 단건 조회 (date 미지정 시 오늘)
// - response: { "result": { today: { date, dayMarket, preMarket, regularMarket, afterMarket } } } — Toss 공통 {"result": {...}} 래퍼
//   각 세션: { startTime(ISO8601), endTime(ISO8601) }
// - isOpen boolean 없음 — regularMarket != null 이면 개장일로 판단
// 범위 조회(from~to)는 날짜별 루프로 처리, 최대 30일 제한
@Slf4j
@Component
@RequiredArgsConstructor
class TossMarketApi {

    private static final String MARKET_CALENDAR_PATH = "/api/v1/market-calendar/US";
    private static final String ACCOUNTS_PATH = "/api/v1/accounts";
    // 범위 조회 최대 일수 — 초과 시 IllegalArgumentException(→ 400)
    private static final int MAX_RANGE_DAYS = 30;

    private final TossHttpClient tossHttpClient;
    // 날짜별 캐시 — 과거(미국 동부 기준 오늘 이전) 확정 날짜는 영구, 오늘·미래는 15분 TTL (Spring bean 아님, PrevCloseCache 스타일)
    private final TossMarketCalendarCache calendarCache = new TossMarketCalendarCache(Duration.ofMinutes(15), Instant::now);

    // ── TossMarketCalendarPort ─────────────────────────────────────────────────

    public List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to) {
        long days = from.until(to).getDays() + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("market-calendar 조회는 최대 " + MAX_RANGE_DAYS + "일 범위만 지원합니다");
        }
        List<TossMarketSession> sessions = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            LocalDate current = date; // 람다 캡처용 final 복사
            sessions.add(calendarCache.getOrFetch(current, () -> fetchSession(current))
                    .orElseGet(() -> {
                        log.warn("Toss market-calendar 응답 없음: date={}", current);
                        // 응답 없으면 해당 날짜 휴장으로 처리 — 캐싱하지 않음(일시 장애 영구오염 방지)
                        return new TossMarketSession(current, null, null, null);
                    }));
        }
        return sessions;
    }

    private Optional<TossMarketSession> fetchSession(LocalDate date) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("date", date.toString()); // YYYY-MM-DD

        // 공통 API — 관리자 토큰 사용, TossResult<MarketCalendarResponse> 제네릭 래퍼 구조
        TossResult<MarketCalendarResponse> wrapper = tossHttpClient.getCommon(
                MARKET_CALENDAR_PATH, params,
                new ParameterizedTypeReference<TossResult<MarketCalendarResponse>>() {});
        MarketCalendarResponse response = wrapper != null ? wrapper.result() : null;

        if (response == null || response.today() == null) {
            return Optional.empty();
        }
        MarketDay today = response.today();
        return Optional.of(new TossMarketSession(
                date,
                toSessionHours(today.preMarket()),
                toSessionHours(today.regularMarket()),
                toSessionHours(today.afterMarket())
        ));
    }

    private SessionHours toSessionHours(SessionWindow w) {
        if (w == null || w.startTime() == null) return null;
        return new SessionHours(
                OffsetDateTime.parse(w.startTime()),
                OffsetDateTime.parse(w.endTime())
        );
    }

    // ── TossAccountListPort ────────────────────────────────────────────────────

    public List<TossAccountInfo> getAccountList(Account account) {
        TossResult<List<AccountItem>> wrapper = tossHttpClient.getNoAccountHeader(
                ACCOUNTS_PATH, account, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<TossResult<List<AccountItem>>>() {});
        List<AccountItem> result = wrapper != null ? wrapper.result() : null;
        if (result == null) {
            log.warn("Toss 계좌 목록 응답 없음");
            return List.of();
        }
        return result.stream()
                .map(a -> new TossAccountInfo(a.accountSeq(), a.accountNo()))
                .toList();
    }

    // ── 내부 응답 record ──────────────────────────────────────────────────────

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

    record AccountItem(
        @JsonProperty("accountSeq") int    accountSeq,
        @JsonProperty("accountNo")  String accountNo
    ) {}
}
