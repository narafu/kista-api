// stats 모듈의 공개 계약 일부 — KbLand·MarketIndexSync 스케쥴러. legacy AdminSchedulerController가
// KbLand 스케쥴러 2개를 수동 트리거용 ObjectProvider<>로 직접 주입하는 이 프로젝트의 기존 관례
// (trading.adapter.in.schedule "schedule" NamedInterface와 동일 이유)를 유지하기 위해 공개. "schedule" 이름.
@org.springframework.modulith.NamedInterface("schedule")
package com.kista.stats.adapter.in.schedule;
