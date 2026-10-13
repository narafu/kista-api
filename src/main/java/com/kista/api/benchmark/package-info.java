// benchmark 애그리게이트(주택/ETF 벤치마크 비교·KB Land/Alpaca 지수 시세 수집) 모듈 — 2026-09-30 root `stats`에서 개명
// 계좌·Toss 통계·백테스트·포트폴리오는 trading-core `stats` 소유.
// domain.model·application.{usecase,port.output,event}·adapter.in.schedule만 공개 계약, application.service·나머지 adapter는 internal.
@org.springframework.modulith.ApplicationModule(id = "benchmark")
package com.kista.api.benchmark;
