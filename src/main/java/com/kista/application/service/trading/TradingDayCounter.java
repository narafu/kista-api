package com.kista.application.service.trading;

import com.kista.domain.port.out.MarketCalendarPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

// VR 첫 사이클 분할 주문에 필요한 남은 미국 거래일 수 계산
@Component
@RequiredArgsConstructor
class TradingDayCounter {

    private final MarketCalendarPort marketCalendarPort; // 미국 시장 개장일 조회

    int countOpenDaysInclusive(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) return 1;
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (marketCalendarPort.isMarketOpen(d)) count++;
        }
        return Math.max(count, 1);
    }
}
