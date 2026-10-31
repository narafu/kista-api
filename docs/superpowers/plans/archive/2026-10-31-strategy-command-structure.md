# 전략 커맨드·등록 경로 구조 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** trading-core trading 모듈의 VR 램프 입력 중복·`StrategyCreationService.register` 비대·`StrategyService` 조립 코드·등록 응답용 계좌 재조회를 동작 변경 없이 정리한다.

**Architecture:** nullable 램프 입력 record `VrRampInput`을 `trading.domain.creation`으로 승격해 등록·재설정·백테스트 커맨드가 공유한다. VR 등록 검증은 포트 없는 순수 도메인 헬퍼 `VrRegistration`으로, 시작금액 산출은 `StrategyStartAmountCalculator` 빈으로 뺀다. `StrategyService`의 조립 코드는 `StrategyDetailAssembler` 빈으로 옮기고, `StrategyCreationService.register`가 이미 조회한 계좌의 market으로 직접 `StrategyDetail`을 반환한다.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + Mockito, Gradle(`:trading-core`)

**Spec:** 별도 spec 문서 없음 — 2026-10-31 세션 대화에서 합의한 설계(아래 Global Constraints·각 Task가 전부). 원 요청: 직전 리팩토링 fd9c54c27 후속 4건.

## Global Constraints

- 순수 구조 리팩토링 — 매매 공식(`docs/agents/modules/tradingcore/trading-formulas.md`)·검증 조건·예외 문구·검증 순서·응답 필드와 값 변경 금지.
- HTTP 요청 DTO(`TradingCycleRequest`, `VrConfigRequest`)와 백테스트 `@RequestParam`의 JSON/파라미터 구조는 kista-ui 계약 — 변경 금지. `toCommand()` 매핑만 바꾼다.
- 신규 클래스는 package-private 우선, `//` 인라인 주석만(Javadoc 금지), 4-space, record 선호(CLAUDE.md 코드 철학·constraints.md 주석 규칙).
- 테스트 실행은 좁혀서: `./gradlew :trading-core:test --tests '<클래스>' 2>&1 | grep -E "FAILED|BUILD|Tests? run|tests completed"`. 전체 스위트는 Task 3 마지막에 1회.
- 커밋 author `narafu <narafu@kakao.com>`, 한글 Conventional Commit. 커밋은 Task 3 끝에 한 번(리뷰 배치 규칙) — Task 1·2는 커밋하지 않는다.

## Review Focus

1. 비VR 등록(INFINITE/PRIVACY/DCA)에서 `vrRamp`가 null로 와도 NPE 없이 이전과 같이 동작 — 커맨드 compact constructor가 null을 `VrRampInput.NONE`으로 정규화하는지 (Task 1 테스트 `registerCommand_nullRamp_normalizedToNone`).
2. 재설정에서 일부 램프 필드만 보낼 때 나머지는 현재 활성 버전 값 상속(기본값 표가 아님) — `inheritFrom` 필드별 검증 (Task 1 `inheritFrom_*`).
3. VR 등록 검증 순서 보존: intervalWeeks → 인출식 최소자산 → 램프 검증. 여러 값이 동시에 틀릴 때 같은 예외 문구가 먼저 나와야 한다 — 기존 `StrategyServiceTest` VR 등록 테스트가 그대로 통과해야 함 (Task 2).
4. 등록 응답이 조회 경로와 동일 값 + 계좌 조회 1회 (Task 3 `register_requiresOwnedAccountOnce`).
5. `ArchitectureMapTest`의 `code:` 참조 — 옮긴 메서드 이름이 flows.yml에 남아 있으면 실패 (Task 3 문서 단계).

---

### Task 1: `VrRampInput` 승격 + 커맨드 3종 통일

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/creation/VrRampInput.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/creation/VrRampParams.java` (static `withDefaults` 9인자 삭제 — 본문을 `VrRampInput.withDefaults`로 이동, 기본값 상수·주석 그대로)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/RegisterStrategyCommand.java` (램프 8필드 → `VrRampInput vrRamp` 1필드, 19/20인자 호환 생성자 삭제)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/ReconfigureVrCommand.java` (램프 8필드 → `VrRampInput vrRamp`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/backtest/BacktestCommand.java` (중첩 `VrRampInput` 삭제, 새 타입 import, `vrRamp()`는 `vrRampInput.withDefaults(vrRecurringAmount)`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/BacktestController.java:63` (`new VrRampInput(...)`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/TradingCycleRequest.java:62` · `VrConfigRequest.java:44` (`toCommand`에서 평평한 8필드를 `new VrRampInput(...)`로 묶기)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyCreationService.java:243` (`normalizeVrRampParams` → `cmd.vrRamp().withDefaults(normalizedRecurringAmount)`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/VrReconfigureService.java:86-98` (램프 8개 삼항 + `new VrRampParams` → `cmd.vrRamp().inheritFrom(currentDetail)`; bandWidth/intervalWeeks/recurringAmount 상속 3줄은 유지)
- Test: Create `trading-core/src/test/java/com/kista/tradingcore/trading/domain/creation/VrRampInputTest.java`
- Test: Modify `StrategyServiceTest`(40곳)·`VrReconfigureServiceTest`(17곳)·`BacktestServiceTest`·`BacktestEngineTest`·`VrPositionTest` 생성자 호출 (기계적)

**Interfaces:**
- Produces:
  - `public record VrRampInput(Integer initialGradient, Integer gGraceWeeks, Integer gStepWeeks, Integer gMax, BigDecimal initialPoolLimitRate, Integer pGraceWeeks, Integer pStepWeeks, BigDecimal poolLimitFloor)`
  - `public static final VrRampInput NONE` — 전부 null
  - `public VrRampParams withDefaults(int recurringAmount)` — 기존 `VrRampParams.withDefaults` 표와 동일
  - `public VrRampParams inheritFrom(StrategyVrDetail current)` — null 필드는 `current`의 같은 필드(`com.kista.tradingcore.matching.domain.model.StrategyVrDetail`)
  - `RegisterStrategyCommand(StrategyType type, StrategyTicker ticker, BigDecimal initialUsdDeposit, StrategyCycleSeedType cycleSeedType, int divisionCount, BigDecimal initialHoldings, BigDecimal initialAvgPrice, Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount, VrRampInput vrRamp, LocalDate scheduledStartDate, BigDecimal initialVrValue, DcaStrategyConfig dca)` — canonical 단일 생성자, compact constructor에서 `vrRamp == null`이면 `VrRampInput.NONE`
  - `ReconfigureVrCommand(BigDecimal bandWidth, Integer intervalWeeks, Integer recurringAmount, VrRampInput vrRamp, Integer injectShares, BigDecimal injectSharePrice, BigDecimal injectDeposit, Integer withdrawShares, BigDecimal withdrawDeposit)` — 같은 null 정규화
  - `BacktestCommand`의 `vrRampInput` 필드명·위치·호환 생성자는 유지, 타입만 새 `VrRampInput`, compact constructor로 null → `NONE`

- [ ] **Step 1: `VrRampInputTest` 작성**
  - `withDefaults_accumulate`: `VrRampInput.NONE.withDefaults(100)` == `new VrRampParams(10, 52, 26, 20, BigDecimal.ONE, 52, 26, new BigDecimal("0.5"))`
  - `withDefaults_hold`: `withDefaults(0)` → gradient 10, gMax 20, rate `0.75`, floor `0.5`
  - `withDefaults_withdraw`: `withDefaults(-100)` → gradient 40, gMax 50, rate `0.1`, floor `0.1`
  - `withDefaults_explicitValuesWin`: 8필드 전부 지정 시 그대로
  - `inheritFrom_nullFieldsTakeCurrent`: `NONE.inheritFrom(detail)`가 detail의 램프 8필드와 같은 `VrRampParams`
  - `inheritFrom_explicitFieldOverrides`: `initialGradient=15`만 지정 → gradient 15, 나머지 7개는 detail 값
  - `registerCommand_nullRamp_normalizedToNone`: `vrRamp=null`로 만든 `RegisterStrategyCommand.vrRamp()`가 `VrRampInput.NONE`과 equals
  - BigDecimal 비교는 `isEqualByComparingTo`
- [ ] **Step 2: 실행해 컴파일 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.creation.VrRampInputTest'` → 컴파일 오류(`VrRampInput` 없음)
- [ ] **Step 3: 위 Files 전부 구현** — 커맨드 필드 주석은 기존 램프 필드 주석을 `VrRampInput` 필드로 이동. 테스트 호출처는 램프 8인자를 `new VrRampInput(...)`(전부 null이면 `VrRampInput.NONE`)로, 호환 생성자로 생략하던 꼬리 인자는 `null`로 채운다.
- [ ] **Step 4: 실행** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.creation.VrRampInputTest' --tests '*StrategyServiceTest' --tests '*VrReconfigureServiceTest' --tests '*BacktestServiceTest' --tests '*BacktestEngineTest' --tests '*BacktestControllerTest' --tests '*TradingCycleControllerTest' --tests '*VrPositionTest'` → BUILD SUCCESSFUL (존재하지 않는 패턴이 있으면 해당 `--tests` 제거)

---

### Task 2: `register` 분리 — `VrRegistration` + `StrategyStartAmountCalculator`

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/creation/VrRegistration.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyStartAmountCalculator.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyCreationService.java` (`resolveVrValue`/`validateVrCommand`/`normalizeMoney`/램프 정규화/`privacyBootstrapStartAmount` 제거, `PrivacyTradePort` 필드 제거)
- Test: Modify `StrategyServiceTest.setUp` (`new StrategyStartAmountCalculator(privacyTradePort)` 주입)
- Test: Create `trading-core/src/test/java/com/kista/tradingcore/trading/domain/creation/VrRegistrationTest.java`

**Interfaces:**
- Consumes: Task 1 `RegisterStrategyCommand.vrRamp()`, `VrRampInput.withDefaults(int)`
- Produces:
  - `public record VrRegistration(BigDecimal vrValue, VrRampParams ramp)` + `public static VrRegistration resolve(RegisterStrategyCommand cmd, ResolvedCreation resolved, BigDecimal evaluatedStockValue)` — 기존 `register()` 107~115행 블록 + `resolveVrValue` + `validateVrCommand`의 로직·순서·문구 그대로(private static `resolveVrValue`, `validate`로 둔다). 비VR 호출 금지(호출측이 `cmd.type() == VR`일 때만).
  - `@Component class StrategyStartAmountCalculator` (package-private, 의존 `PrivacyTradePort`): `BigDecimal calculate(StrategyType type, BigDecimal deposit, BigDecimal initialHoldings, BigDecimal initialStockValue)` — PRIVACY && holdings>0이면 기존 `privacyBootstrapStartAmount`(private로 이동, 로그·문구 그대로), 아니면 `deposit + initialStockValue`. `deposit`은 호출측이 null→0 처리한 값.

- [ ] **Step 1: `VrRegistrationTest` 작성** (이동 대상 로직의 경계 고정)
  - `resolve_explicitVrValueOverridesEvaluated`: `initialVrValue=5000`, 평가금 3000 → `vrValue` 5000
  - `resolve_zeroVrValueFallsBackToEvaluated`: `initialVrValue=0` → 평가금
  - `resolve_negativeVrValue_throws`: 문구 `"VR 전략의 초기 V값(initialVrValue)은 0 이상이어야 합니다"`
  - `resolve_withdrawalUsesEvaluatedNotOverride`: recurringAmount=-100, intervalWeeks=4, 평가금 0·예수금 0·override 1,000,000 → `"인출식 VR 전략의 초기 자산은 10000.00 이상이어야 합니다"` (100×100×4÷4)
  - `resolve_intervalWeeksCheckedFirst`: intervalWeeks=0 + 램프 무효 동시 → intervalWeeks 문구
- [ ] **Step 2: 실행해 컴파일 실패 확인** — `--tests 'com.kista.tradingcore.trading.domain.creation.VrRegistrationTest'`
- [ ] **Step 3: 구현** — `register()`는 `VrRegistration vr = cmd.type() == VR ? VrRegistration.resolve(cmd, resolved, initialStockValue) : null;` 후 `Draft`에 `vr.ramp()`/`vr.vrValue()`(비VR은 null) 전달. 시작금액 줄은 `startAmountCalculator.calculate(...)`. 주석은 옮긴 곳에 그대로.
- [ ] **Step 4: 실행** — `--tests 'com.kista.tradingcore.trading.domain.creation.*' --tests '*StrategyServiceTest'` → BUILD SUCCESSFUL

---

### Task 3: `StrategyDetailAssembler` 추출 + 등록 재조회 제거 + 문서

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyDetailAssembler.java`
- Modify: `StrategyService.java` (`toDetail`/`toDetails`/`assemble`/`buildDcaSummary` 제거 → assembler 위임, 조립 전용 필드 제거, `register`는 `creationService.register(...)` 반환값 그대로)
- Modify: `StrategyCreationService.java` (`register` 반환 `StrategyDetail`, 끝에서 `detailAssembler.toDetail(strategy, account.broker().getMarket())`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/creation/VrRampValidator.java` 주석(4번 판단 근거 한 줄)
- Test: Modify `StrategyServiceTest.setUp` (assembler를 같은 mock들로 생성해 두 서비스에 주입)
- Docs: `docs/agents/modules/tradingcore/trading.md` 6·53·66행, `trading-formulas.md` 71·89행(클래스·메서드 이름만), `stats.md` 5행, `src/test/resources/architecture/flows.yml` 511~513·529·1432·1705행 `code:` 참조

**Interfaces:**
- Consumes: Task 2의 `StrategyCreationService` 생성자
- Produces:
  - `@Component class StrategyDetailAssembler` (package-private, `@Transactional` 없음 — 호출측 트랜잭션을 그대로 탄다; 등록 경로는 지금처럼 NOT_SUPPORTED): `StrategyDetail toDetail(Strategy strategy, Market market)`, `List<StrategyDetail> toDetails(List<Strategy> strategies, Map<UUID, Market> marketByAccountId)` — 본문은 `StrategyService`에서 그대로 이동
  - `StrategyCreationService`: `StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd)`

- [ ] **Step 1: 실패 테스트** — `StrategyServiceTest`에 `register_requiresOwnedAccountOnce`: 기존 정상 INFINITE 등록 시나리오 재사용 후 `verify(accountPort, times(1)).requireOwnedAccount(accountId, userId)` + 반환 `StrategyDetail`이 같은 stub으로 `getById`한 결과와 equals
- [ ] **Step 2: 실행해 실패 확인** — `--tests '*StrategyServiceTest.register_requiresOwnedAccountOnce'` → `times(1)` 위반(현재 2회)
- [ ] **Step 3: 구현** — 위 Files. `StrategyService`에 남는 필드는 실제 사용처가 있는 것만.
- [ ] **Step 4: 문서 갱신**
  - trading.md 53: "`StrategyCreationService`→`StrategyService` 역주입은 순환이라…" 문장을 "조립은 `StrategyDetailAssembler` 하나 — 조회(`StrategyService`)·등록(`StrategyCreationService`, 이미 조회한 계좌 market 사용) 공용"으로 교체. 6·66행의 `toDetail`/`assemble` 공유 서술도 assembler 기준으로.
  - trading.md에 4번 판단 추가: "`VrRampValidator.validateWithdrawalSufficiency`는 등록·재설정 공용 단일 규칙이다. 호출이 둘인 이유: 등록은 override V(gate)와 시장가 평가금(required)을 분리해 넘기고, 재설정은 현재가 조회 뒤에만 자산을 알 수 있어 램프 검증(가격 조회 전)과 시점이 다르다 — 한 호출로 합치면 램프 오류에도 브로커 호출이 생긴다."
  - trading-formulas.md 71·89, stats.md 5: `VrRampParams.withDefaults()` → `VrRampInput.withDefaults()`, `StrategyCreationService.privacyBootstrapStartAmount` → `StrategyStartAmountCalculator.privacyBootstrapStartAmount` (공식 문장은 손대지 않음)
  - flows.yml: `StrategyCreationService#resolveVrValue`/`#validateVrCommand` → `VrRegistration#resolve`, 나머지 `StrategyCreationService#…` 참조는 존재 여부만 확인
- [ ] **Step 5: 전체 검증 1회** — `./gradlew :trading-core:test 2>&1 | grep -E "FAILED|BUILD"` 와 `./gradlew :test --tests 'com.kista.api.architecture.*' 2>&1 | grep -E "FAILED|BUILD"` → 둘 다 BUILD SUCCESSFUL
- [ ] **Step 6: 리뷰 후 커밋** (컨트롤러가 수행) — `refactor(trading): 전략 커맨드 램프 입력 통일·등록 경로 분리·조립기 추출`
