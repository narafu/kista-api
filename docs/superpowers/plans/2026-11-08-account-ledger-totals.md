# 계좌 원장 합산(ledger-totals) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/accounts/{accountId}/ledger-totals`로 계좌의 원장 예수금 합, 평가금 합, 미배정 현금을 내려준다.

**Architecture:** trading-core trading 모듈에 조회 전용 usecase, service, controller를 추가한다. 기존 `UnassignedCashCalculator`를 쪼개 "시드 점유 전략 + 최신 원장" 목록을 한 번만 읽고, 예수금 합과 미배정 계산을 공유한다. 시세와 잔고 호출은 `BrokerCallGuard.tryGet`으로 감싼다(fail-open, 필드 단위 null).

**Tech Stack:** Java 25, Spring Boot 4, JUnit5, Mockito, `@WebMvcTest`

**Spec:** `docs/superpowers/specs/2026-11-08-account-ledger-totals-design.md`

## Global Constraints

- 매매·주문 로직은 변경하지 않는다. 조회만 추가한다.
- 주석은 `//` 인라인만 쓴다. Javadoc은 금지다. 신규 필드에는 역할 한 줄 주석을 단다.
- 금액 반올림은 계좌 통화 기준 `Currency.round(...)`로 한다. 리터럴 scale은 금지다.
- 외부 호출(시세·잔고)은 `@Transactional` 밖에서 한다.
- 소유권은 `accountPort.requireOwnedAccount(accountId, userId)`로 확인한다. 컨트롤러는 `@AuthenticationPrincipal UUID userId`를 쓴다.
- 응답은 전용 Response DTO에 `from()` 팩토리로 만든다. 도메인 record를 직접 반환하지 않는다.
- 커밋 메시지는 한글 Conventional Commit, author는 `narafu <narafu@kakao.com>`이다.
- 테스트 명령은 `./gradlew :trading-core:test --tests '<FQCN>' 2>&1 | grep -E "FAILED|BUILD|ERROR|Test.*>"` 형태로 필터한다.

## Review Focus

1. 보유가 있는 ticker 하나의 가격이 `getPrices` 맵에 빠지면 `evaluation`은 null이어야 한다. 부분합을 내면 안 된다(Task 2 테스트).
2. 총 보유가 0이면 시세를 호출하지 않고 `evaluation`은 0이어야 한다(Task 2 테스트, `verifyNoInteractions`).
3. 잔고 조회가 예외를 던지면 `unassignedCash`만 null이고 나머지 필드는 정상이어야 한다(Task 2 테스트).
4. ARCHIVED 전략은 예수금과 평가금 양쪽에서 빠져야 한다(Task 1·2 테스트).
5. 같은 ticker의 전략이 2개면 holdings를 합산한 뒤 가격을 한 번 곱해야 한다. 요청 ticker 목록은 중복이 없어야 한다(Task 2 테스트).

---

### Task 1: `UnassignedCashCalculator` 분해

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/support/UnassignedCashCalculator.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/support/UnassignedCashCalculatorTest.java`(기존 테스트에 추가)

**Interfaces:**
- Produces:
  - `public record SeedHolder(Strategy strategy, @Nullable CyclePosition latest)`(calculator 안의 nested record). `BigDecimal deposit()`는 latest가 null이면 `ZERO`, 아니면 `usdDeposit`이다. `BigDecimal holdings()`도 같은 규칙으로 `ZERO`나 `holdings`를 반환한다.
  - `public List<SeedHolder> seedHolders(UUID accountId)`: ARCHIVED가 아닌 전략과 각 전략의 최신 원장이다.
  - `public static BigDecimal reserved(List<SeedHolder> holders)`: deposit 합이다.
  - `public @Nullable BigDecimal available(BrokerAccountRef ref, BigDecimal reserved)`: 주문 가능 현금 − reserved다. 증권사가 null을 주면 null이다.
  - 기존 `available(UUID accountId, BrokerAccountRef ref)`는 시그니처와 동작을 유지하고 위 세 메서드를 조합한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다.** 테스트 이름은 `seedHolders_excludes_archived_and_zero_fills_missing_ledger`다. 전략 3개를 둔다. 하나는 ACTIVE에 원장 deposit 100과 holdings 2, 하나는 PAUSED에 원장 없음, 하나는 ARCHIVED다. 단언은 `seedHolders` 크기 2, `reserved(...)`가 `100`(compareTo), 원장 없는 holder의 `deposit()`과 `holdings()`가 0이다.
- [ ] **Step 2: 테스트를 실행해 실패를 확인한다.** `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.support.UnassignedCashCalculatorTest'`를 돌리면 컴파일 실패가 나야 한다.
- [ ] **Step 3: Interfaces대로 구현하고 기존 `available(accountId, ref)`를 위임형으로 바꾼다.** `@Nullable`은 `org.jspecify.annotations.Nullable`이다.
- [ ] **Step 4: 같은 명령을 돌린다.** 기존 4개를 포함해 전부 PASS여야 한다.
- [ ] **Step 5: 커밋하지 않는다.** 컨트롤러가 마지막에 한 번 커밋한다.

### Task 2: ledger-totals 조회 usecase, service, controller

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/AccountLedgerTotals.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/usecase/AccountLedgerTotalsUseCase.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/AccountLedgerTotalsService.java`(package-private)
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/AccountLedgerTotalsController.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/AccountLedgerTotalsResponse.java`(기존 dto 패키지 위치를 확인한다)
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/AccountLedgerTotalsServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/AccountLedgerTotalsControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `seedHolders`, `reserved`, `available(ref, reserved)`, `applies(broker)`. 그리고 `AccountPort.requireOwnedAccount`, `BrokerPricePort.getPrices(List<StrategyTicker>, BrokerAccountRef)`, `CryptoExchanges.of(broker).getCurrentPrices(Collection<StrategyTicker>)`, `BrokerCallGuard.tryGet`.
- Produces:
  - `public record AccountLedgerTotals(Currency currency, BigDecimal deposit, @Nullable BigDecimal evaluation, @Nullable BigDecimal unassignedCash)`
  - `AccountLedgerTotalsUseCase.get(UUID accountId, UUID requesterId): AccountLedgerTotals`
  - `GET /api/accounts/{accountId}/ledger-totals`가 `AccountLedgerTotalsResponse(String currency, BigDecimal deposit, BigDecimal evaluation, BigDecimal unassignedCash)`를 반환한다. currency는 enum name이고 `from(AccountLedgerTotals)`로 만든다.

계산 규칙(spec "계산"과 "실패 정책"):
- 통화는 `account.broker().getCurrency()`다(`AccountResponse`와 같다).
- `deposit`은 `currency.round(reserved(holders))`다.
- 평가금 계산은 ticker별 holdings 합을 기준으로 한다(holdings > 0인 것만).
  - 비어 있으면 `0`이고 시세 호출은 없다.
  - 아니면 브로커 종류에 맞는 배치 시세를 `tryGet`으로 부른다. 결과가 없거나 요청 ticker 중 하나라도 맵에 없으면 null이다.
  - 그 외에는 `currency.round(Σ holdings × price)`다.
- `unassignedCash`는 `applies(broker)`가 false면 null이다. 그 외에는 `tryGet(() -> calculator.available(ref, reserved))`의 결과에 `currency.round`를 적용하고, 실패하면 null이다.
- 이 서비스는 `@Transactional`을 붙이지 않는다. 읽기 포트는 각자 트랜잭션을 가진다.
- `tryGet` 라벨은 기존 예시(`StrategyHistoryQueryService.calcAvailableCash`)처럼 `"... : accountId=" + id` 형태로 쓴다. 단, 로그 접두사 규칙에 맞는지 기존 사용처를 따른다.

- [ ] **Step 1: 서비스 테스트를 쓴다.** `AccountLedgerTotalsServiceTest`, Mockito를 쓴다. 테스트 이름과 단언은 아래와 같다.
  - `sums_deposit_and_evaluation_of_non_archived_strategies`: 주식(KIS) 계좌에 SOXL 전략 2개를 둔다. holdings는 3과 2, deposit은 100과 50이다. TQQQ 전략은 holdings 0, deposit 10이다. `getPrices([SOXL])`가 SOXL=10을 반환한다. 기대값은 deposit 160, evaluation 50이다. 요청 리스트가 `[SOXL]` 하나뿐인지 검증한다.
  - `evaluation_null_when_priced_ticker_missing`: 맵이 비어 있으면 evaluation은 null이고 deposit은 정상이다.
  - `evaluation_null_when_price_call_fails`: `getPrices`가 `StubBrokerApiException`을 던지면 evaluation은 null이다.
  - `zero_holdings_skips_price_call`: evaluation은 0이고 `verifyNoInteractions(brokerPricePort)`다.
  - `unassigned_null_when_balance_lookup_fails_and_for_mock`: calculator.available이 예외를 던지면 null이다. MOCK 계좌도 null이다.
  - `crypto_account_uses_exchange_prices_and_quote_currency`: UPBIT 계좌는 currency KRW이고 `CryptoExchanges.of(UPBIT).getCurrentPrices`를 쓴다. brokerPricePort는 호출하지 않는다.
  - `rejects_non_owner`: `requireOwnedAccount`가 `SecurityException`을 던지면 그대로 전파된다.
  - `UnassignedCashCalculator`는 mock으로 둔다. `seedHolders`가 holder 목록을 반환하도록 stub하고, `reserved`는 static이라 실제 메서드를 쓴다.
- [ ] **Step 2: 서비스 테스트를 실행한다.** 컴파일 실패가 나야 한다.
- [ ] **Step 3: record, usecase, service를 구현한다.**
- [ ] **Step 4: 서비스 테스트를 실행한다.** PASS여야 한다.
- [ ] **Step 5: 컨트롤러 테스트를 쓴다.** `AccountLedgerTotalsControllerTest`는 `@WebMvcTest`, `@Execution(SAME_THREAD)`, UUID principal `authentication(...)`을 쓰는 기존 `TradingCycleControllerTest` 패턴을 따른다. 단언은 아래와 같다.
  - 200 응답이고 `$.currency == "USD"`, `$.deposit == 160`, `$.evaluation == 50`이다.
  - evaluation과 unassignedCash가 null이면 JSON에 `null`로 나온다(`jsonPath(...).value(nullValue())` 또는 `doesNotExist`). 실제 Jackson 설정에 맞춘다.
- [ ] **Step 6: 컨트롤러를 구현한다.** `@Tag`와 `@Operation(summary = "계좌 원장 합산 조회")`를 달고, `@ApiResponses`로 200, 403, 404를 적는다.
- [ ] **Step 7: 가드 테스트를 실행한다.** `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.*' --tests 'com.kista.tradingcore.architecture.*' --tests 'com.kista.tradingcore.web.*' 2>&1 | grep -E "FAILED|BUILD|ERROR"`와 루트의 `./gradlew :test --tests '*CaddyRouting*' --tests '*OpenApi*' --tests '*ApiDocs*' 2>&1 | grep -E "FAILED|BUILD|ERROR"`를 돌린다. 매치가 없는 필터는 빼고 다시 돌린다. 기대 결과는 BUILD SUCCESSFUL이다. 스냅샷이나 계약 테스트가 갱신을 요구하면 그 산출물을 갱신한다.
- [ ] **Step 8: 커밋하지 않는다.**

### Task 3: 문서와 flows.yml 동기화

**Files:**
- Modify: `src/test/resources/architecture/flows.yml`. 계좌 조회 계열 flow 목록(`dca-history` 근처 `flows` 항목들)에 `{id: account-ledger-totals, title: 계좌 원장 합산 조회, when: "GET /api/accounts/{accountId}/ledger-totals", flow: result}` 형식의 항목을 같은 스타일로 추가한다. `code:` 참조를 쓰는 블록이면 `AccountLedgerTotalsController`를 넣는다.
- Modify: `docs/agents/modules/tradingcore/trading.md`. 조회 API 목록이나 미배정 현금 설명이 있으면 한 줄을 추가한다(grep `UnassignedCashCalculator`).

- [ ] **Step 1: 항목을 추가한다.**
- [ ] **Step 2: 아키텍처 맵 테스트를 돌린다.** `./gradlew :test --tests '*ArchitectureMapTest*' 2>&1 | grep -E "FAILED|BUILD|ERROR"`의 결과가 BUILD SUCCESSFUL이어야 한다.
- [ ] **Step 3: 커밋하지 않는다.**

### 마무리 (컨트롤러)

- 리뷰어 검수를 거친 뒤 trading-core 전체 테스트를 1회 돌리고 커밋한다.
- spec과 plan을 `archive/`로 옮기는 일은 UI 반영과 배포가 끝난 뒤에 한다. 지금은 남은 후속 작업이 UI이므로 옮기지 않는다.
- memory `project_account_detail_ledger_totals.md`를 "API 완료, UI 요청 대기"로 갱신한다.
