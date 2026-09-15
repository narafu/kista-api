// trading 소유 통계 값 객체 — 두 계열이 섞여 있다.
// (1) 임시 교차 참조(InvestmentPoint/BenchmarkGranularity, 2026-09-10 Task 2): root com.kista.stats(StatsService/
//     HousingBenchmarkComparisonBuilder)가 Task5(HTTP 내부 API 전환) 전까지만 직접 소비 — Task5 이후 제거 대상.
// (2) 영구 소유(ReturnMetrics/StatsSummary/EquityCurve/EquityPoint/CyclePerformancePage/CyclePerformance/
//     StrategyTypeStats, 2026-09-12 Task 3에서 stats에서 이관): trading의 summary/equity-curve/cycles 통계
//     계산 결과 타입 + HousingBenchmarkComparisonBuilder/BacktestService가 공용으로 쓰는 순수 유틸 — Task5와
//     무관하게 이 패키지에 계속 남는다. 이 주석이 stale해져 (1)만 있다고 오인한 채 package-info를 삭제하지 말 것.
// application(MonthlyReturnCalculator)과 함께 "stats" 이름으로 병합 공개.
@org.springframework.modulith.NamedInterface("stats")
package com.kista.trading.stats.domain.model;
