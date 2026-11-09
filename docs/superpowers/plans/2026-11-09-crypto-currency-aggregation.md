# 코인 계좌 통화별 합산 집계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자 합산 경로 전체(summary·equity-curve·PORTFOLIO 투자지수·계좌 거래 이력·`/status`·`/history`·일별 거래내역)가 코인 계좌를 포함하고, 환율 변환 없이 통화별로 분리해 집계·표기한다.

**Architecture:** 합산 엔드포인트는 `currency` 파라미터(기본값 USD)로 한 통화 묶음만 계산한다. 필터는 지금 `== Currency.USD`로 하드코딩된 자리에 요청 통화를 넣는 방식으로 바꾼다. 행 단위 응답(`/status`·`/history`·일별 거래내역)은 행마다 계좌 통화를 싣는다. 이에 앞서 `usdDeposit`을 `deposit`으로 전면 개명하는 리팩토링을 먼저 커밋한다.

**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith, JPA(native query), Flyway(`migration-trading`), PostgreSQL, JUnit5/Mockito/AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-09-crypto-currency-aggregation-design.md`

## Global Constraints

- 통화 판정은 `Broker.getCurrency()` 하나로 한다(BINANCE=USDT, UPBIT=KRW, 그 외=USD). USDT와 USD는 별개 통화다. 환율 변환은 하지 않는다.
- 합산 경로(summary·equity-curve·PORTFOLIO)는 MOCK을 계속 제외한다. 행 단위 경로의 MOCK 처리는 현재 동작을 유지한다.
- `currency` 파라미터는 생략할 수 있고 기본값은 `USD`다. 타입은 `com.kista.shared.kernel.Currency`다.
- 네이티브 쿼리에는 soft delete 조건(`deleted_at IS NULL`)을 직접 쓴다.
- 커밋 메시지는 한글 Conventional Commit을 쓰고, author는 `narafu <narafu@kakao.com>`이다. 리팩토링 커밋(Task 1)은 동작을 바꾸지 않는다.
- 신규 코드에는 `//` 인라인 주석을 단다(Javadoc 금지). IAE·ISE 메시지는 "~니다."로 끝낸다.
- `--tests` 필터는 서브프로젝트를 지정해 쓴다(`:test`·`:trading-core:test`). 전체 스위트(`./gradlew test`)는 Task 7에서 1회만 돌린다. DB 테스트를 돌리려면 `docker compose up -d postgres`가 먼저 떠 있어야 한다.
- Java 파일 BOM 삽입을 주의한다(`.claude/rules/java-encoding.md`).

## Review Focus

- 코인 계좌만 있는 사용자가 기본값 USD로 summary를 요청하는 경우: 응답은 비어 있어야 하고 `availableCurrencies`에는 `[USDT]`가 들어 있어야 한다. → Task 3 `availableCurrencies는_요청_통화와_무관하다`
- 같은 사용자가 USD와 USDT로 연달아 요청하는 경우: 캐시 항목이 섞이지 않아야 한다. trading-core summary·곡선(Task 3)과 root 벤치마크(Task 4) 양쪽에 테스트를 둔다.
- 방금 등록돼 스냅샷이 없는 전략, `closingPrice`가 null인 포지션, 삭제된 전략: `/status` 목록에서 제외되거나 NPE 없이 출력돼야 한다. → Task 5 DB 테스트와 `TelegramBotServiceTest`
- KRW 체결(가격 × 소수 수량)은 정수로 반올림하고, USDT는 소수 둘째 자리로 반올림한다. `intValueExact` 예외가 나면 안 된다. → Task 6
- PORTFOLIO에 계좌가 없는 통화(예: JPY)를 요청하는 경우: 예외 없이 빈 points와 기존 empty 사유를 돌려줘야 한다. → Task 4

---

### Task 1: `usdDeposit` → `deposit` 전면 개명 (동작 불변 리팩토링)

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V18__cycle_position_rename_deposit.sql`
- Modify: `usdDeposit`·`UsdDeposit`·`usd_deposit`이 들어간 main·test 파일 전부(약 113개). 대상은 `grep -rlE "usdDeposit|UsdDeposit|usd_deposit" --include=*.java src trading-core/src shared/src`로 찾는다. `CyclePositionEntity` `@Column`과 `CyclePositionJpaRepository` 네이티브 SQL도 포함한다.
- Modify: `scripts/dev-seed/dev-seed.sql`(컬럼명), `src/test/resources/architecture/flows.yml:884`, 현행 `docs/agents/**` 문서(archive와 `docs/history`는 제외)

**Interfaces:**
- Produces:
  - `CyclePosition.deposit()`, `CyclePositionHistoryEntry.deposit()`, `AccountBalance.deposit()`, `PortfolioCurrentResponse.deposit()`
  - `initialUsdDeposit` 계열은 `initialDeposit`으로 바꾼다.
  - 응답 JSON 키 `usdDeposit`은 `deposit`으로 바뀐다(cycle-history, next-orders, portfolio-summary 등).

- [ ] **Step 1: 마이그레이션 작성.** `ALTER TABLE trading.cycle_position RENAME COLUMN usd_deposit TO deposit;` 한 줄과 사유 주석을 넣는다. V1·V17은 고치지 않는다.
- [ ] **Step 2: 식별자를 일괄 개명한다.** 규칙: `usdDeposit`은 `deposit`, `UsdDeposit`은 `Deposit`이다(예: `initialUsdDeposit` → `initialDeposit`). 같은 이유로 `CyclePositionHistoryEntry.marketValueUsd()`·`totalAssetUsd()`도 `marketValue()`·`totalAsset()`으로 바꾼다. 이 entry는 이제 코인 통화 값도 담기 때문이다. 다음은 바꾸지 않는다.
  - 벤더 JSON 원문 키(`@JsonProperty("...")` 문자열)
  - `usdBuyable*`·`getUsdBuyableAmount`
  - 개명 결과가 기존 이름과 충돌하는 지역 변수(같은 스코프에 `deposit`이 이미 있으면 의미가 드러나는 이름으로 바꾼다)
- [ ] **Step 3: EPR·Redis payload를 점검한다.** `grep -rn "Deposit" trading-core/src/main/java/**/event` 결과에서 `event_publication`에 직렬화되는 이벤트 record(`InsufficientBalanceEvent` 등)와 Redis 스트림 payload 필드 목록을 Task 7 배포 체크리스트에 적는다(코드 변경 없음).
- [ ] **Step 4: 잔여 이름을 확인한다.**
  - Run: `grep -rnE "usdDeposit|UsdDeposit|usd_deposit" --include=*.java --include=*.sql --include=*.yml src trading-core/src shared/src scripts | grep -v "db/migration-trading/V1__\|V17__"`
  - Expected: 출력 0줄(벤더 `@JsonProperty` 원문 키가 있다면 그 줄만 남는다)
- [ ] **Step 5: 컴파일하고 관련 테스트를 돌린다.**
  - Run: `./gradlew compileTestJava :trading-core:compileTestJava`, `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.*' --tests 'com.kista.tradingcore.stats.*'`, `./gradlew :test --tests 'com.kista.api.admin.*' --tests 'com.kista.api.architecture.*'`
  - Expected: BUILD SUCCESSFUL. `ddl-auto: validate`가 새 컬럼으로 통과해야 한다.
- [ ] **Step 6: 커밋한다.** `refactor: 원장 예수금 usdDeposit을 통화 중립 deposit으로 개명한다`

### Task 2: 로컬 크립토 시드

**Files:**
- Modify: `scripts/dev-seed/dev-seed.sql`(MAGX 블록 뒤, `COMMIT;` 앞에 크립토 블록 추가)
- Modify: `docs/agents/local-dev.md` 시드 절

**Interfaces:**
- Consumes: Task 1의 `cycle_position.deposit` 컬럼

- [ ] **Step 1: 계좌.** BINANCE `[시드] 바이낸스`와 UPBIT `[시드] 업비트` 계좌를 KIS `[시드]` 블록과 같은 방식으로 만든다. MOCK `[시드]` 암호문을 복사하고, `account_no_hash`는 NULL, id는 `md5('dev-seed:crypto:<broker>:account')`로 정한다.
- [ ] **Step 2: 전략.**
  - BINANCE ETH, UPBIT BTC DCA 전략을 각각 만든다. `status='PAUSED'`, `type='DCA'`다.
  - `strategy_version`과 `strategy_dca_rule` 1건을 함께 넣는다: BUY, `frequency`는 `DcaFrequency`의 일 단위 값, `order_type`은 DCA 규칙이 허용하는 값, `sizing_type='AMOUNT'`, `shortfall_policy`는 해당 enum의 기본값. 컬럼 값은 `DcaRule` 도메인 enum에서 확인한다.
  - DCA는 position 서브타입 테이블이 없다.
- [ ] **Step 3: 사이클과 스냅샷.** DCA는 원장 예수금 기반이다(2026-11-06 배포). 회차마다 `deposit`이 매수액만큼 줄고 `holdings`가 늘어난다. 이 모델을 정확히 따라야 로컬 summary·곡선이 의미 있는 값을 낸다. 먼저 `CryptoLedgerRecorder`·`CryptoDcaRoundExecutor`를 읽고 스냅샷 갱신 규칙을 확인한다.
  - 120일 전 시작한 진행 중 사이클을 만든다. `start_amount`·`open_amount`는 BINANCE `1000`(USDT), UPBIT `1500000`(KRW)이다.
  - 개장 스냅샷은 `deposit = open_amount`, `holdings = 0`, `avg_price = NULL`이다.
  - 그 뒤 매일(주말 포함) 04:30 KST 스냅샷을 넣는다. 일 매수액은 BINANCE 5, UPBIT 7500이다. `deposit`은 일 매수액만큼 줄고, `holdings`는 `일 매수액 / closing_price`만큼 늘며, `avg_price`는 누적 매입원가 ÷ holdings다. `closing_price`는 완만한 사인파(ETH 약 3000, BTC 약 95,000,000 기준)로 변한다.
  - SQL은 `generate_series`와 window 합계(`sum() over (order by d)`)로 누적값을 만든다.
  - 60일 전 INJECT `cycle_capital_event` 1건을 넣는다(BINANCE 500, UPBIT 750000). 컬럼은 V15 DDL을 따른다. 그날 이후 스냅샷의 `deposit`에 같은 금액을 더한다. `start_amount`의 의미는 `CapitalLedgerService` INJECT 경로를 따른다(start_amount += 금액이면 그대로 맞춘다). `open_amount`는 바꾸지 않는다.
  - 전제: 로컬 DB에 V18이 적용돼 있어야 한다. 그러려면 Task 1 이후 trading-core를 local로 한 번 기동해야 한다.
- [ ] **Step 4: 체결 주문.** 소수 수량(`filled_quantity` 예: 0.0123) terminal BUY 주문을 최근 7일에 각 계좌 3건씩 넣는다. 기존 `orders` 블록(:142)의 컬럼 패턴을 따른다.
- [ ] **Step 5: 재실행을 확인한다.**
  - Run: `docker exec -i <postgres> psql -U kista -d kistadb < scripts/dev-seed/dev-seed.sql`을 두 번 실행한다.
  - Expected: 두 번 모두 `COMMIT`. 두 번째 실행에서 행 수가 늘지 않는다(최신 날짜분 제외).
- [ ] **Step 6: `local-dev.md`를 갱신한다.** 크립토 시드 내용과 함께 "시드 코인 계좌의 crypto-portfolio(실 거래소 잔고)는 실패하는 것이 정상"이라고 적는다.
- [ ] **Step 7: 커밋한다.** `chore(dev-seed): 로컬 크립토 DCA 계좌·스냅샷 시드를 추가한다`

### Task 3: summary · equity-curve 통화별 집계

**Files:**
- Modify: `trading-core/.../stats/application/service/TradingStatsService.java`(`CycleView`, `loadCycles`, 캐시 키, `computeSummary`, `computeEquityCurve`)
- Modify: `trading-core/.../stats/application/usecase/TradingStatsUseCase.java`, `stats/domain/model/StatsSummary.java`
- Modify: `trading-core/.../stats/adapter/in/web/TradingStatsController.java`, `dto/StatsSummaryResponse.java`
- Test: `trading-core/src/test/.../stats/application/service/TradingStatsServiceTest.java`, `.../adapter/in/web/TradingStatsControllerTest.java`

**Interfaces:**
- Produces:
  - `StatsSummary getSummary(UUID userId, Currency currency)`
  - `EquityCurve getEquityCurve(UUID userId, Currency currency, StrategyType type, LocalDate from, LocalDate to)`
  - `StatsSummary(..., List<Currency> availableCurrencies)`(마지막 필드)
  - `StatsSummaryResponse.availableCurrencies: List<Currency>`
  - 엔드포인트 `GET /api/stats/summary?currency=`, `GET /api/stats/equity-curve?currency=`. `@RequestParam(defaultValue = "USD") Currency currency`

- [ ] **Step 1: 실패하는 테스트를 쓴다.** 기존 `합산_경로는_크립토_KRW_계좌를_제외하지만_사이클_성과_목록은_포함한다`와 `aggregateExcludesBinanceAccount`를 아래 테스트로 교체한다.
  - `합산_경로는_요청_통화_계좌만_포함하고_사이클_성과_목록은_전부_포함한다`: KIS, BINANCE, UPBIT, MOCK 계좌를 둔다. USD 요청 summary에는 KIS만, USDT 요청에는 BINANCE만, KRW 요청에는 UPBIT만 들어가야 한다. `getCyclePerformances`는 4개 계좌를 모두 포함한다.
  - `equityCurve는_요청_통화_계좌만_합산한다`: USDT 요청의 points 자산이 BINANCE 포지션 값과 같아야 한다.
  - `availableCurrencies는_요청_통화와_무관하다`: BINANCE만 가진 사용자가 USD로 요청하면 `byType`은 빈 목록, 합계는 0, `availableCurrencies`는 `[USDT]`다. KIS, BINANCE, UPBIT, MOCK을 가진 사용자는 enum 선언 순서로 `[KRW, USD, USDT]`이고 MOCK은 들어가지 않는다.
  - `summary_캐시는_통화별로_분리된다`: 같은 사용자가 USD와 USDT로 연달아 호출하면 각 결과가 다르고 `accountPort.findByUserId` 호출이 통화당 1회씩이어야 한다.
- [ ] **Step 2: 실패를 확인한다.**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.application.service.TradingStatsServiceTest'`
  - Expected: 컴파일 실패(시그니처 없음)
- [ ] **Step 3: 구현한다.**
  - `CycleView`에 `Currency currency`를 추가한다(계좌 `broker().getCurrency()`).
  - `loadCycles(UUID userId, Predicate<Account> accountFilter)`로 바꾼다. 합산 경로는 `a.broker() != Broker.MOCK`으로 전부 로드한 뒤 `v.currency() == currency`로 거른다. 성과 목록은 `a -> true`를 쓴다.
  - `availableCurrencies`는 MOCK 제외 로드 결과에서 currency를 중복 없이 모아 `Comparator.naturalOrder()`로 정렬한 값이다. 추가 조회는 하지 않는다.
  - `SummaryKey(UUID userId, Currency currency)`, `EquityCurveKey(..., Currency currency, ...)`. `invalidateUserCache`는 userId만 비교한다.
- [ ] **Step 4: 컨트롤러와 DTO.** 두 엔드포인트에 `@RequestParam(defaultValue = "USD") Currency currency`를 추가한다. `StatsSummaryResponse.from`이 `availableCurrencies`를 싣는다. `TradingStatsControllerTest`에 아래 두 가지를 추가한다.
  - `summary는_currency_생략시_USD로_조회한다`: 서비스 mock에 `Currency.USD` 인자로 호출됐는지 확인한다.
  - `summary는_currency를_전달하고_availableCurrencies를_싣는다`: `?currency=USDT`일 때 응답의 `$.availableCurrencies[0]`이 `"USDT"`인지 확인한다.
- [ ] **Step 5: 통과를 확인한다.**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.*'`
  - Expected: PASS
- [ ] **Step 6: 변이 확인.** 필터를 잠시 `== Currency.USD`로 되돌리고 Step 1 테스트가 실패하는지 본 뒤 원복한다.
- [ ] **Step 7: 커밋한다.** `feat(stats): summary·equity-curve를 요청 통화별로 집계하고 사용 통화 목록을 싣는다`

### Task 4: PORTFOLIO 투자지수 통화 파라미터 + 벤치마크

**Files:**
- Modify (trading-core):
  - `stats/application/usecase/InvestmentPointsQuery.java`, `InvestmentPointsResult`(위치는 `InvestmentPointsQuery` 옆 또는 domain/model — grep으로 찾는다)
  - `stats/application/service/InvestmentPointsQueryService.java`
  - `stats/adapter/in/web/TradingStatsInternalController.java`, `StatsContractMapper.java`
- Modify (shared): `shared/.../contract/stats/InvestmentPointsResponse.java`
- Modify (root):
  - `benchmark/application/port/output/InvestmentPointsPort.java`, `adapter/out/internal/InvestmentPointsHttpAdapter.java`
  - `benchmark/domain/model/BenchmarkComparison.java`, `application/service/BenchmarkComparisonBuilder.java`
  - `application/service/BenchmarkService.java`, `application/usecase/BenchmarkUseCase.java`
  - `adapter/in/web/BenchmarkController.java`, `adapter/in/web/dto/BenchmarkComparisonResponse.java`
- Test:
  - `InvestmentPointsQueryServiceTest`, `TradingStatsInternalControllerTest`
  - root `InvestmentPointsHttpAdapterTest`, `BenchmarkServiceTest`, `BenchmarkControllerTest`, `BenchmarkComparisonResponseTest`, `InternalApiContractTest`(계약 잠금 갱신)

**Interfaces:**
- Produces:
  - trading-core: `InvestmentPointsResult fetch(UUID userId, Scope scope, UUID strategyId, Currency currency, LocalDate from, LocalDate to)`
  - `InvestmentPointsResult`의 `strategyCurrency`를 `Currency currency`로 개명한다(STRATEGY는 전략 계좌 통화, PORTFOLIO는 요청 통화, non-null).
  - 내부 API `GET /api/internal/trading/stats/investment-points?...&currency=`(생략 시 USD)
  - `InvestmentPointsResponse`에 최상위 `Currency currency`를 추가한다(`selectedStrategy.currency`는 유지).
  - root: `InvestmentPointsPort.fetch(UUID, BenchmarkScope, UUID strategyId, Currency currency, LocalDate, LocalDate)`, `Result(..., Currency currency)`
  - `BenchmarkComparison`에 `Currency investmentCurrency`를 추가한다(`strategy` 다음).
  - `BenchmarkUseCase.getHousingBenchmarkComparison(userId, scope, strategyId, currency, regionCode, from, to)`, `getEtfBenchmarkComparison(userId, scope, strategyId, currency, symbol, from, to)`
  - `BenchmarkController /housing-benchmark`에 `@RequestParam(defaultValue = "USD") Currency currency`를 추가한다.

- [ ] **Step 1: trading-core 실패하는 테스트를 쓴다.** `InvestmentPointsQueryServiceTest`에서 기존 `PORTFOLIO_scope는_크립토_KRW_계좌를_제외한다`·`excludesBinanceAccount`를 아래 테스트로 교체한다.
  - `PORTFOLIO_scope는_요청_통화_계좌_전략만_조회한다`: USDT 요청이면 BINANCE 전략만 `strategyPort.findByAccountIds` 인자에 들어가고 결과 `currency()`가 USDT여야 한다.
  - `PORTFOLIO_USDT는_사용자_전체_포지션을_읽어도_USD_포지션이_섞이지_않는다`: `findByUserAndRange`가 USD·USDT 포지션을 모두 돌려줘도 points가 USDT 포지션만으로 계산된 값과 같아야 한다. `InvestmentIndexCalculator:58`의 사이클 조인 메커니즘을 고정하는 테스트다.
  - `STRATEGY_scope는_currency_파라미터를_무시하고_전략_계좌_통화를_쓴다`: USD 파라미터에 BINANCE 전략이면 `currency()`가 USDT여야 한다.
  - `PORTFOLIO_계좌_없는_통화는_빈_points를_반환한다`: JPY 요청이면 points가 비고 예외가 없어야 한다.
- [ ] **Step 2: 실패를 확인한다.**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.application.service.InvestmentPointsQueryServiceTest'`
  - Expected: 컴파일 실패
- [ ] **Step 3: trading-core를 구현한다.**
  - 서비스 PORTFOLIO 필터를 `a.broker() != Broker.MOCK && a.broker().getCurrency() == currency`로 바꾼다.
  - 내부 컨트롤러에 `@RequestParam(defaultValue = "USD") Currency currency`를 추가한다.
  - mapper가 최상위 `currency`를 싣는다.
  - `TradingStatsInternalControllerTest`에 currency 전달 케이스를 추가한다.
- [ ] **Step 4: root 실패하는 테스트를 쓴다.**
  - `BenchmarkServiceTest.PORTFOLIO_벤치마크_캐시는_통화별로_분리된다`: USD 후 USDT를 호출하면 `investmentPointsPort.fetch`가 각 1회 불려야 한다.
  - `BenchmarkComparisonResponseTest.PORTFOLIO_USDT는_investmentCurrency를_USDT로_표기한다`: `quality.investmentCurrency == "USDT"`이고 notice에 USDT 표기가 있어야 한다.
  - `BenchmarkControllerTest.currency_생략시_USD를_전달한다`
  - `InvestmentPointsHttpAdapterTest`: 쿼리에 `currency=USDT`가 실리고 응답 `currency`가 매핑되는지 확인한다.
- [ ] **Step 5: root를 구현한다.**
  - `BenchmarkComparisonKey`에 `Currency currency`를 추가한다.
  - `computeHousingComparisonBody`·`computeEtfComparisonBody`가 currency를 port로 넘긴다.
  - `BenchmarkComparisonBuilder.build`에 `Currency investmentCurrency` 인자를 추가하고 `ctx.currency()`를 넘긴다. empty 결과를 만드는 경로도 같은 값을 쓴다.
  - `BenchmarkComparisonResponse:99`는 `comparison.investmentCurrency()`를 쓴다.
  - 컨트롤러 description의 "USD 투자 성과"를 "투자 계좌 통화 기준 성과"로 바꾼다.
- [ ] **Step 6: 통과를 확인한다.**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.*'`, `./gradlew :test --tests 'com.kista.api.benchmark.*' --tests 'com.kista.api.architecture.*'`
  - Expected: PASS
- [ ] **Step 7: 변이 확인.** root 캐시 키에서 currency를 빼면 캐시 분리 테스트가 실패하는지 본 뒤 원복한다.
- [ ] **Step 8: 커밋한다.** `feat(benchmark): PORTFOLIO 투자지수를 요청 통화별로 계산하고 투자 통화를 표기한다`

### Task 5: `NON_USD_BROKERS` 제거 + `/status` 전략별 + `/history` 전 종목

**Files:**
- Modify (trading-core):
  - `trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java`, `CyclePositionJpaRepository.java`, `trading/application/port/output/CyclePositionPort.java`
  - `trading/adapter/out/persistence/OrderPersistenceAdapter.java:144`, `OrderJpaRepository`(findByUser 쿼리), `trading/application/port/output/OrderPort.java:48`
  - `stats/application/usecase/PortfolioUseCase.java`, `stats/application/service/PortfolioService.java`(+ `AccountPort` 주입)
  - Create: `stats/domain/model/PortfolioPosition.java`, `stats/domain/model/PortfolioOrder.java`
  - `trading/domain/model/CyclePositionHistoryEntry.java`(`accountId` 추가)
  - `stats/adapter/in/web/PortfolioQueryInternalController.java`, `StatsContractMapper.java`
- Modify (shared): `contract/stats/PortfolioCurrentResponse.java`, `PortfolioOrderResponse.java`
- Modify (root):
  - `admin/application/port/output/PortfolioQueryPort.java`, `admin/adapter/out/internal/PortfolioQueryHttpAdapter.java`
  - `admin/adapter/in/telegram/TelegramBotService.java:124-159`
- Test:
  - `CyclePositionPersistenceAdapterTest`(DB), `OrderPersistenceAdapterDbTest`
  - `PortfolioServiceTest`, `PortfolioQueryInternalControllerTest`
  - root `PortfolioQueryHttpAdapterTest`, `TelegramBotServiceTest`, `InternalApiContractTest`

**Interfaces:**
- Produces:
  - `CyclePositionPort.findLatestPerStrategyByUser(UUID userId): List<CyclePositionHistoryEntry>`(전략별 최신 1건, `findRecentByUser` 대체·삭제)
  - `CyclePositionHistoryEntry`에 `UUID accountId`를 추가한다(`strategy.accountId()`, 전략이 없으면 null). 어댑터는 지금처럼 `toEntries`·`toEntry`가 `strategyPort`로 찾은 Strategy에서 채운다. 엔티티 네이티브 쿼리에는 계좌 컬럼을 넣지 않는다. 계좌 커서·전략 커서 조회도 같은 `toEntry`를 쓰므로 자동으로 채워진다.
  - 통화 해석은 stats가 한다. stats `domain/model`에 두 record를 둔다: `PortfolioPosition(CyclePositionHistoryEntry position, Currency currency)`, `PortfolioOrder(Order order, Currency currency)`.
  - `OrderPort.findByUser(UUID userId, LocalDate from, LocalDate to)`(ticker 인자 제거)
  - `PortfolioUseCase.getCurrent(UUID userId): List<PortfolioPosition>`
  - `PortfolioUseCase.getHistory(UUID userId, LocalDate from, LocalDate to): List<PortfolioOrder>`
  - `PortfolioService`는 `accountPort.findByUserId(userId)`로 accountId→`broker().getCurrency()` 맵을 만들어 둘 다 채운다. 맵에 없으면 `IllegalStateException("포지션 계좌를 찾을 수 없습니다.")`/`("주문 계좌를 찾을 수 없습니다.")`을 던진다. entry의 accountId가 null(전략 삭제)인 경우도 같다. 단, 쿼리가 삭제 전략을 이미 거르므로 정상 경로에서는 생기지 않는다.
  - 내부 API `/portfolio/current`는 `List<PortfolioCurrentResponse>`를 반환한다(빈 목록 200, 404 경로 삭제). `/portfolio/history`는 `ticker` 파라미터를 삭제한다.
  - `PortfolioCurrentResponse(StrategyType strategyType, StrategyTicker ticker, Currency currency, BigDecimal holdings, BigDecimal avgPrice, BigDecimal deposit, BigDecimal closingPrice)`
  - `PortfolioOrderResponse(..., Currency currency)`(마지막 필드)
  - root `PortfolioQueryPort.getCurrent(UUID): List<PortfolioCurrentResponse>`, `getHistory(UUID, LocalDate, LocalDate)`

- [ ] **Step 1: DB 테스트를 쓴다(`CyclePositionPersistenceAdapterTest`).**
  - `전략별_최신_포지션을_반환한다`: KIS와 BINANCE 전략에 스냅샷을 2건씩 넣는다. 결과는 2건이고 각각 최신 행이며 `accountId`가 각 전략의 계좌여야 한다.
  - `삭제된_전략과_스냅샷_없는_전략은_제외한다`
  - `계좌_커서_조회는_코인_계좌_포지션을_포함한다`: BINANCE 계좌로 `findByAccountIdWithCursor`를 호출하면 결과가 비어 있지 않아야 한다.
  - `OrderPersistenceAdapterDbTest.findByUser는_전_종목을_반환한다`: SOXL·TQQQ·ETH 주문이 모두 나와야 한다.
- [ ] **Step 2: 실패를 확인한다.**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.out.persistence.CyclePositionPersistenceAdapterTest' --tests 'com.kista.tradingcore.trading.adapter.out.persistence.OrderPersistenceAdapterDbTest'`
  - Expected: 컴파일 실패
- [ ] **Step 3: 영속 계층을 구현한다.**
  - `NON_USD_BROKERS` 상수, `excludedBrokers` 파라미터, `AND a.broker NOT IN (:excludedBrokers)` 절을 삭제한다.
  - 새 네이티브 쿼리: `SELECT DISTINCT ON (s.id) cp.*, a.broker ... ORDER BY s.id, cp.created_at DESC, cp.id DESC`. cp·sc·s·a의 `deleted_at IS NULL`을 건다.
  - `toEntry`가 `accountId`를 채운다.
  - `findByUser`의 ticker 조건을 삭제한다.
- [ ] **Step 4: 서비스와 내부 API.**
  - `PortfolioService`를 Interfaces대로 바꾼다(빈 목록 허용, `NoSuchElementException` 삭제).
  - `PortfolioServiceTest`에 다음을 추가한다.
    - `getCurrent는_계좌_통화를_붙인다`(KIS → USD, BINANCE → USDT)
    - `getHistory는_계좌_통화를_붙인다`
    - `계좌_맵에_없는_주문은_IllegalStateException`
  - mapper·컨트롤러·contract record를 위 Interfaces대로 바꾼다.
  - `PortfolioQueryInternalControllerTest`를 갱신한다(빈 목록 200, `ticker` 파라미터 없음).
- [ ] **Step 5: root 텔레그램 테스트를 쓴다(`TelegramBotServiceTest`).**
  - `status는_전략별로_통화_표기해_출력한다`: INFINITE SOXL USD(예수금 1000, 보유 10, 종가 20)와 DCA ETH USDT(보유 0.5, 종가 3000, 예수금 0)를 넣는다. 출력에 `1,000.00 USD`·`1,500.00 USDT`가 포함되고 `$`는 없어야 한다. 정확한 문자열은 `Currency.format` 결과를 따른다.
  - `status는_종가가_없으면_평가액_0으로_출력한다`
  - `status는_빈_목록이면_데이터_없음_문구를_출력한다`: "포트폴리오 데이터가 없습니다."
  - `history는_전_종목을_통화_표기해_출력한다`: TQQQ USD 행과 ETH USDT 소수 수량 행이 모두 나오고, 행에 종목명이 있어야 한다.
- [ ] **Step 6: root를 구현한다.**
  - 포트와 HTTP 어댑터를 목록·무 ticker로 바꾼다(404 복원 로직 삭제).
  - `buildStatusMessage`는 전략마다 블록 하나를 만든다: 헤더 `<b>{strategyType} {ticker}</b>`, 그 아래 보유(`Quantities.normalize`)·평단·평가액·예수금·총자산을 `currency.format(...)`으로 출력한다. `double`은 쓰지 않는다. 블록 사이에 빈 줄을 둔다.
  - `buildHistoryMessage`의 행 형식은 `{tradeDate} {ticker} {direction} {orderType} {quantity} @ {currency.format(price)}`다.
  - `PortfolioQueryHttpAdapterTest`를 갱신한다.
- [ ] **Step 7: 통과를 확인한다.**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.out.persistence.*' --tests 'com.kista.tradingcore.stats.*'`, `./gradlew :test --tests 'com.kista.api.admin.*' --tests 'com.kista.api.architecture.*'`
  - Expected: PASS
- [ ] **Step 8: 커밋한다.** `feat(stats): 최근 포지션·계좌 이력에 코인 계좌를 포함하고 /status를 전략별·/history를 전 종목으로 표시한다`

### Task 6: 일별 거래내역 코인 포함

**Files:**
- Modify:
  - `trading-core/.../broker/domain/model/DailyTransaction.java`, `DailyTransactionResult.java`
  - `DailyTransactionSummary.java`(삭제)
  - `trading-core/.../stats/application/service/AccountStatisticsService.java:51-96`
  - `trading-core/.../stats/adapter/in/web/dto/DailyTransactionResponse.java`
- Test: `AccountStatisticsServiceTest`, `DashboardControllerTest`, `StatisticsControllerTest`

**Interfaces:**
- Produces:
  - `DailyTransaction(String tradeDate, OrderDirection direction, StrategyTicker ticker, String symbolName, BigDecimal quantity, BigDecimal price, BigDecimal tradeAmount, Currency currency)`
  - `DailyTransactionResult(List<DailyTransaction> items)`
  - 응답 `items[].quantity`(number), `items[].tradeAmount`, `items[].currency`. `tradeAmountUsd`와 `summary`는 삭제한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다(`AccountStatisticsServiceTest`).**
  - `일별_거래내역은_코인_소수_수량_체결을_포함한다`: BINANCE ETH 체결(수량 0.0123, 가격 3000.555)이면 quantity는 `0.0123`, tradeAmount는 `36.91`(USDT scale 2, HALF_UP), currency는 USDT다.
  - `KRW_체결_금액은_정수로_반올림한다`: UPBIT BTC 체결(수량 0.00031, 가격 95,000,000)이면 tradeAmount는 `29450`(scale 0), currency는 KRW다.
  - `주식_체결은_USD로_표기한다`: KIS SOXL 10주 × 20.5면 `205.00`, USD다.
  - `계좌_맵에_없는_주문은_IllegalStateException`
- [ ] **Step 2: 실패를 확인한다.**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.application.service.AccountStatisticsServiceTest'`
  - Expected: 컴파일 실패
- [ ] **Step 3: 구현한다.**
  - `isCrypto` 필터와 `intValueExact`를 삭제한다.
  - 수량은 `Quantities.normalize(filledQuantity)`, 금액은 `currency.round(price × quantity)`다.
  - 계좌 단위는 소유 검증한 계좌 1개, 사용자 단위는 `accountPort.findByUserId(requesterId)`로 accountId→Currency 맵을 만든다. `toDailyTransactionResult(List<Order>, Map<UUID, Currency>)` 형태로 넘긴다.
  - 합계 계산과 `DailyTransactionSummary`를 삭제한다.
- [ ] **Step 4: DTO와 컨트롤러 테스트를 갱신한다.** `DashboardControllerTest`·`StatisticsControllerTest`의 JSON 경로를 `tradeAmount`·`currency`로 바꾸고, `$.summary`가 없는지 단언한다.
- [ ] **Step 5: 통과를 확인한다.**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.*'`
  - Expected: PASS
- [ ] **Step 6: 커밋한다.** `feat(stats): 일별 거래내역에 코인 체결을 계좌 통화로 포함한다`

### Task 7: 문서 드리프트 · 전체 검증 · 인계 메모

**Files:**
- Modify:
  - `docs/agents/modules/tradingcore/stats.md`("DCA 포함" 절의 후속·`NON_USD_BROKERS`·일별 거래내역 문장, 포트 목록)
  - `docs/agents/modules/api/benchmark.md`("PORTFOLIO는 USD 계좌만 합산" 문장)
  - `docs/agents/modules/api/admin.md`(`/status`·`/history`, 404 복원 문장)
  - `src/test/resources/architecture/flows.yml:819-820` 부근(summary·equity-curve 통화 파라미터, `/status` 전략별)
  - `README.md`(grep `usdDeposit`·`USD 합산` 해당 시)
- Create: 인계 메모 `<scratchpad>/ui-handoff-crypto-currency.md`(커밋하지 않고 ui-stat 세션에 SendMessage로 보낸다)

- [ ] **Step 1: 위 문서를 새 규칙으로 갱신한다.** "후속: 통화별 분리 집계"는 완료 문장으로 바꾼다.
- [ ] **Step 2: 전체 스위트를 1회 돌린다.**
  - Run: `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`, 이어서 `./gradlew :test --tests 'com.kista.api.architecture.ArchitectureMapTest'`
  - Expected: `BUILD SUCCESSFUL`
- [ ] **Step 3: 로컬 실기동 1회.**
  - root·trading-core를 local로 띄운 뒤 `scripts/dev-seed/seed.sh`를 실행한다.
  - curl로 다음을 확인한다: `/api/stats/summary?currency=USDT`(`availableCurrencies`에 USDT 포함, `byType`에 DCA), `/api/stats/housing-benchmark?currency=USDT`(`quality.investmentCurrency == "USDT"`, points 비어 있지 않음), `/api/daily-trades`(소수 수량 행), `/api/accounts/{binance}/cycle-history`(`deposit` 필드).
- [ ] **Step 4: 커밋 전 리뷰.** sonnet 리뷰어 1개로 Task 3~6 전체 diff를 본다. 지적 사항은 고친 뒤 재확인한다.
- [ ] **Step 5: 문서를 커밋한다.** `docs: 통화별 합산 집계 반영으로 stats·benchmark·admin 문서와 아키텍처 맵을 갱신한다`
- [ ] **Step 6: 인계 메모를 작성해 ui-stat 세션에 보낸다.** 담을 내용:
  - api 커밋 해시 목록
  - 엔드포인트별로 바뀐 필드
    - summary: `?currency`, `availableCurrencies`
    - equity-curve: `?currency`
    - housing-benchmark: `?currency`
    - daily-trades 두 경로: `quantity` number, `tradeAmountUsd` → `tradeAmount`, `currency`, `summary` 삭제
    - `usdDeposit` → `deposit`: cycle-history, next-orders, portfolio-summary 등 Task 1에서 grep한 응답 DTO 목록
  - 통화 출처: 합산은 요청 currency, 행은 응답 `currency` 또는 계좌 `brokerCurrency`
  - UI 작업 목록: 설계 문서 "kista-ui 인계 메모" 절 전체(통화 탭과 기본 통화 규칙, `fmtMoney` 교체, 캘린더 통화별 집계와 주말 배지)
- [ ] **Step 7: 배포 체크리스트(사용자가 push·배포할 때).**
  - 배포 직전 운영에서 `SELECT count(*) FROM trading.event_publication WHERE completion_date IS NULL`과 root의 같은 테이블을 조회해 0건인지 확인한다. Task 1 Step 3에서 정리한 이벤트 필드가 대상이다.
  - API 배포 직후 UI를 배포한다.
  - 배포 후 `/status`·`/history` 텔레그램 출력과 `flyway_schema_history` V18 적용을 읽기 전용으로 확인한다.
- [ ] **Step 8: 완료 처리.** 배포 확인까지 끝나면 계획과 spec을 `git mv`로 archive에 옮긴다(마지막 커밋).
