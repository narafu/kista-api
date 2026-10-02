package com.kista.trading.domain.model;

// 매매 배치 진행 단계 — 재기동 재개 판정 기준 (마감: STARTED→PLANNED→PLACING→PLACED→DONE, 개장: STARTED→PLACING→DONE)
public enum TradingBatchPhase {
    STARTED, // 배치 시작 — 이전 수동 실행의 DONE을 덮어써 중단 시 재개가 가려지지 않게 한다
    PLANNED, // PLANNED 주문 저장 완료 — 접수 대기 중
    PLACING, // 증권사 접수 진입 — 이 단계에서 중단되면 이중 접수 여부 확인 필요
    PLACED,  // 접수 완료 — 마감 후 리포트 대기 중
    DONE     // 배치 종료(조기 반환 포함)
}
