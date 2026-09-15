// trading 모듈의 공개 계약 일부 — stats 흡수분 출력 포트(HistoricalCandlePort, api의 AlpacaIndexPriceAdapter가 구현).
// trading의 application.port.output과 함께 "port" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("port")
package com.kista.trading.stats.application.port.output;
