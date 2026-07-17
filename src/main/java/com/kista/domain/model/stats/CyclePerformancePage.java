package com.kista.domain.model.stats;

import java.time.Instant;
import java.util.List;

public record CyclePerformancePage(List<CyclePerformance> items, Instant nextCursor, boolean hasMore) {}
