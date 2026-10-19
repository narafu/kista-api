package com.kista.tradingcore.stats.application.port.output;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

// 과거 USD/KRW 환율 조회 포트 (공식 기준환율, 영업일만 존재)
public interface HistoricalFxRatePort {

    // 기간 내 영업일별 USD→KRW 환율 원본 (휴일은 키가 없다, 채우기는 호출자 책임)
    Map<LocalDate, BigDecimal> getUsdKrwRates(LocalDate from, LocalDate to);
}
