package com.kista.stats.application.service;

import com.kista.stats.domain.model.HousingBenchmarkPrice;
import com.kista.stats.domain.model.HousingPriceIndex;
import com.kista.stats.application.usecase.FetchHousingBenchmarkUseCase;
import com.kista.stats.application.usecase.FetchHousingPriceIndexUseCase;
import com.kista.stats.application.port.output.HousingBenchmarkFeedPort;
import com.kista.stats.application.port.output.HousingBenchmarkPricePort;
import com.kista.stats.application.port.output.HousingPriceIndexPort;
import com.kista.stats.application.event.StatsAlertRaisedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

// KB Land 아파트 5분위 매매평균가격 + 주간 매매가격지수 수집·저장 — fetch -> upsert -> log ->
// (실패 시) StatsAlertRaisedEvent 발행 구조가 동일해 두 유스케이스를 한 클래스가 구현한다.
@Slf4j
@Service
@RequiredArgsConstructor
class HousingBenchmarkService implements FetchHousingBenchmarkUseCase, FetchHousingPriceIndexUseCase {

    private final HousingBenchmarkFeedPort feedPort;
    private final HousingBenchmarkPricePort pricePort;
    private final HousingPriceIndexPort indexPort;
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

    @Override
    public void fetchAndSave(int years) {
        try {
            // KB Land API에서 지정 기간(년)의 지역별 주간 아파트 매매가격지수를 가져와 upsert한다.
            List<HousingPriceIndex> indices = feedPort.fetchWeeklyAptSalePriceIndex(years);
            indexPort.upsertAll(indices);
            log.info("KB Land 주간 아파트 매매가격지수 저장 완료: years={}, rows={}", years, indices.size());
        } catch (Exception e) {
            log.error("KB Land 주간 아파트 매매가격지수 수집 실패: years={}, {}", years, e.getMessage(), e);
            eventPublisher.publishEvent(new StatsAlertRaisedEvent(e.getMessage()));
        }
    }
}
