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

---

## C. `/api/meta` 내용

`GET /api/meta` → `MetaBundle`(`api/web/dto/MetaBundle.java`, 컨트롤러 `api/web/MetaController.java`). 응답 헤더 `Cache-Control: max-age=1h`. 인증 없이 호출 가능한지는 `RootSecurityPolicy` 소관(이 조사 범위 밖).

### C-1. MetaBundle 필드 전체 (18개)

| 필드 | 원소 타입 | 소스 enum | 싣는 속성 |
|---|---|---|---|
| `strategyTypes` | `StrategyTypeMeta` | `StrategyType` | code, label, description(**label과 동일 값** — 둘 다 `getDescription()`), availableTickers, requiresPrivacyBase, tickerFixed, supportsReverseMode, divisionCounts(분할 수 옵션 목록), orderTypes, directions, frequencies, conditionIndicators |
| `tickers` | `TickerMeta` | `StrategyTicker` | code, description, targetProfitRate, markets. **label 없음**(description이 표시명 역할) |
| `brokers` | `BrokerMeta` | `Broker` | code, label, description(=모바일 약칭), market, currency, minOrderAmount, amountScale, apiKeyExpirySource |
| `strategyStatuses` | `EnumMeta` | `StrategyStatus` | code, label, description(**항상 null**) |
| `cycleSeedTypes` | EnumMeta | `StrategyCycleSeedType` | 동일 |
| `assetClasses` | EnumMeta | finance `AssetClass` | 동일 |
| `markets` | EnumMeta | **finance `Market`** (`api/finance/domain/model/Market.java`, kernel `Market`과 별개) | 동일 |
| `financeAccountTypes` | EnumMeta | `FinanceAccount.Type` | 동일 |
| `financeCategoryTypes` | EnumMeta | `FinanceCategory.Type` | 동일 |
| `orderTypes` | EnumMeta | `OrderType` | 동일 |
| `orderDirections` | EnumMeta | `OrderDirection` | 동일 |
| `orderStatuses` | EnumMeta | `OrderStatus` | 동일 |
| `orderTimings` | EnumMeta | `OrderTiming` | 동일 |
| `dcaFrequencies` | EnumMeta | `DcaFrequency` | 동일 |
| `dcaOperators` | EnumMeta | `DcaOperator` | 동일 |
| `dcaSkipReasons` | EnumMeta | `DcaSkipReason` | 동일 |
| `dcaExecutionStatuses` | EnumMeta | `DcaExecutionStatus` | 동일 |
| `dcaSizingTypes` | EnumMeta | `DcaSizingType` | 동일 |
| `dcaShortfallPolicies` | EnumMeta | `DcaShortfallPolicy` | 동일 |
| `dcaIndicators` | `DcaIndicatorMeta` | `DcaIndicator` | code, label, description, smaPeriods(SMA만 값) |

`EnumMeta`는 `{code, label, description}`이지만 `toEnumMeta(...)`가 description에 `null`을 고정으로 넣어 **EnumMeta 경로 15개 enum 모두 description이 비어 있다**(MetaController `toEnumMeta`). 즉 라벨은 있으나 설명·기본값·범위는 EnumMeta로는 못 나간다.

### C-2. main의 public enum 대조 (53개 enum 선언 중 비-Entity)

**메타에 실려 있음(20)**: StrategyType, StrategyTicker, Broker, StrategyStatus, StrategyCycleSeedType, AssetClass(finance), Market(finance), FinanceAccount.Type, FinanceCategory.Type, OrderType, OrderDirection, OrderStatus, OrderTiming, DcaFrequency, DcaOperator, DcaSkipReason, DcaExecutionStatus, DcaSizingType, DcaShortfallPolicy, DcaIndicator.

**API 요청·응답 계층(Controller/Response/Request/Command)에 쓰이는데 메타에 없음**:

| enum | 위치 | 참조 | 라벨 getter | 비고 |
|---|---|---|---|---|
| `UserRole` | shared/kernel | 4파일 | 없음 | 응답 `role` |
| `UserStatus` | shared/kernel | 5파일 | 없음 | 관리자 필터·사용자 상태 |
| `NotificationChannel` | shared/kernel | 2파일 | 없음 | 사용자 설정 |
| `NotificationType` | shared/kernel | 1파일 | 없음 | |
| `Currency` | shared/kernel | 8파일 | 없음 | `currency` 응답(BrokerMeta에는 name 문자열로만) |
| `Market`(kernel) | shared/kernel | 4파일 | 없음 | BrokerMeta.market 문자열로만 |
| `RecurringMode` | shared/kernel | 1파일 | 없음 | VR 입출금 방향. 허용값은 `/api/runtime-config`로 나감 |
| `BenchmarkScope`, `BenchmarkAssetType`, `EtfBenchmarkSymbol` | api/benchmark | 1/2/2파일 | 없음 | `/api/runtime-config`의 benchmarks에 허용값(문자열)만 |
| `CycleStatusFilter` | tradingcore/stats | 1파일 | 없음 | `/api/stats/cycles?status=` |
| `NextOrdersPreview.SkipReason` | tradingcore/trading | 1파일 | 없음 | 주문 미리보기 skip 사유 → UI가 코드→문구 매핑 필요 |
| `CryptoOrderResult.State` | tradingcore/broker | 1파일 | 없음 | |
| `MarketSession` | shared/kernel | 0 (market/session 응답 문자열 확인 필요) | 없음 | |
| `FearGreedRating` | api/market | 0 | **있음(label 3건)** | 응답에 문자열/라벨 포함 가능 |
| `FinanceGroup.MemberRole`, `FinanceGroupInvitation.Status` | api/finance | 0 | 없음 | 응답 DTO에서 String일 가능성 |
| `ApiKeyExpirySource` | shared/kernel | 0 | 없음 | BrokerMeta로만 노출 |

**메타에 라벨이 없는(=getter 자체가 없는) enum**: 위 표의 "라벨 getter 없음" 전부. 메타에 들어 있는 enum은 모두 `getLabel()` 보유.

### C-3. 전략 등록 기본값·허용 범위가 나가는 경로

| 항목 | 메타(`/api/meta`)로 나감? | 다른 경로 |
|---|---|---|
| 분할 수 옵션(`divisionCounts`) | 예(`StrategyTypeMeta`) | |
| 분할 수 **기본값**(`StrategyDefaults.DEFAULT_DIVISION_COUNT=20`) | 아니오 | `/api/runtime-config`의 `strategies.<type>.fields.divisionCount.defaultValue`(운영 설정값) |
| 티커 허용·기본값, VR `recurringMode`/`bandWidth`/`intervalWeeks` | 아니오 | `/api/runtime-config`(`StrategyFieldSettings{customizable, allowedValues, defaultValue}`) — 공개 API, `GET /api/runtime-config`(RuntimeConfigController:23) |
| 티커 익절 목표 수익률 | 예(`TickerMeta.targetProfitRate`) | |
| 브로커별 최소 주문액·소수 자릿수 | 예(`BrokerMeta`) | |
| **VR 램프 기본값표**(적립 gradient10/gMax20/rate1.0/floor0.5, 거치 …/0.75/…, 인출 gradient40/gMax50/rate0.1/floor0.1, grace·step 52/26주) | **아니오** | `VrRampInput.withDefaults`(trading-core) 하드코딩. 주석이 "kista-ui `RAMP_DEFAULTS_BY_MODE`와 동기화"라고 명시 → **UI가 같은 표를 복제 보유**, API에서 받을 경로 없음 |
| VR/기타 입력 범위(`@Min`/`@Positive` 등) | 아니오 | Request DTO 검증 어노테이션(OpenAPI에 min/max로 나가는지는 D 절) |
| DCA 규칙 상한(`DcaStrategyConfig.MAX_RULES`, `DcaRule.MAX_CONDITIONS`) | 아니오 | 도메인 상수 |
| DCA SMA 기간 옵션 | 예(`DcaIndicatorMeta.smaPeriods`) | |
| 전략 시드 미리보기 | - | `GET /api/accounts/{id}/strategy-seed-preview`(서버 계산) |

관찰: `StrategyTypeMeta.label`과 `description`이 같은 값이다(`getDescription()` 중복). 기본값·범위가 `/api/meta`(enum 메타)와 `/api/runtime-config`(운영 설정) 두 곳으로 갈라져 있고 VR 램프표만 어느 쪽에도 없다.

---

## D. OpenAPI required·nullable 표기 현황

| 항목 | 현황 |
|---|---|
| Response record(`*Response.java`, adapter/in/web 하위) | 59개(web `dto` 디렉터리 record 파일 전체 103개 중). 중첩 record는 별도 집계 안 함 |
| `@Schema(requiredMode=...)` 사용 | **Response 2개 파일에서만**: `CryptoMarketIndicatorsResponse`(61회), `BenchmarkComparisonResponse`(1회, `currentExchangeRate`). Request는 `DcaConfigRequest` 1회. 나머지 57개 Response는 required 표기 없음 |
| `@Schema(description=...)` 사용 Response | 54개 / 59개 |
| jakarta `@NotNull` 사용 Response | 0개 |
| nullable을 명시하는 방식 | `@Schema(types = {"string","null"})` 계열 35회(`BenchmarkComparisonResponse`에 집중), `@JsonInclude(NON_NULL/ALWAYS)`, 그리고 `@Schema(description="…null")` 문장 서술. 즉 null 가능 여부의 대부분이 **description 문장에만** 있고 스키마 속성이 아님 |
| jspecify `@NullMarked` | **0개**(어느 패키지에도 없음, `package-info`에도 없음) |
| jspecify `@Nullable` | 13개 파일(대부분 domain·application: `RegisterAccountCommand`, `AccountLedgerTotals`, `CryptoFearGreedHistory` 등). **Response DTO에는 0개** |
| `springdoc.*` 설정 | root `src/main/resources/application.yml:78`(`api-docs.path=/api-docs`, `swagger-ui.path`, `operations-sorter: method`), trading-core `trading-core/src/main/resources/application.yml:52`(동일 + `paths-to-exclude: /api/internal/**`). `springdoc.default-*`·`use-fqn` 등 required 관련 전역 옵션은 **없음** |
| OpenAPI 커스터마이저 | root `api/user/adapter/in/web/security/OpenApiConfig.java`(`OpenAPI` 빈: info·bearerAuth·태그 순서), `api/web/ErrorCodeOpenApiCustomizer.java`(`ErrorCode` enum 스키마 등록), `api/benchmark/adapter/in/web/openapi/HousingBenchmarkOpenApiCustomizer.java`, trading-core `tradingcore/stats/adapter/in/web/openapi/CryptoMarketIndicatorsOpenApiCustomizer.java`(주석: OAS 3.1에서 springdoc이 `$ref` 속성의 nullable을 버려 `oneOf [$ref, null]`로 수기 보정). `PropertyCustomizer`·`ModelConverter`는 없음 |
| BigDecimal 필드를 가진 Response | 약 30~31개(`*Response.java` 30개, dto 전체 non-Request 31개). 숫자 형식(`number`/`string`, `format`) 지정은 별도 어노테이션으로 하지 않음 — springdoc 기본(number) |
| 관찰 | required를 스키마로 내보내는 일반 규칙이 없고 `@NullMarked` 도입도 없어 springdoc은 record 컴포넌트를 전부 optional로 낼 가능성이 높다(springdoc 기본: `@NotNull`/`@Schema(required)` 없으면 required 아님. **생성 스펙 파일 미확인 — 빌드·기동 금지로 실측하지 않음**). 벤치마크·코인지표 두 곳만 수기 customizer로 보정한 상태 |

## E. 상태 플래그·서버 판정 필드

### E-1. 응답에 이미 있는 판정성 필드

| 응답 | 필드 | 의미 |
|---|---|---|
| `TradingCycleResponse`(trading-core) | `cycleInProgress`(boolean) | 진행 중 사이클 존재 — 투자금 추가 가능 여부 근거(NO_ACTIVE_CYCLE과 동일 기준) |
| 〃 | `isReverseMode`(boolean) | 리버스모드 |
| 〃 | `todayOpenBatchMissed`(Boolean, 등록 응답만) | 오늘 개장 배치 누락 경고 |
| 〃 | `ledgerDeposit` | 투자금 인출 최대 기준(서버가 계산해 내려줌) |
| `NextOrdersResponse` | `skipReason`(enum, 정상이면 null), `ledgerCheck`, `accountCashCheck`, `accountHoldingsCheck`(각 `sufficient`, `trimmed`) | 주문 미리보기 경고. 서버 판정 |
| `DcaExecutionPageResponse.Item` / `DcaRuleResponse.LastExecution` | `status`, `skipReason`, `detail` | DCA 회차 판정 사유 |
| `StrategySeedPreviewResponse` | `skipReason` | 시드 미리보기 불가 사유 |
| `UserResponse` | `hasTelegram`, `rejectReason`(REJECTED에서만 노출) | |
| `MarketSessionResponse` | `isDst` | 서머타임 |
| `FinanceCategoryResponse` | `system` | 시스템 카테고리(수정 불가 판단 근거) |
| `MonthlyClosingResponse` | `completed`, `closedAt`, `groupId` | 마감 여부(쓰기 가드의 근거는 서버 `MONTH_CLOSED`) |
| `AdminTradeCorrectionResponse` | `cycleEnded`, `strategyStatus` | |
| 페이지 응답 | `hasMore`, `nextCursor`, `totalElements`, `totalPages` | |

**`can*` 접두 플래그는 전체 Response에 0개**, `*Allowed`·`*Editable`·`*Blocked` 류도 0개. 사유 코드형은 `skipReason`(3곳)·`rejectReason`뿐.

### E-2. UI가 여러 필드로 추론해야 할 것으로 보이는 대표 응답

| 응답 | UI가 추론하는 것으로 보이는 판정 | 근거 필드 |
|---|---|---|
| `TradingCycleResponse`(전략 상세) | 일시중지/재개/종료/투자금 추가·인출/삭제 가능 여부, "시작 예정" 여부 | `status`(String), `startDate`(미래면 시작예정, 설명문에만 규칙 기술), `cycleInProgress`, `type`. 허용 액션 목록·`canXxx`가 없다. `status`가 enum이 아닌 `String`이라 OpenAPI 상 열거형이 아님 |
| 〃 `vr`(VrSummary) | VR 램프 현재 단계·다음 변화 시점 | 램프 파라미터 8개만 주고 현재 유효값(경과주 기반 현재 G·poolLimitRate)은 `gradient`/`poolLimitRate`로 일부만 제공. 경과 계산 필요 여부는 미확인 |
| `AccountResponse` | API 키 만료 임박·만료 여부 | `apiKeyExpiresAt`(Instant)만. `expired`/남은 일수/상태 플래그 없음 → UI가 현재 시각과 비교(원칙의 "상태 판정" 항목에 해당) |
| `NextOrdersResponse`(주문 미리보기) | 주문 가능 여부·경고 수준 | `skipReason`·세 개의 `*Check`(null 여부와 `sufficient`/`trimmed`의 조합으로 해석). 단일 상태 코드나 "주문 가능" 플래그 없음. `position`·`orders`가 skip 시 null/빈 목록인 규칙도 필드 조합으로 알아야 함 |
| `MonthlyClosingResponse` | 현재 스코프의 활성 마감 행 | 같은 month에 개인·그룹 2건 반환, UI가 `groupId`로 선택(응답 주석에 명시) |
| `AssetSnapshotResponse` | 순자산·부채 판정 | `rootCategoryId == FinanceCategory.SYSTEM_LOAN_ID` 비교를 UI가 수행(응답 주석에 명시) |
| `DailyTransactionResponse.ItemDto` | 매수/매도 부호별 합계 | `direction` + `tradeAmount`(부호 없음) |
| 거래/주문 응답 전반 | 코드→문구 | `status`·`direction`·`orderType`이 String인 응답이 다수(`StrategyOrdersResponse.Item`, `TradingCycleResponse`, `DcaExecutionPageResponse`), enum 타입인 응답도 있어 혼재 |

## F. 오류 응답

| 항목 | 현황 |
|---|---|
| `ErrorCode` 상수 | **13개**(`shared/.../platform/web/ErrorCode.java`): BROKER_UNAVAILABLE, BROKER_CREDENTIAL_INVALID, BROKER_IP_NOT_ALLOWED, BROKER_PERMISSION_DENIED, BROKER_RATE_LIMITED, DUPLICATE_ACCOUNT, ALREADY_ORDERED_TODAY, DCA_MANUAL_EXECUTION_UNSUPPORTED, ORDER_NOT_CANCELLABLE, MONTH_CLOSED, ACCESS_DENIED, COOLDOWN_ACTIVE, TRADING_CORE_UNAVAILABLE. OpenAPI `components.schemas.ErrorCode`로 노출(`ErrorCodeOpenApiCustomizer`) |
| ProblemDetail에 실리는 필드 | RFC 7807 기본(`status`, `title`, `detail`; `type`·`instance`는 설정 안 함) + 확장 `code`(ErrorCode 이름, **code 있는 예외에만**, 없으면 키 자체 생략 `ProblemDetailMappings:120`) + 429 쿨다운 한정 `retryAfter`(+ `Retry-After` 헤더, `GlobalExceptionHandler:75-79`). `title`은 영어 고정 문구("Invalid Request", "Conflict" 등) |
| `code` 없이 나가는 오류 | 대부분. GENERIC 매핑(IAE→400 "Invalid Request", ISE→400 "Invalid State", NoSuchElement→404, SecurityException→403만 `ACCESS_DENIED`)과 도메인 409 충돌 다수(`Conflict`, `MONTH_CLOSED`만 code 보유). UI가 필드 강조·동작 분기를 하려면 title/status로 추론해야 함 |
| `detail` 한국어 사용자 문장 보장 범위 | **보장되는 것**: ① 고정 문구로 대체되는 예외(NoSuchElement, MissingServletRequestParameter, MethodArgumentTypeMismatch, DateTimeParse, HttpMessageNotReadable, NoResourceFound, HttpRequestMethodNotSupported, catch-all 500 "예기치 않은 오류가 발생했습니다.") ② application·domain 패키지의 `new IllegalArgumentException/IllegalStateException("…")` 리터럴은 `ExceptionMessageFormatTest`가 "~니다."로 끝나는 한국어 완전문장을 소스 스캔으로 강제(동결 허용목록 `exception-message-allowlist.txt`는 현재 헤더 주석 1줄뿐 = 위반 0) |
| **보장되지 않는 것** | ① **Bean Validation 오류**(`MethodArgumentNotValidException`)는 `validationMessage()`가 `"[필드명: 기본메시지, ...]"`을 그대로 내보냄(`ProblemDetailMappings:125-130`, root `GlobalExceptionHandler:84`·trading `TradingExceptionHandler:92` 공통). 필드명은 영어 camelCase, 기본 메시지는 Hibernate Validator 로케일 의존(Request의 검증 어노테이션 65개 중 `message=` 지정 4개뿐) → 한국어 문장 아님·대부분 영어 가능성. ② IAE/ISE를 application·domain 밖(adapter·web)에서 던지는 경우는 스캔 비대상(`LAYER_PATH = /(application|domain)/`). ③ 동적 값을 이어 붙이는 `"…입니다: " + id` 형태는 스캔이 위반으로 잡도록 되어 있으나 동결 목록이 비어 있으므로 현재는 없음. ④ 브로커 예외 `detail`(BrokerApiException 계열)이 고정 문구인지는 이 조사에서 개별 확인하지 못함 |
| 두 프로세스 | root `GlobalExceptionHandler`(`api/web`)와 trading-core `TradingExceptionHandler`(`tradingcore/web`)가 공용 `ProblemDetailMappings`(shared)를 공유. 엔트리 테이블 `MAPPINGS`가 SSOT |
