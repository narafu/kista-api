// trading 모듈의 공개 계약 일부 — stats 흡수분 출력 포트(HistoricalCandlePort, 이 모듈 소유 AlpacaCandleAdapter가 구현, BacktestService가 소비).
// trading의 application.port.output과 함께 "port" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("port")
package com.kista.trading.stats.application.port.output;
