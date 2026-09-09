# 주문생성 알고리즘 커널 추출 (com.kista.matching) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `trading.domain.strategy`의 순수 주문생성 커널을 독립 CLOSED 모듈 `com.kista.matching`으로 분리하고, 커널 출력 타입을 `Order`에서 실행 상태 없는 `PlannedOrder`로 바꿔 라이브 실행과 백테스트가 같은 공식을 쓴다는 보장을 타입 경계로 고정한다.

**Architecture:** 순서 원칙 — 커널을 물리 이동하기 전에 (1) 새 모듈 골격 + 주문 어휘 enum을 먼저 세우고, (2) `PlannedOrder`와 승격/강등 헬퍼를 dormant 상태로 추가하고, (3) 순수 값객체를 옮기고, (4) 커널 출력·파이프라인·AccountBalance·stats를 `PlannedOrder`로 한 번에 전환(타입 경계 refactor라 컴파일 단위가 원자적), (5) 커널 클래스를 물리 이동 + `@NamedInterface` + ArchUnit 가드, (6) `PlanContext`에서 `Strategy` 제거, (7) 테스트 이동 + 문서, (8) 최종 `verify()`. 각 태스크는 "이동/치환 → 컴파일 → 좁은 테스트 → 리뷰 → 커밋" 사이클. 대량 import 치환은 python 스크립트(BSD sed 금지 — 아래 Global Constraints).

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5, Mockito, Python(대량 파일 치환 스크립트)

**Spec:** `docs/superpowers/specs/2026-09-09-matching-kernel-extraction-design.md`

## Global Constraints

- 커밋 전 검토자 검수 필수(전역 CLAUDE.md) — 각 태스크 커밋 전 diff를 서브에이전트 리뷰어 또는 자체 검토로 확인, 실제 결함은 커밋 전 수정·재검증.
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit(`refactor(modulith):` 등), 끝에 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01KVvZaQVn4ZSe7ErXjTdEEs` 트레일러.
- `git push`는 사용자 명시 요청 시에만.
- 전체 `./gradlew test`는 **Task 8 완료 시 최종 1회만**(전역 CLAUDE.md) — 그 전 태스크는 `--tests`로 좁힌 범위 + `compileJava compileTestJava` + `--tests 'com.kista.architecture.*'`로 검증.
- **BSD sed 금지**: 이 환경 `sed`는 `\|` alternation·`\b` word boundary를 리터럴 취급해 조용히 no-op한다. 대량 치환은 python 스크립트로(`re.sub`, 실행 후 `grep`으로 잔여 확인).
- **DDL 변경 없음**: 이 재구성은 순수 패키지 이동 + 타입 리네임. Flyway 파일 신규·수정 없음. `orders.order_type`/`orders.direction` 컬럼은 enum 상수명이 byte-identical이라 무변경 — `ddl-auto: validate` 통과 확인만.
- **매매·VR 공식 변경 금지**(`constraints.md`) — 이 재구성은 타입 경계만 옮긴다. `InfiniteStrategy`/`VrStrategy`/`PrivacyStrategy`/`ReverseInfiniteStrategy` 계산 로직 라인은 수정하지 않는다(import·반환타입·`Order.planned`→`PlannedOrder.of` 호출부만).
- **broker/privacy enum 미변경**: `broker.domain.model.{Direction,OrderType}`, `privacy.domain.model.{PrivacyOrderDirection,PrivacyOrderType}`는 이 재구성 대상 아님(로드맵 #2). `matching.OrderType.valueOf(name())` 브릿지 유지.
- 정규식 주의: `Order.OrderType`/`Order.OrderDirection`/`Order.OrderTiming` 치환 시 `Order.OrderStatus`(잔류)와 broker/privacy 동명 타입은 매칭되면 안 됨 — FQN 또는 `Order\.Order(Type|Direction|Timing)\b` 패턴으로 좁힌다.

---

## File Structure (신설/이동 대상)

```
com.kista.matching/                                  ← 신설 (@ApplicationModule CLOSED, Task 1)
  package-info.java                                  ← @ApplicationModule (Task 1)
  domain/model/
    package-info.java                                ← @NamedInterface("kernel") (Task 1)
    OrderType.java  OrderTiming.java  OrderDirection.java   ← Order nested → top-level (Task 1)
    PlannedOrder.java                                ← 신규 (Task 2)
    InfinitePosition.java  VrPosition.java  ReverseModePosition.java
    BootstrapPosition.java  PriceSnapshot.java  StrategyVrDetail.java   ← trading.domain.model에서 이동 (Task 3)
    AccountBalance.java                              ← 분리 후 이동 (Task 4)
  domain/strategy/
    package-info.java                                ← @NamedInterface("kernel") (Task 5)
    CycleOrderStrategy.java (+nested PlanContext/OrderPlan/PriceCapMode)
    CycleOrderStrategies.java
    InfiniteStrategy.java  ReverseInfiniteStrategy.java  VrStrategy.java  PrivacyStrategy.java
    PriceCapPolicy.java
    InfiniteCycleOrderStrategy.java  PrivacyCycleOrderStrategy.java  VrCycleOrderStrategy.java
                                                    ← trading.domain.strategy에서 이동 (Task 5)

com.kista.trading.domain.model/
  Order.java                                         ← Modify: 필드 타입 matching.* enum 참조, OrderStatus nested 잔류,
                                                       Order.fromPlanned()/toPlanned() 추가, Order.planned() 정적팩토리 제거 (Task 1·2·4)

com.kista.trading.domain.strategy/                   ← 잔류: 생성 리졸버만
  package-info.java                                  ← @NamedInterface("domain") 유지
  StrategyCreationResolver.java  StrategyCreationResolvers.java
  InfiniteCreationResolver.java  PrivacyCreationResolver.java  VrCreationResolver.java
  StrategyCreationRequest.java

com.kista.trading.application.service/
  CycleStrategyBeanConfig.java                       ← Modify: import 갱신 (Task 5)
  CycleOrderComputer.java  BuyOrderPriceCapper.java  TradingOrderPlanner.java
  TradingOrderBudgetAllocator.java  TradingBuyCompetitionSimulator.java
  StrategyOrderPlanBuilder.java  TradingService.java  ManualTradingService.java  TradingPreviewService.java  TradingReporter.java
                                                    ← Modify: PlannedOrder 파이프라인 전환 (Task 4)
  Admin* 아님 — admin은 아래

com.kista.trading.application.port.output/
  StrategyVrDetailPort.java                          ← Modify: 반환타입 matching.StrategyVrDetail (Task 3)
  StrategyInfiniteDetailPort.java                    ← 무변경 (StrategyInfiniteDetail은 trading 잔류)

com.kista.admin.application.service/
  AdminTradeCorrectionService.java                   ← Modify: Execution→Fill 인라인 (Task 4)

com.kista.stats.domain.backtest/
  BacktestEngine.java  FillSimulator.java            ← Modify: matching import, PlannedOrder, syntheticStrategy 제거 (Task 4·6)
com.kista.stats.application.service/
  BacktestService.java  AccountStatisticsService.java   ← Modify: matching import (Task 5)
com.kista.stats.domain.model.backtest/
  BacktestCommand.java                               ← Modify: 미사용 Strategy import 제거 (Task 6)

src/test/java/com/kista/architecture/
  HexagonalArchitectureTest.java                     ← Modify: matching_must_not_depend_on_other_modules 규칙 추가 (Task 5)

src/test/java/com/kista/matching/                    ← 신설: 아래 테스트 이동 (Task 7)
  domain/model/InfinitePositionTest.java  VrPosition 계열 등
  domain/strategy/InfiniteStrategyTypeTest.java  ReverseInfiniteStrategyTest.java
    PrivacyStrategyTest.java  VrStrategyTypeTest.java  CycleOrderStrategyCapabilityTest.java

docs/
  CLAUDE.md  AGENTS.md  docs/agents/architecture.md  docs/agents/constraints.md   ← Modify (Task 7)
```

---

## Task 1: matching 모듈 골격 + 주문 어휘 enum 이관

**Files:**
- Create: `src/main/java/com/kista/matching/package-info.java`
- Create: `src/main/java/com/kista/matching/domain/model/package-info.java`
- Create: `src/main/java/com/kista/matching/domain/model/OrderType.java`
- Create: `src/main/java/com/kista/matching/domain/model/OrderTiming.java`
- Create: `src/main/java/com/kista/matching/domain/model/OrderDirection.java`
- Modify: `src/main/java/com/kista/trading/domain/model/Order.java` (nested `OrderType`/`OrderTiming`/`OrderDirection` 삭제, 필드 타입을 `matching.*` 참조로, `OrderStatus` nested 유지)
- Modify: 59개 파일 (repo 전역 `Order.OrderType`/`Order.OrderDirection`/`Order.OrderTiming` 및 static import 참조)

**Interfaces:**
- Produces:
  - `com.kista.matching.domain.model.OrderType` — `enum { LOC, MOC, LIMIT }`
  - `com.kista.matching.domain.model.OrderTiming` — `enum { AT_CLOSE, AT_OPEN, IMMEDIATE }`
  - `com.kista.matching.domain.model.OrderDirection` — `enum { BUY, SELL }`
  - `Order` record: `orderType`/`timing`/`direction` 필드 타입이 위 3개, `status`는 여전히 `Order.OrderStatus`(nested 유지)

- [ ] **Step 0: OrderEntity 구조 확인 (persistence 영향 판정)**

Run:
```bash
grep -n 'Enumerated\|OrderType\|OrderDirection\|Direction\|import' src/main/java/com/kista/trading/adapter/out/persistence/OrderEntity.java
```
- `OrderEntity`가 `Order.OrderType`/`Order.OrderDirection`을 `@Enumerated(STRING)` 필드로 직접 쓰면 → Task 1 스윕이 persistence를 건드리므로 Step 6에 `OrderPersistenceAdapterTest`(ddl-auto validate) 추가.
- `OrderEntity`가 자체 로컬 enum이나 `String`을 쓰면 → Task 1은 persistence 무영향, Step 6은 arch 테스트만.
- 판정 결과를 이 태스크 커밋 메시지 본문에 한 줄 기록.

- [ ] **Step 1: matching 모듈 package-info 생성**

`src/main/java/com/kista/matching/package-info.java`:
```java
// 주문생성 알고리즘 커널 모듈 — 순수 계산(무한매수법/리버스/VR/PRIVACY 사다리 + 가격 캡).
// 라이브 실행(trading)과 백테스트(stats) 두 소비자가 공유. domain-only(레이어 서브구조 없음),
// outbound 엣지는 sharedkernel + privacy뿐(HexagonalArchitectureTest.matching_must_not_depend_on_other_modules 강제).
// "kernel" NamedInterface로 domain.model + domain.strategy 병합 공개.
@org.springframework.modulith.ApplicationModule
package com.kista.matching;
```

`src/main/java/com/kista/matching/domain/model/package-info.java`:
```java
// matching 커널의 공개 계약 일부 — 주문 어휘 enum, PlannedOrder(커널 출력), 순수 position 값객체,
// AccountBalance(순수 잔고 + 예산 계산). domain.strategy와 함께 "kernel" 이름으로 병합 공개.
@org.springframework.modulith.NamedInterface("kernel")
package com.kista.matching.domain.model;
```

- [ ] **Step 2: 3개 enum 파일 생성** (현 `Order.java` nested 정의 그대로 옮김 — 주석 포함)

`OrderType.java`:
```java
package com.kista.matching.domain.model;

// 주문 유형 — LOC(장마감 지정가) / MOC(장마감 시장가) / LIMIT(지정가)
public enum OrderType { LOC, MOC, LIMIT }
```

`OrderTiming.java`:
```java
package com.kista.matching.domain.model;

// 주문 실행 시점 — AT_CLOSE(장마감) / AT_OPEN(장개시) / IMMEDIATE(즉시)
public enum OrderTiming { AT_CLOSE, AT_OPEN, IMMEDIATE }
```

`OrderDirection.java`:
```java
package com.kista.matching.domain.model;

// 매매 방향
public enum OrderDirection { BUY, SELL }
```

(정확한 nested 정의·주석은 `src/main/java/com/kista/trading/domain/model/Order.java`의 현재 `OrderType`/`OrderTiming`/`OrderDirection` 선언을 그대로 사용한다. `OrderStatus`는 옮기지 않는다.)

- [ ] **Step 3: `Order.java` 수정** — nested `OrderType`/`OrderTiming`/`OrderDirection` 삭제, import 추가

`Order.java` 상단에 추가:
```java
import com.kista.matching.domain.model.OrderDirection;
import com.kista.matching.domain.model.OrderTiming;
import com.kista.matching.domain.model.OrderType;
```
- nested `enum OrderType {...}`, `enum OrderTiming {...}`, `enum OrderDirection {...}` 3개 블록 삭제
- `enum OrderStatus { PLANNED, PLACED, FILLED, PARTIALLY_FILLED, FAILED, CANCELLED }` **유지**
- record 컴포넌트 타입은 이미 짧은 이름(`OrderType orderType` 등)이라 import만으로 해소됨
- `UNKNOWN_LEG` 상수, 정적 팩토리(`plan`/`planned`/`filledManual`/`reorder`/`leg`), wither는 이 태스크에서 건드리지 않음(Task 2·4)

- [ ] **Step 4: 충돌 3파일 수동 편집 우선**

`PrivacyStrategy.java` · `FillSimulator.java` · `TradingOrderExecutor.java` 는 matching enum과 broker/privacy 동명 enum(`OrderType`/`Direction`/`PrivacyOrderType`)이 **공존**한다 — unqualified import가 충돌하므로 스크립트 대상에서 제외하고 손으로 편집한다:
- `Order.OrderType` / `Order.OrderDirection` / `Order.OrderTiming` (있으면) → `com.kista.matching.domain.model.OrderType` 등 **FQN 유지** (import 추가 안 함)
- 이 3파일에서 `Order`(record) 참조 자체는 유지
- `TradingOrderExecutor`는 `p.direction()`/`p.orderType()` (Order 필드) → broker `OrderInstruction` 매핑 지점 — `toDirection`/`toOrderType` 헬퍼 인자 타입이 `matching.*`가 됨, FQN 또는 이미 있는 broker import와 다른 별칭 없이 명시적 참조

- [ ] **Step 5: 나머지 파일 전역 치환 스크립트**

`<scratchpad>/rewrite_order_enums.py`:
```python
import re, pathlib

ROOTS = ["src/main/java", "src/test/java"]
EXCLUDE = {"Order.java", "PrivacyStrategy.java", "FillSimulator.java", "TradingOrderExecutor.java"}
PAT_QUALIFIED = re.compile(r'\bOrder\.(OrderType|OrderDirection|OrderTiming)\b')  # OrderStatus 제외
PAT_STATIC_IMPORT = re.compile(
    r'import static com\.kista\.trading\.domain\.model\.Order\.(OrderType|OrderDirection|OrderTiming)\.(\w+);')
PAT_IMPORT_ORDER = re.compile(r'import com\.kista\.trading\.domain\.model\.Order;')

changed = []
for root in ROOTS:
    for p in pathlib.Path(root).rglob("*.java"):
        if p.name in EXCLUDE:
            continue
        t = p.read_text(); orig = t
        needs = set(m.group(1) for m in PAT_QUALIFIED.finditer(t))
        t = PAT_QUALIFIED.sub(r'\1', t)
        needs |= set(m.group(1) for m in PAT_STATIC_IMPORT.finditer(orig))
        t = PAT_STATIC_IMPORT.sub(r'import static com.kista.matching.domain.model.\1.\2;', t)
        for e in sorted(needs):
            imp = f"import com.kista.matching.domain.model.{e};"
            if imp in t:                      # dedup guard — 이미 있으면 삽입 안 함
                continue
            if PAT_IMPORT_ORDER.search(t):
                t = PAT_IMPORT_ORDER.sub(lambda mm: mm.group(0) + "\n" + imp, t, count=1)
            else:
                t = re.sub(r'(package [\w.]+;\n)', r'\1\n' + imp + "\n", t, count=1)
        if t != orig:
            p.write_text(t); changed.append(str(p))

print(f"{len(changed)} files changed")
for c in changed: print(" ", c)
```
Run: `cd /Users/phs/workspace/kista/kista-api && python3 <scratchpad>/rewrite_order_enums.py`
그다음 `./gradlew compileJava` 에러 메시지로 스크립트가 놓친 케이스(같은 파일 여러 static import, import 순서로 인한 중복) 개별 보정.

- [ ] **Step 6: 잔여 참조 확인 + 컴파일**

Run:
```bash
grep -rn 'Order\.\(OrderType\|OrderDirection\|OrderTiming\)\b' src/main/java src/test/java   # Order.java 외 0건 기대
grep -rn 'trading\.domain\.model\.Order\.\(OrderType\|OrderDirection\|OrderTiming\)' src/main/java src/test/java   # 0건 기대
./gradlew compileJava compileTestJava 2>&1 | grep -E 'error:|BUILD'
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: 아키텍처 테스트 + verify()**

Run: `./gradlew test --tests 'com.kista.architecture.*' 2>&1 | grep -E 'FAILED|PASSED|BUILD'`
Expected: `ModulithArchitectureTest` PASS — `matching` 모듈이 인식되고 `trading → matching::kernel` 엣지만 생김(순환 없음). `HexagonalArchitectureTest` PASS. (Step 0 판정이 "persistence 영향 있음"이면 `./gradlew test --tests 'com.kista.trading.adapter.out.persistence.OrderPersistenceAdapterTest'` 추가 실행 — ddl-auto validate 통과 확인)

- [ ] **Step 8: 리뷰 + 커밋**

서브에이전트 리뷰어(`cavecrew-reviewer` 또는 일반 reviewer, model: sonnet)에게 diff 검토 요청 — enum 이동 누락, `OrderStatus` 오이동, broker/privacy enum 오염 확인.

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): 주문 어휘 enum을 com.kista.matching으로 이관

Order.OrderType/OrderTiming/OrderDirection nested enum을
com.kista.matching.domain.model top-level로 이동. OrderStatus는
실행 생명주기 전용이라 Order에 잔류. matching 모듈 골격
(@ApplicationModule + "kernel" NamedInterface) 신설.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KVvZaQVn4ZSe7ErXjTdEEs
EOF
)"
```

---

## Task 2: PlannedOrder 신설 + Order 승격/강등 헬퍼 (dormant)

**Files:**
- Create: `src/main/java/com/kista/matching/domain/model/PlannedOrder.java`
- Modify: `src/main/java/com/kista/trading/domain/model/Order.java` (`fromPlanned`/`toPlanned` 추가)
- Create: `src/test/java/com/kista/matching/domain/model/PlannedOrderTest.java`

**Interfaces:**
- Consumes: `matching.domain.model.{OrderType,OrderTiming,OrderDirection}` (Task 1), `sharedkernel.StrategyTicker`
- Produces:
  - `PlannedOrder(StrategyTicker ticker, LocalDate tradeDate, OrderType orderType, OrderTiming timing, OrderDirection direction, String orderLeg, Integer quantity, BigDecimal price)`
  - `PlannedOrder.of(LocalDate, StrategyTicker, OrderType, OrderDirection, int quantity, BigDecimal price)` — timing=AT_CLOSE, leg=UNKNOWN_LEG
  - `PlannedOrder.of(..., OrderTiming timing)` / `PlannedOrder.of(..., String orderLeg)` / `PlannedOrder.of(..., OrderTiming timing, String orderLeg)` — 현 `Order.planned` 오버로드 대응
  - `PlannedOrder.leg(String prefix, int index)` → `"%s_%02d"`
  - `PlannedOrder.UNKNOWN_LEG = "UNKNOWN"`
  - `PlannedOrder withPrice(BigDecimal newPrice)`
  - `Order.fromPlanned(PlannedOrder p, UUID accountId, UUID strategyCycleId)` → `Order`(id=null, status=PLANNED, externalOrderId/filledQuantity/filledPrice=null, 나머지 p에서 전파)
  - `Order toPlanned()` … 아니오 — 강등은 `PlannedOrder`가 못 만든다(matching→trading 금지). **`Order.toPlanned()` 인스턴스 메서드가 `PlannedOrder`를 반환**한다(trading→matching 허용).

- [ ] **Step 1: 실패 테스트 작성** — `PlannedOrderTest.java`

```java
package com.kista.matching.domain.model;

import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PlannedOrderTest {

    @Test
    void of_blankLeg_becomesUnknown() {
        PlannedOrder o = PlannedOrder.of(LocalDate.of(2026, 9, 9), StrategyTicker.SOXL,
                OrderType.LOC, OrderDirection.BUY, 3, new BigDecimal("10.00"));
        assertThat(o.orderLeg()).isEqualTo(PlannedOrder.UNKNOWN_LEG);
        assertThat(o.timing()).isEqualTo(OrderTiming.AT_CLOSE);
    }

    @Test
    void withPrice_replacesPriceOnly() {
        PlannedOrder o = PlannedOrder.of(LocalDate.of(2026, 9, 9), StrategyTicker.SOXL,
                OrderType.LOC, OrderDirection.BUY, 3, new BigDecimal("10.00"))
                .withPrice(new BigDecimal("9.50"));
        assertThat(o.price()).isEqualByComparingTo("9.50");
        assertThat(o.quantity()).isEqualTo(3);
    }

    @Test
    void leg_formatsPrefixAndIndex() {
        assertThat(PlannedOrder.leg("INFINITE_BUY", 2)).isEqualTo("INFINITE_BUY_02");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.kista.matching.domain.model.PlannedOrderTest' 2>&1 | grep -E 'error:|FAILED|BUILD'`
Expected: 컴파일 실패 (`PlannedOrder` 없음)

- [ ] **Step 3: PlannedOrder 구현**

`src/main/java/com/kista/matching/domain/model/PlannedOrder.java`:
```java
package com.kista.matching.domain.model;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;

// 커널이 산출하는 계획 주문 — 실행 생명주기 상태(id/status/externalOrderId/체결수량) 없음.
// trading이 Order.fromPlanned로 특정 계좌·사이클 PLANNED Order로 승격한다.
public record PlannedOrder(
        StrategyTicker ticker,      // 거래 종목
        LocalDate tradeDate,        // 거래일
        OrderType orderType,        // LOC/MOC/LIMIT
        OrderTiming timing,         // AT_OPEN/AT_CLOSE/IMMEDIATE
        OrderDirection direction,   // BUY/SELL
        String orderLeg,            // 전략 주문 다리 식별자
        Integer quantity,           // 주문 수량 (nullable — SELL "잔량 전부")
        BigDecimal price             // 주문 가격
) {
    public static final String UNKNOWN_LEG = "UNKNOWN";

    public PlannedOrder {
        if (orderLeg == null || orderLeg.isBlank()) orderLeg = UNKNOWN_LEG;
    }

    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price) {
        return new PlannedOrder(ticker, tradeDate, orderType, OrderTiming.AT_CLOSE, direction,
                UNKNOWN_LEG, quantity, price);
    }

    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price, OrderTiming timing) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, UNKNOWN_LEG, quantity, price);
    }

    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price, String orderLeg) {
        return new PlannedOrder(ticker, tradeDate, orderType, OrderTiming.AT_CLOSE, direction, orderLeg, quantity, price);
    }

    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price,
                                  OrderTiming timing, String orderLeg) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, price);
    }

    // 전략 주문 다리 식별자 포맷 — "INFINITE_BUY" + 2 → "INFINITE_BUY_02"
    public static String leg(String prefix, int index) {
        return "%s_%02d".formatted(prefix, index);
    }

    // 가격 캡 보정 — 가격만 교체
    public PlannedOrder withPrice(BigDecimal newPrice) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, newPrice);
    }
}
```
(현 `Order.java`의 `planned(...)`/`leg(...)`/`UNKNOWN_LEG` 정의를 SSOT로 대조 — 인자 순서·기본값 정확히 일치시킬 것.)

- [ ] **Step 4: `Order.java`에 승격/강등 헬퍼 추가**

`Order.java`에 추가 (기존 `plan(Order template, ...)` 옆):
```java
import com.kista.matching.domain.model.PlannedOrder;

    // 커널 계획 주문 → 특정 계좌·사이클 PLANNED Order 승격
    public static Order fromPlanned(PlannedOrder p, UUID accountId, UUID strategyCycleId) {
        return new Order(null, accountId, strategyCycleId, p.tradeDate(), p.ticker(), p.orderType(),
                p.timing(), p.direction(), p.orderLeg(), p.quantity(), p.price(),
                OrderStatus.PLANNED, null, null, null);
    }

    // 영속 Order → 계획 주문 강등 (사후 가격 캡 재산정 시 커널에 재입력)
    public PlannedOrder toPlanned() {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, price);
    }
```
- 이 태스크에서는 `Order.planned(...)` 정적 팩토리·`Order.plan(...)`는 **제거하지 않는다**(Task 4에서 호출부 전환과 함께). `fromPlanned`/`toPlanned`는 아직 아무도 호출 안 함(dormant).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.kista.matching.domain.model.PlannedOrderTest' --tests 'com.kista.trading.domain.model.OrderTest' 2>&1 | grep -E 'FAILED|PASSED|BUILD'`
Expected: PASS. `./gradlew compileJava` BUILD SUCCESSFUL.

- [ ] **Step 6: 리뷰 + 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): PlannedOrder 커널 출력 타입 + Order 승격/강등 헬퍼

matching.domain.model.PlannedOrder 신설 — 실행 생명주기 상태 없는
계획 주문 8필드. Order.fromPlanned(승격)/toPlanned(강등) 추가.
아직 호출부 없음(Task 4에서 파이프라인 전환).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KVvZaQVn4ZSe7ErXjTdEEs
EOF
)"
```

---

## Task 3: 순수 값객체 6개 + StrategyVrDetail → matching 이동

**Files:**
- Move: `trading/domain/model/{InfinitePosition,VrPosition,ReverseModePosition,BootstrapPosition,PriceSnapshot,StrategyVrDetail}.java` → `matching/domain/model/`
- Modify: `trading/application/port/output/StrategyVrDetailPort.java` (반환타입 import 갱신)
- Modify: 32개 파일 (위 6개 타입 import)

**Interfaces:**
- Consumes: `matching.domain.model.{OrderType,OrderTiming,OrderDirection}` (Task 1), `sharedkernel.StrategyTicker`
- Produces: `com.kista.matching.domain.model.{InfinitePosition,VrPosition,ReverseModePosition,BootstrapPosition,PriceSnapshot,StrategyVrDetail}` — 시그니처·메서드 전부 현재와 동일, 패키지만 변경. `StrategyVrDetail.gradientAt(long)`/`poolLimitRateAt(long)` 포함.

- [ ] **Step 1: 파일 이동 (git mv)**

```bash
cd /Users/phs/workspace/kista/kista-api
for f in InfinitePosition VrPosition ReverseModePosition BootstrapPosition PriceSnapshot StrategyVrDetail; do
  git mv src/main/java/com/kista/trading/domain/model/$f.java src/main/java/com/kista/matching/domain/model/$f.java
done
```

- [ ] **Step 2: 이동 파일 package 선언 + 내부 import 수정**

각 파일 `package com.kista.trading.domain.model;` → `package com.kista.matching.domain.model;`.
`InfinitePosition`/`ReverseModePosition`가 참조하는 `AccountBalance`는 아직 `trading.domain.model`에 있음 → `import com.kista.trading.domain.model.AccountBalance;` 추가(Task 4에서 matching으로 재이동). 같은 패키지 타입(`OrderType` 등)은 import 불필요.

- [ ] **Step 3: 전역 import 치환 스크립트**

`<scratchpad>/move_value_objects.py`:
```python
import re, pathlib

TYPES = ["InfinitePosition", "VrPosition", "ReverseModePosition",
         "BootstrapPosition", "PriceSnapshot", "StrategyVrDetail"]
ROOTS = ["src/main/java", "src/test/java"]
changed = []
for root in ROOTS:
    for p in pathlib.Path(root).rglob("*.java"):
        if p.parts[-2:] == ("model",) and "matching" in str(p):
            continue
        t = p.read_text(); orig = t
        for ty in TYPES:
            t = t.replace(f"import com.kista.trading.domain.model.{ty};",
                          f"import com.kista.matching.domain.model.{ty};")
        if t != orig:
            p.write_text(t); changed.append(str(p))
print(f"{len(changed)} files"); [print(" ", c) for c in changed]
```
Run: `python3 <scratchpad>/move_value_objects.py`

- [ ] **Step 4: `StrategyVrDetailPort` 확인**

`StrategyVrDetailPort.java` — `import com.kista.trading.domain.model.StrategyVrDetail;` → `import com.kista.matching.domain.model.StrategyVrDetail;` (스크립트가 처리했는지 확인). 구현체 `StrategyVrDetailPersistenceAdapter`도 동일.

- [ ] **Step 5: 잔여 참조 + 컴파일**

```bash
grep -rn 'trading\.domain\.model\.\(InfinitePosition\|VrPosition\|ReverseModePosition\|BootstrapPosition\|PriceSnapshot\|StrategyVrDetail\)' src/   # 0건 기대
./gradlew compileJava compileTestJava 2>&1 | grep -E 'error:|BUILD'
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 좁은 테스트 + 아키텍처**

Run:
```bash
./gradlew test --tests 'com.kista.matching.*' --tests 'com.kista.trading.domain.*' --tests 'com.kista.architecture.*' 2>&1 | grep -E 'FAILED|BUILD'
```
Expected: BUILD SUCCESSFUL. (`InfinitePositionTest` 등은 아직 `com.kista.trading.domain.model` 패키지에 있지만 이동 타입을 import로 참조 — 정상 컴파일. 물리 이동은 Task 7.)

- [ ] **Step 7: 리뷰 + 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): 순수 position 값객체 6개를 com.kista.matching으로 이동

InfinitePosition/VrPosition/ReverseModePosition/BootstrapPosition/
PriceSnapshot/StrategyVrDetail → matching.domain.model. StrategyVrDetail의
gradientAt/poolLimitRateAt 램프 공식이 라이브·백테스트 공용이라 커널 소속.
StrategyVrDetailPort 반환타입만 갱신(포트는 trading 잔류).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KVvZaQVn4ZSe7ErXjTdEEs
EOF
)"
```

---

## Task 4: 커널 출력 Order→PlannedOrder + 파이프라인 + AccountBalance 분리 + stats 재배선 (원자적)

이 태스크는 타입 경계 refactor라 컴파일 단위가 나뉘지 않는다. 커널 계산 클래스는 **아직 `trading.domain.strategy`에 있는 채로** 출력 타입만 바꾼다(물리 이동은 Task 5).

**Files:**
- Modify: `trading/domain/strategy/{InfiniteStrategy,ReverseInfiniteStrategy,VrStrategy,PrivacyStrategy}.java` — `List<Order>` 반환 → `List<PlannedOrder>`, 내부 `Order.planned(...)` → `PlannedOrder.of(...)` (계산 로직 라인 불변)
- Modify: `trading/domain/strategy/CycleOrderStrategy.java` — nested `OrderPlan`의 `List<Order> orders` → `List<PlannedOrder> orders`; `canSkipOrderComputation(List<Order>, ...)` 시그니처는 `List<PlannedOrder>`? — 아니오, 아래 주의 참고
- Modify: `trading/domain/strategy/{InfiniteCycleOrderStrategy,PrivacyCycleOrderStrategy,VrCycleOrderStrategy}.java`
- Move+Split: `trading/domain/model/AccountBalance.java` → `matching/domain/model/AccountBalance.java` (순수화)
- Modify: `trading/application/service/{CycleOrderComputer,BuyOrderPriceCapper,TradingOrderPlanner,TradingOrderBudgetAllocator,TradingBuyCompetitionSimulator,StrategyOrderPlanBuilder,TradingService,ManualTradingService,TradingPreviewService,TradingReporter}.java`
- Modify: `admin/application/service/AdminTradeCorrectionService.java`
- Modify: `stats/domain/backtest/{BacktestEngine,FillSimulator}.java`
- Modify: `trading/domain/model/Order.java` — `planned(...)` 정적 팩토리 제거, `plan(Order,...)` 제거(→ `fromPlanned` 대체 완료 후)
- Test: 기존 테스트 다수 시그니처 반영 (`TradingServiceTest`, `BuyOrderPriceCapperTest`, `TradingOrderBudgetAllocatorTest`, `AccountBalanceTest`, `BacktestEngineTest`, strategy 계열 등)

**Interfaces:**
- Consumes: `PlannedOrder` (Task 2), `Order.fromPlanned`/`toPlanned` (Task 2), matching value objects (Task 3)
- Produces:
  - `matching.domain.model.AccountBalance(int holdings, BigDecimal avgPrice, BigDecimal usdDeposit)` — `holdings()`/`avgPrice()`/`usdDeposit()` getter
  - `static BigDecimal AccountBalance.buyTotal(List<PlannedOrder> orders)`
  - `boolean AccountBalance.hasSufficientDepositFor(List<PlannedOrder> orders, BigDecimal otherStrategyBuyTotal)`
  - `AccountBalance AccountBalance.applyExecutions(List<? extends Fill> executions)`
  - `interface AccountBalance.Fill { OrderDirection direction(); int quantity(); BigDecimal amountUsd(); }`
  - `CycleOrderStrategy.OrderPlan(InfinitePosition position, VrPosition vrPosition, List<PlannedOrder> orders)`
  - 모든 커널 `buildOrders`/`buildCappedBuyOrders` → `List<PlannedOrder>`
  - `TradingOrderPlanner.savePlannedOrders(List<PlannedOrder> templates, Account account, UUID strategyCycleId)`

- [ ] **Step 1: AccountBalance 분리 — matching 순수 record**

```bash
git mv src/main/java/com/kista/trading/domain/model/AccountBalance.java src/main/java/com/kista/matching/domain/model/AccountBalance.java
```
`AccountBalance.java` 재작성:
- `package com.kista.matching.domain.model;`
- import에서 `com.kista.broker.domain.model.Execution`·`Direction` **삭제**
- record 컴포넌트 `(int holdings, BigDecimal avgPrice, BigDecimal usdDeposit)` 유지
- `interface Fill { OrderDirection direction(); int quantity(); BigDecimal amountUsd(); }` — `Order.OrderDirection` → `OrderDirection`(같은 패키지)
- `Fill.of(Execution)` / `Fill.listOf(List<Execution>)` **삭제**
- `buyTotal(List<Order>)` → `buyTotal(List<PlannedOrder> orders)` — 본문 `o.price()`/`o.quantity()` 그대로
- `isOrderValid(...)` **삭제** (死 코드)
- `hasSufficientDepositFor(List<Order>, BigDecimal)` → `hasSufficientDepositFor(List<PlannedOrder>, BigDecimal)`
- `applyExecutions(List<? extends Fill>)` 유지 (순수 산술)
- private `sumQuantity`/`sumAmount` — `List<PlannedOrder>` 대상으로 시그니처 조정

- [ ] **Step 2: Execution→Fill 인라인 3곳**

`TradingReporter.java:61` 부근 — `AccountBalance.Fill.listOf(executions)` 를:
```java
// broker 체결 → 잔고 재계산용 Fill (matching이 broker를 참조하지 않도록 호출부에서 변환)
List<AccountBalance.Fill> fills = executions.stream()
        .map(e -> (AccountBalance.Fill) new AccountBalance.Fill() {
            @Override public OrderDirection direction() {
                return e.direction() == com.kista.broker.domain.model.Direction.BUY
                        ? OrderDirection.BUY : OrderDirection.SELL;
            }
            @Override public int quantity() { return e.quantity(); }
            @Override public BigDecimal amountUsd() { return e.amountUsd(); }
        })
        .toList();
balance = balance.applyExecutions(fills);
```
동일 패턴을 `AdminTradeCorrectionService.java:124`(단건 `List.of(...)`), `BacktestEngine.java:428`에 적용. 세 파일 모두 `com.kista.matching.domain.model.OrderDirection` import 추가. **공용 헬퍼 추출하지 않는다** — 세 파일이 서로 다른 모듈(trading/admin/stats)이라 공유 불가하고, 익명 클래스 3블록이 크로스모듈 헬퍼보다 총 복잡도가 낮다. 각 사이트에 인라인.

- [ ] **Step 3: 커널 계산 클래스 반환타입 전환**

`InfiniteStrategy.java` / `ReverseInfiniteStrategy.java` / `VrStrategy.java` / `PrivacyStrategy.java`:
- `import com.kista.trading.domain.model.Order;` → `import com.kista.matching.domain.model.PlannedOrder;`
- 메서드 시그니처 `List<Order>` → `List<PlannedOrder>` (public·private 전부)
- `Order.planned(...)` → `PlannedOrder.of(...)` (인자 동일), `Order.leg(...)` → `PlannedOrder.leg(...)`, `Order.UNKNOWN_LEG` → `PlannedOrder.UNKNOWN_LEG`
- static import `Order.OrderDirection.BUY` 등은 Task 1에서 이미 `matching.OrderDirection.BUY`로 치환됨 — 확인만
- `PrivacyStrategy.toTradingType`: `Order.OrderType.valueOf(type.name())` → `com.kista.matching.domain.model.OrderType.valueOf(type.name())` (privacy enum → matching enum 브릿지 유지)
- **계산식 라인(BigDecimal 연산, scale, 조건 분기)은 절대 수정 금지** — 타입 이름만

- [ ] **Step 4: CycleOrderStrategy 인터페이스 + 구현체**

`CycleOrderStrategy.java`:
- `import com.kista.trading.domain.model.Order;` → `PlannedOrder`
- `record OrderPlan(InfinitePosition position, VrPosition vrPosition, List<PlannedOrder> orders)`
- `canSkipOrderComputation(List<Order> existingOrders, Set<Order.OrderTiming> creatableTimings)` — `existingOrders`는 **DB에서 온 영속 Order**다(호출부 `TradingService`). `Order`를 matching이 못 보므로 `canSkipOrderComputation(List<PlannedOrder> existingOrders, Set<OrderTiming> creatableTimings)` 로 바꾸고 호출부에서 `existingOrders.stream().map(Order::toPlanned).toList()` 변환.
  - **강등 안전성 검증 (타입뿐 아니라 의미)**: 이 메서드 구현부(`InfiniteCycleOrderStrategy`의 `ExistingLegSlot` 판정)가 `status()`를 읽어 PLANNED vs PLACED/CANCELLED 점유를 구분하는지 먼저 확인한다. 슬롯 점유는 `timing + direction + orderLeg`(전부 `PlannedOrder`에 있음)여야 하고, `status()`를 본다면 강등이 그걸 소실시켜 타입체커가 못 잡는 슬롯 로직 파손(주문 누락)이 된다. `status()` 참조가 있으면: 호출부에서 이미 `PLANNED`만 조회하는지 확인(그렇다면 무해) 또는 점유 판정을 status 무관하게 조정. testing.md의 "partial concrete leg는 buildOrders 호출" / "AT_CLOSE BUY가 AT_OPEN SELL 복구를 막지 않음" 회귀 테스트로 검증.
- `PlanContext` 컴포넌트 `AccountBalance balance` — 이제 `matching.domain.model.AccountBalance` (같은 패키지 이동 후) / 현재 `trading.domain.strategy`에 있으므로 import 갱신

`InfiniteCycleOrderStrategy` / `PrivacyCycleOrderStrategy` / `VrCycleOrderStrategy`:
- `plan()` 내부 `List<Order>` → `List<PlannedOrder>`, `new OrderPlan(...)` 인자 타입 반영
- `minRequiredDeposit(...)` — `Order` 미참조 확인

- [ ] **Step 5: 파이프라인 서비스 전환 (trading.application.service)**

- `CycleOrderComputer.compute(...)` — 반환 `Optional<OrderPlan>` 그대로, 내부 `plan.orders()` 타입만 변경 전파
- `StrategyOrderPlanBuilder` — `PlanResult(OrderPlan plan, SkipReason)` 그대로
- `BuyOrderPriceCapper`:
  - `prepareForAllocation(List<Order> orders, ...)` → `prepareForAllocation(List<PlannedOrder> orders, ...)`; `order.withPrice(cap)` → `PlannedOrder.withPrice`; `infiniteStrategy.buildCappedBuyOrders(position, tradeDate, buyOrders, cap)` — `buyOrders`가 `List<PlannedOrder>`
  - `capIfNeeded`/`capIfNeededAtOpen`: `loadBuyOrders(...)`가 반환한 `List<Order>`(영속)를 `.stream().map(Order::toPlanned).toList()`로 강등 후 `infiniteStrategy.buildCappedBuyOrders`에 전달. `markCancelled(o.id())`는 여전히 원본 `List<Order>`에서 `id()` 읽음(강등 전 목록 유지)
  - `capVrIfNeeded`/`capVrIfNeededAtOpen`: `vrStrategy.buildCappedBuyOrders(vrPosition, ticker, today, cap)`는 `List<Order>` 파라미터 없음 — 반환만 `List<PlannedOrder>`
  - `capPrivacyIfNeeded`: `o.withPrice(cap)` in-memory — `o`가 강등된 `PlannedOrder`
  - 재저장 `orderPlanner.savePlannedOrders(corrected, account, cycleId)` — `corrected`가 `List<PlannedOrder>`
- `TradingOrderPlanner.savePlannedOrders(List<PlannedOrder> templates, Account account, UUID strategyCycleId)` — 본문 `Order.plan(o, account.id(), strategyCycleId)` → `Order.fromPlanned(o, account.id(), strategyCycleId)`
- `TradingOrderBudgetAllocator` — `Candidate` record의 `List<Order> orders` → `List<PlannedOrder> orders`; `AccountBalance.buyTotal(...)`·`hasSufficientDepositFor(...)` 호출 인자 타입 자동 반영; 승인/거절 분리 로직 그대로
- `TradingBuyCompetitionSimulator` — `AccountBalance.buyTotal(result.plan().orders())` 타입 반영
- `TradingService` — `orderComputer.compute(...)` → `priceCapper.prepareForAllocation(plan.orders(), ...)` → `allocator.allocate(...)` → `orderPlanner.savePlannedOrders(approved.orders(), ...)`; `validateConcreteOrderLegs(strategy, preparedOrders)`는 `List<PlannedOrder>` 대상 (`orderLeg()` 읽음 — PlannedOrder에 있음); `filterCreatableOrders(...)`도 `List<PlannedOrder>`; `buildCycleStateFromExistingOrders`는 `orderComputer.compute(...).map(OrderPlan::position)`만 쓰므로 영향 적음; `canSkipOrderComputation` 호출 시 `existingOrders.stream().map(Order::toPlanned).toList()`
- `ManualTradingService:103` — `savePlannedOrders(...)` 인자 타입
- `TradingPreviewService` — `plan.orders()`를 buy/sell 분리해 `NextOrdersPreview` DTO로; `NextOrdersResponse`/preview DTO가 `Order` 필드를 받으면 `PlannedOrder` 필드로 (web dto는 자체 record라 매핑 코드만)

- [ ] **Step 6: stats backtest 전환**

- `FillSimulator.java`: `import com.kista.trading.domain.model.Order;` → `PlannedOrder`; `fills(Order, ...)`·`simulate(List<Order>, ...)`·`fillsOhlc(Order, ...)` → `PlannedOrder`; `order.orderType()`/`price()`/`ticker()`/`direction()`/`quantity()`/`orderLeg()` 전부 `PlannedOrder`에 존재; `toDirection(OrderDirection)` → `broker.Direction` 변환 유지; `Execution.ofManualFill(...)` 호출 그대로
- `BacktestEngine.java`: `strategies.of(...).plan(ctx)` → `OrderPlan.orders()`가 `List<PlannedOrder>`; `VR_STRATEGY.buildCappedBuyOrders(...)` / `INFINITE_STRATEGY.buildCappedBuyOrders(position, tradeDate, buys, cap)` — `buys`가 `List<PlannedOrder>`; `simulate(pendingOrders, candle)`에 `List<PlannedOrder>` 전달; `balance.applyExecutions(...)`는 Step 2 인라인 Fill; `Order` import 제거

- [ ] **Step 7: Order 정적 팩토리 정리**

`Order.java` — `planned(...)` 오버로드 4개 삭제, `plan(Order template, UUID, UUID)` 삭제 (호출부가 `fromPlanned`로 전환 완료). `reorder`/`filledManual`/`withPlaced`/`withPrice`/`withQuantity`/`withFailed`/`withLeg`/`leg`/`UNKNOWN_LEG`는 Order에 **유지** (실행 경로·관리자 재주문·테스트가 사용). `grep -rn 'Order\.planned\|Order\.plan(' src/` 0건 확인.

- [ ] **Step 8: 테스트 동기화 + 컴파일**

기존 테스트 시그니처 반영 (`List<Order>` → `List<PlannedOrder>`, `Order.planned` → `PlannedOrder.of`):
- `TradingServiceTest`, `BuyOrderPriceCapperTest`, `TradingOrderBudgetAllocatorTest`, `TradingBuyCompetitionSimulatorTest`, `StrategyOrderPlanBuilderTest`, `TradingPreviewServiceTest`
- `AccountBalanceTest` — `Fill.of(Execution)` 케이스는 인라인 Fill mock으로, `buyTotal`/`hasSufficientDepositFor` 는 `PlannedOrder` 목록
- `InfiniteStrategyTypeTest`/`VrStrategyTypeTest`/`PrivacyStrategyTest`/`ReverseInfiniteStrategyTest` — `buildOrders(...)` 반환 단언을 `PlannedOrder` 필드로
- `BacktestEngineTest`, `FillSimulatorTest`
- `KisOrderApiTest` — `Order` 직접 생성자 사용, `orderType`/`direction` 인자 타입이 `matching.*`가 됨(테스트에서 import 추가)

Run:
```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E 'error:|BUILD'
./gradlew test --tests 'com.kista.trading.*' --tests 'com.kista.matching.*' --tests 'com.kista.stats.domain.*' --tests 'com.kista.stats.application.service.BacktestServiceTest' --tests 'com.kista.architecture.*' 2>&1 | grep -E 'FAILED|BUILD'
```
Expected: BUILD SUCCESSFUL. 실패 테스트는 시그니처 미반영 — 개별 수정.

- [ ] **Step 9: ddl-auto validate 확인**

Run: `./gradlew test --tests 'com.kista.trading.adapter.out.persistence.OrderPersistenceAdapterTest' 2>&1 | grep -E 'FAILED|BUILD|SchemaManagement'`
Expected: PASS — `OrderEntity`의 `@Enumerated(STRING)` 필드가 `matching.OrderType`/`OrderDirection`이어도 상수명 동일이라 스키마 검증 통과. (`OrderEntity`는 자체 `OrderType`/`Direction` enum을 쓰거나 `Order` enum을 재사용 — 현재 구조 확인 후 import만 갱신)

- [ ] **Step 10: 리뷰 + 커밋**

서브에이전트 리뷰어(model: **opus** — 이 태스크는 diff 크고 매매 파이프라인·money path라 리스크 최상): 계산식 라인 불변 여부, 강등/승격 경계 정확성(`markCancelled`가 강등 전 `id` 읽는지), `Fill` 인라인 3곳 동치성, allocator 승인/거절 순서 보존, `canSkipOrderComputation` 시그니처 전환 누락 확인.

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): 커널 출력을 Order→PlannedOrder로 전환 + AccountBalance 분리

CycleOrderStrategy.OrderPlan·모든 buildOrders가 PlannedOrder 방출.
저장 이전 파이프라인(CycleOrderComputer/BuyOrderPriceCapper/
TradingOrderBudgetAllocator/preview) 전부 PlannedOrder. 승격은
TradingOrderPlanner.savePlannedOrders 단일 지점(Order.fromPlanned).
AccountBalance는 matching 순수 record로 분리 — Execution→Fill 변환은
호출부 3곳 인라인, isOrderValid 死코드 삭제. stats BacktestEngine/
FillSimulator 재배선. 계산식 불변.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KVvZaQVn4ZSe7ErXjTdEEs
EOF
)"
```

---

## Task 5: 커널 계산 클래스 물리 이동 + NamedInterface + ArchUnit 가드

**Files:**
- Move: `trading/domain/strategy/{CycleOrderStrategy,CycleOrderStrategies,InfiniteStrategy,ReverseInfiniteStrategy,VrStrategy,PrivacyStrategy,PriceCapPolicy,InfiniteCycleOrderStrategy,PrivacyCycleOrderStrategy,VrCycleOrderStrategy}.java` → `matching/domain/strategy/`
- Create: `src/main/java/com/kista/matching/domain/strategy/package-info.java`
- Modify: `trading/domain/strategy/package-info.java` (주석만 — 리졸버 잔류 반영)
- Modify: `trading/application/service/CycleStrategyBeanConfig.java` (import)
- Modify: `stats/domain/backtest/BacktestEngine.java`, `stats/application/service/{BacktestService,AccountStatisticsService}.java` (import)
- Modify: `src/test/java/com/kista/architecture/HexagonalArchitectureTest.java` (규칙 추가)
- Modify: import 참조 파일 다수 (`trading.domain.strategy.*` → `matching.domain.strategy.*`, 리졸버 6개 제외)

**Interfaces:**
- Produces: `com.kista.matching.domain.strategy.{CycleOrderStrategy,CycleOrderStrategies,InfiniteStrategy,ReverseInfiniteStrategy,VrStrategy,PrivacyStrategy,PriceCapPolicy,InfiniteCycleOrderStrategy,PrivacyCycleOrderStrategy,VrCycleOrderStrategy}` — 시그니처 전부 동일, 패키지만.

- [ ] **Step 1: git mv 10개 파일**

```bash
cd /Users/phs/workspace/kista/kista-api
for f in CycleOrderStrategy CycleOrderStrategies InfiniteStrategy ReverseInfiniteStrategy VrStrategy PrivacyStrategy PriceCapPolicy InfiniteCycleOrderStrategy PrivacyCycleOrderStrategy VrCycleOrderStrategy; do
  git mv src/main/java/com/kista/trading/domain/strategy/$f.java src/main/java/com/kista/matching/domain/strategy/$f.java
done
```

- [ ] **Step 2: 이동 파일 package 선언 수정**

`package com.kista.trading.domain.strategy;` → `package com.kista.matching.domain.strategy;` (10개). 같은 패키지였던 리졸버(`StrategyCreationResolvers` 등)를 참조하는 곳 없음(확인) — 커널과 리졸버는 서로 독립.

- [ ] **Step 3: matching.domain.strategy package-info**

```java
// matching 커널의 공개 계약 일부 — CycleOrderStrategy 계열 주문생성 로직 + PriceCapPolicy.
// domain.model과 함께 "kernel" 이름으로 병합 공개. 전부 Spring 비의존 순수 계산 클래스 —
// 빈 배선은 com.kista.trading.application.service.CycleStrategyBeanConfig가 담당(BacktestEngine은 직접 new).
@org.springframework.modulith.NamedInterface("kernel")
package com.kista.matching.domain.strategy;
```

- [ ] **Step 4: 전역 import 치환**

`<scratchpad>/move_kernel.py`:
```python
import re, pathlib
TYPES = ["CycleOrderStrategy", "CycleOrderStrategies", "InfiniteStrategy", "ReverseInfiniteStrategy",
         "VrStrategy", "PrivacyStrategy", "PriceCapPolicy", "InfiniteCycleOrderStrategy",
         "PrivacyCycleOrderStrategy", "VrCycleOrderStrategy"]
for root in ["src/main/java", "src/test/java"]:
    for p in pathlib.Path(root).rglob("*.java"):
        if "matching/domain/strategy" in str(p):
            continue
        t = p.read_text(); orig = t
        for ty in TYPES:
            t = t.replace(f"import com.kista.trading.domain.strategy.{ty};",
                          f"import com.kista.matching.domain.strategy.{ty};")
        # nested type refs: CycleOrderStrategy.PlanContext / .OrderPlan / .PriceCapMode 는 import가 CycleOrderStrategy라 위 치환으로 해소
        if t != orig:
            p.write_text(t); print("  ", p)
```
Run + `grep -rn 'trading\.domain\.strategy\.\(CycleOrderStrateg\|InfiniteStrategy\|ReverseInfiniteStrategy\|VrStrategy\|PrivacyStrategy\|PriceCapPolicy\)' src/` (0건 기대 — 리졸버 `StrategyCreationResolver`는 `trading.domain.strategy` 유지라 매칭 안 됨)

- [ ] **Step 5: `CycleStrategyBeanConfig` import 갱신**

`InfiniteStrategy`/`VrStrategy`/`PrivacyStrategy`/`ReverseInfiniteStrategy`/`CycleOrderStrategy`/`CycleOrderStrategies`/`InfiniteCycleOrderStrategy`/`PrivacyCycleOrderStrategy`/`VrCycleOrderStrategy` import → `com.kista.matching.domain.strategy.*`. 리졸버 5개(`InfiniteCreationResolver` 등) + `StrategyCreationResolver(s)` import는 `com.kista.trading.domain.strategy.*` 유지. `@Bean` 본문 `new ...()` 무변경.

- [ ] **Step 6: HexagonalArchitectureTest 규칙 추가**

`HexagonalArchitectureTest.java`의 `platform_must_not_depend_on_other_modules` 옆에:
```java
@Test
@DisplayName("matching 커널은 sharedkernel·privacy 외 다른 모듈에 의존하지 않는다")
void matching_must_not_depend_on_other_modules() {
    noClasses().that().resideInAPackage("com.kista.matching..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.kista.finance..", "com.kista.notify..", "com.kista.broker..",
                    "com.kista.trading..", "com.kista.market..", "com.kista.stats..",
                    "com.kista.admin..", "com.kista.user..", "com.kista.account..",
                    "com.kista.web..", "com.kista.platform..", "com.kista.common..")
            .check(CLASSES);
}
```
(허용: `com.kista.sharedkernel..`, `com.kista.privacy..`, JDK. `sharedkernel_must_not_depend_on_other_modules` 패턴 미러 — 단 privacy 허용이 차이.)

- [ ] **Step 7: 컴파일 + verify() + 새 규칙**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E 'error:|BUILD'
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | grep -E 'FAILED|PASSED|BUILD'
```
Expected: `ModulithArchitectureTest.verifyModularStructure` PASS — 생성된 `build/spring-modulith-docs/` PlantUML에서 `matching` outbound = `sharedkernel`, `privacy`만인지 육안 확인. `HexagonalArchitectureTest.matching_must_not_depend_on_other_modules` PASS.

만약 `verify()`가 지연 순환을 보고하면(과거 이전 전례 2회) — 보고된 엣지를 분석, 대개 커널이 아직 참조하는 trading 타입 1~2개. Task 4에서 놓친 것이므로 해당 타입을 matching으로 옮기거나 `PlanContext`로 주입 전환.

- [ ] **Step 8: 좁은 테스트 + 리뷰 + 커밋**

Run: `./gradlew test --tests 'com.kista.matching.*' --tests 'com.kista.trading.application.service.*' --tests 'com.kista.stats.domain.*' 2>&1 | grep -E 'FAILED|BUILD'`

리뷰어(model: sonnet): package 선언 누락, 리졸버 오이동, `CycleStrategyBeanConfig` 배선 정합성.

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): 커널 계산 클래스 10개를 com.kista.matching으로 물리 이동

CycleOrderStrategy 계열 + Infinite/ReverseInfinite/Vr/PrivacyStrategy +
PriceCapPolicy → matching.domain.strategy("kernel" NamedInterface).
생성 리졸버 6개는 trading.domain.strategy 잔류. CycleStrategyBeanConfig
배선 import 갱신. HexagonalArchitectureTest에 matching outbound 가드
(sharedkernel·privacy만 허용) 추가.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KVvZaQVn4ZSe7ErXjTdEEs
EOF
)"
```

---

## Task 6: PlanContext에서 Strategy 제거

**Files:**
- Modify: `matching/domain/strategy/CycleOrderStrategy.java` (`PlanContext` 컴포넌트)
- Modify: `matching/domain/strategy/CycleOrderStrategies.java` (`of(Strategy)` 삭제)
- Modify: `matching/domain/strategy/{InfiniteCycleOrderStrategy,VrCycleOrderStrategy}.java` (`ctx.strategy().ticker()` → `ctx.ticker()`)
- Modify: `trading/application/service/{CycleOrderComputer,StrategyOrderPlanBuilder}.java` (`of(strategy)` → `of(strategy.type())`, `PlanContext` 조립)
- Modify: `stats/domain/backtest/BacktestEngine.java` (`syntheticStrategy` 제거, `PlanContext` 조립)
- Modify: `stats/domain/model/backtest/BacktestCommand.java` (미사용 `Strategy` import 제거)
- Test: `BacktestEngineTest`, `CycleOrderComputerTest`, strategy 계열

**Interfaces:**
- Produces:
  - `CycleOrderStrategy.PlanContext(AccountBalance balance, StrategyType type, StrategyTicker ticker, LocalDate tradeDate, String label, InfiniteInputs infinite, PrivacyInputs privacy, VrInputs vr)` — `Strategy strategy` 제거, `StrategyType type` + `StrategyTicker ticker` 추가
  - `CycleOrderStrategies.of(StrategyType)` 만 (of(Strategy) 없음)

- [ ] **Step 1: PlanContext 시그니처 변경**

`CycleOrderStrategy.java` — `record PlanContext(..., Strategy strategy, ...)` → `..., StrategyType type, StrategyTicker ticker, ...`. `import com.kista.trading.domain.model.Strategy;` 삭제, `import com.kista.sharedkernel.StrategyType;` + `StrategyTicker` 추가.

- [ ] **Step 2: of(Strategy) 삭제 + 커널 구현체 ctx.ticker()**

`CycleOrderStrategies.java` — `public CycleOrderStrategy of(Strategy strategy) { return of(strategy.type()); }` 삭제, `import ...Strategy;` 삭제.
`InfiniteCycleOrderStrategy.java:134,137,151` / `VrCycleOrderStrategy.java:64` — `ctx.strategy().ticker()` → `ctx.ticker()`, `ctx.strategy().ticker().name()` → `ctx.ticker().name()`.

- [ ] **Step 3: 호출부 전환**

`CycleOrderComputer.java:97` — `cycleStrategies.of(strategy)` → `cycleStrategies.of(strategy.type())`; `new CycleOrderStrategy.PlanContext(balance, strategy, tradeDate, ...)` → `new CycleOrderStrategy.PlanContext(balance, strategy.type(), strategy.ticker(), tradeDate, ...)`.
`StrategyOrderPlanBuilder.java:59` — `cycleOrderStrategies.of(strategy)` → `.of(strategy.type())`.
`BacktestEngine.java` — `syntheticStrategy(command)` 메서드 삭제; `PlanContext` 조립 시 `command.type()` + `command.ticker()` 직접 전달; `import ...Strategy;` 제거.
`BacktestCommand.java:3` — `import com.kista.trading.domain.model.Strategy;` 삭제 (미사용).

- [ ] **Step 4: 컴파일 + verify()**

```bash
grep -rn 'PlanContext' src/main/java | grep -i strategy   # ctx.strategy() 잔여 0건
./gradlew compileJava compileTestJava 2>&1 | grep -E 'error:|BUILD'
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | grep -E 'FAILED|BUILD'
```
Expected: BUILD SUCCESSFUL. `verify()` — `matching` outbound에서 `trading` 완전 소멸 확인(이 태스크가 마지막 `trading.domain.model.Strategy` 참조 제거).

- [ ] **Step 5: 테스트 + 리뷰 + 커밋**

Run: `./gradlew test --tests 'com.kista.matching.*' --tests 'com.kista.stats.domain.backtest.*' --tests 'com.kista.trading.application.service.CycleOrderComputerTest' 2>&1 | grep -E 'FAILED|BUILD'`

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): PlanContext에서 Strategy 애그리게이트 제거

커널은 ticker만 쓰는데 Strategy 전체를 요구하던 결합 해소. PlanContext가
StrategyType+StrategyTicker 직접 보유, CycleOrderStrategies.of(Strategy)
오버로드 삭제. BacktestEngine.syntheticStrategy 제거. matching이
trading.domain.model.Strategy를 참조하는 곳 0.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KVvZaQVn4ZSe7ErXjTdEEs
EOF
)"
```

---

## Task 7: 테스트 파일 이동 + 문서 갱신

**Files:**
- Move: `src/test/java/com/kista/trading/domain/strategy/{InfiniteStrategyTypeTest,ReverseInfiniteStrategyTest,PrivacyStrategyTest,VrStrategyTypeTest,CycleOrderStrategyCapabilityTest}.java` → `src/test/java/com/kista/matching/domain/strategy/`
- Move: `src/test/java/com/kista/trading/domain/model/{InfinitePositionTest, VrPosition·ReverseModePosition·StrategyVrDetail 관련 테스트}.java` → `src/test/java/com/kista/matching/domain/model/`
- Modify: `CLAUDE.md`, `AGENTS.md`, `docs/agents/architecture.md`, `docs/agents/constraints.md`

**Interfaces:** 없음 (테스트·문서 전용)

- [ ] **Step 1: 테스트 파일 이동 대상 식별**

기준: **테스트 대상(SUT) 프로덕션 타입이 전부 `com.kista.matching`에 있을 때만** 이동. Task 4 이후 거의 모든 trading 테스트가 `PlannedOrder`를 import하므로 import 유무는 기준 아님. 애매하면 이동 안 함 — `com.kista.trading.*` 테스트가 matching 타입을 import해도 컴파일·실행 정상, 이동 이득 없음.

- 확실한 이동: `InfiniteStrategyTypeTest` `ReverseInfiniteStrategyTest` `PrivacyStrategyTest` `VrStrategyTypeTest` (SUT = `matching.domain.strategy.*Strategy`), `InfinitePositionTest` 및 Vr/ReverseMode/StrategyVrDetail position 테스트 (SUT = `matching.domain.model.*`)
- 확인 필요: `CycleOrderStrategyCapabilityTest` — capability 플래그가 trading 서비스에서 소비되지만 SUT가 `CycleOrderStrategy` 구현체면 이동. `AccountBalanceTest` — SUT가 matching `AccountBalance`면 이동하되 `Fill` mock은 익명 클래스로 (broker `Execution` 직접 참조 제거)
- 이동 안 함: `TradingServiceTest` `BuyOrderPriceCapperTest` `TradingOrderBudgetAllocatorTest` 등 (SUT = trading application service)

- [ ] **Step 2: git mv + package 선언**

```bash
git mv src/test/java/com/kista/trading/domain/strategy/InfiniteStrategyTypeTest.java src/test/java/com/kista/matching/domain/strategy/
# ... (식별된 파일 전부)
```
각 파일 `package com.kista.trading.domain.strategy;` → `package com.kista.matching.domain.strategy;` (model도 동일). 같은 패키지 접근(package-private)에 의존하던 단언이 있으면 확인 — 이동으로 커널과 같은 패키지가 되어 오히려 정상화.

- [ ] **Step 3: 컴파일 + 이동 테스트 실행**

```bash
./gradlew compileTestJava 2>&1 | grep -E 'error:|BUILD'
./gradlew test --tests 'com.kista.matching.*' 2>&1 | grep -E 'FAILED|PASSED|BUILD'
```
Expected: 이동한 테스트 전부 PASS.

- [ ] **Step 4: architecture.md 갱신**

`docs/agents/architecture.md`:
- 패키지 트리 최상단 근처에 `com.kista.matching/` 절 신설 — "주문생성 커널 모듈(CLOSED). `domain/model`(주문 어휘 enum·PlannedOrder·position 값객체·AccountBalance) + `domain/strategy`(CycleOrderStrategy 계열·PriceCapPolicy) 두 패키지가 `"kernel"` NamedInterface로 병합 공개. outbound 엣지 sharedkernel·privacy뿐(`HexagonalArchitectureTest.matching_must_not_depend_on_other_modules` 강제). Spring 비의존 — 배선은 `trading` `CycleStrategyBeanConfig`, `BacktestEngine`은 직접 `new`."
- `com.kista.trading/` 절 수정 — `domain/strategy`가 생성 리졸버(`StrategyCreationResolver(s)`/`*CreationResolver`/`StrategyCreationRequest`)만 남음, `domain/model`에서 position 값객체·`AccountBalance`·주문 어휘 enum 이탈(→ matching), `Order`는 `matching.*` enum 참조 + `OrderStatus` nested 유지 + `fromPlanned`/`toPlanned`, `OrderPlan`이 `List<PlannedOrder>`
- `com.kista.stats/` 절 — `BacktestEngine`이 `com.kista.matching` 커널 소비(trading 아님), `syntheticStrategy` 제거
- `### Spring Modulith 모듈 구성` — "12개 모듈" → "13개 모듈(finance/notify/broker/trading/matching/market/privacy/stats/admin/user/account/platform/web)"
- `CycleOrderStrategy Capability 패턴` 절 — SSOT 위치를 `com.kista.matching.domain.strategy`로 갱신

- [ ] **Step 5: constraints.md 갱신**

`docs/agents/constraints.md`:
- `### Spring Modulith 이전 중 신규 파일 배치` — matching 항목 추가: "주문생성 알고리즘 커널은 `com.kista.matching`으로 이전됨 — 신규 전략 계산 클래스·position 값객체·주문 어휘 enum·`PlannedOrder`는 `matching.domain.{model,strategy}`에 추가, `"kernel"` NamedInterface 공개. 전략 *등록 정책* 리졸버는 trading 잔류. 커널은 `com.kista.sharedkernel` + `com.kista.privacy` 외 의존 금지."
- `### 모듈 경계 own-type` 원장 — `Order.OrderType`/`OrderDirection` 항목 주석에 "#1로 커널 어휘가 `com.kista.matching.domain.model`로 이동(`Order`는 이를 참조). privacy `PrivacyOrderType/Direction`·broker `Direction/OrderType` sharedkernel 승격 재판정은 #2 — advisor 판단상 matching 잔류가 영구 정답일 수 있음(커널 출력 알파벳 ≠ sharedkernel 정체성 어휘)."
- `### 매매 공식` / `### VR 공식` 절 — 공식 SSOT 클래스 경로를 `com.kista.matching.domain.strategy`로 갱신(있으면)
- `AccountBalance` 언급 절(잔고검증 토글 등) — `com.kista.matching.domain.model.AccountBalance`로 경로 갱신, "순수 잔고 record + `buyTotal(List<PlannedOrder>)`/`hasSufficientDepositFor`/`applyExecutions`. `Execution→Fill` 변환은 호출부(TradingReporter/AdminTradeCorrectionService/BacktestEngine)."

- [ ] **Step 6: CLAUDE.md + AGENTS.md**

`CLAUDE.md` 상단 아키텍처 요약 문장 — 모듈 목록에 `matching` 추가, "12개 애그리게이트" → "13개". `AGENTS.md`도 동일 변경 반영(Codex 진입점 — 같은 요약 문구면 동일 수정).

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
docs(modulith): com.kista.matching 커널 추출 문서 반영 + 테스트 이동

architecture.md에 matching 절 신설·trading/stats 절 갱신(13개 모듈),
constraints.md 신규 파일 배치·own-type 원장·AccountBalance 경로 갱신,
CLAUDE.md/AGENTS.md 모듈 수. 커널 단위테스트를 com.kista.matching.*로 이동.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KVvZaQVn4ZSe7ErXjTdEEs
EOF
)"
```

---

## Task 8: 최종 전체 검증

**Files:** 없음 (검증만, 필요 시 수정)

- [ ] **Step 1: 전체 테스트 스위트**

Run: `./gradlew clean test 2>&1 | grep -E 'FAILED|BUILD|Tests:'`
Expected: `BUILD SUCCESSFUL`. 실패 시 XML로 진단: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`

- [ ] **Step 2: verify() 산출물 육안 확인**

Run: `./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest' && cat build/spring-modulith-docs/*matching* 2>/dev/null; ls build/spring-modulith-docs/`
확인: `matching` 모듈 outbound 의존이 `sharedkernel`, `privacy` **둘뿐**. `trading`·`broker`·`stats` 등 없음.

- [ ] **Step 3: 빌드 산출물**

Run: `./gradlew bootJar 2>&1 | grep -E 'BUILD'`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 스펙 수용 기준 대조**

`docs/superpowers/specs/2026-09-09-matching-kernel-extraction-design.md` §수용 기준 4개 항목 체크:
1. `verify()` GREEN + matching outbound = {sharedkernel, privacy} — Step 2
2. `HexagonalArchitectureTest` GREEN (`matching_must_not_depend_on_other_modules` 포함) — Step 1
3. `./gradlew test` GREEN + 이동한 공식 단위테스트 — Step 1
4. `ddl-auto: validate` 통과 (persistence 슬라이스) — Step 1에 포함

- [ ] **Step 5: 최종 리뷰 결정**

전역 CLAUDE.md 규칙 — 태스크별 리뷰를 전부 거쳤고 순차 작업이므로 **최종 전체 브랜치 리뷰는 생략을 기본**으로 사용자에게 확인. Task 4가 대형 diff·money path였다는 점만 별도 고지("Task 4 opus 리뷰 완료, 전체 재리뷰 필요 여부").

- [ ] **Step 6: finishing-a-development-branch 스킬로 통합**

`superpowers:finishing-a-development-branch` 스킬 호출 — main 병합 방식 결정.

---

## Self-Review

**Spec coverage:**
- §모듈 형태 (CLOSED, domain-only, "kernel" 단일 NamedInterface, application 미신설) → Task 1(골격), Task 5(strategy package-info) ✓
- §matching으로 이동 (커널 11클래스·값객체 6·enum 3·PlannedOrder·AccountBalance 분리분) → Task 1·2·3·4·5 ✓
- §trading 잔류 (Order+OrderStatus, StrategyInfiniteDetail, 리졸버 5, 포트, CycleStrategyBeanConfig) → Task 1 Step 3, Task 3(포트), Task 5(리졸버 명시) ✓
- §PlannedOrder (8필드, of/leg/UNKNOWN_LEG, withPrice, from(Order)) → Task 2. **스펙 수정**: `PlannedOrder.from(Order)`는 matching→trading 금지라 불가 → `Order.toPlanned()` 인스턴스 메서드로 강등(Task 2 Step 4). 계획서가 스펙 대비 이 지점 정정.
- §AccountBalance 분리 (순수 record + buyTotal/hasSufficientDepositFor + Fill + applyExecutions, Fill.of(Execution) 3곳 인라인, isOrderValid 삭제) → Task 4 Step 1·2 ✓
- §PlanContext (Strategy → type+ticker, of(Strategy) 삭제, syntheticStrategy 제거) → Task 6 ✓
- §stats 측 변경 (BacktestEngine/FillSimulator/BacktestService/AccountStatisticsService import, BacktestCommand Strategy 제거) → Task 4 Step 6, Task 5 Step 4, Task 6 Step 3 ✓
- §비목표 4개 → 계획서에 계산식 불변 제약(Global Constraints) + enum 브릿지 유지(Task 4 Step 3) + FidaPlannedOrder 미변경(대상 파일에 없음) ✓
- §수용 기준 4개 → Task 5 Step 6(ArchUnit), Task 8 ✓
- §문서 갱신 (CLAUDE/architecture/constraints/AGENTS) → Task 7 ✓

**Placeholder scan:** "similar to Task N" 없음, 각 스텝 코드/명령 구체. Task 4 Step 5는 파일별 변경을 나열식으로 기술(코드 블록 대신) — refactor 특성상 "이 타입을 저 타입으로" 수준이 적절, 각 파일 현재 라인 번호 명시.

**Type consistency:**
- `PlannedOrder` 필드 순서 `(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, price)` — Task 2 정의와 Task 4 사용 일치. (주의: 현 `Order`는 `(id, accountId, strategyCycleId, tradeDate, ticker, orderType, timing, direction, orderLeg, quantity, price, status, ...)` 순서 — `PlannedOrder.of`/`Order.fromPlanned` 구현 시 필드 매핑 정확히.)
- `AccountBalance.buyTotal(List<PlannedOrder>)` — Task 4 정의, Task 4 내 allocator/simulator 호출 일치.
- `Order.fromPlanned(PlannedOrder, UUID, UUID)` / `Order.toPlanned()` — Task 2 정의, Task 4 `TradingOrderPlanner`/`BuyOrderPriceCapper` 사용 일치.
- `CycleOrderStrategy.OrderPlan(InfinitePosition, VrPosition, List<PlannedOrder>)` — Task 4 정의, `CycleOrderComputer`/`BacktestEngine` 사용 일치.
- `PlanContext(AccountBalance, StrategyType, StrategyTicker, LocalDate, String, InfiniteInputs, PrivacyInputs, VrInputs)` — Task 6 정의, `CycleOrderComputer`/`BacktestEngine` 조립 일치.
- `matching_must_not_depend_on_other_modules` — Task 5 정의, Task 8 Step 4 참조 일치.

**정정 사항 1건**: 스펙 §PlannedOrder의 `PlannedOrder.from(Order)`는 모듈 방향(matching→trading 금지) 위반 → 계획서는 `Order.toPlanned()` 인스턴스 메서드(trading 소유)로 강등을 구현한다. 스펙 문서도 다음 커밋에서 이 문구를 정정할 것(Task 2에 포함).
