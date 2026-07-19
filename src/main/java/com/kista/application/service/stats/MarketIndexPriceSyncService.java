package com.kista.application.service.stats;

import com.kista.common.TimeZones;
import com.kista.domain.model.stats.EtfBenchmarkSymbol;
import com.kista.domain.model.stats.IndexPrice;
import com.kista.domain.port.in.SyncMarketIndexPricesUseCase;
import com.kista.domain.port.out.IndexPriceFeedPort;
import com.kista.domain.port.out.IndexPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
class MarketIndexPriceSyncService implements SyncMarketIndexPricesUseCase {

    private static final LocalDate EARLIEST_INDEX_DATE = LocalDate.of(2000, 1, 1);

    private final IndexPricePort indexPricePort;
    private final IndexPriceFeedPort indexPriceFeedPort;

    @Override
    public void syncAndSave() {
        List<String> failed = new ArrayList<>();
        for (EtfBenchmarkSymbol symbol : EtfBenchmarkSymbol.values()) {
            try {
                syncOne(symbol.name());
            } catch (Exception e) {
                log.error("{} 지수 종가 동기화 실패: {}", symbol, e.getMessage(), e);
                failed.add(symbol.name());
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("동기화 실패 심볼: " + failed);
        }
    }

    private void syncOne(String symbol) {
        LocalDate from = indexPricePort.findMaxTradeDate(symbol)
                .map(d -> d.plusDays(1)).orElse(EARLIEST_INDEX_DATE);
        LocalDate to = LocalDate.now(TimeZones.KST);
        if (from.isAfter(to)) return;
        List<IndexPrice> prices = indexPriceFeedPort.fetchDailyCloses(symbol, from, to);
        if (!prices.isEmpty()) {
            indexPricePort.saveAll(prices);
        }
        log.info("{} 지수 종가 동기화 완료: {}건 ({} ~ {})", symbol, prices.size(), from, to);
    }
}
