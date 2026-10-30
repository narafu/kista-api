// stats 공개 계약 일부 — 백테스트 엔진·체결 시뮬레이터(BacktestEngine/FillSimulator)와 커맨드·결과 값(BacktestCommand/BacktestPoint/BacktestResult/BacktestSummary)을 한 패키지로 묶었다.
// domain.model과 함께 "domain" 이름으로 병합 공개된다(엔진·시뮬레이터는 모듈 내부 소비뿐이라 외부 참조는 없다).
@org.springframework.modulith.NamedInterface("domain")
package com.kista.tradingcore.stats.domain.backtest;
