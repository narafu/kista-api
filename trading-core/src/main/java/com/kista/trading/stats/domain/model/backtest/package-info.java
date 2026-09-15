// trading 모듈의 공개 계약 일부 — 백테스트 커맨드·결과 도메인 타입(BacktestCommand/BacktestPoint/BacktestResult/
// BacktestSummary/DailyCandle, 구 com.kista.stats.domain.model.backtest). api의 AlpacaIndexPriceAdapter(HistoricalCandlePort
// 구현체)가 DailyCandle을 소비하므로 "domain" 이름으로 trading의 domain.model+domain.strategy와 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.trading.stats.domain.model.backtest;
