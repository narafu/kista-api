package com.kista.application.service.stats;

import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.domain.port.in.FetchHousingBenchmarkUseCase;
import com.kista.domain.port.out.HousingBenchmarkFeedPort;
import com.kista.domain.port.out.HousingBenchmarkPricePort;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
class HousingBenchmarkService implements FetchHousingBenchmarkUseCase {

    private final HousingBenchmarkFeedPort feedPort;
    private final HousingBenchmarkPricePort pricePort;
    private final NotifyPort notifyPort;

    @Override
    public void fetchAndSave() {
        try {
            // KB Land API에서 최근 1년치 지역별 아파트 5분위 매매평균가격을 가져와 upsert한다.
            List<HousingBenchmarkPrice> prices = feedPort.fetchAptQteSalePrices();
            pricePort.upsertAll(prices);
            log.info("KB Land 주택 벤치마크 저장 완료: rows={}", prices.size());
        } catch (Exception e) {
            log.error("KB Land 주택 벤치마크 수집 실패: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }
    }
}
