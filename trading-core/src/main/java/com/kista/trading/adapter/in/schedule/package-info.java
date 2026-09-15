// trading 모듈의 공개 계약 — TradingOpenScheduler/TradingCloseScheduler/BatchContextFactory. com.kista.web의
// AdminSchedulerController가 수동 트리거용으로 구체 클래스를 직접 주입하는 이 프로젝트의 기존 관례(KbLand 스케쥴러와 동일 패턴)를 유지하기 위해 공개한다. "schedule" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("schedule")
package com.kista.trading.adapter.in.schedule;
