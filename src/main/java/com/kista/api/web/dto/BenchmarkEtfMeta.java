package com.kista.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// ETF 벤치마크 비교 자산 허용값·기본값 — RuntimeConfigService benchmarks.etf와 같은 값(RuntimeConfigService 결과)
public record BenchmarkEtfMeta(
        @Schema(description = "허용 값 목록")
        List<String> allowedValues, // 허용 값 목록
        @Schema(description = "비교 기본값")
        String defaultValue         // 비교 기본값
) {
}
