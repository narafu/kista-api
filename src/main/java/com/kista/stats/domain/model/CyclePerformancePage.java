package com.kista.stats.domain.model;

import java.time.Instant;
import java.util.List;

public record CyclePerformancePage(List<CyclePerformance> items, Instant nextCursor, boolean hasMore) {}
