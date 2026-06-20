package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.port.out.TossAccountListPort;
import com.kista.domain.port.out.TossMarketCalendarPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossMarketApi implements TossMarketCalendarPort, TossAccountListPort {

    // Toss 해외 장 운영 정보 API 경로 (미국)
    private static final String MARKET_CALENDAR_PATH = "/api/v1/market-calendar/US";
    // Toss 계좌 목록 API 경로
    private static final String ACCOUNTS_PATH = "/api/v1/accounts";
    // Toss API 날짜 파라미터 포맷
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TossHttpClient tossHttpClient;

    // ── TossMarketCalendarPort ─────────────────────────────────────────────────

    @Override
    public List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to, Account account) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("from", from.format(DATE_FMT));
        params.add("to",   to.format(DATE_FMT));

        // 장 운영 정보는 계좌 컨텍스트 불필요
        MarketCalendarResponse response = tossHttpClient.getNoAccountHeader(
                MARKET_CALENDAR_PATH, account, params, MarketCalendarResponse.class);

        if (response == null || response.result() == null) {
            log.warn("Toss 장 운영 정보 응답 없음: from={}, to={}", from, to);
            return List.of();
        }
        return response.result().stream()
                .map(s -> new TossMarketSession(
                        LocalDate.parse(s.date(), DATE_FMT),
                        Boolean.TRUE.equals(s.isOpen())
                ))
                .toList();
    }

    // ── TossAccountListPort ────────────────────────────────────────────────────

    @Override
    public List<TossAccountInfo> getAccountList(Account account) {
        // 계좌 헤더 없이 Bearer 토큰만으로 조회 (계좌 선택 전 단계)
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

    record MarketCalendarResponse(
        @JsonProperty("result") List<SessionItem> result
    ) {}

    record SessionItem(
        @JsonProperty("date")   String  date,   // YYYYMMDD
        @JsonProperty("isOpen") Boolean isOpen  // 개장 여부
    ) {}

    record AccountsResponse(
        @JsonProperty("result") List<AccountItem> result
    ) {}

    record AccountItem(
        @JsonProperty("accountSeq") int    accountSeq, // 계좌 일련번호
        @JsonProperty("accountNo")  String accountNo   // 계좌번호
    ) {}
}
