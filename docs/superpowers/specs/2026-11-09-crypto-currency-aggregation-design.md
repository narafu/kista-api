# 코인 계좌 통화별 합산 집계 — 설계

작성 2026-11-09. 대상 레포: kista-api(구현) + kista-ui(인계 메모만, 구현은 UI 세션).

## 의도

코인 계좌(UPBIT=KRW, BINANCE=USDT)는 사이클 성과 목록·STRATEGY 투자지수에는 나오지만, 통화가 섞이는 사용자 합산 경로에서는 `Currency.USD` 필터로 빠져 있다. 사용자 요구는 "크립토·주식 구분 없이 일관"이다. 모든 합산 경로가 같은 규칙으로 돌고, 코인만 다르게 다루는 특수 경로를 만들지 않는다.

## 결정 (사용자 확정)

- **환율 변환 없음, 통화별 분리 집계.** 각 통화 묶음은 지금 USD 경로와 같은 계산을 한다. 참고용 환산 총액도 넣지 않는다.
- **USDT ≠ USD.** 1:1 합산도 환율 가정이라 하지 않는다. 통화 판정은 `Broker.getCurrency()` 하나로 한다.
- **파생 지표(전략 유형 비교, 승률, 평균 수익률)도 통화 묶음 안에서 계산한다.**
- **MOCK은 합산 경로에서 계속 뺀다.**
- **`NON_USD_BROKERS` 두 경로(텔레그램 `/status`, 계좌 거래 이력)와 일별 거래내역도 이번 범위에 넣는다.**
- **API·UI 연속 배포 중 잠깐 깨지는 것은 감수한다.** 호환 필드는 두지 않는다. 그래도 아래 설계는 대부분 하위 호환이다.
- **배포는 API 1회 + UI 1회.** 커밋은 경로별로 나눈다.
- **로컬 크립토 시드를 첫 태스크로 넣는다.** kista-ui 세션 요청이고, 로컬 검증의 전제 조건이다.

## 목표 / 비목표 / 반목표

- 목표: summary, equity-curve, PORTFOLIO 투자지수(벤치마크), 계좌 거래 이력, `/status`, 일별 거래내역이 코인 계좌를 포함하고 통화를 정확히 표기한다.
- 비목표: 환율 환산, 통화 합산 총액, `/history`의 SOXL 하드코딩, KRW 묶음 금액이 scale 2로 나오는 문제(`totalAssetWithAvgPriceFallback`. UI `fmtMoney`가 KRW를 정수로 표시하므로 이번에는 그대로 둔다).
- 반목표: 서로 다른 통화의 금액이 한 숫자로 합쳐지는 것. 코인만을 위한 분기를 서비스에 추가하는 것.

## API 설계

### 1. summary · equity-curve (trading-core `TradingStatsService`)

- `GET /api/stats/summary?currency=USD`, `GET /api/stats/equity-curve?currency=USD&type=&from=&to=`
  - `currency` 파라미터는 생략 가능하고 기본값은 `USD`다. 지금 UI는 수정 없이 그대로 동작한다.
  - 타입은 `Currency` enum이다. 잘못된 값은 기존 타입 불일치 400 경로를 탄다. 계좌가 없는 통화(JPY 등)는 빈 결과를 돌려준다.
- `loadCycles(userId, aggregate)`의 필터 `getCurrency() == Currency.USD`를 `== currency`로 바꾼다. 사이클 성과 목록(aggregate=false)은 지금처럼 전부 포함한다.
- summary 응답에 `availableCurrencies: Currency[]`를 추가한다.
  - **요청한 currency와 무관하게** 계산한다. MOCK이 아닌 계좌 중 사이클이 1개 이상인 계좌의 통화를 중복 없이 모은 목록이다.
  - 정렬은 `Currency` enum 선언 순서를 따른다.
  - UI는 이 목록으로 통화 탭을 만든다.
- 캐시 키 `SummaryKey`·`EquityCurveKey`에 currency를 추가한다. 사용자 단위 무효화(`invalidateUserCache`)는 그대로 둔다.
- 응답 금액 형식은 바꾸지 않는다(통화 단위 없는 BigDecimal). 통화 해석은 요청 currency로 한다.

### 2. PORTFOLIO 투자지수 + 벤치마크

- trading-core
  - `InvestmentPointsQuery.fetch`에 `Currency currency`를 추가한다.
  - 내부 API `GET /api/internal/trading/stats/investment-points`에 `currency` 파라미터를 추가한다. 생략 시 USD다.
  - PORTFOLIO 필터를 `== currency`로 바꾼다.
- 포지션 처리
  - PORTFOLIO 포지션은 사용자 전체(`findByUserAndRange`)로 읽는다.
  - 그래도 `InvestmentIndexCalculator`는 `cycleById`에 없는 사이클의 포지션을 버린다(`InvestmentIndexCalculator:58`). 그래서 사이클 필터만 바꾸면 다른 통화 포지션은 섞이지 않는다. 테스트로 고정한다.
- 응답 계약 `InvestmentPointsResult`·`contract.stats.InvestmentPointsResponse`
  - 기존 `strategyCurrency`/`selectedStrategy.currency`는 그대로 둔다.
  - 최상위에 `currency`를 추가한다. 값은 STRATEGY일 때 전략 계좌 통화, PORTFOLIO일 때 요청 통화다.
- STRATEGY scope에서 `currency`가 같이 오면 **무시한다.** 전략 계좌 통화가 우선한다. UI가 페이지 단위 통화를 그대로 넘겨도 안전하게 하려는 것으로, 내 판단이다.
- root benchmark
  - `BenchmarkController` 두 비교 라우트(housing·ETF)에 `currency` 파라미터를 추가한다. 기본값은 USD다.
  - 값은 `BenchmarkService` → `InvestmentPointsPort.fetch` → `InvestmentPointsHttpAdapter`로 전달한다.
  - **`BenchmarkComparisonKey`에 currency를 추가한다.** 빠뜨리면 USD와 USDT의 PORTFOLIO 결과가 캐시 항목 하나를 같이 쓴다.
- `BenchmarkComparisonResponse:99`의 `strategy == null ? USD : strategy.currency()`는 결과에 실린 투자 통화(위 최상위 `currency`)를 쓰도록 바꾼다. `Quality.investmentCurrency`와 notice가 PORTFOLIO USDT/KRW도 정확히 표기하게 된다.
- 현재 환율(`currentExchangeRate`)은 지금처럼 정보성 표시만 한다. 수익률 환산에는 쓰지 않는다.

### 3. `NON_USD_BROKERS` 제거 (trading-core `CyclePositionPersistenceAdapter`)

- `findRecentByUserId`·`findByAccountIdWithCursor` 네이티브 쿼리에서 `AND a.broker NOT IN (:excludedBrokers)` **절 자체를 삭제한다.** 빈 리스트를 넘기면 PG 구문 오류가 난다. 상수와 파라미터도 함께 제거한다.
- 계좌 거래 이력 `GET /api/accounts/{id}/cycle-history`
  - 계좌 단위 조회라 UI가 이미 계좌 통화를 알고 있다(`brokerCurrency`). 그래서 응답 형식은 바꾸지 않는다.
  - UI는 `$` 대신 계좌 통화로 포맷한다.
- `/status`: **전략별 최신 포지션 1건씩**으로 바꾼다(사용자 확정, 옵션 b). 지금처럼 사용자 전체에서 최신 1건만 보여주면 매일 도는 코인 DCA가 주식 포지션을 가리기 때문이다.
  - 영속 계층
    - `findRecentByUser(userId, limit)`(소비처는 `PortfolioService.getCurrent` 하나뿐)를 `findLatestPerStrategyByUser(userId)`로 대체한다.
    - 쿼리는 삭제되지 않은 전략·계좌마다 최신 `cycle_position` 1건이다(PG `DISTINCT ON (s.id)` + `ORDER BY s.id, cp.created_at DESC, cp.id DESC`).
    - 네이티브 쿼리라 soft delete 조건을 직접 쓴다.
  - MOCK 포함 여부는 지금 동작을 유지한다(현재 쿼리에 MOCK 필터가 없다).
  - 유스케이스와 내부 API
    - `PortfolioUseCase.getCurrent`가 목록을 반환하게 바꾼다.
    - `GET /api/internal/trading/stats/portfolio/current`는 `List<PortfolioCurrentResponse>`를 반환한다. 빈 목록이면 200 `[]`이고, 지금의 404·`NoSuchElementException` 경로는 제거한다.
  - 계약 `PortfolioCurrentResponse`에 `strategyType`과 `currency`(포지션 계좌 통화)를 추가한다. API와 root를 연속 배포하는 사이 `/status`가 잠깐 실패하는 것은 감수한다.
  - root 쪽
    - `PortfolioQueryPort.getCurrent`·`PortfolioQueryHttpAdapter`는 목록을 받는다.
    - `TelegramBotService.buildStatusMessage`는 전략마다 블록 하나를 출력한다(유형·종목 헤더, 보유·평가액·예수금·총자산). 빈 목록이면 기존 "데이터 없음" 문구를 쓴다.
    - `$` 리터럴과 `double` 계산 대신 BigDecimal + `Currency.format`을 쓴다. 수량은 `Quantities.normalize` 표기를 쓴다(코인 소수 수량).
    - 통화가 다른 전략의 총자산은 합산하지 않는다.
- **`usdDeposit`은 `deposit`으로 전면 개명한다**(아래 "6. 예수금 필드 전면 개명", 사용자 확정).

### 4. 일별 거래내역 (trading-core `AccountStatisticsService.toDailyTransactionResult`)

- `!o.ticker().isCrypto()` 필터를 삭제한다.
- `DailyTransaction.quantity`를 `int`에서 `BigDecimal`(`Quantities.normalize`)로 바꾼다. `intValueExact`는 제거한다.
- `currency`에는 체결 계좌의 `broker().getCurrency()`를 넣는다.
  - `Order`에는 broker가 없으므로 accountId → Account 맵으로 구한다. 계좌 단위 조회는 이미 소유 검증한 계좌 1개, 사용자 단위 조회는 `accountPort.findByUserId`로 맵을 만든다.
- `tradeAmountUsd`는 `tradeAmount`로 개명한다(도메인 record와 응답 DTO). 금액은 `price × quantity`를 그 통화의 `amountScale()`로 반올림한다. UI 계약이 깨지는 변경이다.
- `summary`(`buyAmountFcr`·`sellAmountFcr`·수수료)는 **응답에서 삭제한다.** 내 판단이다.
  - 사용자 단위에서는 통화가 섞인 합계가 되고, 수수료는 항상 0이며, UI에서 렌더링하는 곳이 없다(타입과 테스트 fixture만 있다).
  - `DailyTransactionSummary`도 함께 제거한다.
- MOCK 포함 여부는 지금 동작을 유지한다(사용자 단위 쿼리에 broker 필터가 없다).
- 계좌 맵에 없는 주문은 `IllegalStateException`으로 처리한다. 사용자 단위 쿼리가 이미 `accounts.deleted_at IS NULL`로 조인하므로 정상 경로에서는 생기지 않는다. USD로 가정해 조용히 잘못 표기하거나 건너뛰지 않는다. 내 판단이다. 구현 시 조인 조건을 확인한다.
- 미국 휴장일 캘린더(`WeeklyMarketCalendar`) 칸에 주말·휴장일 코인 체결이 표시되는 것은 "일관" 원칙에 따라 그대로 둔다. 내 판단이다.

### 5. 로컬 크립토 시드 (`scripts/dev-seed`)

- `dev-seed.sql`에 다음을 추가한다.
  - BINANCE(USDT) 계좌와 ETH DCA 전략 1개, UPBIT(KRW) 계좌와 DCA 전략 1개
  - 각 전략에 `strategy_cycle`과 약 3~4개월치 일별 `cycle_position` 스냅샷(벤치마크 공통 월 확보)
  - 코인 체결 `orders` 몇 건(terminal 상태, 소수 수량 — 일별 거래내역 확인용)
  - `cycle_capital_event` 1건(INJECT, CQ-69 흐름 확인용)
- DCA 서브타입 테이블 확인
  - INFINITE의 `*_infinite`처럼 DCA 전용 version·position 서브타입 테이블이 있는지 DDL로 확인하고, 있으면 함께 넣는다.
  - 기존 MAGX 블록의 삽입 패턴을 따른다.
- 계좌 자격증명
  - 기존 KIS `[시드]` 블록처럼 MOCK 계좌 암호문을 복사한다. MOCK 계좌가 없으면 블록을 건너뛴다.
  - `account_no_hash`는 NULL로 둔다.
- 시드 코인 전략은 **PAUSED**로 넣는다. 복사한 가짜 자격증명으로 로컬 스케줄러가 실제 Binance·Upbit를 호출하지 않게 하려는 것이다.
- 계좌 화면의 `crypto-portfolio`(실 거래소 잔고 조회)는 로컬 시드 계좌에서 실패할 수 있다. 이건 감수하고 `local-dev.md`에 적는다.
- 재실행해도 안전해야 한다. 기존 블록의 멱등 패턴을 따른다.

### 6. 예수금 필드 전면 개명 (`usdDeposit` → `deposit`)

kista-ui 세션에서 사용자가 결정했고, 이 세션에서 다시 확인받았다. 코인 원장의 예수금은 USDT·KRW이므로 `usd` 접두사가 틀린 이름이 된다.

- **이름 규칙**
  - `usdDeposit`은 `deposit`, `UsdDeposit`이 들어간 식별자는 `Deposit`으로 바꾼다. 예: `initialUsdDeposit` → `initialDeposit`.
  - 이미 있는 `DepositAmounts`·`AccountLedgerTotals.deposit` 표기와 맞춘다.
- **범위**
  - 코드: api main 약 74개 파일(trading-core 대부분 + shared contract + root admin), 테스트를 포함하면 약 113개 파일
  - 응답·내부 계약 DTO 필드: cycle-history, next-orders, 포트폴리오, `PortfolioCurrentResponse` 등
  - 데이터: `dev-seed.sql`, `flows.yml`의 `usd_deposit` 표기, 현행 agent 문서
  - archive 문서와 적용된 마이그레이션(V1·V17)은 고치지 않는다.
- **제외**
  - 벤더 응답 JSON 필드명(`@JsonProperty`로 매핑하는 KIS·Toss 원문 키)
  - 진짜 USD 의미인 브로커 값(`getUsdBuyableAmount` 등 `usdBuyable*`)
  - 판단 기준: 식별자가 원장·전략 예수금을 뜻하면 개명하고, 벤더가 준 USD 원값을 뜻하면 유지한다.
- **DB**
  - trading-core 새 마이그레이션(`migration-trading` 최신 다음 번호)으로 `ALTER TABLE trading.cycle_position RENAME COLUMN usd_deposit TO deposit`을 실행한다.
  - trading 스키마는 expand/contract 대상이 아니고 kista-trading이 stop-first 단일 인스턴스라 한 번에 바꿔도 된다.
  - 엔티티 `@Column`과 네이티브 쿼리의 컬럼명을 함께 바꾼다.
- **EPR 위험**
  - `InsufficientBalanceEvent` 등 `event_publication`에 직렬화되는 이벤트 record의 필드명도 바뀐다.
  - 배포 직전에 미완료 publication(`completion_date IS NULL`)이 0건인지 읽기 전용 SELECT로 확인하고, 남아 있으면 처리될 때까지 배포를 미룬다. 계획의 배포 체크리스트에 넣는다.
  - Redis 스트림 payload에 해당 필드가 있는지도 grep으로 확인한다.
- **커밋**
  - 동작 변경 없는 리팩토링 커밋으로 따로 만들고 기능 커밋과 섞지 않는다.
  - 계획의 첫 코드 태스크(시드 다음)로 둔다. 시드 SQL은 새 컬럼명으로 작성한다.
- **UI 영향**: 응답 필드명이 바뀌므로 UI와 함께 배포해야 한다(깨지는 변경).

## 테스트 (구현 시 기준)

- `TradingStatsServiceTest`
  - 기존 `합산_경로는_크립토_KRW_계좌를_제외하지만…`·`aggregateExcludesBinanceAccount`를 "요청 통화 계좌만 합산" 테스트로 바꾼다. USD 요청에는 코인이 제외되고, USDT 요청에는 BINANCE만 포함된다.
  - `availableCurrencies`가 요청 통화와 무관한지 확인한다.
  - 캐시 키가 통화별로 분리되는지 확인한다.
- `InvestmentPointsQueryServiceTest`
  - PORTFOLIO+USDT에 USD 포지션이 섞이지 않는지(계산기의 사이클 조인 메커니즘을 고정)
  - STRATEGY가 currency 파라미터를 무시하는지
- root `BenchmarkServiceTest`·`BenchmarkControllerTest`
  - currency 파라미터 전달과 캐시 키 분리
  - PORTFOLIO USDT의 `quality.investmentCurrency`
- `CyclePositionPersistenceAdapter` DB 테스트: 코인 계좌 포지션이 두 조회에 포함되는지
- `AccountStatisticsService`: 지금은 일별 거래내역 테스트가 없다. 다음을 새로 추가한다.
  - 코인 소수 수량 체결
  - 계좌 통화 표기
  - KRW `amountScale` 반올림
- `CyclePositionPersistenceAdapter` DB 테스트: 전략별 최신 1건(같은 전략에 여러 스냅샷이 있을 때 최신만, 삭제된 전략 제외)
- `TelegramBotService`: `/status`가 여러 전략을 각 통화 표기로 출력하는지, 빈 목록 문구
- 변이 확인: 필터를 되돌렸을 때 새 테스트가 실패하는지

## 문서 드리프트 (같은 작업에서 갱신)

- `docs/agents/modules/tradingcore/stats.md` "DCA 포함" 절: "후속: 통화별 분리 집계"와 `NON_USD_BROKERS` 문장을 새 규칙으로 교체한다. 일별 거래내역 문장도 갱신한다.
- `docs/agents/modules/api/benchmark.md`: "PORTFOLIO는 USD 계좌만 합산해 USD" 문장을 교체한다.
- `src/test/resources/architecture/flows.yml` 819~820행 부근: summary·equity-curve의 통화 파라미터를 반영한다.
- README: 영향 없을 것으로 본다. 구현 시 grep으로 확인한다.
- `docs/agents/local-dev.md`: 시드 내용과 crypto-portfolio 실패 가능성을 적는다.

## kista-ui 인계 메모 (구현 완료 후 작성 — 담을 내용)

- 통계 페이지
  - 상단에 통화 탭을 두고 summary·equity-curve·유형 비교에 같은 currency를 적용한다.
  - `availableCurrencies`가 2개 이상일 때만 탭을 보여준다.
  - **기본 통화는 목록에 USD가 있으면 USD, 없으면 목록의 첫 번째 통화로 한다.** 코인만 가진 사용자가 USD 요청으로 빈 화면을 보는 것을 막기 위해서다.
  - `StatsKpiRow`·`StrategyTypeComparison`의 `$`·`fmtSignedUsd` 하드코딩을 `fmtMoney`/`fmtSignedMoney(n, currency)`로 바꾼다.
- 벤치마크 PORTFOLIO
  - 같은 통화 탭을 두고 `currency` 파라미터를 전달한다.
  - `quality.investmentCurrency`는 이미 반영돼 있다.
- 계좌 거래 이력 `CycleHistoryTable` 등: `$`·`fmtUsd`를 계좌 통화 포맷으로 바꾼다.
- `usdDeposit` → `deposit` 개명: 엔드포인트별로 바뀐 응답 필드 목록, API 커밋 해시, 통화를 어디서 가져오는지(계좌 `currency`/`brokerCurrency`)를 메모에 싣는다. UI는 `fetch:spec`/`gen:types` 후 entities/trade·entities/order·widgets/account-detail·widgets/cycle-history를 고친다.
- 일별 거래내역
  - `tradeAmountUsd`를 `tradeAmount`로, `quantity`를 number(소수)로 바꾸고 `summary` 필드를 삭제한다.
  - `useDailyTradesRangeQuery`는 날짜와 통화별로 집계한다. 서로 다른 통화를 한 금액으로 합치면 안 된다.
  - `WeeklyMarketCalendar` 배지에 통화별 금액을 표시한다.
- 깨지는 변경은 일별 거래내역(`tradeAmountUsd`·`quantity`·`summary`)과 `usdDeposit` 개명이다. 나머지는 하위 호환이다.
- 인계 대상은 ui-stat 세션이다.

## 빌더에게 맡기는 것

- `loadCycles`에 통화 인자를 넘기는 방식, `availableCurrencies`를 계산하는 위치(추가 조회 없이 계좌·사이클 로드를 재사용)
- 시드 SQL의 구체 값(가격 경로, 날짜 범위)
- 커밋 분할 단위(경로별: 시드 / 예수금 개명 / summary·곡선 / 투자지수·벤치마크 / NON_USD·`/status` / 일별 거래내역 / 문서)
