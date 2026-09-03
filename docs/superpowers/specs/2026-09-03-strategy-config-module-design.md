# strategy-config 신모듈 선언 + 4개 모듈 순환 해소 설계 (strategy-config 이전 서브프로젝트 C)

## 배경/목적

strategy-config 이전([[2026-08-31-legacy-module-catalog-design]] 4단계)을 A(완료, 커밋 `4bc7c6f3`)/B(완료, 커밋 `46d1612b`..`0f5a771c`)/C(이 문서) 3단계로 분해해 진행 중이다. A는 `Strategy` nested enum 4종을 sharedkernel로, B는 `StrategyVersion`/`StrategyInfiniteDetail`/`StrategyVrDetail`+`VrStrategyLifecycle`을 trading 소유로 이관했다. C는 얇아진 `Strategy` 애그리게이트(`Strategy`/`StrategyPort`/`StrategyUseCase`/`RegisterStrategyCommand`/`UpdateStrategyCommand`/`StrategySeedPreview`/`StrategyDetail`)로 신모듈 `com.kista.strategyconfig`를 CLOSED 선언하고, 그 과정에서 실측 발견된 모듈 순환을 전부 해소한다.

## 원래 브리핑과 실제 결합도의 차이

원래 브리핑은 "admin↔strategy-config 순환 1건"만 언급했다. 이 설계를 브레인스토밍하며 grep으로 재확인한 결과, 실제로는 **4개 모듈쌍 순환 + notify로의 연쇄**가 존재했다 — 이 프로젝트에서 반복돼온 "pairwise 사전조사가 실제 결합을 과소평가한다"는 교훈이 이번에도 재현됐다.

| 모듈쌍 | strategy-config → X (forward) | X → strategy-config (reverse, 순환의 원인) |
|---|---|---|
| admin | 없음(admin 소비만) | 6개 서비스가 `Strategy`/`StrategyPort` 소비(정상) — `StrategyService`가 `RuntimeSettingsPort`(admin "port") 역참조 |
| user | `UserPort`/`UserSettingsPort`(사용자 존재확인·잔고검증 설정) | `UserCascadeDeleter.strategyPort.deleteByUserId()`, `UserSettingsService.countActiveStrategies()`(`StrategyPort`+`AccountPort`) |
| broker | `BrokerAdapterRegistry`/`BrokerCallGuard`/`BrokerPricePort`/`MarginPort`/`BrokerAccountRef`(등록 시 자격증명·시드 검증) | `MockBrokerAdapter`가 `Strategy.id()`/`.ticker()` 직접 조회 |
| trading | `StrategyVersionPort`/`VrStrategyDetailUseCase`/`StrategyCyclePort`/`CyclePositionPort`/`CycleOrderStrategy` 계열(등록 시 사이클·버전 생성) | trading 내부 44개 파일이 `Strategy`/`StrategyPort`를 스케쥴러·실행 코어에서 상시 조회 — trading 자신의 domain record(`BatchContext` 등)와 이벤트 3종(trading "event" 공개 계약)에까지 필드로 박혀있음 |

trading 순환은 추가로 **notify로 연쇄**된다 — trading의 공개 이벤트 `NewCycleStartedEvent`/`CycleCompletedEvent`/`CycleEndedEvent`가 `Strategy` 필드를 그대로 노출해, 이를 구독하는 notify의 `UserNotificationPort`(+구현체 4개)까지 `Strategy` 타입을 직접 참조하게 됐다.

admin/user/broker 3건은 각각 이 코드베이스에 이미 검증된 own-type 역전 패턴(8번째 인스턴스)으로 해소 가능한 규모다. trading 건은 파일 수는 많지만(44개) 실제 재설계가 필요한 지점은 1곳(`CycleRotationService`의 쓰기 경로)뿐이고 나머지는 기계적 타입 치환이다 — 상세는 아래 "trading 결합 실측" 참고.

## trading 결합 실측 (own-type 근거)

44개 파일 중:
- **죽은 import 12개**(`Strategy` import만 있고 본문 미사용): `BuyCompetitionPreview`/`CyclePositionHistoryEntry`/`InfinitePosition`/`ReverseModePosition`/`TradingReport`/`BuyPriorityOrdering`/`PrivacyCycleOrderStrategy`/`VrCycleOrderStrategy`/`VrStrategy`/`StrategyCreationResolvers`/`InsufficientBalanceEvent`/`BuyOrderPriceCapper` — import 삭제만.
- **상수만 사용 3개**(`InfiniteCreationResolver`/`PrivacyCreationResolver`/`VrCreationResolver`): `Strategy.DEFAULT_DIVISION_COUNT`.
- **기계적 치환 ~28개**: `.id()`/`.accountId()`/`.type()`/`.ticker()`/`.status()`/`.cycleSeedType()`/`.isActive()`/`.isInfinite()`/`.isPrivacy()`/`.isVr()` 읽기만 — `Strategy`→`StrategyRef` 타입 치환으로 끝남(`TradingService`/`CycleOrderComputer`/`ManualTradingService`/`TradingReporter`/`CyclePositionPersistor`/`VrCycleRolloverService`/`CyclePositionPersistenceAdapter`/`TradingBuyCompetitionSimulator`/`TradingPreviewService`/`OrderCancelService`/`TradingBalanceLoader`/`TradingSellSufficiencySimulator`/`VrReconfigureService`/`TradingOrderExecutor`/`StrategyOrderPlanBuilder`/`SeedResolutionPolicy`/`BatchContext`/`BatchContextFactory`/`TradingOpenScheduler`/`TradingCloseScheduler`/`TradingExecutionFacade`/`TradingExecutionUseCase`/`CycleOrderStrategy`/`CycleOrderStrategies` 등).
- **진짜 재설계 1곳**: `CycleRotationService` — `strategyPort.save(strategy.withStatus(PAUSED))`(사이클 종료 시 시스템 자동 일시정지, 2곳). 읽기 전용 own-type으로 안 끝나고 쓰기 포트 1개 필요.
- **2차 결합**: `StrategyCreationResolver`(인터페이스)+`Infinite`/`Privacy`/`VrCreationResolver` 구현체 3개가 `RegisterStrategyCommand`(strategy-config 소유)를 파라미터로 받음 — `Strategy` 자체와 별개로 동일 모양의 양방향 결합.

`isPaused()`/`withStatus()`/`withCycleSeedType()`는 trading 어디서도 안 쓰여 own-type에서 제외(YAGNI) — 쓰기는 전용 포트로 분리.

## 설계

### 1. 모듈 구조

`com.kista.strategyconfig` 신설, CLOSED. NamedInterface 3개:
- **domain**(`domain.model`): `Strategy`, `StrategyDetail`, `RegisterStrategyCommand`, `UpdateStrategyCommand`, `StrategySeedPreview`
- **usecase**(`application.usecase`): `StrategyUseCase`
- **port**(`application.port.output`): `StrategyPort`

internal(비공개): `application.service`(`StrategyService`), `adapter.out.persistence`(`StrategyEntity`/`StrategyJpaRepository`/`StrategyPersistenceAdapter`/`PersistenceSupport`). event/schedule NamedInterface 없음(notify 직접 참조 없음, 스케쥴러 없음 — admin과 동일 사유).

`AccountCascadeListener`(레거시, `AccountDeletedEvent` 구독해 `StrategyPort.deleteByAccountId` 호출)도 strategy-config `application.service`로 이관 — account는 이벤트만 발행하고 strategy-config를 참조하지 않으므로 기존에도 순환이 아니었고, 물리 위치만 옮긴다.

### 2. trading own-type

```java
// com.kista.trading.domain.model.StrategyRef
public record StrategyRef(UUID id, UUID accountId, StrategyType type, StrategyStatus status,
                           StrategyTicker ticker, StrategyCycleSeedType cycleSeedType) {
    public boolean isActive()   { return status == StrategyStatus.ACTIVE; }
    public boolean isInfinite() { return type == StrategyType.INFINITE; }
    public boolean isPrivacy()  { return type == StrategyType.PRIVACY; }
    public boolean isVr()       { return type == StrategyType.VR; }
}

// com.kista.trading.application.port.output.StrategyLookupPort (trading 정의, strategy-config 구현) — 읽기 전용
public interface StrategyLookupPort {
    List<StrategyRef> findAllActive();
    List<StrategyRef> findByAccountId(UUID accountId);
    Optional<StrategyRef> findById(UUID id);
    StrategyRef findByIdOrThrow(UUID id);
    StrategyTicker findTickerById(UUID id);
    Map<UUID, StrategyTicker> findTickersByIds(Collection<UUID> ids);
}

// com.kista.trading.application.port.output.StrategyPausePort — 단일 책임, 조회 포트와 분리(ISP)
public interface StrategyPausePort {
    void pause(UUID strategyId); // 시스템 자동 일시정지(롤오버 실패) 전용 — StrategyUseCase.pause()의 소유권검증 경로와 무관
}
```

`StrategyLookupPort`/`StrategyPausePort`를 분리하는 이유: 조회(Query)와 명령(Command)을 한 인터페이스에 섞지 않는다 — 이 코드베이스의 기존 narrow own-type 포트(`ApprovalPolicyPort`/`BrokerEnabledPort`)도 전부 단일 메서드 단일 책임이라 관례와 일치한다. `pause()`를 쓰는 소비자는 `CycleRotationService` 하나뿐이므로 나머지 28개 소비자가 불필요한 쓰기 메서드에 노출되지 않는다(ISP).

strategy-config의 `StrategyPort` 구현체(`StrategyPersistenceAdapter`)를 그대로 두고, strategy-config `application.service`에 `StrategyLookupPort`/`StrategyPausePort` 구현체(`StrategyLookupAdapter` 등, internal)를 신설해 내부적으로 `StrategyPort`를 호출 + `Strategy`→`StrategyRef` 매핑한다.

### 3. 연쇄 변경 — trading 이벤트 + notify

trading 공개 이벤트 3종의 `Strategy` 필드를 `StrategyRef`로 교체:
- `NewCycleStartedEvent(UUID userId, UUID accountId, StrategyRef strategy, BigDecimal initialUsdDeposit)`
- `CycleCompletedEvent(UUID userId, UUID accountId, StrategyRef strategy)`
- `CycleEndedEvent(UUID userId, UUID accountId, StrategyRef strategy)`

notify의 `UserNotificationPort`(+구현체 `TelegramUserNotificationAdapter`/`FcmAdapter`/`CompositeUserNotificationAdapter`)가 읽는 필드는 `.type()`/`.ticker()`/`.cycleSeedType()`뿐이라 순수 타입 치환(로직 무변경). `NotifyPort`/`TelegramAdapter`의 `Strategy` import는 죽은 import라 삭제만 한다.

### 4. `StrategyCreationResolver` 시그니처 축소

trading `domain.strategy`의 `StrategyCreationResolver` 인터페이스 + `Infinite`/`Privacy`/`VrCreationResolver` 구현체 3개가 `RegisterStrategyCommand`(strategy-config 소유) 통짜를 받던 것을, 각 구현체가 실제로 읽는 원시값만 받도록 좁힌다(Infinite: divisionCount 요청값, Privacy: 없음, Vr: intervalWeeks/bandWidth/recurringAmount 등). strategy-config의 `StrategyService`가 커맨드에서 값을 꺼내 넘긴다. 이 축소로 trading이 `RegisterStrategyCommand` 자체를 더 이상 참조하지 않는다.

`Strategy.DEFAULT_DIVISION_COUNT`는 `com.kista.sharedkernel`(신규 상수, 또는 기존 sharedkernel 클래스에 추가)로 추출 — `InfiniteCreationResolver`/`PrivacyCreationResolver`/`VrCreationResolver`(trading)와 `RuntimeSettings.defaults()`(admin) 양쪽이 이 상수로 `Strategy` 타입 자체를 더 이상 안 봐도 된다.

### 5. admin↔strategy-config 해소

strategy-config가 own-type 포트 정의:
```java
// com.kista.strategyconfig.application.port.output.StrategyCreationPolicyPort
public interface StrategyCreationPolicyPort {
    Optional<com.kista.trading.domain.strategy.StrategyCreationSettings> find(StrategyType type);
}
```
admin의 `RuntimeSettingsService`가 구현 — `RuntimeSettings.strategies().get(type)`(admin 내부 타입) → trading 소유 `StrategyCreationSettings`로의 매핑 로직(`toTradingSettings`/`mapField`/`mapRecurringField`, 상수명 byte-identical `valueOf` 매핑 그대로)을 `StrategyService`에서 admin으로 옮긴다. admin은 이미 trading에 정상 forward 의존(Order/StrategyCycle 소비)이 있어 반환 타입이 trading 소유여도 문제없다.

결과: strategy-config → admin 참조 0건. `StrategyService`에서 `RuntimeSettingsPort` 필드 + `toTradingSettings`/`mapField`/`mapRecurringField` 3개 메서드 삭제(순감소).

### 6. user↔strategy-config 해소

1. **삭제**: `UserCascadeDeleter.strategyPort.deleteByUserId(userId)` 제거. strategy-config 내부에 `UserDeletedEvent` 구독 리스너 신설(`application.service` internal, `@TransactionalEventListener(AFTER_COMMIT)`) — 자기 데이터는 자기 모듈이 지운다(finance/trading의 기존 cascade 리스너 패턴과 동일). trading의 기존 `UserCascadeListener`에 얹지 않는다(삭제 대상 테이블 소유자가 다름).
2. **카운트**: user가 own-type 포트 정의 —
```java
// com.kista.user.application.port.output.ActiveStrategyCountPort
public interface ActiveStrategyCountPort {
    long countActiveByUserId(UUID userId);
}
```
strategy-config가 구현 — 내부에서 `AccountPort.findByUserId`+`StrategyPort.findByAccountId` 조합(이미 strategy-config→account 정상 forward). `UserSettingsService`에서 `StrategyPort`/`AccountPort` 필드 제거(두 필드 모두 이 카운트 용도로만 쓰였으므로 통째 삭제).

### 7. broker↔strategy-config 해소

`MockBrokerAdapter`가 쓰는 건 `Strategy.id()`+`.ticker()` 2필드뿐(계좌+ticker→전략 해석, 계좌 내 전략 순회). 신규 own-type을 만들지 않고 기존 역전 포트 `MockSimulationDataPort`(broker 정의, trading `MockSimulationDataAdapter` 구현)에 메서드 추가:
```java
// broker 소유 초경량 뷰 — PlacedOrderView/PositionView와 동일 패턴
public record StrategyRefLite(UUID id, StrategyTicker ticker) {}

// MockSimulationDataPort에 추가
List<StrategyRefLite> findStrategiesByAccountId(UUID accountId);
```
`MockBrokerAdapter`에서 `StrategyPort` 필드 완전 제거. trading의 `MockSimulationDataAdapter`가 내부적으로 `StrategyLookupPort.findByAccountId`를 호출해 `StrategyRefLite`로 매핑.

## 마이그레이션 순서 원칙

account 서브프로젝트 Task 4 1차 시도가 "먼저 옮기고 나중에 `verify()`로 확인"하다 Step 10에서 BLOCKED, 커밋 없이 revert된 전례가 있다. 이번엔 반대 순서로 진행한다 — **`Strategy`가 아직 레거시 OPEN인 상태에서 역방향 엣지(admin/user/broker/trading+notify) 4건을 먼저 끊고, 각각 컴파일+관련 테스트로 검증한 뒤, 마지막에 물리 이동 + `@ApplicationModule(CLOSED)` 선언 + `verify()`**. 각 역방향 엣지 해소는 서로 독립적이라 순서 무관하게 개별 커밋 가능.

## 파킹된 minor 6건 (B단계 리뷰 이월, 첫 태스크에 묶음)

1. `VrStrategyDetailUseCase.saveInitialCycleDetail`의 미사용 파라미터 `initialUsdDeposit` 제거
2. `TradingCycleResponse.java` 죽은 import(`com.kista.trading.domain.model.VrSummary`, 6번 줄) + 낡은 주석(78번 줄) 정리
3. `StrategyServiceTest`의 `buildSummary` any() 매처 stub 회귀탐지력 약화 — `VrStrategyLifecycleTest`가 포뮬러 자체는 독립 커버 중이므로 스킵 가능(급하지 않으면 생략)
4. `constraints.md:83` — Strategy 4종 이관 서술이 옛 dotted 표기(`Strategy.Ticker` 등) 사용, sibling 서술(`architecture.md:46`, `constraints.md:47`)은 개명된 이름(`StrategyTicker` 등) 사용 — 통일
5. `constraints.md:47` — "이관 완료(2026-09-03, ...)" 날짜 오기재, 실제 커밋 `4bc7c6f3` 날짜는 **2026-09-02**(git log 확인 완료) — 정정
6. `constraints.md:102-104` — `VrSummary`가 아직 "nested"로 서술됨(B단계에서 top-level `com.kista.trading.domain.model.VrSummary`로 승격됨), `StrategyVersion`/`InfiniteDetail`/`VrDetail`의 trading 소유권 이전도 미반영 — 갱신

## 스코프 아웃

- C 완료 후에도 레거시 OPEN 잔존물: `AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`(+usecase), `MarketUseCase`, `TradingCycleController`+DTO, `Dashboard`/`Statistics`/`Meta` 컨트롤러, `common/`, 레거시 `PersistenceSupport`(strategy 전용은 이관, 공용은 잔류) — "C = 마지막 서브프로젝트"는 맞지만 "C = 레거시 OPEN 패키지 완전 소멸"은 아니다.
- stats/privacy/market 모듈은 strategy-config를 forward로만 소비하고 역방향 참조가 없어 이번 스코프에서 추가 조치 불필요(확인 완료).
- `account`(CLOSED) 모듈은 `Strategy`/`StrategyPort`를 전혀 참조하지 않아(레거시 `com.kista.application.service.account`의 통계 서비스만 참조, 이건 account CLOSED 모듈이 아님) 조치 불필요.

## 테스트/검증

1. 역방향 엣지 4건 각각 좁은 `--tests` 범위로 컴파일+테스트 확인(admin/user/broker/trading 관련 클래스)
2. 물리 이동 후 `./gradlew compileJava compileTestJava`
3. `./gradlew test --tests 'com.kista.architecture.*'` — ArchUnit + `ApplicationModules.verify()`
4. 전체 `./gradlew test` 최종 1회(전역 CLAUDE.md 규칙)
5. 문서 갱신: `architecture.md`/`constraints.md`에 `com.kista.strategyconfig/` 절 신설, trading 절에 `StrategyRef`/`StrategyLookupPort`/`StrategyPausePort` 추가, "Spring Modulith 점진 도입" 서술에 C 완료 반영
