// 주문생성 알고리즘 커널 모듈 — 순수 계산(무한매수법/리버스/VR/PRIVACY 사다리 + 가격 캡).
// 라이브 실행(trading)과 백테스트(stats) 두 소비자가 공유. domain-only(레이어 서브구조 없음),
// outbound 엣지는 sharedkernel + privacy뿐(HexagonalArchitectureTest.matching_must_not_depend_on_other_modules 강제).
// "kernel" NamedInterface로 domain.model + domain.strategy 병합 공개.
@org.springframework.modulith.ApplicationModule
package com.kista.matching;
