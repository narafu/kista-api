package com.kista.api.benchmark.application.port.output;

import com.kista.api.benchmark.domain.model.IndexPrice;
import com.kista.api.benchmark.domain.model.SplitAdjustment;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IndexPricePort {
    // symbol의 [from, to] 구간 종가 (trade_date 오름차순)
    List<IndexPrice> findBySymbolAndRange(String symbol, LocalDate from, LocalDate to);

    // 저장된 마지막 거래일 — 없으면 empty (lazy backfill 시작점 판단)
    Optional<LocalDate> findMaxTradeDate(String symbol);

    // 같은 (symbol, tradeDate)가 있으면 종가를 덮어쓰고 없으면 추가한다
    void saveAll(List<IndexPrice> prices);

    // symbol의 through 이하 종가에 분할 비율을 곱한다 — 반영 건수 반환
    int applySplit(String symbol, SplitAdjustment adjustment);
}
