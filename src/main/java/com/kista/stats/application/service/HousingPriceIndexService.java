package com.kista.stats.application.service;

import com.kista.stats.domain.model.HousingPriceIndex;
import com.kista.stats.application.usecase.FetchHousingPriceIndexUseCase;
import com.kista.stats.application.port.output.HousingBenchmarkFeedPort;
import com.kista.stats.application.port.output.HousingPriceIndexPort;
import com.kista.stats.application.event.StatsAlertRaisedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
class HousingPriceIndexService implements FetchHousingPriceIndexUseCase {

    private final HousingBenchmarkFeedPort feedPort;
    private final HousingPriceIndexPort indexPort;
    private final ApplicationEventPublisher eventPublisher;

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
