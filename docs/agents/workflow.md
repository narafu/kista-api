# 매매·스케쥴러 워크플로 인덱스

매매·스케쥴러·주문 로직 작업 시 이 인덱스를 읽고 작업 주제에 맞는 하위 문서를 Read한다.

## 전체 흐름 요약
마감(`TradingCloseScheduler`, 화~토 04:30 KST)·개장(`TradingOpenScheduler`) 배치가 `kista-trading` 프로세스에서 돈다: 활성 전략 → context 빌드 → `TradingService.executeBatch()`(시세 일괄 조회 → leg-aware 후보 → BUY cap·보정 계산 → 전략 원장 상한 판정 → `orders` PLANNED 저장 → 증권사 접수 → 리포트). 재기동 시 `TradingBatchResumer`가 체크포인트로 재개한다. root 배치는 `kista-scheduler`(`scheduler-time-table.md`).

## 하위 문서 (언제 읽을지)
- `workflow-batch.md` — 배치 단계·마감/개장 경로·수동 트리거·실행 결과·오류 알림, "재기동 종료·재개", MarketSession(DIRECT|BLOCKED). 배치 흐름·재기동·수동 실행을 건드릴 때.
- `workflow-ledger-price.md` — 전략별 원장 상한(`StrategyLedgerBudget`), 재캡 재진입, 브로커 토큰, "시세 출처". 예산 배정·BUY 상한·시세 조회를 건드릴 때.
- `workflow-capper.md` — BuyOrderPriceCapper 보정 주문(INFINITE·PRIVACY·VR별 cap·보정). cap·보정 주문을 건드릴 때.
- `workflow-orders.md` — 미체결 주문 정리, 접수 선점(SUBMITTING), "PLANNED 상태 전이 경합", 수동 체결 보정, orders/cycle_position 기록 테이블 구분. 접수·취소·기록을 건드릴 때.
