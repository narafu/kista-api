package com.kista.stats.application.port.output;

import com.kista.stats.domain.model.HousingBenchmarkPrice;
import com.kista.stats.domain.model.HousingPriceIndex;

import java.util.List;

public interface HousingBenchmarkFeedPort {
    // KB Land 아파트 5분위 매매평균가격 월별 지역 데이터를 조회
    List<HousingBenchmarkPrice> fetchAptQteSalePrices();

    // KB Land 주간 아파트 매매가격지수 지역 데이터를 조회 — years: 조회 기간(년)
    List<HousingPriceIndex> fetchWeeklyAptSalePriceIndex(int years);
}
