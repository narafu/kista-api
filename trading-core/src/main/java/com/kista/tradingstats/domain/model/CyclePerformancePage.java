package com.kista.tradingstats.domain.model;

import java.util.List;

// 번호 페이징 결과 — page는 0-based
public record CyclePerformancePage(List<CyclePerformance> items, int page, long totalElements, int totalPages) {}
