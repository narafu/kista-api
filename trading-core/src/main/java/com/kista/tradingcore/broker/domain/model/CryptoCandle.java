package com.kista.tradingcore.broker.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 완성된 코인 일봉 — kstDate는 봉 시작일(KST 09:00 시작, 다음날 09:00 마감), close는 마감 종가
public record CryptoCandle(LocalDate kstDate, BigDecimal close) {

    // 지금 시점에 완성된 마지막 봉의 시작일 — KST 09:00(= UTC 00:00) 경계, 바이낸스 1d·업비트 공통
    public static LocalDate latestCompletedKstDate(LocalDateTime nowKst) {
        return nowKst.getHour() >= 9 ? nowKst.toLocalDate().minusDays(1) : nowKst.toLocalDate().minusDays(2);
    }
}
