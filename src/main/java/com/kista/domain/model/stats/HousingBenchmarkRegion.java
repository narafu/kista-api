package com.kista.domain.model.stats;

// KB Land 지역 카탈로그 — 외부 API가 제공하는 지역 목록을 DB에서 동적으로 집계한 값 (하드코딩 아님)
public record HousingBenchmarkRegion(String regionCode, String regionName) {}
