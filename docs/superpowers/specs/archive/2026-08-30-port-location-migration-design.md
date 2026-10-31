# 포트 위치 전환 설계 — domain/port/{in,out} → application/{usecase,port/output}

Spring Modulith 4모듈(finance/notify/broker/trading) 이전 완료 후 보류됐던 후속 항목 1번. 원칙 SSOT는 [2026-08-27-spring-modulith-migration-design.md](2026-08-27-spring-modulith-migration-design.md) — 해당 문서 39~41행에서 "포트 위치는 domain 유지"로 결정했던 것을 이번에 뒤집는다. 순수 컨벤션 변경(정오 문제 아님, 취향 문제) — 모듈 경계와 무관하게 hex 레이어 내부 규칙만 바꾼다.

## 목표

인터페이스 이름(`*UseCase`/`*Query`/`*Port`)은 그대로 두고, 물리적 패키지 위치만 이동한다:
- `domain/port/in/` → `application/usecase/`
- `domain/port/out/` → `application/port/output/`

`domain/model/`(record/enum)은 이동하지 않는다 — 포트가 참조하는 타입 위치 규칙(constraints.md "도메인 포트 인터페이스와 타입 위치 규칙")은 안 바뀐다.

## 스코프 — 5개 청크, 총 109개 포트 파일

레거시 top-level(`com.kista.domain`, OPEN 모듈)과 finance/notify/broker/trading(CLOSED 모듈) 전체. 순서는 과거 4모듈 이전과 동일하게 작은 것 → 큰 것이 아니라, **레거시(가장 큼, NamedInterface 재구성 불필요)를 먼저 하고 패턴을 확립한 뒤 finance→notify→broker→trading(작은 것→큰 것, NamedInterface 재구성 필요) 순으로 진행** — 사용자 확정.

### 1. 레거시 (`com.kista.domain.port.{in,out}` → `com.kista.application.usecase`/`com.kista.application.port.output`) — 59개

`domain/port/in` 30개: AccountStatisticsUseCase, AccountUseCase, AdminQueryUseCase, AdminReorderUseCase, AdminSettingsUseCase, AdminStrategyUseCase, AdminTradeCorrectionUseCase, AdminUserUseCase, BacktestUseCase, BlacklistUseCase, FetchFearGreedUseCase, FetchHousingBenchmarkUseCase, FetchHousingPriceIndexUseCase, GetFearGreedUseCase, **GetUserSettingsQuery**(UseCase 접미사 아닌 유일한 예외), MarketUseCase, PortfolioUseCase, PrivacyTradeValidationUseCase, PrivacyUseCase, RuntimeSettingsUseCase, StrategyUseCase, SyncMarketIndexPricesUseCase, TokenUseCase, TossStatisticsUseCase, UpdateBalanceCheckUseCase, UpdateNotificationPrefUseCase, UpdateStrategySuggestionsUseCase, UserProfileUseCase, UserStatsUseCase, UserUseCase

`domain/port/out` 29개: AccountPort, AdminUserViewPort, AppErrorLogPort, AuditLogPort, BlacklistPort, CnnFearGreedPort, CryptoFearGreedPort, FearGreedSnapshotPort, HeartbeatPort, HistoricalCandlePort, HousingBenchmarkFeedPort, HousingBenchmarkPricePort, HousingPriceIndexPort, IndexPriceFeedPort, IndexPricePort, KakaoOAuthPort, MarketCalendarPort, MarketCalendarRefreshPort, MarketHolidayStorePort, PrivacyTradePort, RealtimeNotificationPort, RefreshTokenPort, RuntimeSettingsPort, StrategyInfiniteDetailPort, StrategyPort, StrategyVersionPort, StrategyVrDetailPort, UserPort, UserSettingsPort

NamedInterface 불필요(OPEN 모듈).

### 2. finance — 16개

`domain/port/in` 9개(package-info 제외): AssetSnapshotUseCase, BulkFinanceRegisterUseCase, FinanceAccountUseCase, FinanceBudgetUseCase, FinanceCategoryUseCase, FinanceGroupUseCase, FinanceRegistrationReminderUseCase, FinanceTransactionUseCase, MonthlyClosingUseCase
`domain/port/out` 7개: AssetSnapshotPort, FinanceAccountPort, FinanceBudgetPort, FinanceCategoryPort, FinanceGroupPort, FinanceTransactionPort, MonthlyClosingPort

### 3. notify — 4개

`domain/port/out`만: FcmDeviceTokenPort, NotifyPort, TelegramBotInfoPort, UserNotificationPort (port/in 없음 — UseCase 이전 없음, "usecase" NamedInterface 자체가 불필요)

### 4. broker — 16개

`domain/port/out`만: BrokerAccountPort, BrokerAdapterPort, BrokerConnectionTestPort, BrokerMarketCalendarPort, BrokerOrderCorrectionPort, BrokerPricePort, BrokerTokenCachePort, CandlePort, ExchangeRatePort, ExecutionPort, LiveBalancePort, MarginPort, MockSimulationDataPort, PortfolioPort, SellableQuantityPort, StockInfoPort (port/in 없음 — "usecase" NamedInterface 불필요)

### 5. trading — 8개

`domain/port/in` 2개: TradingExecutionUseCase, VrReconfigureUseCase
`domain/port/out` 6개: CyclePositionInfiniteDetailPort, CyclePositionPort, OrderPort, StrategyCyclePort, StrategyCycleVrPort, TradingErrorReportPort

**총 소스 파일**: 59+16+4+16+8 = 103개 (+ 대응 단위테스트 동일 개수만큼, 착수 직전 `find`로 재확인 — 과거 매 모듈 이전 때 사전 추정보다 실제가 많았던 선례 있음). 인터페이스 파일 자체 외에 이를 구현/참조하는 adapter·service·controller의 import 라인 수정이 diff 대부분을 차지한다.

## ArchUnit 규칙 재작성 (`HexagonalArchitectureTest.java`)

### 규칙 2 — 인바운드 어댑터 비의존 범위 축소

기존:
```java
ArchRule rule = noClasses()
        .that().resideInAPackage("com.kista..adapter.in..")
        .should().dependOnClassesThat()
        .resideInAPackage("com.kista..application..");
```

변경 후 — `application` 전체가 아니라 구현체 패키지(`application.service`)만 금지, 인터페이스 패키지(`application.usecase`/`application.port.output`)는 허용:
```java
ArchRule rule = noClasses()
        .that().resideInAPackage("com.kista..adapter.in..")
        .should().dependOnClassesThat()
        .resideInAPackage("com.kista..application.service..");
```
(`application.event`는 trading이 이미 이 규칙 밖에서 별도 취급 중 — 컨트롤러가 이벤트 타입을 직접 의존하는 경로 없음, 영향 없음)

### `*Port` 접미사 규칙 — 대상 패키지 경로 변경

```java
.that().resideInAPackage("com.kista..application.port.output..")
```
(`domain.port.out` → `application.port.output`, 조건·로직 동일)

### 신규 규칙 — 스코프 밖

`application.usecase` 인터페이스에 `*UseCase`/`*Query` 접미사 강제하는 규칙은 이번 스코프에 넣지 않는다(순수 위치 이동만, 명명 강제는 별도 개선 항목으로 분리). `GetUserSettingsQuery` 예외 1건이 있어 강제하려면 `*UseCase` 또는 `*Query` OR 조건이 필요 — 다룰 경우 별도 논의.

## NamedInterface 재구성 (finance/notify/broker/trading만 — 레거시는 OPEN이라 무관)

**결정**: usecase와 port를 별도 NamedInterface로 분리(사용자 확정) — `"usecase"`(application.usecase), `"port"`(application.port.output). `"domain"`은 domain.model(+broker의 kis/toss 서브패키지, +trading의 domain.strategy)만 남는다. broker의 기존 `"application"`(application.service) NamedInterface는 이름 충돌 없이 그대로 유지.

| 모듈 | 기존 NamedInterface | 변경 후 |
|---|---|---|
| finance | `"domain"` = model+port/in+port/out | `"domain"`=model만 / `"usecase"`=application.usecase / `"port"`=application.port.output |
| notify | `"domain"` = port/out만(model 없음) | `"port"`=application.port.output만 (port/in 없어 usecase 불필요) |
| broker | `"domain"`=model(+kis+toss)+port/out, `"application"`=application.service | `"domain"`=model(+kis+toss)만 / `"port"`=application.port.output / `"application"`=application.service 그대로(변경 없음, port/in 없어 usecase 불필요) |
| trading | `"domain"`=model+strategy+port/in+port/out, `"event"`, `"schedule"` | `"domain"`=model+strategy만 / `"usecase"`=application.usecase / `"port"`=application.port.output / `"event"`·`"schedule"` 그대로(변경 없음) |

각 신규 `application/usecase/package-info.java`·`application/port/output/package-info.java`는 기존 domain 하위 package-info의 설명 문구를 그대로 물려받되 NamedInterface 이름만 갱신한다(예: finance `application/usecase/package-info.java` — "finance 모듈의 공개 계약 일부 — UseCase 인터페이스(입력 포트). \"usecase\" 이름으로 공개된다.").

## 크로스모듈 import 사전 스캔 (notify/broker 이전 교훈 반영 필수)

과거 notify 이전 때 와일드카드 import(`import com.kista.domain.port.out.*;`) 누락 사고, broker 이전 때 문자열 리터럴 FQN(AOP pointcut) 누락 사고 있었음. 각 청크 착수 직전 반드시:

```bash
# 와일드카드 import 전수 스캔 (대상 청크의 옛 경로 기준으로 매 청크마다 재실행)
grep -rn "import com\.kista\.\(domain\|finance\.domain\|notify\.domain\|broker\.domain\|trading\.domain\)\.port\.\(in\|out\)\.\*;" src/main src/test

# 개별 import 전수 스캔 (경로 치환 대상 파악)
grep -rln "com\.kista\.domain\.port\.\(in\|out\)\." src/main src/test   # 레거시 청크 기준 예시, 청크마다 패턴 교체
```

문자열 리터럴 FQN(`@Around` 포인트컷 등)도 각 청크 착수 직전 재확인 — notify 청크에서 `ErrorLogAspect`의 `NotifyPort` 포인트컷 문자열 참조 1건 실제 발견됨(plain import grep으로는 안 잡힘). 매 청크(특히 broker/trading)마다 `git grep "<옛 패키지 경로>"`를 확장자 필터 없이 반드시 재실행할 것.

## constraints.md 개정

"도메인 포트 인터페이스와 타입 위치 규칙" 섹션 전면 개정:

```diff
- ### 도메인 포트 인터페이스와 타입 위치 규칙
- - `domain/port/in` 또는 `domain/port/out` 인터페이스의 파라미터·반환 타입으로 쓰이는 record/class는 반드시 `domain/model/` 하위에 위치 — `adapter/in/web/dto/`에 두면 `domain → adapter` ArchUnit 규칙 위반
- - `application/service`도 마찬가지로 `adapter` 패키지 import 금지 (`application → adapter` 규칙)
- - 컨트롤러 DTO와 겹치는 타입이 있으면 `domain/model/<도메인>` 패키지로 이동 후 DTO에서 re-import
+ ### 포트 인터페이스 위치 규칙
+ - 인바운드 포트(UseCase/Query 인터페이스)는 `application/usecase/`, 아웃바운드 포트(`*Port`)는 `application/port/output/`에 위치 — `domain/port/{in,out}`은 더 이상 사용하지 않는다
+ - 포트의 파라미터·반환 타입으로 쓰이는 record/class는 여전히 `domain/model/` 하위에 위치 — 포트 위치가 옮겨가도 타입 소유는 domain 유지
+ - `adapter/in`(컨트롤러 등)은 `application.usecase`/`application.port.output`(인터페이스)에는 의존 가능하지만 `application.service`(구현체)에는 의존 금지 — ArchUnit이 이 경계만 강제
+ - `application.service`도 마찬가지로 `adapter` 패키지 import 금지 (`application → adapter` 규칙, 변경 없음)
+ - 컨트롤러 DTO와 겹치는 타입이 있으면 `domain/model/<도메인>` 패키지로 이동 후 DTO에서 re-import (변경 없음)
```

### domain/port/out 네이밍 규칙 섹션도 경로 갱신

```diff
- ### domain/port/out/ 네이밍 규칙
- - 아웃바운드 포트 인터페이스: `*Port` 접미사. `*Repository` 접미사 사용 금지 — adapter 레이어 `*JpaRepository`와 혼동 유발
+ ### application/port/output/ 네이밍 규칙
+ - 아웃바운드 포트 인터페이스: `*Port` 접미사. `*Repository` 접미사 사용 금지 — adapter 레이어 `*JpaRepository`와 혼동 유발
```

### Spring Modulith 이전 중 신규 파일 배치 섹션의 경로 언급도 갱신

`com.kista.finance.domain.port.out`, `com.kista.broker.domain.port.out`, `com.kista.trading.domain.port.{in,out}` 등 이 섹션에서 언급하는 경로 전부 `application.{usecase,port.output}`로 교체.

## architecture.md 개정

패키지 맵 텍스트 전역에서 `domain/port/in`, `domain/port/out`, "도메인 포트 인터페이스" 서술을 찾아 갱신 — 항목별:
- 최상단 hex 레이어 개요의 `port/in/`·`port/out/` 라인
- finance/notify/broker/trading 각 모듈 블록의 `domain/port/{in,out}` 라인 및 NamedInterface 서술
- "Spring Modulith 점진 도입" 단락의 각 모듈 NamedInterface 목록 서술(`"domain"(domain.model+domain.port.{in,out})` 형태 전부 갱신)

CLAUDE.md 자체는 `docs/agents/*.md` 링크만 갖고 있어 직접 수정 불필요, `docs/agents/workflow.md`도 포트 경로 언급 있으면 확인.

## 청크 순서·worktree 전략 (확정)

레거시 → finance → notify → broker → trading, 청크별 별도 worktree(과거 4모듈 이전과 동일 패턴): worktree 생성 → SDD 태스크 분할 → 태스크별 구현+리뷰 → main 병합 → worktree 삭제 → 다음 청크는 새 worktree.

**태스크 분할 기준**: 레거시(59개 포트 파일 + 다수 consumer)는 broker 이전 때(94파일→4태스크) 전례 참고해 in/out 또는 애그리게이트 그룹 단위로 2~4개 태스크로 세분화. finance(16)·trading(8)은 각 1~2태스크. notify(4)·broker(15)는 각 1태스크로 충분할 가능성 높음 — 정확한 태스크 개수·경계는 writing-plans 단계에서 확정.

**각 태스크 공통 작업**: ① 대상 인터페이스 파일 이동(패키지 선언+디렉토리) ② 구현체(adapter)·소비자(service/controller) import 경로 일괄 치환 ③ 대응 단위테스트 동일 구조로 이동 ④ (finance/notify/broker/trading 청크만) package-info.java NamedInterface 재선언 ⑤ 컴파일 확인.

## 테스트/검증

- `ModulithArchitectureTest.verifyModularStructure()` — 각 청크 병합 전 필수 통과(NamedInterface 재선언이 정확한지 검증)
- `HexagonalArchitectureTest` — 위 규칙 변경 반영 후 전체 통과
- 각 청크 최종 병합 전 전체 테스트 스위트 1회(전역 CLAUDE.md 원칙 — 중간 반복 실행 금지, 청크 완료 시점에만)
- 레거시 청크는 5개 청크 중 유일하게 NamedInterface 영향 없음(OPEN 모듈) — 컴파일+ArchUnit만으로 충분, Modulith verify는 finance부터 의미 있음

## 스코프 밖

- 이벤트 발행 레지스트리 전환(memory 보류 2번 항목) — 별도 작업, 이번과 무관
- `application.usecase` 명명 강제 ArchUnit 규칙 신규 추가 — 위 "신규 규칙" 참고, 필요 시 별도 제안
- domain/model record 위치 변경 — 없음, 그대로

## 미해결 확인 필요 항목

없음. 브레인스토밍에서 NamedInterface 전략(A안, usecase/port 분리)·청크 순서(레거시 우선)·worktree 전략(청크별 분리) 전부 사용자 확정 완료.
