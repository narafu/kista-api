# Spring Modulith stats 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 최상위(`com.kista.domain`/`application`/`adapter`, `Type.OPEN`)에 흩어진 통계·백테스트·포트폴리오 애그리게이트(사용자 통계·주택/ETF 벤치마크 비교·과거 일봉 시뮬레이션·텔레그램 포트폴리오 조회)를 신규 `com.kista.stats` Spring Modulith `CLOSED` 모듈로 이전한다.

**Architecture:** finance/notify/broker/trading/market/privacy 6모듈 이전과 동일 패턴 — 모듈 내부는 기존 Hexagonal 레이어(`domain/application/adapter`)를 그대로 유지하고 최상위 패키지만 `com.kista.stats`로 옮긴다. 순수 구조 이전이라 TDD red-green이 아닌 "이동 → import 정합화 → 컴파일/테스트 그린 → 커밋" 사이클. stats는 CLOSED 전환 시 드러나는 **모듈 순환 1건**(`stats ↔ notify` 직접 2-cycle)이 사전 실측으로 확인됐으므로, 모듈 선언 직전에 순환을 끊는 코드 변경 태스크를 둔다. `Spring Modulith`가 모듈 경계를, 기존 `HexagonalArchitectureTest`가 모듈 내부 레이어 방향을 검증한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5

**Spec:** `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` ("착수 순서 (실측 기반, v2)" 1단계 잔여분 중 stats)

## Global Constraints

- **[스펙 정정] 스펙 "결합도 실측" 표의 "stats: 순환 없음, backward 0건" 판정은 부분적으로만 맞다.** 사전 실측 결과:
  - `stats → notify`: `HousingBenchmarkService`·`HousingPriceIndexService`가 `NotifyPort.notifyError(e)`를 직접 호출(2곳). `FearGreedService`(market)·`PrivacyService`(privacy)와 동일한 스케쥴러 구동 외부 페처 패턴.
  - `notify → stats`: `TelegramBotService`(notify 인바운드)가 `PortfolioUseCase`를 참조해 텔레그램 `/portfolio` 명령에 응답. **단방향이면 정상**이지만, stats가 CLOSED가 되고 `PortfolioUseCase`가 `com.kista.stats.application.usecase`로 옮겨가면 위 `stats → notify`와 합쳐져 **직접 2-cycle**(`stats ↔ notify`)이 된다.
  - → **Task 3**에서 `HousingBenchmarkService`·`HousingPriceIndexService`의 `NotifyPort` 직접 호출을 `StatsAlertRaisedEvent` 발행으로 전환하고 notify가 `StatsAlertNotifier`로 구독(market `FearGreedFetchFailedEvent`/`MarketAlertNotifier`, privacy `PrivacyAlertRaisedEvent`/`PrivacyAlertNotifier`와 동일 패턴 — 3번째 인스턴스). `notify → stats`(`TelegramBotService` → `PortfolioUseCase`)는 정상 단방향이므로 **건드리지 않는다**.
  - trading/broker/market/privacy/finance는 stats 후보 패키지를 역참조하지 않음(실측 완료). 단 `com.kista.broker.adapter.out.mock.MockBrokerAdapter.java:172`에 `com.kista.domain.backtest.FillSimulator` FQN을 언급하는 **주석**이 있다(import 아님, 컴파일 무관) — Task 2에서 `com.kista.stats.domain.backtest.FillSimulator`로 문자열 갱신.
- **[market 계획의 미결 IOU 정산] 이번 이전이 레거시 `adapter/out/alpaca/`를 소멸시킨다.** market 이전 때 `AlpacaConfig.java`/`AlpacaProperties.java`는 "이동이 아닌 복제"로 처리하고 레거시 원본을 `AlpacaIndexPriceAdapter`(stats 소유 예정)를 위해 남겨뒀다(market 계획 Global Constraints). 이번에 `AlpacaIndexPriceAdapter`가 stats로 옮겨가므로:
  - 레거시 `adapter/out/alpaca/AlpacaConfig.java`·`AlpacaProperties.java`의 소유권을 stats가 가져간다(`com.kista.stats.adapter.out.alpaca`로 이동).
  - 이동 후 레거시 `src/main/java/com/kista/adapter/out/alpaca/` 디렉토리가 비었는지 확인하고 `rmdir`.
  - **빈 이름 충돌 주의**: market 판은 `marketAlpacaConfig`/`marketAlpacaRestClient`로 명시 개명돼 있다(architecture.md). 레거시(=stats로 이동하는) 판은 `alpacaRestClient` 등 원래 빈 이름을 **그대로 유지**한다 — `RestClient`/`RestTemplate` 빈이 여러 개일 때 필드명=빈이름 일치 규칙(`AlpacaIndexPriceAdapter`가 필드명으로 주입)을 깨면 `NoUniqueBeanDefinitionException`. stats 판 `AlpacaConfig`/`AlpacaProperties`의 빈 이름·`@ConfigurationProperties(prefix)`는 이동 전과 byte-identical.
  - 레거시 `AlpacaConfigTest.java`는 stats로 이동(market 판 복제본은 그대로 유지).
- **와일드카드 import 전수 처리 필수.** `StatsService.java:11`에 `import com.kista.application.port.output.*;`(한 줄에 `import com.kista.trading.application.port.output.*;`와 병기)와 `StatsService.java:5`에 `import com.kista.domain.model.stats.*;`가 있다. `domain/model/stats`는 20개 타입이라 다른 소비자도 와일드카드일 가능성이 market/privacy보다 높다. 각 이동/치환 태스크에서 `git grep -n "^import com\.kista.*\*;"` 로 stats 후보 파일의 와일드카드를 먼저 훑고, 경로 치환 sed 패턴에 `com.kista.domain.model.stats.*` → `com.kista.stats.domain.model.*` 등 **와일드카드 형태**를 포함시킬 것. (market·privacy 계획이 반복해서 놓친 실수 — 각각 태스크의 절반이 이걸 놓쳤음.)
- **문자열 리터럴 FQN 사전 스캔.** broker 이전 때 `application-prod.yml`의 Logback 로거 이름이 조용히 깨진 사례. 각 물리 이동 태스크 시작 전 `grep -rn` 으로 `resources/**` (`*.yml`/`*.xml`) 와 java 문자열 리터럴(AOP `@Around`/`@Pointcut`) 내 `com.kista.(domain.model.stats|domain.model.backtest|domain.backtest|application.service.(stats|backtest|portfolio)|adapter.out.(alpaca|kbland|persistence.housingbenchmark|persistence.marketindex))` 참조를 확인. 매치 시 해당 태스크에서 함께 갱신.
- **`ApplicationModules.verify()` 게이트는 모듈 선언(Task 3) 시점에만 유효.** stats가 `@ApplicationModule` 미선언인 동안은 레거시 OPEN의 일부로 취급돼 순환이 안 잡힌다. 사전 실측으로 순환 1건(`stats↔notify`)을 특정했지만 pairwise 한계로 놓친 전이 순환이 있을 수 있다(market `market→notify→trading→market`, privacy `privacy→notify→trading→privacy` 교훈). Task 3에서 이벤트 전환 후 `verify()`가 예측 못한 추가 순환을 보고하면 **즉시 멈추고 보고**(추측 수정 금지).
- **이동/유지 경계 — 통계 서비스 3종은 이번 스코프 밖.** `AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`(및 `AccountStatisticsUseCase`/`TossStatisticsUseCase`)는 스펙상 4단계(account+strategy-config 묶음)에서 stats로 재배치될 예정 — **이번엔 레거시 잔류**. 레거시가 `Type.OPEN`이라 이들이 stats의 `"domain"`/`"usecase"` NamedInterface를 소비하는 건 정상 forward 의존. 이동하는 usecase는 `UserStatsUseCase`/`BacktestUseCase`/`PortfolioUseCase`/`FetchHousingBenchmarkUseCase`/`FetchHousingPriceIndexUseCase`/`SyncMarketIndexPricesUseCase` 6개뿐. `StatisticsController`/`TossStatisticsController`/`DashboardController`/`TradingCycleController`는 레거시 잔류.
- **`MarketUseCase` 레거시 잔류 존중.** `MarketUseCase`(시장 캘린더/캔들 조회)는 market 내부 `MarketHolidayService`가 구현하는데 레거시 `com.kista.application.usecase`에 남아있다(architecture.md). backtest 경로가 이걸 소비하면 경로 그대로 두고 스코프 확장 금지.
- 커밋 전 검토자(리뷰어 서브에이전트) 검수 필수 — 전역 CLAUDE.md 규칙, 문서 전용 변경(Task 4) 제외.
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit. macOS sed는 `sed -i ''`, Linux는 `sed -i`.
- **sed 이식성**: 이 계획의 sed 명령 중 `\(A\|B\)` 형태 alternation은 GNU sed 전용(BSD/macOS 기본 sed는 `\|`를 리터럴로 취급). BSD sed 환경이면 해당 명령을 `perl -pi -e 's/.../.../g'`(동일 정규식) 또는 `gsed`로 치환하거나, alternation을 개별 `-e` 절/`for` 루프로 풀어 실행할 것. 치환 후 반드시 `git grep`으로 잔존 옛 경로 0건 확인(각 태스크 검증 스텝에 이미 포함).

---

## File Structure (최종 `com.kista.stats` 트리)

```
com.kista.stats/
  package-info.java                       ← @ApplicationModule (Task 3)
  domain/
    model/                                ← "domain" NamedInterface (Task 3)
      (stats 20개: BenchmarkAssetType, BenchmarkGranularity, BenchmarkScope, CurrentExchangeRate,
       CyclePerformance, CyclePerformancePage, EquityCurve, EquityPoint, EtfBenchmarkSymbol,
       HousingBenchmarkComparison, HousingBenchmarkPoint, HousingBenchmarkPrice, HousingBenchmarkRegion,
       HousingPriceIndex, IndexPrice, InvestmentPoint, PerformanceComparisonSummary, ReturnMetrics,
       StatsSummary, StrategyTypeStats)
      backtest/                           ← "domain" NamedInterface (Task 3) — BacktestCommand,
       BacktestPoint, BacktestResult, BacktestSummary, DailyCandle (레거시 domain/model/backtest 구조 유지)
    backtest/                             ← internal (BacktestEngine, FillSimulator)
  application/
    usecase/                              ← "usecase" NamedInterface (Task 3)
      UserStatsUseCase, BacktestUseCase, PortfolioUseCase,
      FetchHousingBenchmarkUseCase, FetchHousingPriceIndexUseCase, SyncMarketIndexPricesUseCase
    port/output/                          ← "port" NamedInterface (Task 3)
      HistoricalCandlePort, HousingBenchmarkFeedPort, HousingBenchmarkPricePort,
      HousingPriceIndexPort, IndexPriceFeedPort, IndexPricePort
    event/                                ← "event" NamedInterface (Task 3)
      StatsAlertRaisedEvent (신규, Task 3)
    service/                              ← internal
      StatsService, StatsResultCache, MonthlyReturnCalculator, HousingBenchmarkComparisonBuilder,
      HousingBenchmarkService, HousingPriceIndexService, MarketIndexPriceSyncService,
      BacktestService, PortfolioService
  adapter/
    in/web/                               ← internal — StatsController, BacktestController
    in/web/dto/                           ← internal — StatsSummaryResponse, EquityCurveResponse,
      CyclePerformancePageResponse, EtfPriceSeriesResponse, BacktestResponse,
      HousingBenchmarkComparisonResponse, HousingBenchmarkRegionsResponse,
      HousingBenchmarkSeriesResponse, HousingPriceIndexSeriesResponse
    in/schedule/                          ← "schedule" NamedInterface (Task 3) — KbLandHousingBenchmarkScheduler,
      KbLandPriceIndexScheduler, MarketIndexPriceSyncScheduler. legacy AdminSchedulerController가
      KbLand 스케쥴러 2개를 수동 트리거용 ObjectProvider<>로 직접 주입(trading.adapter.in.schedule와 동일 관례) →
      internal이면 verify()가 non-exposed-type 위반. trading "schedule" NamedInterface 선례 그대로.
    out/alpaca/                           ← internal — AlpacaIndexPriceAdapter, AlpacaConfig, AlpacaProperties
    out/kbland/                           ← internal — KbLandHousingBenchmarkAdapter, KbLandConfig, KbLandProperties
    out/persistence/housingbenchmark/     ← internal — 6개 (Entity/JpaRepository/PersistenceAdapter ×2)
    out/persistence/marketindex/          ← internal — 3개 (Entity/JpaRepository/PersistenceAdapter)
```

레거시 잔류(경로만 갱신): `adapter/in/web/dto/AdminSettingsRequest.java`(stats 도메인 enum), `adapter/in/web/openapi/HousingBenchmarkOpenApiCustomizer.java`(이동 DTO 참조), `notify/adapter/in/telegram/TelegramBotService.java`(`PortfolioUseCase`), 그 외 Task 1/2 grep이 색출하는 소비자.

레거시 공유 기반 클래스 계속 참조(그대로 둠): `com.kista.adapter.out.persistence.{BaseAuditEntity,BaseCreatedAtEntity,JpaAuditingConfig}`, `com.kista.adapter.in.schedule.{SchedulerJobRunner,SchedulerLockService}`(둘 다 `public`, `Type.OPEN`) — finance/broker/trading/market 어댑터도 동일하게 경계를 넘어 참조 중.

---

## Task 1: 코어(domain + application) 물리 이전 + 전역 소비자 import 정합화

**Files:**
- Move: `src/main/java/com/kista/domain/model/stats/*.java` (20개) → `src/main/java/com/kista/stats/domain/model/`
- Move: `src/main/java/com/kista/domain/model/backtest/*.java` (5개) → `src/main/java/com/kista/stats/domain/model/backtest/`
- Move: `src/main/java/com/kista/domain/backtest/{BacktestEngine,FillSimulator}.java` → `src/main/java/com/kista/stats/domain/backtest/`
- Move: `src/main/java/com/kista/application/service/stats/*.java` (7개: `StatsService`, `StatsResultCache`, `MonthlyReturnCalculator`, `HousingBenchmarkComparisonBuilder`, `HousingBenchmarkService`, `HousingPriceIndexService`, `MarketIndexPriceSyncService`) → `src/main/java/com/kista/stats/application/service/`
- Move: `src/main/java/com/kista/application/service/backtest/BacktestService.java` → `src/main/java/com/kista/stats/application/service/`
- Move: `src/main/java/com/kista/application/service/portfolio/PortfolioService.java` → `src/main/java/com/kista/stats/application/service/`
- Move: `src/main/java/com/kista/application/usecase/{UserStatsUseCase,BacktestUseCase,PortfolioUseCase,FetchHousingBenchmarkUseCase,FetchHousingPriceIndexUseCase,SyncMarketIndexPricesUseCase}.java` → `src/main/java/com/kista/stats/application/usecase/`
- Move: `src/main/java/com/kista/application/port/output/{HistoricalCandlePort,HousingBenchmarkFeedPort,HousingBenchmarkPricePort,HousingPriceIndexPort,IndexPriceFeedPort,IndexPricePort}.java` → `src/main/java/com/kista/stats/application/port/output/`
- Move tests: `src/test/java/com/kista/domain/model/stats/ReturnMetricsTest.java` → `src/test/java/com/kista/stats/domain/model/`; `src/test/java/com/kista/domain/backtest/{BacktestEngineTest,FillSimulatorTest}.java` → `src/test/java/com/kista/stats/domain/backtest/`; `src/test/java/com/kista/application/service/stats/*.java` (7개: `StatsServiceTest`, `StatsResultCacheTest`, `MonthlyReturnCalculatorTest`, `HousingBenchmarkComparisonBuilderTest`, `HousingBenchmarkServiceTest`, `HousingPriceIndexServiceTest`, `MarketIndexPriceSyncServiceTest`) → `src/test/java/com/kista/stats/application/service/`; `src/test/java/com/kista/application/service/backtest/BacktestServiceTest.java` → `src/test/java/com/kista/stats/application/service/`; `src/test/java/com/kista/application/service/portfolio/PortfolioServiceTest.java` → `src/test/java/com/kista/stats/application/service/`
- Modify (import 경로만, 이동 안 함): Step 4의 전역 소비자 목록

**Interfaces:**
- Produces: `com.kista.stats.domain.model.*` (25개), `com.kista.stats.domain.backtest.{BacktestEngine,FillSimulator}`, `com.kista.stats.application.usecase.*` (6개), `com.kista.stats.application.port.output.*` (6개) — Task 2(어댑터)·Task 3(모듈 선언)이 이 경로를 소비.

- [ ] **Step 0: 문자열 리터럴 FQN 사전 스캔**

```bash
grep -rn "com\.kista\.\(domain\.model\.stats\|domain\.model\.backtest\|domain\.backtest\|application\.service\.\(stats\|backtest\|portfolio\)\|application\.usecase\.\(UserStats\|Backtest\|Portfolio\|FetchHousing\|SyncMarketIndex\)\|application\.port\.output\.\(HistoricalCandle\|HousingBenchmark\|HousingPriceIndex\|IndexPrice\)\)" src/main/resources/
git grep -n '"[^"]*com\.kista\.[^"]*\(stats\|backtest\|[Pp]ortfolio\|[Hh]ousing\|IndexPrice\)' src/main/java
```
매치 있으면 이 태스크 diff에 포함해 함께 갱신(로거 이름·포인트컷 문자열). 없으면 다음 스텝.

- [ ] **Step 1: 와일드카드 import 사전 확인**

```bash
git grep -n "^import com\.kista.*\*;" -- \
  src/main/java/com/kista/domain/model/stats src/main/java/com/kista/domain/model/backtest \
  src/main/java/com/kista/domain/backtest \
  src/main/java/com/kista/application/service/stats src/main/java/com/kista/application/service/backtest \
  src/main/java/com/kista/application/service/portfolio \
  src/main/java/com/kista/application/usecase src/main/java/com/kista/application/port/output
```
현재 알려진 것: `StatsService.java` 2건(`com.kista.domain.model.stats.*`, `com.kista.application.port.output.*`). 추가로 나오면 Step 3 sed 패턴이 커버하는지 확인.

- [ ] **Step 2: 코어 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/stats/domain/model/backtest
mkdir -p src/main/java/com/kista/stats/domain/backtest
mkdir -p src/main/java/com/kista/stats/application/service
mkdir -p src/main/java/com/kista/stats/application/usecase
mkdir -p src/main/java/com/kista/stats/application/port/output

git mv src/main/java/com/kista/domain/model/stats/*.java    src/main/java/com/kista/stats/domain/model/
rmdir src/main/java/com/kista/domain/model/stats
git mv src/main/java/com/kista/domain/model/backtest/*.java  src/main/java/com/kista/stats/domain/model/backtest/
rmdir src/main/java/com/kista/domain/model/backtest
git mv src/main/java/com/kista/domain/backtest/BacktestEngine.java src/main/java/com/kista/stats/domain/backtest/BacktestEngine.java
git mv src/main/java/com/kista/domain/backtest/FillSimulator.java  src/main/java/com/kista/stats/domain/backtest/FillSimulator.java
rmdir src/main/java/com/kista/domain/backtest

git mv src/main/java/com/kista/application/service/stats/StatsService.java                    src/main/java/com/kista/stats/application/service/StatsService.java
git mv src/main/java/com/kista/application/service/stats/StatsResultCache.java                src/main/java/com/kista/stats/application/service/StatsResultCache.java
git mv src/main/java/com/kista/application/service/stats/MonthlyReturnCalculator.java         src/main/java/com/kista/stats/application/service/MonthlyReturnCalculator.java
git mv src/main/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilder.java src/main/java/com/kista/stats/application/service/HousingBenchmarkComparisonBuilder.java
git mv src/main/java/com/kista/application/service/stats/HousingBenchmarkService.java         src/main/java/com/kista/stats/application/service/HousingBenchmarkService.java
git mv src/main/java/com/kista/application/service/stats/HousingPriceIndexService.java        src/main/java/com/kista/stats/application/service/HousingPriceIndexService.java
git mv src/main/java/com/kista/application/service/stats/MarketIndexPriceSyncService.java     src/main/java/com/kista/stats/application/service/MarketIndexPriceSyncService.java
rmdir src/main/java/com/kista/application/service/stats
git mv src/main/java/com/kista/application/service/backtest/BacktestService.java   src/main/java/com/kista/stats/application/service/BacktestService.java
rmdir src/main/java/com/kista/application/service/backtest
git mv src/main/java/com/kista/application/service/portfolio/PortfolioService.java src/main/java/com/kista/stats/application/service/PortfolioService.java
rmdir src/main/java/com/kista/application/service/portfolio

git mv src/main/java/com/kista/application/usecase/UserStatsUseCase.java            src/main/java/com/kista/stats/application/usecase/UserStatsUseCase.java
git mv src/main/java/com/kista/application/usecase/BacktestUseCase.java             src/main/java/com/kista/stats/application/usecase/BacktestUseCase.java
git mv src/main/java/com/kista/application/usecase/PortfolioUseCase.java            src/main/java/com/kista/stats/application/usecase/PortfolioUseCase.java
git mv src/main/java/com/kista/application/usecase/FetchHousingBenchmarkUseCase.java   src/main/java/com/kista/stats/application/usecase/FetchHousingBenchmarkUseCase.java
git mv src/main/java/com/kista/application/usecase/FetchHousingPriceIndexUseCase.java  src/main/java/com/kista/stats/application/usecase/FetchHousingPriceIndexUseCase.java
git mv src/main/java/com/kista/application/usecase/SyncMarketIndexPricesUseCase.java   src/main/java/com/kista/stats/application/usecase/SyncMarketIndexPricesUseCase.java

git mv src/main/java/com/kista/application/port/output/HistoricalCandlePort.java       src/main/java/com/kista/stats/application/port/output/HistoricalCandlePort.java
git mv src/main/java/com/kista/application/port/output/HousingBenchmarkFeedPort.java    src/main/java/com/kista/stats/application/port/output/HousingBenchmarkFeedPort.java
git mv src/main/java/com/kista/application/port/output/HousingBenchmarkPricePort.java   src/main/java/com/kista/stats/application/port/output/HousingBenchmarkPricePort.java
git mv src/main/java/com/kista/application/port/output/HousingPriceIndexPort.java       src/main/java/com/kista/stats/application/port/output/HousingPriceIndexPort.java
git mv src/main/java/com/kista/application/port/output/IndexPriceFeedPort.java          src/main/java/com/kista/stats/application/port/output/IndexPriceFeedPort.java
git mv src/main/java/com/kista/application/port/output/IndexPricePort.java              src/main/java/com/kista/stats/application/port/output/IndexPricePort.java

mkdir -p src/test/java/com/kista/stats/domain/model
mkdir -p src/test/java/com/kista/stats/domain/backtest
mkdir -p src/test/java/com/kista/stats/application/service
git mv src/test/java/com/kista/domain/model/stats/ReturnMetricsTest.java src/test/java/com/kista/stats/domain/model/ReturnMetricsTest.java
rmdir src/test/java/com/kista/domain/model/stats
git mv src/test/java/com/kista/domain/backtest/BacktestEngineTest.java src/test/java/com/kista/stats/domain/backtest/BacktestEngineTest.java
git mv src/test/java/com/kista/domain/backtest/FillSimulatorTest.java  src/test/java/com/kista/stats/domain/backtest/FillSimulatorTest.java
rmdir src/test/java/com/kista/domain/backtest
git mv src/test/java/com/kista/application/service/stats/StatsServiceTest.java                    src/test/java/com/kista/stats/application/service/StatsServiceTest.java
git mv src/test/java/com/kista/application/service/stats/StatsResultCacheTest.java                src/test/java/com/kista/stats/application/service/StatsResultCacheTest.java
git mv src/test/java/com/kista/application/service/stats/MonthlyReturnCalculatorTest.java         src/test/java/com/kista/stats/application/service/MonthlyReturnCalculatorTest.java
git mv src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java src/test/java/com/kista/stats/application/service/HousingBenchmarkComparisonBuilderTest.java
git mv src/test/java/com/kista/application/service/stats/HousingBenchmarkServiceTest.java         src/test/java/com/kista/stats/application/service/HousingBenchmarkServiceTest.java
git mv src/test/java/com/kista/application/service/stats/HousingPriceIndexServiceTest.java        src/test/java/com/kista/stats/application/service/HousingPriceIndexServiceTest.java
git mv src/test/java/com/kista/application/service/stats/MarketIndexPriceSyncServiceTest.java     src/test/java/com/kista/stats/application/service/MarketIndexPriceSyncServiceTest.java
rmdir src/test/java/com/kista/application/service/stats
git mv src/test/java/com/kista/application/service/backtest/BacktestServiceTest.java   src/test/java/com/kista/stats/application/service/BacktestServiceTest.java
rmdir src/test/java/com/kista/application/service/backtest
git mv src/test/java/com/kista/application/service/portfolio/PortfolioServiceTest.java src/test/java/com/kista/stats/application/service/PortfolioServiceTest.java
rmdir src/test/java/com/kista/application/service/portfolio
```

- [ ] **Step 3: 이동 파일의 package 선언 + 상호 import 치환**

```bash
find src/main/java/com/kista/stats src/test/java/com/kista/stats -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.domain\.model\.stats;/package com.kista.stats.domain.model;/' \
  -e 's/^package com\.kista\.domain\.model\.backtest;/package com.kista.stats.domain.model.backtest;/' \
  -e 's/^package com\.kista\.domain\.backtest;/package com.kista.stats.domain.backtest;/' \
  -e 's/^package com\.kista\.application\.service\.stats;/package com.kista.stats.application.service;/' \
  -e 's/^package com\.kista\.application\.service\.backtest;/package com.kista.stats.application.service;/' \
  -e 's/^package com\.kista\.application\.service\.portfolio;/package com.kista.stats.application.service;/' \
  -e 's/^package com\.kista\.application\.usecase;/package com.kista.stats.application.usecase;/' \
  -e 's/^package com\.kista\.application\.port\.output;/package com.kista.stats.application.port.output;/' \
  -e 's/com\.kista\.domain\.model\.backtest\./com.kista.stats.domain.model.backtest./g' \
  -e 's/com\.kista\.domain\.model\.stats\./com.kista.stats.domain.model./g' \
  -e 's/com\.kista\.domain\.backtest\./com.kista.stats.domain.backtest./g' \
  -e 's/com\.kista\.application\.usecase\.UserStatsUseCase/com.kista.stats.application.usecase.UserStatsUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.BacktestUseCase/com.kista.stats.application.usecase.BacktestUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.PortfolioUseCase/com.kista.stats.application.usecase.PortfolioUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.FetchHousingBenchmarkUseCase/com.kista.stats.application.usecase.FetchHousingBenchmarkUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.FetchHousingPriceIndexUseCase/com.kista.stats.application.usecase.FetchHousingPriceIndexUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.SyncMarketIndexPricesUseCase/com.kista.stats.application.usecase.SyncMarketIndexPricesUseCase/g' \
  -e 's/com\.kista\.application\.port\.output\.HistoricalCandlePort/com.kista.stats.application.port.output.HistoricalCandlePort/g' \
  -e 's/com\.kista\.application\.port\.output\.HousingBenchmarkFeedPort/com.kista.stats.application.port.output.HousingBenchmarkFeedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.HousingBenchmarkPricePort/com.kista.stats.application.port.output.HousingBenchmarkPricePort/g' \
  -e 's/com\.kista\.application\.port\.output\.HousingPriceIndexPort/com.kista.stats.application.port.output.HousingPriceIndexPort/g' \
  -e 's/com\.kista\.application\.port\.output\.IndexPriceFeedPort/com.kista.stats.application.port.output.IndexPriceFeedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.IndexPricePort/com.kista.stats.application.port.output.IndexPricePort/g' \
  {} +
```

- [ ] **Step 4: `StatsService.java` 와일드카드 import 수동 처리**

`StatsService.java:5` `import com.kista.domain.model.stats.*;` 는 Step 3에서 `import com.kista.stats.domain.model.*;`로 이미 치환됨 — 확인만.
`StatsService.java:11` 은 `import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;` (한 줄 2 import). Step 3 sed는 개별 port 이름만 치환하므로 `com.kista.application.port.output.*` 와일드카드는 **그대로 남는다**. Edit 도구로 이 한 줄을 두 줄로 분리하면서 첫 import를 stats 경로로 바꾼다(sed의 `\n` 치환은 BSD sed에서 안 됨 — 반드시 에디터 사용):

```
# 변경 전 (StatsService.java 한 줄):
import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;
# 변경 후 (두 줄):
import com.kista.stats.application.port.output.*;
import com.kista.trading.application.port.output.*;
```

그 다음 이 와일드카드가 실제로 stats 포트만 가져오는지(=stats 경로로 바꿔도 되는지), 아니면 레거시 `com.kista.application.port.output` 의 비-stats 포트도 필요한지 컴파일 에러로 확인:

```bash
./gradlew compileJava 2>&1 | grep -E "StatsService.*cannot find symbol|error:" | head -20
```
`StatsService`에서 `cannot find symbol` 이 뜨는 비-stats 포트가 있으면(예: 레거시 `ExchangeRatePort` 아님 — 그건 이미 `com.kista.broker.application.port.output.ExchangeRatePort` 명시 import), 그 포트만 명시 import 추가. **없을 가능성이 높다** — StatsService가 쓰는 아웃바운드 포트는 stats 6개 + broker `ExchangeRatePort`(명시) + trading `*Port`(와일드카드) 조합으로 확인됨.

- [ ] **Step 5: 전역 소비자 import 경로 치환 (이번 태스크에서 이동 안 하는 파일)**

먼저 대상 색출(어댑터·DTO는 Task 2에서 이동과 함께 처리하므로 제외):

```bash
git grep -ln "com\.kista\.domain\.model\.stats\|com\.kista\.domain\.model\.backtest\|com\.kista\.domain\.backtest\|com\.kista\.application\.usecase\.\(UserStatsUseCase\|BacktestUseCase\|PortfolioUseCase\|FetchHousingBenchmarkUseCase\|FetchHousingPriceIndexUseCase\|SyncMarketIndexPricesUseCase\)\|com\.kista\.application\.port\.output\.\(HistoricalCandlePort\|HousingBenchmarkFeedPort\|HousingBenchmarkPricePort\|HousingPriceIndexPort\|IndexPriceFeedPort\|IndexPricePort\)" -- src/main src/test \
  | grep -v "src/main/java/com/kista/stats/\|src/test/java/com/kista/stats/" \
  | grep -vE "/(StatsController|BacktestController|StatsControllerTest|BacktestControllerTest|KbLandHousingBenchmarkScheduler|KbLandPriceIndexScheduler|MarketIndexPriceSyncScheduler|KbLandHousingBenchmarkSchedulerTest|KbLandPriceIndexSchedulerTest|MarketIndexPriceSyncSchedulerTest|AlpacaIndexPriceAdapter|AlpacaIndexPriceAdapterTest|KbLandHousingBenchmarkAdapter|KbLandHousingBenchmarkAdapterTest|StatsSummaryResponse|EquityCurveResponse|CyclePerformancePageResponse|EtfPriceSeriesResponse|BacktestResponse|HousingBenchmarkComparisonResponse|HousingBenchmarkRegionsResponse|HousingBenchmarkSeriesResponse|HousingPriceIndexSeriesResponse|HousingBenchmarkApiDocsTest|HousingBenchmarkComparisonResponseSchemaTest|HousingBenchmarkComparisonResponseTest)\.java" \
  | grep -vE "adapter/out/persistence/(housingbenchmark|marketindex)/" \
  | sort -u
```

현재 실측 기준 이 목록에 남는 것: `notify/adapter/in/telegram/TelegramBotService.java` + `TelegramBotServiceTest.java`, `adapter/in/web/dto/AdminSettingsRequest.java`, `broker/adapter/out/mock/MockBrokerAdapter.java`(주석 FQN — Task 2 Step 4에서 처리하므로 여기선 건너뜀). 나머지 파일에 아래 sed 적용:

```bash
sed -i '' \
  -e 's#com\.kista\.domain\.model\.backtest\.#com.kista.stats.domain.model.backtest.#g' \
  -e 's#com\.kista\.domain\.model\.stats\.#com.kista.stats.domain.model.#g' \
  -e 's#com\.kista\.domain\.backtest\.#com.kista.stats.domain.backtest.#g' \
  -e 's#com\.kista\.application\.usecase\.PortfolioUseCase#com.kista.stats.application.usecase.PortfolioUseCase#g' \
  -e 's#com\.kista\.application\.usecase\.UserStatsUseCase#com.kista.stats.application.usecase.UserStatsUseCase#g' \
  -e 's#com\.kista\.application\.usecase\.BacktestUseCase#com.kista.stats.application.usecase.BacktestUseCase#g' \
  -e 's#com\.kista\.application\.usecase\.FetchHousingBenchmarkUseCase#com.kista.stats.application.usecase.FetchHousingBenchmarkUseCase#g' \
  -e 's#com\.kista\.application\.usecase\.FetchHousingPriceIndexUseCase#com.kista.stats.application.usecase.FetchHousingPriceIndexUseCase#g' \
  -e 's#com\.kista\.application\.usecase\.SyncMarketIndexPricesUseCase#com.kista.stats.application.usecase.SyncMarketIndexPricesUseCase#g' \
  <위 색출된 파일 목록>
```

다시 색출 명령을 돌려 결과가 비었는지(Task 2 이동 대상 어댑터만 남는지) 확인.

- [ ] **Step 6: 컴파일 확인 (완전 그린은 Task 2 이후)**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|FAILED"
```
Expected: Task 2 이동 대상 어댑터(`StatsController`/`BacktestController`/`Kb*Adapter`/`*PersistenceAdapter`/`AlpacaIndexPriceAdapter`/`Kb*Scheduler`/`MarketIndexPriceSyncScheduler` 및 이동 DTO)에서 `cannot find symbol` 다수 — 정상. 이 외 파일이 깨졌으면 Step 5 누락이므로 즉시 확인.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): stats 모듈 코어(domain+application) 이전

통계·백테스트·포트폴리오 도메인 25타입(stats 20 + backtest 5 flatten),
BacktestEngine/FillSimulator, 서비스 9개, usecase 6개, output port 6개를
com.kista.stats로 이전. 통계 서비스 3종(Account/Toss/BrokerStatistics)은
스펙 4단계 대상이라 레거시 잔류. 어댑터 레이어는 Task 2에서 이어서 이전 —
이 시점 컴파일 에러(이동 대상 어댑터의 레거시 경로 참조)는 정상.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 어댑터(in/out) 물리 이전 + 레거시 alpaca 소멸 + 전체 컴파일 그린화

> **SDD 오케스트레이터 주의:** 이 태스크는 ~55개 파일 이동으로 이번 계획에서 가장 크다. privacy 이전 때 유사 규모 태스크가 세션 한도로 중단됐고 `SendMessage`로 **동일 에이전트 재개**(부분 산출물 유지, 콜드 재디스패치 안 함)로 복구됐다. 이 태스크 실행 중 에이전트가 세션 한도/인프라 오류로 죽으면 `ListAgents`로 상태 확인 후 `SendMessage`로 먼저 재개를 시도할 것.

**Files:**
- Move: `src/main/java/com/kista/adapter/in/web/{StatsController,BacktestController}.java` → `src/main/java/com/kista/stats/adapter/in/web/`
- Move: `src/main/java/com/kista/adapter/in/web/dto/{StatsSummaryResponse,EquityCurveResponse,CyclePerformancePageResponse,EtfPriceSeriesResponse,BacktestResponse,HousingBenchmarkComparisonResponse,HousingBenchmarkRegionsResponse,HousingBenchmarkSeriesResponse,HousingPriceIndexSeriesResponse}.java` → `src/main/java/com/kista/stats/adapter/in/web/dto/`
- Move: `src/main/java/com/kista/adapter/in/schedule/{KbLandHousingBenchmarkScheduler,KbLandPriceIndexScheduler,MarketIndexPriceSyncScheduler}.java` → `src/main/java/com/kista/stats/adapter/in/schedule/`
- Move: `src/main/java/com/kista/adapter/out/kbland/{KbLandHousingBenchmarkAdapter,KbLandConfig,KbLandProperties}.java` → `src/main/java/com/kista/stats/adapter/out/kbland/`
- Move: `src/main/java/com/kista/adapter/out/persistence/housingbenchmark/*.java` (6개) → `src/main/java/com/kista/stats/adapter/out/persistence/housingbenchmark/`
- Move: `src/main/java/com/kista/adapter/out/persistence/marketindex/*.java` (3개) → `src/main/java/com/kista/stats/adapter/out/persistence/marketindex/`
- Move: `src/main/java/com/kista/adapter/out/alpaca/{AlpacaIndexPriceAdapter,AlpacaConfig,AlpacaProperties}.java` → `src/main/java/com/kista/stats/adapter/out/alpaca/` (레거시 alpaca 디렉토리 소멸)
- Modify (import 경로만, 레거시 잔류): `src/main/java/com/kista/adapter/in/web/dto/AdminSettingsRequest.java`(이동 DTO 참조 시), `src/main/java/com/kista/adapter/in/web/openapi/HousingBenchmarkOpenApiCustomizer.java`, `src/main/java/com/kista/broker/adapter/out/mock/MockBrokerAdapter.java`(주석 FQN)
- Move tests: `src/test/java/com/kista/adapter/in/web/{StatsControllerTest,BacktestControllerTest}.java` → `src/test/java/com/kista/stats/adapter/in/web/`; `src/test/java/com/kista/adapter/in/web/dto/{HousingBenchmarkApiDocsTest,HousingBenchmarkComparisonResponseSchemaTest,HousingBenchmarkComparisonResponseTest}.java` → `src/test/java/com/kista/stats/adapter/in/web/dto/`; `src/test/java/com/kista/adapter/in/schedule/{KbLandHousingBenchmarkSchedulerTest,KbLandPriceIndexSchedulerTest,MarketIndexPriceSyncSchedulerTest}.java` → `src/test/java/com/kista/stats/adapter/in/schedule/`; `src/test/java/com/kista/adapter/out/kbland/{KbLandConfigTest,KbLandHousingBenchmarkAdapterTest}.java` → `src/test/java/com/kista/stats/adapter/out/kbland/`; `src/test/java/com/kista/adapter/out/persistence/housingbenchmark/*.java` (2개) → `src/test/java/com/kista/stats/adapter/out/persistence/housingbenchmark/`; `src/test/java/com/kista/adapter/out/persistence/marketindex/MarketIndexPricePersistenceAdapterTest.java` → `src/test/java/com/kista/stats/adapter/out/persistence/marketindex/`; `src/test/java/com/kista/adapter/out/alpaca/{AlpacaConfigTest,AlpacaIndexPriceAdapterTest}.java` → `src/test/java/com/kista/stats/adapter/out/alpaca/`

**Interfaces:**
- Consumes: Task 1이 만든 `com.kista.stats.{domain.model,domain.backtest,application.usecase,application.port.output}.*`
- Produces: `com.kista.stats.adapter.*` 전체 — Task 3 NamedInterface 대상 아님(internal 유지)

- [ ] **Step 0: 문자열 리터럴 FQN 사전 스캔 (어댑터 경로 포함)**

```bash
grep -rn "com\.kista\.adapter\.\(in\.web\.\(StatsController\|BacktestController\)\|in\.schedule\.\(KbLand\|MarketIndexPriceSync\)\|out\.\(alpaca\|kbland\|persistence\.housingbenchmark\|persistence\.marketindex\)\)" src/main/resources/ src/main/java --include='*.yml' --include='*.xml'
git grep -n '"[^"]*com\.kista\.adapter\.out\.\(alpaca\|kbland\)' src/main/java
```
매치 시 함께 갱신. `application-*.yml`의 Logback 로거(`com.kista.adapter.out.alpaca` 등)·`scheduler` cron property key는 특히 확인.

- [ ] **Step 1: 어댑터 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/stats/adapter/in/web/dto
mkdir -p src/main/java/com/kista/stats/adapter/in/schedule
mkdir -p src/main/java/com/kista/stats/adapter/out/alpaca
mkdir -p src/main/java/com/kista/stats/adapter/out/kbland
mkdir -p src/main/java/com/kista/stats/adapter/out/persistence/housingbenchmark
mkdir -p src/main/java/com/kista/stats/adapter/out/persistence/marketindex

git mv src/main/java/com/kista/adapter/in/web/StatsController.java    src/main/java/com/kista/stats/adapter/in/web/StatsController.java
git mv src/main/java/com/kista/adapter/in/web/BacktestController.java src/main/java/com/kista/stats/adapter/in/web/BacktestController.java

for f in StatsSummaryResponse EquityCurveResponse CyclePerformancePageResponse EtfPriceSeriesResponse BacktestResponse HousingBenchmarkComparisonResponse HousingBenchmarkRegionsResponse HousingBenchmarkSeriesResponse HousingPriceIndexSeriesResponse; do
  git mv "src/main/java/com/kista/adapter/in/web/dto/$f.java" "src/main/java/com/kista/stats/adapter/in/web/dto/$f.java"
done

git mv src/main/java/com/kista/adapter/in/schedule/KbLandHousingBenchmarkScheduler.java src/main/java/com/kista/stats/adapter/in/schedule/KbLandHousingBenchmarkScheduler.java
git mv src/main/java/com/kista/adapter/in/schedule/KbLandPriceIndexScheduler.java       src/main/java/com/kista/stats/adapter/in/schedule/KbLandPriceIndexScheduler.java
git mv src/main/java/com/kista/adapter/in/schedule/MarketIndexPriceSyncScheduler.java   src/main/java/com/kista/stats/adapter/in/schedule/MarketIndexPriceSyncScheduler.java

git mv src/main/java/com/kista/adapter/out/kbland/KbLandHousingBenchmarkAdapter.java src/main/java/com/kista/stats/adapter/out/kbland/KbLandHousingBenchmarkAdapter.java
git mv src/main/java/com/kista/adapter/out/kbland/KbLandConfig.java                  src/main/java/com/kista/stats/adapter/out/kbland/KbLandConfig.java
git mv src/main/java/com/kista/adapter/out/kbland/KbLandProperties.java              src/main/java/com/kista/stats/adapter/out/kbland/KbLandProperties.java
rmdir src/main/java/com/kista/adapter/out/kbland

git mv src/main/java/com/kista/adapter/out/persistence/housingbenchmark/*.java src/main/java/com/kista/stats/adapter/out/persistence/housingbenchmark/
rmdir src/main/java/com/kista/adapter/out/persistence/housingbenchmark
git mv src/main/java/com/kista/adapter/out/persistence/marketindex/*.java src/main/java/com/kista/stats/adapter/out/persistence/marketindex/
rmdir src/main/java/com/kista/adapter/out/persistence/marketindex

git mv src/main/java/com/kista/adapter/out/alpaca/AlpacaIndexPriceAdapter.java src/main/java/com/kista/stats/adapter/out/alpaca/AlpacaIndexPriceAdapter.java
git mv src/main/java/com/kista/adapter/out/alpaca/AlpacaConfig.java            src/main/java/com/kista/stats/adapter/out/alpaca/AlpacaConfig.java
git mv src/main/java/com/kista/adapter/out/alpaca/AlpacaProperties.java        src/main/java/com/kista/stats/adapter/out/alpaca/AlpacaProperties.java
rmdir src/main/java/com/kista/adapter/out/alpaca   # 비어야 함 — 안 비면 STOP하고 남은 파일 보고
```

- [ ] **Step 2: 테스트 파일 물리 이동**

```bash
mkdir -p src/test/java/com/kista/stats/adapter/in/web/dto
mkdir -p src/test/java/com/kista/stats/adapter/in/schedule
mkdir -p src/test/java/com/kista/stats/adapter/out/alpaca
mkdir -p src/test/java/com/kista/stats/adapter/out/kbland
mkdir -p src/test/java/com/kista/stats/adapter/out/persistence/housingbenchmark
mkdir -p src/test/java/com/kista/stats/adapter/out/persistence/marketindex

git mv src/test/java/com/kista/adapter/in/web/StatsControllerTest.java    src/test/java/com/kista/stats/adapter/in/web/StatsControllerTest.java
git mv src/test/java/com/kista/adapter/in/web/BacktestControllerTest.java src/test/java/com/kista/stats/adapter/in/web/BacktestControllerTest.java
git mv src/test/java/com/kista/adapter/in/web/dto/HousingBenchmarkApiDocsTest.java                 src/test/java/com/kista/stats/adapter/in/web/dto/HousingBenchmarkApiDocsTest.java
git mv src/test/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponseSchemaTest.java src/test/java/com/kista/stats/adapter/in/web/dto/HousingBenchmarkComparisonResponseSchemaTest.java
git mv src/test/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponseTest.java       src/test/java/com/kista/stats/adapter/in/web/dto/HousingBenchmarkComparisonResponseTest.java
git mv src/test/java/com/kista/adapter/in/schedule/KbLandHousingBenchmarkSchedulerTest.java src/test/java/com/kista/stats/adapter/in/schedule/KbLandHousingBenchmarkSchedulerTest.java
git mv src/test/java/com/kista/adapter/in/schedule/KbLandPriceIndexSchedulerTest.java       src/test/java/com/kista/stats/adapter/in/schedule/KbLandPriceIndexSchedulerTest.java
git mv src/test/java/com/kista/adapter/in/schedule/MarketIndexPriceSyncSchedulerTest.java   src/test/java/com/kista/stats/adapter/in/schedule/MarketIndexPriceSyncSchedulerTest.java
git mv src/test/java/com/kista/adapter/out/kbland/KbLandConfigTest.java                 src/test/java/com/kista/stats/adapter/out/kbland/KbLandConfigTest.java
git mv src/test/java/com/kista/adapter/out/kbland/KbLandHousingBenchmarkAdapterTest.java src/test/java/com/kista/stats/adapter/out/kbland/KbLandHousingBenchmarkAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/kbland
git mv src/test/java/com/kista/adapter/out/persistence/housingbenchmark/*.java src/test/java/com/kista/stats/adapter/out/persistence/housingbenchmark/
rmdir src/test/java/com/kista/adapter/out/persistence/housingbenchmark
git mv src/test/java/com/kista/adapter/out/persistence/marketindex/MarketIndexPricePersistenceAdapterTest.java src/test/java/com/kista/stats/adapter/out/persistence/marketindex/MarketIndexPricePersistenceAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/persistence/marketindex
git mv src/test/java/com/kista/adapter/out/alpaca/AlpacaConfigTest.java           src/test/java/com/kista/stats/adapter/out/alpaca/AlpacaConfigTest.java
git mv src/test/java/com/kista/adapter/out/alpaca/AlpacaIndexPriceAdapterTest.java src/test/java/com/kista/stats/adapter/out/alpaca/AlpacaIndexPriceAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/alpaca   # 비어야 함
```

- [ ] **Step 3: package 선언 + import 일괄 치환 (이동 파일)**

```bash
find src/main/java/com/kista/stats/adapter src/test/java/com/kista/stats/adapter -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.adapter\.in\.web\.dto;/package com.kista.stats.adapter.in.web.dto;/' \
  -e 's/^package com\.kista\.adapter\.in\.web;/package com.kista.stats.adapter.in.web;/' \
  -e 's/^package com\.kista\.adapter\.in\.schedule;/package com.kista.stats.adapter.in.schedule;/' \
  -e 's/^package com\.kista\.adapter\.out\.alpaca;/package com.kista.stats.adapter.out.alpaca;/' \
  -e 's/^package com\.kista\.adapter\.out\.kbland;/package com.kista.stats.adapter.out.kbland;/' \
  -e 's/^package com\.kista\.adapter\.out\.persistence\.housingbenchmark;/package com.kista.stats.adapter.out.persistence.housingbenchmark;/' \
  -e 's/^package com\.kista\.adapter\.out\.persistence\.marketindex;/package com.kista.stats.adapter.out.persistence.marketindex;/' \
  -e 's/com\.kista\.domain\.model\.backtest\./com.kista.stats.domain.model.backtest./g' \
  -e 's/com\.kista\.domain\.model\.stats\./com.kista.stats.domain.model./g' \
  -e 's/com\.kista\.domain\.backtest\./com.kista.stats.domain.backtest./g' \
  -e 's/com\.kista\.application\.usecase\.UserStatsUseCase/com.kista.stats.application.usecase.UserStatsUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.BacktestUseCase/com.kista.stats.application.usecase.BacktestUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.PortfolioUseCase/com.kista.stats.application.usecase.PortfolioUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.FetchHousingBenchmarkUseCase/com.kista.stats.application.usecase.FetchHousingBenchmarkUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.FetchHousingPriceIndexUseCase/com.kista.stats.application.usecase.FetchHousingPriceIndexUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.SyncMarketIndexPricesUseCase/com.kista.stats.application.usecase.SyncMarketIndexPricesUseCase/g' \
  -e 's/com\.kista\.application\.port\.output\.HistoricalCandlePort/com.kista.stats.application.port.output.HistoricalCandlePort/g' \
  -e 's/com\.kista\.application\.port\.output\.HousingBenchmarkFeedPort/com.kista.stats.application.port.output.HousingBenchmarkFeedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.HousingBenchmarkPricePort/com.kista.stats.application.port.output.HousingBenchmarkPricePort/g' \
  -e 's/com\.kista\.application\.port\.output\.HousingPriceIndexPort/com.kista.stats.application.port.output.HousingPriceIndexPort/g' \
  -e 's/com\.kista\.application\.port\.output\.IndexPriceFeedPort/com.kista.stats.application.port.output.IndexPriceFeedPort/g' \
  -e 's/com\.kista\.application\.port\.output\.IndexPricePort/com.kista.stats.application.port.output.IndexPricePort/g' \
  -e 's/com\.kista\.adapter\.in\.web\.dto\.\(StatsSummaryResponse\|EquityCurveResponse\|CyclePerformancePageResponse\|EtfPriceSeriesResponse\|BacktestResponse\|HousingBenchmarkComparisonResponse\|HousingBenchmarkRegionsResponse\|HousingBenchmarkSeriesResponse\|HousingPriceIndexSeriesResponse\)/com.kista.stats.adapter.in.web.dto.\1/g' \
  -e 's/com\.kista\.adapter\.out\.kbland\./com.kista.stats.adapter.out.kbland./g' \
  -e 's/com\.kista\.adapter\.out\.persistence\.housingbenchmark\./com.kista.stats.adapter.out.persistence.housingbenchmark./g' \
  -e 's/com\.kista\.adapter\.out\.persistence\.marketindex\./com.kista.stats.adapter.out.persistence.marketindex./g' \
  {} +
```

와일드카드 확인:

```bash
git grep -n "^import com\.kista.*\*;" -- src/main/java/com/kista/stats/adapter src/test/java/com/kista/stats/adapter
```
`com.kista.domain.model.stats.*` / `com.kista.application.port.output.*` 등 잔존 와일드카드가 있으면 위 sed의 개별 치환이 커버 못 한 것 — 수동으로 `com.kista.stats.*` 로 교체 후 컴파일로 검증.

- [ ] **Step 4: 레거시 잔류 소비자 2곳 import/주석 갱신**

`AdminSettingsRequest.java`는 stats *도메인 enum*만 참조(Task 1 Step 5에서 이미 처리됨) — 이동 DTO는 참조하지 않으므로 여기서 손대지 않는다.

```bash
# HousingBenchmarkOpenApiCustomizer: 이동한 응답 DTO(HousingBenchmarkComparisonResponse 등) 참조 경로 갱신
git grep -n "com\.kista\.adapter\.in\.web\.dto\.\(StatsSummaryResponse\|EquityCurveResponse\|CyclePerformancePageResponse\|EtfPriceSeriesResponse\|BacktestResponse\|HousingBenchmark\|HousingPriceIndexSeriesResponse\)" src/main/java/com/kista/adapter/in/web/openapi/HousingBenchmarkOpenApiCustomizer.java
sed -i '' 's#com\.kista\.adapter\.in\.web\.dto\.\(StatsSummaryResponse\|EquityCurveResponse\|CyclePerformancePageResponse\|EtfPriceSeriesResponse\|BacktestResponse\|HousingBenchmarkComparisonResponse\|HousingBenchmarkRegionsResponse\|HousingBenchmarkSeriesResponse\|HousingPriceIndexSeriesResponse\)#com.kista.stats.adapter.in.web.dto.\1#g' \
  src/main/java/com/kista/adapter/in/web/openapi/HousingBenchmarkOpenApiCustomizer.java

# MockBrokerAdapter 주석 내 FQN (import 아님, 컴파일 무관 — 문서 정확성)
sed -i '' 's#com\.kista\.domain\.backtest\.FillSimulator#com.kista.stats.domain.backtest.FillSimulator#' \
  src/main/java/com/kista/broker/adapter/out/mock/MockBrokerAdapter.java
```

- [ ] **Step 5: 전체 컴파일 + 대상 테스트 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 에러 없음. 실패 시 잔존 옛 경로 재검색:
```bash
git grep -n "com\.kista\.domain\.model\.stats\|com\.kista\.domain\.model\.backtest\|com\.kista\.domain\.backtest\|com\.kista\.application\.service\.\(stats\|backtest\|portfolio\)\|com\.kista\.application\.usecase\.\(UserStats\|Backtest\|Portfolio\|FetchHousing\|SyncMarketIndex\)\|com\.kista\.application\.port\.output\.\(HistoricalCandle\|HousingBenchmark\|HousingPriceIndex\|IndexPrice\)\|com\.kista\.adapter\.in\.web\.\(StatsController\|BacktestController\)\|com\.kista\.adapter\.in\.schedule\.\(KbLand\|MarketIndexPriceSync\)\|com\.kista\.adapter\.out\.\(alpaca\|kbland\|persistence\.housingbenchmark\|persistence\.marketindex\)" src/main src/test
```

```bash
./gradlew test --tests 'com.kista.stats.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`, ~24개 테스트 클래스 전부 통과.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): stats 모듈 어댑터 이전 + 레거시 alpaca 소멸

StatsController/BacktestController + 응답 DTO 9종, KbLand·MarketIndexSync
스케쥴러 3종, kbland·alpaca·persistence(housingbenchmark/marketindex)
어댑터를 com.kista.stats로 이전. market 이전 때 복제로 남겨둔 레거시
adapter/out/alpaca(AlpacaConfig/Properties)를 stats가 소유권 인수 —
레거시 디렉토리 소멸(market 판 marketAlpacaConfig 빈은 무관, 유지).
전체 컴파일·stats 테스트 그린 확인.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: stats→notify 이벤트 전환 + 모듈 선언(CLOSED + NamedInterface) — 순환 해소·검증

> **배경:** stats CLOSED 전환 시 `stats → notify`(`HousingBenchmarkService`·`HousingPriceIndexService`가 `NotifyPort.notifyError` 직접 호출) + `notify → stats`(`TelegramBotService`가 `PortfolioUseCase` 참조, 텔레그램 `/portfolio` 응답 — 기존 설계) 두 변이 **직접 2-cycle**(`stats ↔ notify`)을 만든다. `notify → stats`는 정상 단방향이라 건드리지 않고, 새로 문제되는 `stats → notify` 두 지점만 이벤트로 끊는다 — market `FearGreedFetchFailedEvent`/`MarketAlertNotifier`, privacy `PrivacyAlertRaisedEvent`/`PrivacyAlertNotifier`와 동일 패턴(3번째 인스턴스).
>
> **이 태스크가 모듈 선언까지 포함하는 이유:** 전이 순환은 `@ApplicationModule` 선언 후 `ApplicationModules.verify()`로만 관찰 가능하다(market/privacy 교훈). 이벤트 전환(코드 변경)과 모듈 선언을 다른 태스크로 쪼개면 이벤트 전환 태스크가 "고쳤다는 증거 0"으로 커밋되고, 예측 못한 3번째 순환이 있으면 두 태스크치 변경을 bisect해야 한다. 한 태스크로 묶어 `verify()`를 실질 게이트로 삼는다(privacy Task 4와 동일 구조).

**Files:**
- Create: `src/main/java/com/kista/stats/application/event/StatsAlertRaisedEvent.java`
- Create: `src/main/java/com/kista/stats/application/event/package-info.java`
- Create: `src/main/java/com/kista/stats/package-info.java`
- Create: `src/main/java/com/kista/stats/domain/model/package-info.java`
- Create: `src/main/java/com/kista/stats/domain/model/backtest/package-info.java`
- Create: `src/main/java/com/kista/stats/application/usecase/package-info.java`
- Create: `src/main/java/com/kista/stats/application/port/output/package-info.java`
- Create: `src/main/java/com/kista/stats/adapter/in/schedule/package-info.java`  ← "schedule" NamedInterface (AdminSchedulerController가 KbLand 스케쥴러 직접 주입, Task 2에서 발견)
- Modify: `src/main/java/com/kista/stats/application/service/HousingBenchmarkService.java`
- Modify: `src/main/java/com/kista/stats/application/service/HousingPriceIndexService.java`
- Create: `src/main/java/com/kista/notify/adapter/out/gateway/StatsAlertNotifier.java`
- Modify: `src/test/java/com/kista/stats/application/service/HousingBenchmarkServiceTest.java`, `src/test/java/com/kista/stats/application/service/HousingPriceIndexServiceTest.java`
- Create: `src/test/java/com/kista/notify/adapter/out/gateway/StatsAlertNotifierTest.java`

**Interfaces:**
- Produces: `com.kista.stats.application.event.StatsAlertRaisedEvent(String message)` — "event" NamedInterface, notify가 구독. `"domain"`/`"usecase"`/`"port"`/`"event"`/`"schedule"` 5개 NamedInterface 선언 완료 (schedule은 Task 2에서 발견된 AdminSchedulerController 직접 주입 때문 — trading 선례).
- Consumes: 기존 `com.kista.notify.application.port.output.NotifyPort.notifyError(Exception)` (notify 쪽에서만 계속 사용).

- [ ] **Step 1: 이벤트 record + package-info("event") 작성**

```java
// src/main/java/com/kista/stats/application/event/StatsAlertRaisedEvent.java
package com.kista.stats.application.event;

// KB Land 벤치마크(주택 5분위 매매가·매매가격지수) 수집 실패 알림 — 관리자 전용(NotifyPort.notifyError), userId 없음.
// stats→notify 직접 호출을 끊기 위한 이벤트(market FearGreedFetchFailedEvent / privacy PrivacyAlertRaisedEvent와 동일 패턴).
// Exception 자체는 EPR 직렬화 부적합(스택트레이스·cause 체인)이라 message(String)만 담는다 —
// 소비처(notify)가 e.getMessage() 문자열만 쓴다는 걸 두 서비스의 기존 notifyError(e) 호출로 확인했다.
public record StatsAlertRaisedEvent(String message) {
}
```

```java
// src/main/java/com/kista/stats/application/event/package-info.java
// stats 모듈의 공개 계약 일부 — StatsAlertRaisedEvent. notify 모듈이 @TransactionalEventListener로 구독한다
// (CLOSED↔CLOSED 모듈 간 이벤트 교차, trading/market/privacy.application.event와 동일 패턴). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.stats.application.event;
```

- [ ] **Step 2: 두 서비스를 이벤트 발행으로 전환**

각 서비스에서 `private final NotifyPort notifyPort;` + `import com.kista.notify.application.port.output.NotifyPort;` 제거, `ApplicationEventPublisher` 로 교체. `catch` 블록의 `notifyPort.notifyError(e)` → `eventPublisher.publishEvent(new StatsAlertRaisedEvent(e.getMessage()))`. 기존 `log.error` 호출·catch 범위·나머지 로직은 그대로.

`HousingBenchmarkService.java` (현재 구조 기준 — 실제 파일 읽고 맞출 것):
```java
package com.kista.stats.application.service;

import com.kista.stats.application.event.StatsAlertRaisedEvent;
// ... 기존 stats 도메인/포트/usecase import 유지 ...
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class HousingBenchmarkService implements FetchHousingBenchmarkUseCase {

    // ... 기존 feed/price 포트 필드 유지 ...
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void fetchAndSave(/* 기존 시그니처 */) {
        try {
            // ... 기존 수집·저장 로직 그대로 ...
        } catch (Exception e) {
            log.error("KB Land 주택 벤치마크 수집 실패: {}", e.getMessage(), e);
            eventPublisher.publishEvent(new StatsAlertRaisedEvent(e.getMessage()));
        }
    }
}
```
`HousingPriceIndexService.java` 도 동일 패턴(메시지 문자열만 "KB Land 매매가격지수 수집 실패"로).

- [ ] **Step 3: notify에 리스너 추가 (MarketAlertNotifier / PrivacyAlertNotifier와 동일)**

```java
// src/main/java/com/kista/notify/adapter/out/gateway/StatsAlertNotifier.java
package com.kista.notify.adapter.out.gateway;

import com.kista.stats.application.event.StatsAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// stats가 발행하는 KB Land 벤치마크 수집 실패 이벤트를 구독해 기존 NotifyPort.notifyError를 그대로 호출한다.
// 두 서비스의 fetchAndSave()에 @Transactional이 없어 phase 미지정 + fallbackExecution=true로
// 트랜잭션이 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다(MarketAlertNotifier와 동일 이유).
@Component
@RequiredArgsConstructor
public class StatsAlertNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onStatsAlertRaised(StatsAlertRaisedEvent event) {
        notifyPort.notifyError(new RuntimeException(event.message()));
    }
}
```

- [ ] **Step 4: 모듈 선언 package-info 6개 작성** (root + domain.model + domain.model.backtest + usecase + port.output + adapter.in.schedule; event는 Step 1에서 이미 생성)

```java
// src/main/java/com/kista/stats/package-info.java
// stats 애그리게이트(사용자 통계·주택/ETF 벤치마크 비교·과거 일봉 백테스트·텔레그램 포트폴리오 조회) 모듈 —
// domain.model·application.{usecase,port.output,event}·adapter.in.schedule만 공개 계약, application.service·domain.backtest·나머지 adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.stats;
```

```java
// src/main/java/com/kista/stats/domain/model/package-info.java
// stats 모듈의 공개 계약 일부 — 불변 값 객체(record/enum), 통계·벤치마크 도메인 타입. "domain" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.stats.domain.model;
```

```java
// src/main/java/com/kista/stats/domain/model/backtest/package-info.java
// stats 모듈의 공개 계약 일부 — 백테스트 커맨드·결과 도메인 타입(레거시 domain/model/backtest 구조 유지).
// "domain" 이름으로 병합 공개된다(domain/model과 동일 이름 — trading의 model+strategy 병합 공개와 같은 패턴).
@org.springframework.modulith.NamedInterface("domain")
package com.kista.stats.domain.model.backtest;
```

```java
// src/main/java/com/kista/stats/application/usecase/package-info.java
// stats 모듈의 공개 계약 일부 — UseCase/Query 인터페이스. "usecase" 이름으로 공개된다.
// (AccountStatisticsUseCase/TossStatisticsUseCase는 스펙 4단계 대상이라 레거시 잔류 — 이 패키지 아님.)
@org.springframework.modulith.NamedInterface("usecase")
package com.kista.stats.application.usecase;
```

```java
// src/main/java/com/kista/stats/application/port/output/package-info.java
// stats 모듈의 공개 계약 일부 — *Port 접미사 출력 포트 인터페이스. "port" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("port")
package com.kista.stats.application.port.output;
```

```java
// src/main/java/com/kista/stats/adapter/in/schedule/package-info.java
// stats 모듈의 공개 계약 일부 — KbLand·MarketIndexSync 스케쥴러. legacy AdminSchedulerController가
// KbLand 스케쥴러 2개를 수동 트리거용 ObjectProvider<>로 직접 주입하는 이 프로젝트의 기존 관례
// (trading.adapter.in.schedule "schedule" NamedInterface와 동일 이유)를 유지하기 위해 공개. "schedule" 이름.
@org.springframework.modulith.NamedInterface("schedule")
package com.kista.stats.adapter.in.schedule;
```

- [ ] **Step 5: 테스트 수정 + 신규 테스트**

`HousingBenchmarkServiceTest`·`HousingPriceIndexServiceTest`: `@Mock NotifyPort notifyPort` → `@Mock ApplicationEventPublisher eventPublisher`. `verify(notifyPort).notifyError(any())` → `verify(eventPublisher).publishEvent(any(StatsAlertRaisedEvent.class))` (또는 `argThat`로 메시지 검증). 나머지 assertion 스타일 유지 — 실제 기존 테스트 구조를 읽고 맞출 것.

`StatsAlertNotifierTest`(신규, `src/test/java/com/kista/notify/adapter/out/gateway/`): `MarketAlertNotifierTest`·`PrivacyAlertNotifierTest` 구조 참고 — `StatsAlertRaisedEvent("펌프 실패")`를 리스너에 직접 전달하고 `verify(notifyPort).notifyError(argThat(e -> e.getMessage().equals("펌프 실패")))` 검증.

- [ ] **Step 6: ArchUnit — 순환 해소 + 모듈 검증**

```bash
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | tail -80
```
Expected: `ModulithArchitectureTest`(`ApplicationModules.verify()`)·`HexagonalArchitectureTest` 전부 통과. `verify()`가 `Cycle detected` 를 보고하면:
- `stats -> notify -> stats` 만 남았으면 Step 2 전환 누락 — `git grep -n "NotifyPort" src/main/java/com/kista/stats` 재확인.
- 예측 못한 3번째 모듈이 순환에 끼어있으면(예: `stats -> X -> ... -> stats`) **즉시 멈추고 보고** — 이 세션의 사전 실측이 놓친 케이스이므로 추측으로 고치지 않는다(market/privacy에서 pairwise가 놓친 전이 순환 전례).
- "non-exposed type" 경고: 외부(레거시 소비자)가 stats의 비공개 패키지(`application.service`/`domain.backtest`/`adapter.out.*`/`adapter.in.web*`) 타입을 참조하면 발생. 해당 소비자가 공개 대상(`domain.model`/`usecase`/`port`/`event`/`adapter.in.schedule`)만 쓰도록 조정하거나, 정말 공개해야 하는 타입이면 보고 후 NamedInterface 조정. (AdminSchedulerController → `adapter.in.schedule`는 이미 "schedule"로 공개하므로 정상.)

- [ ] **Step 7: 전체 컴파일 + stats/notify 테스트 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.stats.*' --tests 'com.kista.notify.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: 에러 없음, 전부 통과.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): stats 모듈 선언 + stats→notify 이벤트 전환 — 순환 해소

HousingBenchmarkService/HousingPriceIndexService의 NotifyPort.notifyError
직접 호출을 StatsAlertRaisedEvent 발행으로 전환, notify가 StatsAlertNotifier로
구독(market/privacy 이벤트 패턴 3번째 인스턴스). stats↔notify 직접 2-cycle
해소. @ApplicationModule CLOSED + domain·usecase·port·event·schedule 5개 NamedInterface
공개. ApplicationModules.verify()·HexagonalArchitectureTest 그린 확인.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 문서 갱신 + 전체 테스트 스위트 최종 검증

**Files:**
- Modify: `docs/agents/architecture.md` (stats 모듈 절 추가, 레거시 `domain/model`·`application/service`·`adapter/out` 목록에서 stats/backtest/portfolio/alpaca/kbland/housingbenchmark/marketindex 언급 제거, `adapter/out/alpaca` 관련 서술 정정 — 이제 레거시엔 없음)
- Modify: `docs/agents/constraints.md` (stats 항목 필요 시 — 예: "Spring Modulith 이전 중 신규 파일 배치"에 stats 추가)
- Modify: `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` (stats를 "완료"로 표시, "결합도 실측" 표 stats 행에 `stats↔notify` 직접 순환이 실측됐고 이벤트로 해소했다는 각주 추가 — pairwise가 놓친 게 아니라 표가 "단방향이라 순환 아님"으로 판단했으나 PortfolioUseCase 이동으로 순환이 됐다는 점 명시)
- Modify: `README.md` (아키텍처 다이어그램·배지에 stats/alpaca/kbland 관련 클래스·패키지명이 있으면 갱신 — 없으면 스킵)

**Interfaces:** 없음(문서 전용, 코드 변경 없음)

- [ ] **Step 1: architecture.md에 stats 모듈 절 추가**

기존 `market`/`privacy` 절과 동일 형식으로 `com.kista.stats/` 트리 구조(위 "File Structure")와 NamedInterface 구성("domain"/"usecase"/"port"/"event"/"schedule")을 기술 — "schedule"은 trading과 동일하게 AdminSchedulerController의 KbLand 스케쥴러 직접 주입 때문. `Spring Modulith 점진 도입` 절의 "→ privacy✅(6번째)" 뒤에 "→ stats✅(7번째)" 추가하고, `stats↔notify` 순환 해소(이벤트 전환, `StatsAlertRaisedEvent`/`StatsAlertNotifier`)를 market/privacy와 같은 줄에 요약. 레거시 절에서 제거할 언급:
- `domain/model/` 서술의 stats/backtest 서브패키지
- `application/service/` 서술의 stats/backtest/portfolio 서브패키지 + `stats/` 하위 상세(StatsService/StatsResultCache/MonthlyReturnCalculator/HousingBenchmarkComparisonBuilder 설명 → stats 모듈 절로 이동)
- `adapter/out/` 서술의 `alpaca/`(AlpacaIndexPriceAdapter·"stats 소유라 market 이전 대상에서 배제" 문구 → 이제 stats 모듈로 이전 완료로 갱신), `kbland/`, `persistence/` 의 housingbenchmark/marketindex
- `MarketIndexPrice*` 계열이 "stats 소유"라던 market 절 각주 → "stats 모듈로 이전 완료"로 갱신

- [ ] **Step 2: constraints.md — "Spring Modulith 이전 중 신규 파일 배치"에 stats 추가**

market/privacy 문단과 같은 형식으로: "통계·백테스트·포트폴리오 애그리게이트는 `com.kista.stats`로 이미 옮겨졌다 — 신규 관련 코드도 레거시 최상위가 아닌 `com.kista.stats` 안에 추가. `domain/model`이 "domain", `application/usecase`가 "usecase", `application/port/output`이 "port", `application/event`가 "event", `adapter/in/schedule`이 "schedule"로 NamedInterface 공개 — `application/service`·`domain/backtest`·나머지 `adapter/*`는 비공개(internal). 통계 서비스 3종(`AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`)·`AccountStatisticsUseCase`/`TossStatisticsUseCase`는 스펙 4단계(account+strategy-config) 대상이라 레거시 잔류."

- [ ] **Step 3: 스펙 문서에 완료 표시**

`docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` "착수 순서" 1단계 stats 옆에 "✅ 완료(2026-09-01, `2026-09-01-modulith-stats-migration` 실행 계획 4개 태스크)" 각주. "결합도 실측" 표 stats 행 정정: `stats↔notify` 직접 순환이 실재했다 — 표는 `notify→stats`(TelegramBotService→PortfolioUseCase)를 "단방향이라 순환 아님"으로 판정했으나, `PortfolioUseCase`가 stats 모듈로 이동하면서 기존 `stats→notify`(HousingBenchmarkService/HousingPriceIndexService의 notifyError)와 합쳐져 2-cycle이 됐다. Task 3에서 `StatsAlertRaisedEvent`/`StatsAlertNotifier`로 해소(market `FearGreedFetchFailedEvent`, privacy `PrivacyAlertRaisedEvent`와 동일 패턴, 3번째). **다음 모듈(user) 착수 시**: "단방향이라 안전"이라고 표에 적힌 forward/backward도, 그 대상 타입이 이동 대상이면 이동 후 방향이 뒤집힐 수 있으니 재확인.

- [ ] **Step 4: README.md drift 확인**

```bash
grep -n "stats\|Stats\|backtest\|Backtest\|Alpaca\|KbLand\|Housing\|MarketIndex\|Portfolio" README.md
```
매치가 옛 패키지 경로(`com.kista.adapter.out.alpaca` 등)나 "stats 소유 예정" 류 서술이면 갱신. 매치 없으면 스킵.

- [ ] **Step 5: 전체 테스트 스위트 최종 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`, 실패 0. (privacy 완료 시점 1832개 — 이번은 순수 이동 + 이벤트 리스너 1개 신규 테스트라 총 개수는 `StatsAlertNotifierTest` 케이스 수만큼만 증가.)

XML 교차확인:
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```
Expected: 출력 없음.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
docs(modulith): stats 모듈 이전 반영 — architecture.md/constraints.md/스펙 갱신

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 메모 (계획 작성자 기준)

- **스펙 커버리지**: 스펙 "착수 순서 v2" 1단계 잔여분 중 stats 항목 전체 커버 — 이동 대상 ~55개 main 파일 + ~24개 test 파일 + 레거시 잔류 소비자(TelegramBotService/AdminSettingsRequest → Task 1, HousingBenchmarkOpenApiCustomizer/MockBrokerAdapter주석 → Task 2) import 정합화 + alpaca 레거시 소멸(market 계획 IOU 정산) + 모듈 선언 + 문서. `AccountStatisticsService` 등 통계 서비스 3종은 스펙상 4단계라 명시적 스코프 아웃.
- **backtest 도메인 구조**: 레거시 `domain/model/backtest`(커맨드·결과 5타입)와 `domain/backtest`(엔진 2클래스)를 각각 `com.kista.stats.domain.model.backtest`·`com.kista.stats.domain.backtest`로 구조 그대로 이전(flatten 안 함 — 다른 모듈과 동일하게 내부 레이아웃 보존, sed 1:1). 전자는 "domain" 병합 공개, 후자는 internal.
- **플레이스홀더 스캔**: Task 3 Step 2의 서비스 본문은 "실제 파일 읽고 맞출 것"으로 위임 — 순수 이동이 아닌 유일한 로직 변경 지점이고, `catch` 블록 1줄 교체라 market `FearGreedService`(계획에 전체 본문 포함)와 달리 전체 재작성 불필요. 나머지 Step은 실행 가능한 정확한 명령/경로.
- **타입 일관성**: Task 1이 만든 25 domain + 6 usecase + 6 port FQN이 Task 2·3에서 동일 재사용. Task 3 `StatsAlertRaisedEvent(String message)` 시그니처가 Task 3 Step 1(정의)·Step 3(notify 소비)·Step 5(테스트)에서 일치.
- **순환 리스크**: 사전 실측으로 `stats↔notify` 1건 특정, Task 3에서 해소. pairwise 맹점 대비 — Task 3 Step 6에서 `verify()`가 예측 못한 순환 보고 시 즉시 중단 규정 명시(market/privacy 전례).
- **와일드카드**: `StatsService.java:11` 한 줄 2-import(`com.kista.application.port.output.*` + `com.kista.trading.application.port.output.*`)가 최대 함정 — Task 1 Step 4에서 전용 처리. `domain.model.stats.*`(20타입)는 sed 개별 치환 + `com.kista.domain.model.stats.` 접두 치환 둘 다 적용.
- **문자열 리터럴 FQN**: Task 1 Step 0 / Task 2 Step 0에서 resources(yml/xml Logback 로거) + java 문자열 스캔. `MockBrokerAdapter.java:172` 주석 FQN은 Task 2 Step 4에서 명시 갱신.
- **alpaca 빈 이름**: Task 2에서 stats 판은 레거시 빈 이름(`alpacaRestClient` 등) 그대로 유지, market 판(`marketAlpacaConfig`)과 무충돌 — Global Constraints에 명시.
