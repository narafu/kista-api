package com.kista.domain.port.out;

import com.kista.domain.model.stats.HousingBenchmarkPrice;

import java.util.List;

public interface HousingBenchmarkFeedPort {
    // KB Land 아파트 5분위 매매평균가격 월별 지역 데이터를 조회
    List<HousingBenchmarkPrice> fetchAptQteSalePrices();
}
