# 07. API 목록·집계·메타·오류 현황 전수 (읽기 전용 조사)

조사 2026-11-11. 기준 문서 `../ui-api-principles.md`. 컨트롤러 52개, 매핑 176개(GET/POST/PUT/PATCH/DELETE) 중 컬렉션 반환 GET(+조회형 POST)만 표에 올렸다. 경로의 `internal` 표시는 `/api/internal/**`(root↔trading-core 서버 간 호출, UI 무관).

## A. 컬렉션 반환 엔드포인트

공통 사실: **컨트롤러 어디에도 정렬 파라미터(`sort`/`orderBy`)가 없다.** 정렬은 전부 쿼리·서비스 고정. Spring `Pageable`을 컨트롤러가 받는 곳도 없다(전부 수기 `page`/`size`/`cursor`).

범례 — 페이징: **전량**(상한·기간 없음) / **기간**(from·to만) / **limit**(건수만) / **커서** / **page·size** / **고정**(서버 내부 상수).

### A-1. 사용자(UI) 엔드포인트

| 메서드·경로 | 컨트롤러:line | 반환 | 페이징 | 정렬 | 필터·검색 | 봉투 | 증가 데이터? | 실제 LIMIT |
|---|---|---|---|---|---|---|---|---|
| GET `/api/accounts` | tradingcore/account/AccountController:33 | `List<AccountResponse>` | 전량 | 고정 | 없음 | 배열 | 아니오(사용자 계좌 수) | 없음 |
| GET `/api/trading-cycles` | tradingcore/trading/TradingCycleController:51 | `List<TradingCycleResponse>` | 전량 | 고정 | `includeArchived` | 배열 | 아니오(전략 수, 단 archived 포함 시 증가) | 없음 |
| GET `/api/accounts/{accountId}/trading-cycles` | TradingCycleController:61 | `List<TradingCycleResponse>` | 전량 | 고정 | 없음 | 배열 | 아니오 | 없음 |
| GET `/api/accounts/{accountId}/trading-cycles/previews` | TradingCycleController:218 | `Map<UUID,NextOrdersResponse>` | 전량(계좌의 전략 수) | - | 없음 | 맵 | 아니오 | 없음 |
| GET `/api/trading-cycles/{id}/orders` | TradingCycleController:258 | `StrategyOrdersResponse{orders[]}` | 기간 | 고정 | `from`,`to`(미지정 시 최근 30일, line 262-263) | 자체 record `orders` | **예(주문)** | 기간 기본 30일 외 상한 없음(`to`-`from` 길이 무제한) |
| GET `/api/trading-cycles/{id}/history` | TradingCycleController:241 | `CycleHistoryPageResponse` | **커서** | 고정(최신순) | `from`,`to`,`cursor`(String, Instant),`size`(기본 50) | `{items, nextCursor, hasMore}` | 예 | `Math.clamp(size,1,200)` + DB `PageRequest.of(0,size+1)` |
| GET `/api/trading-cycles/{id}/dca-executions` | TradingCycleController:272 | `DcaExecutionPageResponse` | **page·size** | 고정(`DcaExecutionPersistenceAdapter:67` 내부 Sort) | `from`,`to`,`direction`,`ruleKey`,`page`(0-based, 기본 0),`size`(기본 20) | `{content, page, size, totalElements}` | 예 | clamp 1~100(`DcaExecutionQueryService:43`), DB 페이징 |
| GET `/api/accounts/{accountId}/cycle-history` | tradingcore/stats/DashboardController:35 | `CycleHistoryPageResponse`(stats 쪽 동명 별도 record) | **커서** | 고정 | `from`,`to`,`cursor`,`size`(기본 50) | `{items, nextCursor, hasMore}` | 예 | clamp 1~200 + DB 페이징 |
| GET `/api/stats/cycles` | tradingcore/stats/TradingStatsController:52 | `CyclePerformancePageResponse` | **page·size** | 고정(createdAt desc, id) | `type`,`accountId`,`ticker`,`status`,`page`(0-based,기본 0),`size`(기본 50) | `{items, page, totalElements, totalPages}` | 예(사이클 이력) | size clamp 1~200이나 **전량 로드 후 메모리 slice**(`TradingStatsService:202-215`, DB LIMIT 아님) |
| GET `/api/daily-trades` | DashboardController:53 | `DailyTransactionResponse{items[]}` | 기간(`from`,`to` **필수**) | 고정 | 없음 | 자체 record `items`(ItemDto) | 예(체결) | 기간 길이 상한 없음 |
| GET `/api/accounts/{accountId}/daily-trades` | tradingcore/stats/StatisticsController:53 | `DailyTransactionResponse{items[]}` | 기간(`from`,`to` 필수) | 고정 | 없음 | 동일 | 예 | 상한 없음 |
| GET `/api/market/holidays` | api/market/MarketHolidayController:36 | `ResponseEntity<List<String>>` | 고정(년·월 필수) | 고정 | `year`,`month` | 배열 | 아니오(월 1개) | 월 단위로 구조적 제한 |
| GET `/api/market/candles` | MarketHolidayController:69 | `List<TossCandleResponse>` | limit(`count` 기본 200)/기간 | 고정(시간순) | `ticker`,`count`,`from`,`to` | 배열 | 시세(외부) | count clamp 1~600, 기간 최대 5년(`MarketHolidayService:17,37`) |
| GET `/api/finance/transactions` | api/finance/FinanceTransactionController:34 | `List<FinanceTransactionResponse>` | **기간(선택)** — `from`·`to` 모두 null이면 **전량** | 고정(`transaction_date DESC, id DESC`, 네이티브 쿼리) | `from`,`to`,`categoryId`,`userId`(별칭 filterUserId) | 배열 | **예(거래내역)** | **없음**(`FinanceTransactionJpaRepository:21-27`) |
| GET `/api/finance/asset-snapshots` | AssetSnapshotController:43 | `List<AssetSnapshotResponse>` | 기간(선택, 미지정=전량) | 고정 | `from`,`to`,`userId` | 배열 | **예(월별 스냅샷)** | 없음 |
| GET `/api/finance/budgets` | FinanceBudgetController:34 | `List<FinanceBudgetResponse>` | 전량 | 고정 | `categoryId`,`date`(해당 일자 적용분) | 배열 | 느리게 증가(예산 규칙) | 없음 |
| GET `/api/finance/monthly-closings` | MonthlyClosingController:28 | `List<MonthlyClosingResponse>` | 전량 | 고정 | 없음 | 배열 | 월 1건씩 증가 | 없음 |
| GET `/api/finance/categories` | FinanceCategoryController:34 | `List<FinanceCategoryResponse>` | 전량 | 고정 | `type` | 배열 | 아니오 | 없음 |
| GET `/api/finance/accounts` | FinanceAccountController:32 | `List<FinanceAccountResponse>` | 전량 | 고정 | 없음 | 배열 | 아니오 | 없음 |
| GET `/api/finance/groups` | FinanceGroupController:38 | `List<FinanceGroupResponse>` | 전량 | 고정 | 없음 | 배열 | 아니오 | 없음 |
| GET `/api/finance/groups/{id}/members` | FinanceGroupController:50 | `List<FinanceGroupMemberResponse>` | 전량 | 고정 | 없음 | 배열 | 아니오 | 없음 |
| GET `/api/accounts/{accountId}/crypto-portfolio` 등 | CryptoPortfolioController | 자체 record(내부 리스트) | - | - | - | 자체 | 보유 종목 수 | - |

### A-2. 관리자 엔드포인트(`/api/admin/**`)

| 메서드·경로 | 컨트롤러:line | 반환 | 페이징 | 필터 | 증가 데이터? | 실제 LIMIT |
|---|---|---|---|---|---|---|
| GET `/api/admin/trades` | api/admin/AdminTradeController:48 | `List<AdminTradeResponse>` | 기간(선택) | `from`,`to` | **예(주문)** | 미지정 시 최근 30일(`AdminQueryService:69-70`), 상한 없음 |
| GET `/api/admin/accounts/{accountId}/strategies/{strategyId}/trade-dates` | AdminTradeController:56 | `List<LocalDate>` | 전량 | 없음 | 예(거래일) | 없음 |
| GET `/api/admin/privacy-trade-bases` | AdminPrivacyTradeController:43 | `List<AdminPrivacyBaseResponse>` | limit성 `range`(정수, 최소 30, 미전달 시 **전체**, `AdminPrivacyTradeController:41-46`) | `range` | 느리게 증가 | 미전달 시 상한 없음 |
| GET `/api/admin/logs/audit` | AdminObservabilityController:41 | `List<AuditLogResponse>` | **limit 고정 100**(`AuditLogJpaRepository:16-17` `LIMIT 100`) | `from`,`to` | **예(로그)** | 있음(100 고정, UI가 바꿀 수 없음, 다음 페이지 없음) |
| GET `/api/admin/logs/errors` | AdminObservabilityController:54 | `List<ErrorLogResponse>` | **limit만**(`limit` 기본 100, 최대 500 `:33,59`) | `from`,`to` | **예(로그)** | 있음(`AppErrorLogJpaRepository:13`) |
| GET `/api/admin/accounts` | AdminAccountController:45 | `List<AdminAccountResponse>` | 기간(선택) | `from`,`to` | 사용자 수 비례 | 상한 없음 |
| GET `/api/admin/accounts/{accountId}/strategies` | AdminAccountController:60 | `List<AdminStrategyResponse>` | 전량 | 없음 | 아니오 | 없음 |
| GET `/api/admin/users` | AdminUserController:32 | `List<AdminUserResponse>` | 기간(선택) | `status`,`from`,`to` | 사용자 수 비례(증가) | 상한 없음 |
| GET `/api/admin/finance/categories` | api/finance/AdminFinanceCategoryController:39 | `List<FinanceCategoryResponse>` | 전량 | `type` | 아니오 | 없음 |

### A-3. 내부 엔드포인트(`/api/internal/**`, UI 비대상)

| 메서드·경로 | 컨트롤러:line | 반환 | 페이징 | 증가? | 비고 |
|---|---|---|---|---|---|
| GET `/api/internal/accounts` | tradingcore/account/AccountInternalController:36 | `List<AccountSummaryResponse>` | 기간(선택) | 사용자 수 | |
| GET `/api/internal/trading/orders` | TradingInternalQueryController:34 | `List<OrderResponse>` | 기간(`from`,`to` 필수) | **예(주문)** | admin trades가 호출. 상한 없음 |
| GET `/api/internal/trading/orders/distinct-account-ids` | :42 | `List<UUID>` | 기간(필수) | 예 | |
| GET `/api/internal/trading/accounts/{accountId}/strategies` | :50 | `List<StrategyResponse>` | 전량 | 아니오 | |
| POST `/api/internal/trading/strategies/by-account-ids` | :56 | `Map<UUID,List<StrategyResponse>>` | 전량(조회형 POST) | 아니오 | |
| POST `/api/internal/trading/strategy-summaries` | :64 | `Map<UUID,StrategySummaryResponse>` | 전량 | 아니오 | |
| GET `/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/trade-dates` | :71 | `List<LocalDate>` | 전량 | 예 | |
| GET `/api/internal/trading/stats/portfolio/current` | stats/PortfolioQueryInternalController:26 | `List<PortfolioCurrentResponse>` | 전량 | 아니오 | `userId` |
| GET `/api/internal/trading/stats/portfolio/history` | :34 | `List<PortfolioOrderResponse>` | 기간(필수) | 예 | |
| GET `/api/internal/broker/candles/latest` | broker/CandleInternalController:26 | `List<DailyCandleResponse>` | limit(`count`) | 외부 시세 | |
| GET `/api/internal/broker/candles/range` | :34 | `List<DailyCandleResponse>` | 기간(필수) | 외부 시세 | |
| GET `/api/internal/privacy/trade-bases` | privacy/PrivacyInternalQueryController:24 | `List<PrivacyTradeBaseResponse>` | 기간(`fromReleaseDate` 필수) | 느리게 증가 | |
| GET `/api/internal/marketcalendar/holidays` | marketcalendar/MarketCalendarInternalController:31 | `List<LocalDate>` | 고정(년·월) | 아니오 | |

### A-4. 요약

**"늘어나는 데이터인데 전량(또는 기간만) 반환" — 원칙상 페이지형이어야 할 후보**

| # | 엔드포인트 | 현재 | 비고 |
|---|---|---|---|
| 1 | GET `/api/finance/transactions` | from/to 모두 선택, 미지정 시 전량, LIMIT 없음 | 가계부 핵심. 배열 그대로 |
| 2 | GET `/api/finance/asset-snapshots` | 동일 | 월별 누적 |
| 3 | GET `/api/trading-cycles/{id}/orders` | 기간 기본 30일, 길이 상한 없음, 페이징 없음 | 주문 이력. 같은 이력인 `/history`는 커서형 → **이질** |
| 4 | GET `/api/daily-trades`, `/api/accounts/{id}/daily-trades` | 기간 필수, 상한 없음 | 체결 이력 |
| 5 | GET `/api/admin/trades` | 기본 30일, 상한 없음 | 관리자 |
| 6 | GET `/api/admin/users`, `/api/admin/accounts` | 전량(기간 선택) | 사용자 수 증가에 비례 |
| 7 | GET `/api/admin/logs/audit` | 100건 고정, 다음 페이지 불가 | 잘림이 UI에 노출되지 않음 |
| 8 | GET `/api/admin/logs/errors` | limit만(최대 500) | 다음 페이지 불가 |
| 9 | GET `/api/stats/cycles` | page·size 형이지만 **서버가 전량 로드 후 메모리 slice** | 형식은 페이지형, 구현은 전량 |
| 10 | GET `/api/finance/monthly-closings` | 전량, 월 1건씩 증가 | 느린 증가 |
| 11 | GET `/api/finance/budgets` | 전량 | 느린 증가 |
| 12 | GET `/api/trading-cycles?includeArchived=true` | 전량 | 보관 전략 누적 |

**페이징 형식 종류별 개수 (컬렉션 엔드포인트 전체: 사용자 24 + 관리자 9 + 내부 13 = 46개 중)**

| 형식 | 개수 | 해당 |
|---|---|---|
| 커서(`items,nextCursor,hasMore`, `cursor`+`size`) | 2 | `/trading-cycles/{id}/history`, `/accounts/{id}/cycle-history` |
| page·size A(`content,page,size,totalElements`) | 1 | `/trading-cycles/{id}/dca-executions` |
| page·size B(`items,page,totalElements,totalPages`) | 1 | `/stats/cycles` |
| limit만(건수 파라미터) | 3 | `/admin/logs/errors`, `/market/candles`(count), `/internal/broker/candles/latest` |
| limit 고정(파라미터 없음) | 1 | `/admin/logs/audit`(100) |
| 기간 파라미터(from·to)만 | 약 14 | finance 거래·스냅샷, orders, daily-trades 2, admin trades/accounts/users, internal 5 |
| 전량 | 약 20 | 계좌·전략·카테고리·그룹·예산·마감 등 |

→ 페이지형은 **세 갈래**(커서 / page·size A / page·size B)로 응답 필드명까지 제각각이고 `size` 기본값도 50/20/50으로 다르며 clamp 상한도 200/100/200이다. 정렬 파라미터는 0개. 봉투가 "배열 그대로"인 목록이 대부분이고, 자체 record 봉투(`{orders}`, `{items}`)가 별도로 있어 "리스트 응답이 배열 / `{items}` / `{orders}` / `{content}`" 4갈래다.

---

## B. 집계·계산 엔드포인트 현황

### B-1. 서버가 집계해서 내려주는 것

| 엔드포인트 | 컨트롤러:line | 서버가 계산하는 것 |
|---|---|---|
| GET `/api/stats/summary` | tradingcore/stats/TradingStatsController:33 | 실현·미실현 손익 합, 운용 원금, 전략유형별 승률·평균수익률·평균기간(`StatsSummaryResponse.byType`), 가능 통화 |
| GET `/api/stats/equity-curve` | TradingStatsController:40 | 일자별 총자산·원금 포인트 |
| GET `/api/stats/cycles` | TradingStatsController:52 | 사이클별 pnl·returnRate·durationDays·closed |
| GET `/api/stats/housing-benchmark` | api/benchmark/BenchmarkController:40 | 투자 누적수익률 vs 벤치마크 비교(summary·points·quality) |
| GET `/api/accounts/{id}/ledger-totals` | tradingcore/trading/AccountLedgerTotalsController:30 | 예수금·평가금·미배정 현금 합계 |
| GET `/api/accounts/{id}/portfolio` | StatisticsController:38 | 종목별 평가·손익률, 계좌 총자산·총수익률 |
| GET `/api/backtest` | BacktestController:37 | 백테스트 전체 계산 |
| GET `/api/admin/dashboard/stats` | api/admin/AdminDashboardController:22 | 사용자·계좌 수 카운트 |
| GET `/api/market/fear-greed`, `/api/crypto-market-indicators` | market/*, stats/* | 지수 이력(기간 `days`) |
| GET `/api/internal/trading/stats/investment-points` | TradingStatsInternalController:28 | 투자 시점별 누적 포인트(root benchmark가 소비, 내부) |

금액 반올림은 `Currency.round()`로 서버가 수행(constraints.md). 원칙 문서가 요구하는 "합계·비율·수익률·기간 집계는 API" 기준에서 **trading 도메인은 충족**.

### B-2. 원자료만 내려줘 클라이언트가 집계해야 하는 것

**finance 가계부는 집계 엔드포인트가 0개다.** finance 컨트롤러 9개의 모든 GET은 원자료 목록이다.

| 엔드포인트 | 내려주는 것 | 클라이언트가 해야 하는 집계 |
|---|---|---|
| GET `/api/finance/transactions` | `{id, categoryId, groupId, transactionDate, amount(long 절대값), memo}` 행 전량 | 월별·카테고리별·부모(L1/L2) 카테고리별 합계, 수입/지출 부호(카테고리 type으로 판정), 월 총합 |
| GET `/api/finance/budgets` | `{categoryId, applyStartDate, applyEndDate, amount}` 규칙 | 월별 예산 대비 지출 소진액·잔액·소진율(예산 규칙과 거래의 조인) |
| GET `/api/finance/asset-snapshots` | 행별 `{categoryId, rootCategoryId, entryDate, amount, assetClass, market, strategy ...}` | 일자별 순자산(자산 합 - 부채 합, `rootCategoryId == SYSTEM_LOAN_ID`로 부채 판정은 UI), 자산군·시장·전략별 비중, 월간 증감. `AssetSnapshotResponse` 주석에 "UI가 순자산/총부채 판정을 할 수 있도록"이라고 명시 |
| GET `/api/finance/monthly-closings` | `{month, completed, closedAt, groupId}` | 응답 주석: 같은 month에 개인·그룹 2건이 올 수 있어 "클라이언트가 groupId로 자신의 활성 스코프 행을 골라야" 함(서버 가드와 일치시키는 규칙을 UI가 재현) |
| GET `/api/finance/categories` | 카테고리 트리 원자료 | 트리 구성·부모 경로 계산 |

월별 합계·카테고리별 합계·순자산·예산 소진 엔드포인트는 **없음**(`/api/finance/**` 매핑 전수 확인: transactions, budgets, categories, accounts, asset-snapshots, monthly-closings, groups, bulk-register, invitations).

### B-3. 그 외 클라이언트 집계 가능성이 있는 곳

| 엔드포인트 | 비고 |
|---|---|
| GET `/api/daily-trades`, `/api/accounts/{id}/daily-trades` | 체결 행(`price, quantity, tradeAmount`)만. 일자·종목별 소계는 없음(확인: `DailyTransactionResponse.ItemDto`) |
| GET `/api/trading-cycles/{id}/orders` | 주문 행만(합계·평균 체결가 없음) |
| GET `/api/admin/trades` | 행만 |
| GET `/api/trading-cycles` | 전략 목록 행. 합산(총 원금 등)은 `/stats/summary` 사용 가능 |
