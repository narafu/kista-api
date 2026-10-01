---
paths: "**/com/kista/tradingstats/adapter/in/web/*Controller.java"
---

## DashboardController vs StatisticsController 응답 형식 차이

- 세 컨트롤러(Dashboard/Statistics/TossStatistics) 모두 `com.kista.tradingstats.adapter.in.web`(`:trading-core`) 소유
- `DashboardController`: DB 기반 전용 DTO 반환 — `GET /api/accounts/{accountId}/cycle-history` → `CycleHistoryPageResponse` (커서 페이지네이션). 이 DTO는 tradingstats(`DashboardController`, 계좌 전체 이력)와 trading(`TradingCycleController.getStrategyHistory`, 단일 전략 이력)에 내용이 동일한 사본이 각각 존재 — 둘 다 살아있는 별개 엔드포인트
- `StatisticsController`: 브로커 live API 직접 호출(KIS/Toss는 브로커 어댑터가 분기) → 전용 Response DTO 반환 (`PortfolioSummaryResponse`/`List<MarginResponse>`/`DailyTransactionResponse`/`MultiPriceResponse`)
  - normalizer는 이미 API 서버 쪽(`PortfolioSummaryResponse.from()` 등)에서 수행 — `PresentBalanceResult` 등 도메인 모델은 컨트롤러 반환 직전에 DTO로 정규화되어 kista-ui는 그대로 소비
  - 신규 live 엔드포인트 추가 시 kista-ui 타입과 응답 필드명 반드시 대조 확인
- `TossStatisticsController`: **Toss 전용** — 캔들/종목정보/환율/시장세션/증권사계좌 5개 엔드포인트 (`/api/accounts/{accountId}/*`)
