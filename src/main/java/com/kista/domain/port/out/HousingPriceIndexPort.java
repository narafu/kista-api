package com.kista.domain.port.out;

import com.kista.domain.model.stats.HousingPriceIndex;

import java.util.List;

public interface HousingPriceIndexPort {
    // 자연키(source+metric+region+baseDate) 기준 저장 또는 갱신
    void upsertAll(List<HousingPriceIndex> indices);
}
