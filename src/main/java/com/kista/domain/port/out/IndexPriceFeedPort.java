package com.kista.domain.port.out;

import com.kista.domain.model.stats.IndexPrice;

import java.time.LocalDate;
import java.util.List;

// 외부 시세 제공자에서 지수 일별 종가 조회 (Alpaca)
public interface IndexPriceFeedPort {
    List<IndexPrice> fetchDailyCloses(String symbol, LocalDate from, LocalDate to);
}
