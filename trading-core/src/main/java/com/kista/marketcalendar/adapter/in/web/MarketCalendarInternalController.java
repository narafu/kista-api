package com.kista.marketcalendar.adapter.in.web;

import com.kista.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.marketcalendar.domain.model.MarketSessionSnapshot;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

// root market 모듈(공포탐욕지수)이 marketcalendar 값을 직접 참조하지 않도록 하는 내부 전용 엔드포인트 —
// com.kista.market.adapter.out.internal.MarketCalendarQueryHttpAdapter가 소비
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/marketcalendar")
@RequiredArgsConstructor
public class MarketCalendarInternalController {

    private final MarketCalendarPort marketCalendarPort;

    @GetMapping("/holidays")
    public List<LocalDate> holidays(@RequestParam int year, @RequestParam int month) {
        return marketCalendarPort.findHolidaysForMonth(year, month);
    }

    @GetMapping("/is-open")
    public boolean isOpen(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return marketCalendarPort.isMarketOpen(date);
    }

    @GetMapping("/session")
    public SessionResponse session() {
        MarketSessionSnapshot snapshot = MarketSessionSnapshot.now();
        return new SessionResponse(snapshot.session().name(), snapshot.isDst());
    }

    record SessionResponse(String session, boolean isDst) {}
}
