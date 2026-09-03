package com.kista.stats.application.service;

import com.kista.stats.domain.model.HousingBenchmarkPrice;
import com.kista.stats.application.usecase.FetchHousingBenchmarkUseCase;
import com.kista.stats.application.port.output.HousingBenchmarkFeedPort;
import com.kista.stats.application.port.output.HousingBenchmarkPricePort;
import com.kista.stats.application.event.StatsAlertRaisedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
class HousingBenchmarkService implements FetchHousingBenchmarkUseCase {

    private final HousingBenchmarkFeedPort feedPort;
    private final HousingBenchmarkPricePort pricePort;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void fetchAndSave() {
        try {
            // KB Land API에서 최근 1년치 지역별 아파트 5분위 매매평균가격을 가져와 upsert한다.
            List<HousingBenchmarkPrice> prices = feedPort.fetchAptQteSalePrices();
            pricePort.upsertAll(prices);
            log.info("KB Land 주택 벤치마크 저장 완료: rows={}", prices.size());
        } catch (Exception e) {
            log.error("KB Land 주택 벤치마크 수집 실패: {}", e.getMessage(), e);
            eventPublisher.publishEvent(new StatsAlertRaisedEvent(e.getMessage()));
        }
    }
}
