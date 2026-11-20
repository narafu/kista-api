package com.kista.shared.platform.web;

// ProblemDetail "code" 확장 프로퍼티 값 — kista-ui가 분기·문구 매핑에 쓰는 공개 계약(openapi.json components.schemas.ErrorCode).
// 상수 이름 변경·삭제 금지: 의미가 바뀌면 새 상수를 추가한다
public enum ErrorCode {
    BROKER_UNAVAILABLE,          // 503 증권사 API 장애
    BROKER_CREDENTIAL_INVALID,   // 422 증권사 API 키 오류
    BROKER_PERMISSION_DENIED,    // 422 거래소 API 키 권한 부족 (메리츠)
    BROKER_RATE_LIMITED,         // 429 증권사 API 호출 한도 초과
    DUPLICATE_ACCOUNT,           // 409 이미 등록된 증권 계좌
    ALREADY_ORDERED_TODAY,       // 409 발행처 없음(2026-11 바로 주문 삭제), kista-ui 계약상 보존
    DCA_MANUAL_EXECUTION_UNSUPPORTED, // 409 발행처 없음(2026-11 바로 주문 삭제), kista-ui 계약상 보존
    ORDER_NOT_CANCELLABLE,       // 409 취소 불가 상태의 주문
    MONTH_CLOSED,                // 409 기록 점검 완료(마감)된 달의 가계부 쓰기
    ACCESS_DENIED,               // 403 소유·권한 위반
    COOLDOWN_ACTIVE,             // 429 재신청 대기 시간 미경과
    TRADING_CORE_UNAVAILABLE,    // 503 매매 서버(trading-core) 도달 실패
    WRONG_ROLE                   // 503 이 서버 role이 처리하지 않는 경로로 온 요청
}
