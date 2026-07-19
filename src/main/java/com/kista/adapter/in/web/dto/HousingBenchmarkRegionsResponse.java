package com.kista.adapter.in.web.dto;

import com.kista.domain.model.stats.HousingBenchmarkRegion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 5분위 시계열 조회에 사용 가능한 KB Land 지역 카탈로그 (외부 API 제공 값 그대로)
public record HousingBenchmarkRegionsResponse(List<Region> regions) {

    @Schema(name = "HousingBenchmarkRegionItem")
    public record Region(String code, String name) {}

    public static HousingBenchmarkRegionsResponse from(List<HousingBenchmarkRegion> regions) {
        return new HousingBenchmarkRegionsResponse(
                regions.stream().map(r -> new Region(r.regionCode(), r.regionName())).toList());
    }
}
