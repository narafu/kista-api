// 여러 애그리게이트가 합의한 전역 공용 어휘(ubiquitous vocabulary) — DDD Shared Kernel과 유사.
// outbound reference 0이면 담을 수 있다: 도메인 값 타입(enum) + JDK-only 유틸(TimeZones) +
// 자체 검증만 하는 정책 record(TradingPolicySettings/BrokerSettings/StrategyCreationSettings/StrategyFieldSettings).
// 정책 record의 소유자는 trading-core(집행 주체)이고 root admin은 편집 UI로서 같은 shape을 내부 API body로 주고받는다 —
// 두 프로세스가 합의한 어휘라 여기 둔다. 프로세스 간 통합 이벤트(UserDeletedEvent/AppErrorRaisedEvent 등)도 같은 이유.
// 포트 인터페이스는 두지 않는다 — 포트는 그것을 필요로 하는 모듈이 소유한다(과거 sharedkernel.port는 폐지됨).
// US 거래일 변환 유틸(UsTradeDates)은 어댑터 전용이라 com.kista.platform.time으로 분리됐다.
// 이 패키지가 다른 모듈을 참조하는 순간 sharedkernel 전제가 깨진다.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.sharedkernel;
