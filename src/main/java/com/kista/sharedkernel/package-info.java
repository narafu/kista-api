// 여러 애그리게이트가 합의한 전역 공용 어휘(ubiquitous vocabulary) — DDD Shared Kernel과 유사하되
// 순수 값 타입(enum)만 담는다. common/과 달리 기술 유틸이 아닌 도메인 개념이라 별도 패키지로 분리.
// outbound reference 0인 타입만 여기 둔다 — 이 패키지가 다른 모듈을 참조하는 순간 sharedkernel 전제가 깨진다.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.sharedkernel;
