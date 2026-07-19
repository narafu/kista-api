package com.kista.domain.port.out;

import com.kista.domain.model.stats.IndexPrice;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IndexPricePort {
    // symbol의 [from, to] 구간 종가 (trade_date 오름차순)
    List<IndexPrice> findBySymbolAndRange(String symbol, LocalDate from, LocalDate to);

    // 저장된 마지막 거래일 — 없으면 empty (lazy backfill 시작점 판단)
    Optional<LocalDate> findMaxTradeDate(String symbol);

    void saveAll(List<IndexPrice> prices);
}
