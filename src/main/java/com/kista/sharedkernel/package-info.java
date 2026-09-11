// 여러 애그리게이트가 합의한 전역 공용 어휘(ubiquitous vocabulary) — DDD Shared Kernel과 유사.
// outbound reference 0이면 담을 수 있다: 도메인 값 타입(enum) + JDK-only 유틸(TimeZones) +
// 자체 검증만 하는 정책 record(StrategyCreationSettings/StrategyFieldSettings). 정책 record는 admin이 소유하면
// admin↔trading 순환이 생기고 trading 소유는 admin 설정 shape을 소비 모듈로 옮기는 꼴이라, 양쪽이 합의한 어휘로 둔다.
// US 거래일 변환 유틸(UsTradeDates)은 어댑터 전용이라 com.kista.platform.time으로 분리됐다.
// 이 패키지가 다른 모듈을 참조하는 순간 sharedkernel 전제가 깨진다.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.sharedkernel;
