// 여러 애그리게이트가 합의한 전역 공용 어휘(ubiquitous vocabulary) — DDD Shared Kernel과 유사.
// outbound reference 0인 도메인 값 타입(enum) + JDK-only 유틸(TimeZones/UsTradeDates)만 둔다 —
// 이 패키지가 다른 모듈을 참조하는 순간 sharedkernel 전제가 깨진다.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.sharedkernel;
