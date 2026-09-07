---
paths: "**/com/kista/stats/adapter/in/web/*Controller.java"
---

## DashboardController vs StatisticsController 응답 형식 차이

- 세 컨트롤러(Dashboard/Statistics/TossStatistics) 모두 `com.kista.stats.adapter.in.web` 소유 (컨트롤러명·경로 불변)
- `DashboardController`: DB 기반 전용 DTO 반환 — `GET /api/accounts/{accountId}/cycle-history` → `CycleHistoryPageResponse` (커서 페이지네이션). 이 DTO는 stats(`DashboardController`)와 web(`TradingCycleController` 전략 이력 반환)에 byte-identical 사본이 각각 존재
- `StatisticsController`: **KIS 전용** live API 직접 호출 → 전용 Response DTO 반환 (`PortfolioSummaryResponse`/`List<MarginResponse>`/`DailyTransactionResponse`/`MultiPriceResponse`)
  - normalizer는 이미 API 서버 쪽(`PortfolioSummaryResponse.from()` 등)에서 수행 — `PresentBalanceResult` 등 도메인 모델은 컨트롤러 반환 직전에 DTO로 정규화되어 kista-ui는 그대로 소비
  - 신규 live 엔드포인트 추가 시 kista-ui 타입과 응답 필드명 반드시 대조 확인
- `TossStatisticsController`: **Toss 전용** — 캔들/환율/세션/종목정보/계좌정보 5개 엔드포인트 (`/api/accounts/{accountId}/*`)
