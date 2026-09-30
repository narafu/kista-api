package com.kista.sharedkernel;

// 벤치마크 비교 시계열의 집계 단위 — root stats와 trading-core stats가 공유하는 어휘(내부 API 쿼리 파라미터로도 쓰인다)
public enum BenchmarkGranularity { MONTHLY, DAILY, WEEKLY }
