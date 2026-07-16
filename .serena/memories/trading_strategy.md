# 매매 전략 패턴 (포인터)

SSOT: `docs/agents/constraints.md`의 "매매 공식"·"VR 공식" (변경 금지, 단위 테스트 검증) + `docs/agents/workflow.md`
(스케쥴러 실행 흐름·BuyOrderPriceCapper·주문 예산 배정) + `docs/agents/architecture.md` (CycleOrderStrategy capability 패턴).

이 메모리의 과거 내용(S=0.20 하드코딩 공식, privacy_trades_master 테이블, TradingCycleHistoryPort)은 전부 폐기됨 —
현재 공식은 Ticker별 targetProfitRate × divisionCount 기반, PRIVACY 기준표는 privacy_trade_bases.
