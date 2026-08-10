package com.kista.application.service.stats;

import com.kista.domain.model.stats.HousingPriceIndex;
import com.kista.domain.port.in.FetchHousingPriceIndexUseCase;
import com.kista.domain.port.out.HousingBenchmarkFeedPort;
import com.kista.domain.port.out.HousingPriceIndexPort;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
class HousingPriceIndexService implements FetchHousingPriceIndexUseCase {

    private final HousingBenchmarkFeedPort feedPort;
    private final HousingPriceIndexPort indexPort;
    private final NotifyPort notifyPort;

    @Override
    public void fetchAndSave() {
        try {
            // KB Land API에서 최근 20년치 지역별 주간 아파트 매매가격지수를 가져와 upsert한다.
            List<HousingPriceIndex> indices = feedPort.fetchWeeklyAptSalePriceIndex();
            indexPort.upsertAll(indices);
            log.info("KB Land 주간 아파트 매매가격지수 저장 완료: rows={}", indices.size());
        } catch (Exception e) {
            log.error("KB Land 주간 아파트 매매가격지수 수집 실패: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }
    }
}
