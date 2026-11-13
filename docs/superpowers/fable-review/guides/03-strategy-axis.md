# 지침서 03. 전략 축 재설계 — 전략 종류 하나가 한 곳에 모인다

작성 2026-11-12 (Fable). 근거 측정: `findings/03-parallel-structures.md` A·E절, `findings/04-test-bloat.md` B절, `findings/05-frozen-decisions.md` S-11·C-07·C-08·C-44. 직접 읽은 코드: `CycleOrderStrategy`, `CycleOrderComputer`, `StrategyCreationPersister`, `Strategy`.

> **2026-11-14 갱신**: 코인 DCA 파이프라인은 삭제된다(`15-crypto-removal.md`). 이 문서의 `Crypto*` 언급(`CryptoDcaRunner:58`, "코인 DCA 파이프라인" 문장)은 대상이 없다.

## 1. 현재 구조와 문제

전략 종류는 4개(INFINITE·VR·PRIVACY·DCA)다. 종류별로 다른 것은 주문 계산만이 아닌데, 다형성은 주문 계산과 등록 정책 두 곳에만 있다.

- **타입 분기 약 63곳.** `strategy.isX()`·`switch (type)`가 등록 저장, 계획 입력 조립, 상세 조립, 갱신, 원장, 가드, 백테스트에 흩어져 있다. 많은 순서: `BacktestEngine`(13), `StrategyDetailAssembler`(12, 단건·배치 두 벌), `BacktestService`(7), `StrategyCreationPersister`(switch 2개 7케이스), `CycleOrderComputer`(4).
- **`PlanContext`가 4종 입력을 nullable 필드로 전부 나른다.** `CycleOrderStrategy.java`의 `PlanContext(balance, ticker, tradeDate, accountId, infinite, privacy, vr, dca)`. `CycleOrderComputer.compute()`는 타입과 무관하게 INFINITE·PRIVACY 묶음을 항상 만들고(`:44-56`), VR만 조건부로 만든다. 구현체는 자기 묶음만 읽는다. 태그드 유니온을 null로 흉내 낸 것이다.
- **`OrderPlan`도 같다.** `OrderPlan(position, vrPosition, orders, skips, referencePrices, reverseMode)`에서 `position`은 INFINITE만, `vrPosition`은 VR만, `skips`·`referencePrices`는 DCA만 채운다. 이 nullable 쌍이 `capBuyOrders(buyOrders, cap, position, vrPosition, ticker, tradeDate)`·`needsCapCheck(position, vrPosition)`·`TradingOrderExecutor.placeOrders(..., position, vrPosition, strategy)`까지 그대로 전파된다.
- **인터페이스에 상수 플래그가 쌓인다.** `CycleOrderStrategy`의 default 메서드 중 `requiresPrevClose`·`endsCycleOnLiquidation`·`tracksReverseMode`·`requiresRolloverCheck`·`capsIndividualOrders`·`fixesPricesAtPlan`·`recordsRounds`는 입력 없이 타입마다 고정값을 돌려준다. 같은 성격의 값이 이미 `StrategyType.capability()`(`StrategyCapability`, shared.kernel)에 있고 인터페이스 주석도 "SSOT는 capability"라고 적는다. 고정값의 집이 두 곳이다.
- **타입 전용 포트 5종이 공통 서비스에 전부 주입된다.** `StrategyInfiniteDetailPort`, `StrategyVrDetailPort`, `StrategyDcaDetailPort`, `CyclePositionInfiniteDetailPort`, `StrategyCycleVrPort`. 의존 8개 이상 서비스 19개가 타입 분기 파일 목록과 대부분 겹치는 이유다(`findings/03` D-3).
- **백테스트가 같은 조립을 다시 쓴다.** `BacktestEngine`(582줄)의 `runVr`·`runInfinite`·`runPrivacy`와 `plan*Orders` 3벌이 `CycleOrderComputer`의 입력 조립과 같은 일을 한다. 주문 계산 자체는 커널을 재사용하므로 중복은 "입력 구성"과 "일일 상태"다.
- **등록 resolver 3개가 20줄 동형이다.** `Infinite`·`Privacy`·`Dca` `CreationResolver`(유사도 0.87).

결과: 전략 종류를 하나 추가하거나 한 종류의 입력을 바꾸면 손댈 파일이 패키지 6~7곳에 걸친다. 컴파일러가 누락을 잡아 주는 곳은 `StrategyCreationPersister`의 default 없는 switch 하나뿐이다.

## 2. 목표 구조

**원칙: 종류별로 다른 것은 그 종류의 패키지 한 곳에 두고, 공통 서비스는 종류를 묻지 않는다.** 타입을 묻는 코드는 "이 엔드포인트는 VR 전용" 같은 진입 가드만 남긴다.

### 2-1. 커널(matching): 입력과 상태를 sealed 타입으로

```java
// 전략별 계획 입력 — 구현체는 자기 타입만 받는다
public sealed interface PlanInput permits InfiniteInput, VrInput, PrivacyInput, DcaInput {}

// 계획 결과에 딸린 전략별 상태 — 캡 재산정·화면 표시가 쓴다
public sealed interface PlanState permits InfinitePosition, VrPosition, PlanState.None {}

public interface CycleOrderStrategy<I extends PlanInput> {
    StrategyType cycleType();
    Optional<OrderPlan> plan(PlanCommon common, I input);          // common = balance, ticker, tradeDate, accountId
    BigDecimal minRequiredDeposit(BigDecimal price, I input);       // 지금은 (price, privacyPlan, divisionCount)
    List<PlannedOrder> capBuyOrders(List<PlannedOrder> buys, BigDecimal cap, PlanState state, StrategyTicker ticker, LocalDate tradeDate);
    List<PlannedOrder> fitBuysToBudget(List<PlannedOrder> buys, BigDecimal budget);
    boolean canSkipOrderComputation(List<PlannedOrder> existing, Set<OrderTiming> creatableTimings);
}
```

- `PlanContext`의 중첩 record 4개(`InfiniteInputs` 등)는 필드 그대로 `InfiniteInput` 등으로 올린다. 공식 코드(`InfiniteStrategy`·`VrStrategy`·`PrivacyStrategy` 본문)는 **한 줄도 바꾸지 않는다.** 바뀌는 것은 어댑터(`*CycleOrderStrategy`)의 입력 꺼내는 줄뿐이다.
- `OrderPlan(PlanState state, List<PlannedOrder> orders, List<DcaRuleDecision.Skipped> skips, Map<String, BigDecimal> referencePrices)`. `reverseMode` 불리언은 `InfinitePosition` 쪽 표시 여부로 옮긴다(`displayPosition()`의 의미 유지). `needsCapCheck(position, vrPosition)`는 `state instanceof PlanState.None`이면 건너뛰는 것으로 대체하되, 구현체가 추가 조건을 갖고 있으면 `capBuyOrders` 안으로 옮긴다(입력을 그대로 돌려주면 "변경 없음"이라는 기존 계약이 이미 있다).
- 고정값 플래그 7개는 `StrategyCapability` 필드로 옮기고 인터페이스에서 지운다. 호출부는 `strategy.type().capability().recordsRounds()`처럼 읽는다. `Strategy`에 위임 메서드를 늘리지 않는다.

### 2-2. trading: 종류별 핸들러 하나

```java
// 공통 서비스가 종류를 묻지 않도록 종류별 차이를 모은 진입점 — 구현은 종류 패키지에 하나씩
interface StrategyKind {
    StrategyType type();
    ResolvedCreation resolveCreation(RegisterCommand cmd, StrategyCreationSettings settings); // 지금의 *CreationResolver
    void saveCreationDetail(CreationSaved saved, Draft draft);     // 지금의 Persister switch 2개
    PlanInput loadPlanInput(PlanRequest request);                   // 지금의 CycleOrderComputer 분기
    Map<UUID, KindDetail> loadDetails(List<StrategyCycleRef> refs); // 지금의 Assembler 분기(배치 한 벌)
    void afterPositionSaved(PositionSaved saved);                   // VR 롤오버 판정, INFINITE 리버스모드 기록 — 없으면 빈 구현
}
```

- 레지스트리는 기존 패턴(`Map<StrategyType, X>`, 없으면 ISE)을 그대로 쓴다. `StrategyCreationResolvers`는 이 레지스트리에 흡수돼 사라진다. `CycleOrderStrategies`(커널)는 남는다.
- `PlanRequest(strategy, cycle, prevClosePrice, currentPrice, privacyBase, dcaInputs)`. `compute()`의 9인자(같은 타입 인접, nullable 3개)를 묶는다.
- `KindDetail`은 sealed(`InfiniteDetail`, `VrDetail`, `DcaDetail`, `None`). 응답 DTO는 이 값을 패턴 매칭해 필드를 채운다. `TradingCycleResponse.java:125`의 분기가 그 한 곳이다.
- 상세 조립은 **배치 한 벌**만 둔다. 단건은 `loadDetails(List.of(ref))`다. N+1 회피가 목적이던 배치 버전이 단건에서 느려질 이유가 없다.
- 타입 전용 포트 5종은 핸들러 안으로만 주입된다. 공통 서비스(`StrategyService`, `StrategyCreationService`, `StrategyUpdateWriter`, `CapitalLedgerService`, `CycleOrderComputer`, `StrategyDetailAssembler`, `StrategyCreationPersister`)의 생성자에서 빠진다. 저장 형태(Store 통합)는 지침서 02의 2-3절이 정한다. 이 지침서는 도메인 모양만 정한다: 버전 단위 설정(`InfiniteConfig(divisionCount)`, `VrConfig`, `DcaStrategyConfig`), 사이클 단위 상태(`VrCycleState(value, poolLimitRate)`), 포지션 단위 표시(INFINITE `reverseMode`). **테이블은 그대로 둔다.**

### 2-3. 패키지: 종류별로 모은다

```
trading/application/service/
  kind/StrategyKind.java, StrategyKinds.java(레지스트리)
  kind/infinite/   InfiniteKind, InfiniteMinSeedGuard
  kind/vr/         VrKind, VrReconfigureService, VrCycleRolloverService, VrStrategyDetailService, VrRamp*
  kind/privacy/    PrivacyKind, PrivacyBaseGuard
  kind/dca/        DcaKind, DcaConfigService, DcaExecutionQueryService, DcaExecutionRecorder, DcaPlanInputCollector, DcaSummaryAssembler
```

`domain/creation`의 종류별 파일도 같은 기준으로 옮긴다. 코인 DCA 실행 파이프라인(`Crypto*`)은 지침서 06이 다룬다.

### 2-4. 남는 타입 질의와 가드

- 진입 가드(`VrReconfigureService:68`, `DcaConfigService:40`, `ManualTradingService:62` 등)는 `strategy.requireType(StrategyType.VR)` 한 메서드로 통일한다. 메시지 문구는 지금 것을 유지한다.
- `Strategy.isInfinite()`·`isVr()`·`isPrivacy()`·`isDca()`는 지운다. 필터가 필요한 곳(`CryptoDcaRunner:58`)은 `s.type() == StrategyType.DCA`로 쓴다.
- ArchUnit 가드 신설 `strategy_type_branching_only_in_kind_packages`: `..kind..` 밖에서 `StrategyType`의 개별 상수(`INFINITE` 등)를 참조하는 클래스는 허용 목록(가드·필터·DTO 매핑, 파일명 명시)에 있어야 한다. 분기의 재발을 막는다.

### 2-5. 백테스트

`BacktestEngine`의 `runVr`·`runInfinite`·`runPrivacy`를 `runDays(BacktestKind kind, ...)` 하나로 합친다. `BacktestKind`(stats 모듈, 종류별 3구현)는 두 가지만 한다: 시뮬레이션 상태에서 `PlanInput` 만들기, 체결 결과를 상태에 반영하기(롤오버 포함). 운영과 백테스트가 **같은 `PlanInput` 타입**을 쓰므로 조립 실수가 컴파일에서 걸린다. DCA 미지원(`BacktestEngine.java:67` 예외)은 그대로 둔다.

체결 규칙 두 벌(`FillSimulator` ↔ `MockBrokerAdapter:127,157`)은 규칙을 `matching` 커널로 내리고 양쪽이 호출한다. broker가 matching에 의존할 수 있는지는 지침서 05 단계 R의 `allowedDependencies`에서 확인한다. 허용되지 않으면 두 벌을 유지하고 양쪽에 같은 표 기반 테스트를 둔다.

### 이렇게 정한 이유

- 코드 철학의 1순위가 비일관성이다. 같은 문제(종류별 차이)를 주문 계산은 다형성으로, 나머지는 분기로 풀고 있다.
- 줄 수 감량은 크지 않다(4절). 이 주제의 값은 "종류 하나를 고칠 때 여는 패키지 수"와 "컴파일러가 누락을 잡는 범위"다.
- 핸들러 메서드가 6개인 것은 실제로 종류별 차이가 6가지 역할에 있기 때문이다. 역할마다 인터페이스를 따로 두면(상세 포트·가드·조립기 각각) 지금의 "작은 클래스 × 종류 수" 곱셈이 다시 생긴다.

### 기각한 대안

- **`CycleOrderStrategy`에 메서드를 더 얹는다**: 커널(matching)은 순수 계산이어야 한다. DB 조회·저장이 들어갈 수 없다.
- **종류별 상세를 JSONB 한 컬럼으로**: DCA 규칙·실행 이력은 조회 조건으로 쓰이고, trading 스키마 마이그레이션(05의 expand/contract)이 따라온다. 얻는 것은 Entity 몇 개다.
- **JPA 상속 매핑(JOINED)**: 엔티티 계층이 도메인 모양을 끌고 간다. 버전·사이클·포지션 세 단계에 걸친 상세를 한 계층으로 표현할 수 없다.
- **`Strategy`를 sealed 서브타입으로**: `Strategy`는 6필드 record이고 종류별 데이터는 버전·사이클에 붙어 있다. 서브타입에 담을 것이 없다.
- **DCA를 별도 애그리게이트·모듈로 분리**: 주식 DCA는 같은 배치·같은 원장 상한·같은 주문 테이블을 쓴다. 분리하면 공통부를 복제하게 된다.

## 3. 재심한 과거 결정

| 결정 | 출처 | 재심 결과 |
|---|---|---|
| S-11 `CycleOrderStrategy` 이중 계층(순수 계산기 + 어댑터) 유지 | memory `project_2026_07_11_full_review.md` | **유지.** 계산기는 공식 SSOT(지침서 08)이고 어댑터는 입력 변환·캡·예산 축소를 맡는다. 합치면 공식 파일에 운영 관심사가 섞인다. DCA만 계산기가 어댑터 안에 있는 것은 그대로 둔다(규칙 판정이 곧 계산이다) |
| C-44 `CycleOrderComputer.compute()` 파라미터 객체화 기각("PlanContext가 이미 캡슐화") | 같은 memory | **뒤집는다.** 9인자에 nullable 3개, 같은 타입 인접이다. `constraints.md`의 파라미터 규칙에도 어긋난다 |
| C-08 `StrategyDetailAssembler` 단건·배치 두 벌 유지 | backlog L601 | **뒤집는다.** 배치 한 벌 |
| C-07 타입 가드·필터·표시의 `isX()` 유지 | backlog L600 | **부분 유지.** 가드·필터는 남기되 `requireType`·`type() ==`로 표기를 통일하고 허용 목록으로 고정 |
| CQ-03 "동작 분기는 capability로" | backlog | **유지·확장.** 고정값 플래그를 전부 capability로 모은다 |

## 4. 예상 감량과 위험

- main: 입력 조립·OrderPlan 전파 약 150줄, Assembler 단건 버전 약 60줄, 백테스트 3벌 약 200줄, resolver·레지스트리 약 70줄, 플래그 default 약 30줄. 핸들러 선언으로 약 150줄이 는다. **순감 약 350~500줄(추정)**. 타입 분기 63곳 → 허용 목록 약 15곳.
- test: `StrategyServiceTest`의 `register_*` 약 80개가 핸들러·resolver 단위 테스트로 내려갈 자리가 생긴다(지침서 07 T2). 이 지침서에서는 옮기지 않는다.
- 위험 1: 커널 시그니처 변경이 공식 결과를 바꾸는 것. 공식 본문을 건드리지 않는다는 규칙과 5절의 스냅샷 테스트로 막는다.
- 위험 2: `OrderPlan`의 nullable 의미가 호출부마다 다르게 쓰이고 있을 수 있다(예: preview의 INSUFFICIENT_BALANCE에서도 position 보존). `PlanState`로 바꿀 때 "null이면 건너뜀" 분기를 전부 찾아 `None` 처리로 옮긴다(`grep -n "position() == null\|vrPosition() == null\|position != null\|vrPosition != null"`).
- 위험 3: `afterPositionSaved`로 옮기는 VR 롤오버·리버스모드 기록은 트랜잭션 경계 안에서 순서가 의미를 갖는다. 호출 위치와 트랜잭션을 그대로 두고 호출 대상만 바꾼다.
- 동작 변경은 없다.

## 5. 동작 보존용 선행 테스트

1. **계획 스냅샷**: 종류별 대표 입력(INFINITE 일반·리버스 첫날·리버스 2일차·0회차, VR 일반·bootstrap, PRIVACY 기준표 있음·없음, DCA 규칙 due·skip)으로 `CycleOrderComputer.compute` 결과의 주문 목록(leg·방향·가격·수량·timing)을 고정한다. 기존 `CycleOrderComputerTest`(395줄)에 빠진 조합만 추가한다.
2. **백테스트 골든**: `BacktestEngineTest`(1,162줄)와 골든 파일이 이미 있다. T7 전후로 결과가 바이트 단위로 같아야 한다.
3. **등록 저장**: `StrategyCreationPersisterDbTest`가 종류별로 어떤 상세 행이 생기는지 단언하는지 확인하고, 빠진 종류를 추가한다.
4. 각 테스트는 변이 확인을 한다(입력 조립 한 줄을 일부러 틀리게 해 실패하는지).

## 6. 태스크

공통 완료 판정: `./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"` 성공, `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.*' --tests 'com.kista.tradingcore.trading.*' --tests 'com.kista.tradingcore.stats.*' 2>&1 | grep -E "FAILED|BUILD"` 통과, `./gradlew :test --tests 'com.kista.api.architecture.*' 2>&1 | grep -E "FAILED|BUILD"` 통과. 전체 스위트는 마지막에 1회.

| # | 내용 | 대상 | 모델 |
|---|---|---|---|
| T1 | 5절의 선행 테스트 보강과 변이 확인 | `CycleOrderComputerTest`, `StrategyCreationPersisterDbTest` | Opus |
| T2 | 고정값 플래그 7개를 `StrategyCapability`로 옮기고 인터페이스에서 삭제. 호출부 치환 | `StrategyCapability`, `CycleOrderStrategy`, 4구현, 호출부 | Sonnet |
| T3 | `PlanInput`·`PlanState` 도입, `PlanContext`·`OrderPlan` 교체, `capBuyOrders` 등 시그니처 정리. 공식 본문 변경 금지 | matching `domain/strategy`, `CycleOrderComputer`, `BuyOrderPriceCapper`, `TradingOrderExecutor`, `TradingCandidatePlanner`, `TradingPreviewService`, `BacktestEngine` | Opus |
| T4 | `StrategyKind` 도입과 `loadPlanInput` 이전. `CycleOrderComputer`는 "핸들러에서 입력 받기 → 커널 호출"만 남긴다 | `kind/**` 신설, `CycleOrderComputer` | Opus 설계, Sonnet 적용 |
| T5 | `resolveCreation`·`saveCreationDetail` 이전. resolver 3개 동형은 `StrategyKind`의 default 구현 하나로. `StrategyCreationResolvers` 삭제 | `domain/creation`, `StrategyCreationPersister`, `StrategyCreationService` | Sonnet |
| T6 | `loadDetails` 이전, Assembler 단건 버전 삭제, DTO 매핑을 `KindDetail` 패턴 매칭으로. 지침서 02 T5와 같은 파일이라 **한 세션에서 함께** 한다 | `StrategyDetailAssembler`, `TradingCycleResponse`, `DcaSummaryAssembler`, `VrSummary` | Sonnet |
| T7 | 백테스트 `runDays` 통합, `BacktestKind` 3구현. 골든 일치 확인 | `stats/domain/backtest` | Opus |
| T8 | 갱신·원장·롤오버의 남은 분기를 핸들러로(`StrategyUpdateWriter:58,61`, `CapitalLedgerService:78,183`, `StrategyService:177,237`). 분기마다 "종류별 동작"인지 "가드"인지 판정해 보고에 표로 남긴다 | 해당 서비스 | Opus 판정, Sonnet 적용 |
| T9 | 패키지 이동(2-3), `requireType` 통일, `isX()` 삭제, ArchUnit 가드 신설 | trading 전반 | Sonnet |
| T10 | 문서: `modules/tradingcore/trading.md`·`matching.md`의 전략 서술, `flows.yml`의 `code:` 참조. 지침서 09의 모듈 문서 축소와 같이 한다 | docs | Sonnet |

커밋은 태스크 단위. T3과 T7은 단독 커밋으로 두어 되돌리기 쉽게 한다.

## 7. 하지 말 것

- `InfiniteStrategy`·`ReverseInfiniteStrategy`·`VrStrategy`·`PrivacyStrategy`·`DcaConditions`의 계산 본문을 고치지 않는다. 공식 변경은 지침서 08만 한다.
- 테이블·컬럼을 바꾸지 않는다.
- 핸들러에 "나중에 쓸" 메서드를 미리 넣지 않는다. 2-2의 6개에서 시작하고 T8에서 실제 분기가 요구할 때만 늘린다.
- 종류가 하나뿐인 동작에 대해 나머지 3종의 빈 구현을 강제하지 않는다. `StrategyKind`의 default 빈 구현을 쓴다.
- 코인 DCA 파이프라인을 여기서 합치지 않는다(지침서 06).
- T3과 지침서 08의 공식 수정(S2)을 같은 시기에 하지 않는다. 같은 파일이다.

## 8. 다른 지침서와의 관계

- 08(공식): S2 묶음이 **먼저** 끝나야 한다. 같은 커널 파일이고 08은 동작 변경, 03은 구조 변경이다.
- 01(헥사고날): 단계 B(UseCase 제거)가 trading까지 끝난 뒤가 편하다. 필수는 아니다.
- 02(영속·DTO): T6은 02 T5와 한 세션. 종류별 상세의 Store 통합(02 T6)은 이 지침서의 T4·T5 뒤에 한다(포트 주입처가 핸들러로 모인 뒤라 치환 범위가 작다).
- 04(브로커): KIS 삭제(W1)로 `supportsOpenAuctionOrders` 분기가 사라진다. 순서 무관.
- 05(통합): 단계 R의 패키지 루트 rename과 T9의 패키지 이동이 겹친다. T9를 R보다 먼저 하거나 R의 ⑦과 한 번에 한다.
- 06(배치): `afterPositionSaved`와 DCA 핸들러를 06이 쓴다. 03이 먼저다.
- 07(테스트): `StrategyServiceTest` 분해는 03 뒤.
