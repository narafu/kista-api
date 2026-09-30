// 프로세스 경계(root :api ↔ :trading-core)를 넘는 통합 계약 — Published Language.
// 내부 HTTP API(/api/internal/**)의 요청·응답 body와 Redis Pub/Sub payload를 한 곳에 선언하고 양쪽이 컴파일 타임에 공유한다.
// 도메인 어휘(sharedkernel)와 별개다 — sharedkernel은 여러 모듈이 합의한 값 타입, contract는 wire 스키마(필드명이 곧 계약).
// 규칙: 순수 데이터 record만 둔다. JDK + sharedkernel + Jackson/Swagger/Bean Validation 어노테이션 외 참조 금지 —
// 도메인 타입을 import할 수 없으므로 domain→contract 매핑은 소유 모듈의 컨트롤러/어댑터가 담당한다.
// 필드 이름·타입을 바꾸면 wire 계약이 바뀐다(두 프로세스가 독립 배포되므로 expand/contract 순서를 지킬 것).
// Type.OPEN이되 InternalApiContractTest·HexagonalArchitectureTest.contract_must_not_depend_on_other_modules가 outbound를 강제한다.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.contract;
