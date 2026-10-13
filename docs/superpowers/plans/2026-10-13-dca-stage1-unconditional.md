# DCA 단계 ① — 무조건 DCA 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 조건 없는 DCA 전략을 처음부터 끝까지 동작하게 만든다. 대상은 등록·수정, 주기 판정, 개장/마감 배치에서의 주문 생성·접수, 잔고 축소, 회차 이력, skip 알림, 조회·메타 API다.

**Architecture:**
- DCA는 신규 `CycleOrderStrategy` 구현체(`DcaCycleOrderStrategy`, matching 커널)와 trading의 `DcaRoundPlanner`로 기존 `TradingService` 배치에 합류한다. 별도 배치는 만들지 않는다.
- 판정은 순수 함수(`DcaSchedule`, `DcaCycleOrderStrategy.decide`)로 하고, I/O(캘린더·일봉·잔고·이력 기록)는 trading 서비스가 한다.
- 개장 배치를 22:00 KST로 당기고, KIS DCA MOO는 개장 전에 접수한다.

**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith, JPA + Flyway(PostgreSQL), JUnit 5 + Mockito + AssertJ, ArchUnit

**Spec:** `docs/superpowers/specs/2026-10-13-dca-strategy-design.md` — 실행자는 이 계획과 spec을 함께 읽는다. 단계 ②(지표 조건)와 ③(공포탐욕지수)는 이 계획 범위가 아니다.

## Global Constraints

- 조건(`conditions`)이 비어 있지 않은 등록·수정 요청은 400으로 거부한다. 단계 ①은 조건을 지원하지 않는다(spec 11).
- 4-space 들여쓰기, 불변 값은 record, 생성자 주입. 주석은 `//` 인라인만 쓴다(Javadoc·블록 주석 금지). 신규 필드에는 `// 역할` 주석을 단다.
- enum DB 컬럼은 VARCHAR(20), `@Enumerated(EnumType.STRING)`만 쓴다. `CREATE TYPE`은 금지.
- 신규 마이그레이션은 `trading-core/src/main/resources/db/migration-trading/V6__dca_strategy.sql` 한 파일이다. 기존 V1~V5는 수정 금지.
- 엔티티에는 `@Table(schema = "trading")`을 명시한다. 컬럼 순서는 `pk, fk, 비즈니스…, created_at, updated_at`.
- UUID PK 엔티티는 `@UuidGenerator(style = UuidGenerator.Style.VERSION_7)`를 쓴다(`@GeneratedValue` 금지). 도메인→엔티티 저장 시 신규 행의 id는 null이다.
- `UsTradeDates`는 allowlist 4곳(`KisTradingApi`, `KisPriceApi`, `TossPriceApi`, `MarketCalendarPersistenceAdapter`) 밖에서 쓰지 않는다.
- `@Async`·`CompletableFuture` 금지(Virtual Thread). `@Transactional` 안에서 외부 HTTP 호출 금지.
- 상수명 변경 금지: 기존 enum 상수(`StrategyType`, `StrategyTicker`, `OrderType`, `ErrorCode`)는 추가만 한다.
- 커밋 author는 `narafu <narafu@kakao.com>`. 메시지는 한글 Conventional Commit에 명령형으로 쓴다. push 금지.
- 테스트는 서브프로젝트를 지정해 좁혀 돌린다(`./gradlew :trading-core:test --tests '…'`, `./gradlew :test --tests '…'`). 전체 스위트는 마지막 태스크에서 1회만 돌린다.
- 테스트 DB를 쓰려면 `docker compose up -d postgres`가 필요하다.

## Review Focus

1. **배치 재개·이중 진입**: 같은 거래일에 개장(또는 마감) 배치가 재개돼도 DCA 주문·이력·skip 알림이 한 번만 생겨야 한다. Task 6의 `DcaRoundPlannerTest`가 "이력 행 존재 시 skip"과 "주문은 있는데 이력이 없으면 ORDERED 복구"를 고정한다.
2. **월말·휴장 경계**: 31일 지정에 30일인 달, 말일이 휴장인 달, 주 전체가 휴장인 주에서 실행일이 정확히 하루여야 한다. Task 3의 `DcaScheduleTest`가 고정한다.
3. **예산 부족 시 축소가 캡가 기준인지**: 예수금 1000, 전일종가 100, 금액 2000이면 캡가 105 기준으로 9주가 돼야 한다(10주면 APBK0988). Task 6의 `DcaCycleOrderStrategyTest.fitBuysToBudget_shrinks_by_cap_price`가 고정한다.
4. **Toss 매수 0.01 버그 회귀**: Toss의 BUY MOC/MOO는 캡가로 나가고, SELL만 0.01이어야 한다. Task 2의 `TossOrderApiTest`가 고정한다.
5. **INFINITE 오염**: UPRO·QLD가 INFINITE capability와 INFINITE 런타임 정책 기본 허용 티커에 섞이지 않아야 한다. Task 1의 `StrategyTypeCapabilityTest`와 `TradingPolicySettingsTest`가 고정한다.

---

### Task 1: 공용 어휘와 런타임 정책 (DCA 타입·티커·enum·정책·리졸버)

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyType.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyTicker.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyCapability.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/OrderDirection.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/Broker.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/TradingPolicySettings.java`
- Create: `shared/src/main/java/com/kista/shared/kernel/DcaFrequency.java`
- Create: `shared/src/main/java/com/kista/shared/kernel/DcaOperator.java`
- Create: `shared/src/main/java/com/kista/shared/kernel/DcaIndicator.java`
- Create: `shared/src/main/java/com/kista/shared/kernel/DcaSkipReason.java`
- Modify: `src/main/java/com/kista/api/admin/adapter/in/web/dto/AdminSettingsRequest.java:80` (switch)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/kis/KisExchangeRegistry.java:16-21`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/strategy/DcaCreationResolver.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/support/CycleStrategyBeanConfig.java` (`vrCreationResolver()` 옆에 빈 추가)
- Modify: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/StrategyServiceTest.java:201` (StrategyType switch에 DCA 분기)
- Test: `shared/src/test/java/com/kista/shared/kernel/StrategyTypeCapabilityTest.java` (없으면 생성)
- Test: `shared/src/test/java/com/kista/shared/kernel/TradingPolicySettingsTest.java` (없으면 생성)

**Interfaces:**
- Produces:
  - `StrategyType.DCA`, `StrategyTicker.UPRO`, `StrategyTicker.QLD`
  - `StrategyCapability(availableTickers, tickerFixed, requiresPrivacyBase, supportsReverseMode, divisionCounts, orderTypes: List<OrderType>, directions: List<OrderDirection>, frequencies: List<DcaFrequency>, conditionIndicators: List<DcaIndicator>)`
  - `DcaFrequency{DAILY,WEEKLY,MONTHLY}.label()`
  - `DcaOperator{LTE,LT,GTE,GT}.label()`, `DcaOperator.test(BigDecimal left, BigDecimal right)`
  - `DcaIndicator{AVG_PRICE,SMA,RSI,FEAR_GREED}.label()`, `.description()`, `DcaIndicator.SMA_PERIODS`
  - `DcaSkipReason{DATA_UNAVAILABLE,CONDITION_NOT_MET,NO_HOLDINGS,INSUFFICIENT_BALANCE,BELOW_ONE_SHARE}.label()`
  - `OrderDirection.label()`
  - `Broker.supportsOpenAuctionOrders()` — spec 5.6의 "BrokerCapabilitiesPort 메서드" 대신 broker enum 메서드로 둔다. 판정은 정적 사실이고 소비처가 trading 한 곳이라 포트를 늘릴 이유가 없다. spec도 같은 커밋에서 이 내용으로 고친다.
  - DCA 생성 리졸버: `DcaCreationResolver`

- [ ] **Step 1: 실패하는 테스트 작성**

`shared/src/test/java/com/kista/shared/kernel/StrategyTypeCapabilityTest.java`:

```java
package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyTypeCapabilityTest {

    @Test
    void infinite_does_not_include_dca_only_tickers() {
        assertThat(StrategyType.INFINITE.capability().availableTickers())
                .containsExactly(StrategyTicker.MAGX, StrategyTicker.USD, StrategyTicker.TQQQ, StrategyTicker.SOXL);
    }

    @Test
    void dca_capability_exposes_dca_choices() {
        StrategyCapability dca = StrategyType.DCA.capability();
        assertThat(dca.availableTickers()).containsExactly(StrategyTicker.UPRO, StrategyTicker.QLD);
        assertThat(dca.tickerFixed()).isFalse();
        assertThat(dca.divisionCounts()).isEmpty();
        assertThat(dca.orderTypes()).containsExactly(OrderType.MOO, OrderType.MOC);
        assertThat(dca.directions()).containsExactly(OrderDirection.BUY, OrderDirection.SELL);
        assertThat(dca.frequencies()).containsExactly(DcaFrequency.values());
        assertThat(dca.conditionIndicators()).containsExactly(DcaIndicator.values());
    }

    @Test
    void non_dca_types_have_empty_dca_choices() {
        for (StrategyType type : List.of(StrategyType.INFINITE, StrategyType.PRIVACY, StrategyType.VR)) {
            StrategyCapability c = type.capability();
            assertThat(c.orderTypes()).isEmpty();
            assertThat(c.directions()).isEmpty();
            assertThat(c.frequencies()).isEmpty();
            assertThat(c.conditionIndicators()).isEmpty();
        }
    }

    @Test
    void operator_compares_by_value() {
        assertThat(DcaOperator.LTE.test(new java.math.BigDecimal("10.0"), new java.math.BigDecimal("10"))).isTrue();
        assertThat(DcaOperator.LT.test(new java.math.BigDecimal("10"), new java.math.BigDecimal("10"))).isFalse();
        assertThat(DcaOperator.GTE.test(new java.math.BigDecimal("10"), new java.math.BigDecimal("10.00"))).isTrue();
        assertThat(DcaOperator.GT.test(new java.math.BigDecimal("11"), new java.math.BigDecimal("10"))).isTrue();
    }
}
```

`OrderType.MOO`는 Task 2에서 추가된다. 그래서 Task 1의 테스트와 capability에서는 `OrderType.MOO`를 아직 참조할 수 없다. **Task 1과 Task 2는 한 커밋으로 묶어 같은 서브에이전트가 연속 수행한다.** Task 1 Step 3에서 `OrderType.MOO`를 함께 추가하고, 그 때문에 깨지는 exhaustive switch 수정은 Task 2 Step 3에서 끝낸 뒤 테스트를 돌린다.

`shared/src/test/java/com/kista/shared/kernel/TradingPolicySettingsTest.java`:

```java
package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingPolicySettingsTest {

    @Test
    void defaults_keep_dca_disabled_with_dca_tickers() {
        StrategyCreationSettings dca = TradingPolicySettings.defaults().strategy(StrategyType.DCA);
        assertThat(dca.enabled()).isFalse();
        assertThat(dca.ticker().allowedValues()).containsExactly(StrategyTicker.UPRO, StrategyTicker.QLD);
        assertThat(dca.ticker().defaultValue()).isEqualTo(StrategyTicker.UPRO);
        assertThat(dca.divisionCount()).isNull();
    }

    @Test
    void defaults_keep_infinite_tickers_explicit() {
        assertThat(TradingPolicySettings.defaults().strategy(StrategyType.INFINITE).ticker().allowedValues())
                .containsExactly(StrategyTicker.MAGX, StrategyTicker.USD, StrategyTicker.TQQQ, StrategyTicker.SOXL);
    }

    @Test
    void rejects_ticker_outside_capability() {
        TradingPolicySettings defaults = TradingPolicySettings.defaults();
        Map<StrategyType, StrategyCreationSettings> strategies = new EnumMap<>(defaults.strategies());
        StrategyCreationSettings infinite = strategies.get(StrategyType.INFINITE);
        strategies.put(StrategyType.INFINITE, new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.SOXL, StrategyTicker.UPRO), StrategyTicker.SOXL),
                infinite.divisionCount(), null, null, null));
        assertThatThrownBy(() -> new TradingPolicySettings(defaults.brokers(), strategies))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INFINITE");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :shared:test --tests 'com.kista.shared.kernel.StrategyTypeCapabilityTest' --tests 'com.kista.shared.kernel.TradingPolicySettingsTest'`
Expected: 컴파일 실패(`StrategyType.DCA`, `DcaFrequency` 등 없음)

- [ ] **Step 3: 어휘 구현**

`DcaFrequency.java`:

```java
package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// DCA 실행 주기 — strategy_dca_version.frequency 컬럼 상수명과 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaFrequency {
    DAILY("매일"),   // 매 미국 거래일
    WEEKLY("매주"),  // 지정 요일(같은 주 안 이월)
    MONTHLY("매월"); // 지정 일자 또는 말일(같은 달 안 이월)

    private final String label; // 한국어 표시 이름
}
```

`DcaOperator.java`:

```java
package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

// DCA 조건 비교 연산자 — strategy_dca_condition.operator 상수명과 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaOperator {
    LTE("이하"),
    LT("미만"),
    GTE("이상"),
    GT("초과");

    private final String label; // 한국어 표시 이름

    // left op right — BigDecimal scale 차이는 compareTo로 무시
    public boolean test(BigDecimal left, BigDecimal right) {
        int cmp = left.compareTo(right);
        return switch (this) {
            case LTE -> cmp <= 0;
            case LT -> cmp < 0;
            case GTE -> cmp >= 0;
            case GT -> cmp > 0;
        };
    }
}
```

`DcaIndicator.java`:

```java
package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

// DCA 조건 지표 — strategy_dca_condition.indicator 상수명과 직결(변경 금지). 평가 구현은 단계 ②·③
@Getter
@RequiredArgsConstructor
public enum DcaIndicator {
    AVG_PRICE("평단가", "전일 종가 vs 평단가 × (1 + n%)"),
    SMA("이동평균선", "전일 종가 vs SMA(기간)"),
    RSI("RSI(14)", "RSI(14) vs 기준값(0~100)"),
    FEAR_GREED("공포탐욕지수(CNN)", "CNN 공포탐욕지수 vs 기준값(0~100)");

    // SMA 기간 옵션 SSOT — 메타 응답 smaPeriods와 등록 검증이 공유
    public static final List<Integer> SMA_PERIODS = List.of(20, 50, 120, 200);

    private final String label;       // 한국어 표시 이름
    private final String description; // 비교 방식 설명 (UI 메타)
}
```

`DcaSkipReason.java`:

```java
package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// DCA 회차 미주문 사유 — dca_execution.skip_reason 상수명과 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaSkipReason {
    DATA_UNAVAILABLE("데이터 확보 실패"),
    CONDITION_NOT_MET("조건 미충족"),
    NO_HOLDINGS("보유 수량 없음"),
    INSUFFICIENT_BALANCE("예수금 부족"),
    BELOW_ONE_SHARE("1주 미만 금액");

    private final String label; // 한국어 표시 이름
}
```

`OrderDirection.java`에 label을 추가한다. 상수명은 그대로다.

```java
package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 매수/매도 방향 — broker/privacy/matching이 공유하는 전역 어휘(2026-09-09 own-type 원장 재판정으로 승격)
@Getter
@RequiredArgsConstructor
public enum OrderDirection {
    BUY("매수"),
    SELL("매도");

    private final String label; // 한국어 표시 이름 (메타 orderDirections)
}
```

`OrderType.java`에 `MOO`를 추가한다(맨 뒤).

```java
    LIMIT, // 일반 지정가 주문
    MOO    // Market On Open: 장개시 시장가 — 매수는 브로커 어댑터가 캡 지정가(KIS LOO)로 변환
```

`StrategyTicker.java`의 SOXL 뒤에 추가한다. 쉼표·세미콜론 위치에 주의한다.

```java
    SOXL(new BigDecimal("0.20"), "DIREXION SEMICONDUCTOR DAILY 3X"), // 베타: 5.3~5.5
    UPRO(BigDecimal.ZERO, "PROSHARES ULTRAPRO S&P500 3X"), // DCA 전용 — 익절률 미사용
    QLD(BigDecimal.ZERO, "PROSHARES ULTRA QQQ 2X");        // DCA 전용 — 익절률 미사용
```

`StrategyCapability.java`에 4필드를 추가하고 방어적 복사한다.

```java
public record StrategyCapability(
        Set<StrategyTicker> availableTickers, // 사용 가능한 티커 집합
        boolean tickerFixed,                  // 티커 고정 여부 (단일 티커면 선택 불가)
        boolean requiresPrivacyBase,          // basePrice 소스가 기준 매매표인지 (PRIVACY만 true)
        boolean supportsReverseMode,          // 리버스모드(소진 후 모드) 지원 여부 (INFINITE만 true)
        List<Integer> divisionCounts,         // 분할 수 옵션 — 빈 목록이면 분할 개념 없음
        List<OrderType> orderTypes,           // 사용자 선택 주문유형 — 빈 목록이면 선택 없음 (DCA만 값)
        List<OrderDirection> directions,      // 사용자 선택 방향 — 빈 목록이면 선택 없음 (DCA만 값)
        List<DcaFrequency> frequencies,       // DCA 실행 주기 옵션
        List<DcaIndicator> conditionIndicators // DCA 조건 지표 옵션
) {
    public StrategyCapability {
        availableTickers = Collections.unmodifiableSet(availableTickers.isEmpty()
                ? EnumSet.noneOf(StrategyTicker.class)
                : EnumSet.copyOf(availableTickers));
        divisionCounts = List.copyOf(divisionCounts);
        orderTypes = List.copyOf(orderTypes);
        directions = List.copyOf(directions);
        frequencies = List.copyOf(frequencies);
        conditionIndicators = List.copyOf(conditionIndicators);
    }

    // 비DCA 전략 공용 — DCA 선택지 4종을 빈 목록으로 채운다
    public static StrategyCapability basic(Set<StrategyTicker> tickers, boolean tickerFixed, boolean requiresPrivacyBase,
                                           boolean supportsReverseMode, List<Integer> divisionCounts) {
        return new StrategyCapability(tickers, tickerFixed, requiresPrivacyBase, supportsReverseMode, divisionCounts,
                List.of(), List.of(), List.of(), List.of());
    }
}
```

`StrategyType.java`:

```java
public enum StrategyType {
    INFINITE("무한매수법"), // MAGX/USD/TQQQ/SOXL 지원 (DCA 전용 티커 제외)
    PRIVACY("Fanding P전략"), // SOXL 전용
    VR("밸류리밸런싱"), // TQQQ 전용 — 밸류 기반 리밸런싱
    DCA("적립식 분할매매"); // UPRO/QLD 전용 — 주기별 고정 금액 단방향 매매

    private final String description; // 전략 설명

    // 전략 타입별 capability 상수 SSOT — RuntimeSettings 기본 허용 분할 수(20/30/40)와 동기화
    public StrategyCapability capability() {
        return switch (this) {
            case INFINITE -> StrategyCapability.basic(
                    EnumSet.of(StrategyTicker.MAGX, StrategyTicker.USD, StrategyTicker.TQQQ, StrategyTicker.SOXL),
                    false, false, true, List.of(20, 30, 40));
            case PRIVACY -> StrategyCapability.basic(EnumSet.of(StrategyTicker.SOXL), true, true, false, List.of());
            case VR -> StrategyCapability.basic(EnumSet.of(StrategyTicker.TQQQ), true, false, false, List.of());
            case DCA -> new StrategyCapability(EnumSet.of(StrategyTicker.UPRO, StrategyTicker.QLD),
                    false, false, false, List.of(),
                    List.of(OrderType.MOO, OrderType.MOC), List.of(OrderDirection.BUY, OrderDirection.SELL),
                    List.of(DcaFrequency.values()), List.of(DcaIndicator.values()));
        };
    }
```

(`availableTickers()` 등 기존의 나머지 메서드는 유지한다.) `new StrategyCapability(` 5-인자 호출이 저장소 다른 곳에 있으면 컴파일 오류로 드러난다. `basic(...)`으로 바꾼다.

`Broker.java`에 메서드를 추가한다(상수·필드는 그대로).

```java
    // 개장 경매 주문(MOO/LOO)을 개장 전에 접수할 수 있는 증권사 — KIS만 31/32 지원, Toss·MOCK은 개장 후 지정가 근사
    public boolean supportsOpenAuctionOrders() {
        return this == KIS;
    }
```

`TradingPolicySettings.java`
- `defaults()` 안의 INFINITE ticker 목록을 다음으로 바꾼다.
  ```java
  field(true, List.copyOf(StrategyType.INFINITE.capability().availableTickers()), StrategyTicker.SOXL)
  ```
- VR 뒤에 DCA를 추가한다.
  ```java
  strategies.put(StrategyType.DCA, new StrategyCreationSettings(false, // 단계 배포 — 운영자가 관리자 설정에서 켠다
          field(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD), StrategyTicker.UPRO), null, null, null, null));
  ```
- 컴팩트 생성자의 `strategies = immutableEnumMap(...)` 다음 줄에 `validateTickersWithinCapability(strategies);`를 넣는다.
  ```java
  // 각 타입 ticker 허용값은 capability 티커의 부분집합 — 관리자가 INFINITE에 DCA 전용 티커를 여는 설정을 저장 시점에 거부
  private static void validateTickersWithinCapability(Map<StrategyType, StrategyCreationSettings> strategies) {
      strategies.forEach((type, settings) -> {
          if (!type.capability().availableTickers().containsAll(settings.ticker().allowedValues())) {
              throw new IllegalArgumentException(type + " ticker allowedValues must be within capability tickers");
          }
      });
  }
  ```
- 저장된 정책 JSON에 DCA 키가 없을 때 쓰는 "신규 enum 상수 백필" 경로를 확인한다(`grep -rn "defaults()" trading-core/src/main/java/com/kista/tradingcore/trading`). 백필이 `defaults().strategy(type)`으로 누락 키를 채우는지 읽어서 확인한다. 채우지 않으면 같은 지점에 누락 키 병합을 추가한다.

`AdminSettingsRequest.java`의 switch에 `case DCA`를 추가한다.

```java
                case DCA -> new StrategyCreationSettings(enabled,
                        value.tickerValue(), null, null, null, null);
```

`KisExchangeRegistry.java`의 `Map.of(...)`에 두 종목을 추가한다. 둘 다 NYSE Arca이고, KIS 코드는 기존 AMEX 계열과 같다.

```java
            StrategyTicker.UPRO, new Mapping("AMEX", "AMS"), // ProShares UltraPro S&P500 — NYSE Arca
            StrategyTicker.QLD, new Mapping("AMEX", "AMS")   // ProShares Ultra QQQ — NYSE Arca
```

`DcaCreationResolver.java`:

```java
package com.kista.tradingcore.trading.domain.strategy;

import com.kista.shared.kernel.StrategyCreationSettings;
import com.kista.shared.kernel.StrategyDefaults;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

// DCA 생성 정책 해석 — 런타임 정책은 ticker만 관리하고 DCA 고유 값(주기·금액)은 사용자 입력이라 여기서 다루지 않는다
public class DcaCreationResolver implements StrategyCreationResolver {

    @Override
    public StrategyType type() {
        return StrategyType.DCA;
    }

    @Override
    public ResolvedCreation resolveTypeFields(StrategyCreationRequest request, StrategyCreationSettings settings, StrategyTicker ticker) {
        return new ResolvedCreation(ticker, StrategyDefaults.DEFAULT_DIVISION_COUNT, null, null, null);
    }
}
```

`CycleStrategyBeanConfig.java`의 `vrCreationResolver()` 아래에 추가한다.

```java
    @Bean
    DcaCreationResolver dcaCreationResolver() {
        return new DcaCreationResolver();
    }
```

`StrategyServiceTest.java:201`의 `switch(type)`에 컴파일이 깨지면 `case DCA ->`를 추가한다. 이 테스트는 DCA를 다루지 않으므로 `throw new IllegalArgumentException("unused")`로 둔다.

- [ ] **Step 4: Task 2로 이어간다**

이 시점에는 `OrderType.MOO` 때문에 exhaustive switch(KisOrderApi, KisResponseParser, TossOrderApi)가 깨져 있다. 테스트는 Task 2 Step 4에서 함께 돌린다.

---

### Task 2: OrderType.MOO 브로커 매핑과 Toss 매수 0.01 버그 수정

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/kis/KisOrderApi.java:40,54,109-114`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/kis/KisResponseParser.java:41-46`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossOrderApi.java:43-45,158-168`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/mock/MockBrokerAdapter.java:~165`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/backtest/FillSimulator.java:24`
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/kis/KisOrderApiTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/kis/KisResponseParserTest.java` (없으면 생성)
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/toss/TossOrderApiTest.java`

**Interfaces:**
- Consumes: Task 1의 `OrderType.MOO`
- Produces:
  - `KisResponseParser.formatPrice(OrderType type, OrderDirection direction, BigDecimal price)`. 기존 2-인자 시그니처는 삭제하고 호출부를 모두 바꾼다.
  - `KisOrderApi.resolveOrderDvsn(OrderType, OrderDirection)` (private)

- [ ] **Step 1: 실패하는 테스트 작성**

`KisResponseParserTest.java`(같은 패키지, package-private 접근):

```java
package com.kista.tradingcore.broker.adapter.out.kis;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class KisResponseParserTest {

    @Test
    void market_price_zero_only_for_sell_moo_moc() {
        BigDecimal cap = new BigDecimal("105.1234");
        assertThat(KisResponseParser.formatPrice(OrderType.MOC, OrderDirection.SELL, cap)).isEqualTo("0");
        assertThat(KisResponseParser.formatPrice(OrderType.MOO, OrderDirection.SELL, cap)).isEqualTo("0");
        assertThat(KisResponseParser.formatPrice(OrderType.MOC, OrderDirection.BUY, cap)).isEqualTo("105.12");
        assertThat(KisResponseParser.formatPrice(OrderType.MOO, OrderDirection.BUY, cap)).isEqualTo("105.12");
        assertThat(KisResponseParser.formatPrice(OrderType.LOC, OrderDirection.BUY, cap)).isEqualTo("105.12");
    }
}
```

`KisOrderApiTest.java`에 테스트를 추가한다. 기존 테스트가 요청 본문을 캡처하는 방식(`ArgumentCaptor`로 JSON 문자열 캡처 등)을 그대로 따른다. 파일을 먼저 읽고, 기존 LOC 테스트를 복제해 `orderType`·`direction`만 바꾼다.

```java
    @ParameterizedTest
    @CsvSource({
            "MOO,SELL,31,0",
            "MOO,BUY,32,105.00",
            "MOC,SELL,33,0",
            "MOC,BUY,34,105.00",
            "LOC,BUY,34,105.00",
            "LIMIT,BUY,00,105.00"
    })
    void place_maps_order_type_and_direction(OrderType type, OrderDirection direction, String ordDvsn, String price) {
        // 기존 LOC 테스트와 같은 준비 코드로 instruction = new OrderInstruction(StrategyTicker.SOXL, direction, type, 3, new BigDecimal("105"))
        // place 호출 후 캡처한 본문에 "ORD_DVSN": "<ordDvsn>" 와 "OVRS_ORD_UNPR": "<price>" 가 들어있는지 단언
    }
```

본문 단언 방식은 기존 테스트 코드를 그대로 따른다. 주석은 준비 코드를 기존 테스트에서 복사하라는 지시이고, 단언 두 줄은 반드시 작성한다.

`TossOrderApiTest.java`에 추가한다. 기존 place 테스트의 body 캡처 방식을 따른다.

```java
    @ParameterizedTest
    @CsvSource({
            "MOC,BUY,CLS,105",
            "MOC,SELL,CLS,0.01",
            "MOO,BUY,DAY,105",
            "MOO,SELL,DAY,0.01",
            "LOC,BUY,CLS,105",
            "LIMIT,SELL,DAY,105"
    })
    void place_uses_cap_price_for_buys_and_floor_price_only_for_market_sells(
            OrderType type, OrderDirection direction, String timeInForce, String price) {
        // instruction = new OrderInstruction(StrategyTicker.SOXL, direction, type, 3, new BigDecimal("105"))
        // 캡처한 body Map에서 body.get("timeInForce") == timeInForce, new BigDecimal(body.get("price").toString()) compareTo new BigDecimal(price) == 0 단언
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.kis.*' --tests 'com.kista.tradingcore.broker.adapter.out.toss.TossOrderApiTest'`
Expected: 컴파일 실패(`formatPrice` 3-인자 없음, switch에 MOO 미처리)

- [ ] **Step 3: 매핑 구현**

`KisResponseParser.java`:

```java
    // KIS 가격 파라미터 포맷 SSOT — 시장가(매도 MOO/MOC)만 "0", 그 외(매수 MOO/MOC는 LOO/LOC 캡 지정가)는 2자리 지정가
    static String formatPrice(OrderType type, OrderDirection direction, BigDecimal price) {
        boolean marketSell = direction == OrderDirection.SELL && (type == OrderType.MOC || type == OrderType.MOO);
        return marketSell ? "0" : price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
```

`formatPrice(` 호출부를 모두 찾아(`grep -rn "formatPrice(" trading-core/src`) 방향 인자를 넣는다. `KisOrderApi.java:54`는 `KisResponseParser.formatPrice(instruction.orderType(), instruction.direction(), instruction.price())`가 된다.

`KisOrderApi.java:40`은 `resolveOrderDvsn(instruction.orderType(), instruction.direction())`로 바꾼다.

```java
    // KIS 미국 ORD_DVSN — MOO(31)/MOC(33)는 매도 전용이라 매수는 LOO(32)/LOC(34) 캡 지정가로 대체
    private String resolveOrderDvsn(OrderType type, OrderDirection direction) {
        boolean buy = direction == OrderDirection.BUY;
        return switch (type) {
            case LOC   -> "34";             // 장마감지정가(LOC)
            case MOC   -> buy ? "34" : "33"; // 장마감시장가(MOC) — 매수는 LOC
            case MOO   -> buy ? "32" : "31"; // 장개시시장가(MOO) — 매수는 LOO
            case LIMIT -> "00";             // 지정가
        };
    }
```

`TossOrderApi.java`:

```java
    // LOC/MOC → 장마감 지정가(CLS), LIMIT/MOO → 정규장 지정가(DAY — MOO는 개장 후 접수로 근사)
    private String resolveTimeInForce(OrderType type) {
        return switch (type) {
            case LOC, MOC -> "CLS"; // MOC 미지원 → LIMIT+CLS로 장마감 경매 참여
            case LIMIT, MOO -> "DAY";
        };
    }

    // 시장가 근사: 매도 MOC/MOO만 최저가(0.01)로 즉시 체결 유도 — 매수에 0.01을 쓰면 체결되지 않으므로 캡 지정가 유지
    private BigDecimal resolvePrice(OrderType type, OrderDirection direction, BigDecimal price) {
        boolean marketSell = direction == OrderDirection.SELL && (type == OrderType.MOC || type == OrderType.MOO);
        return marketSell ? new BigDecimal("0.01") : price;
    }
```

`:45`는 `resolvePrice(instruction.orderType(), instruction.direction(), instruction.price())`로 바꾼다.

`MockBrokerAdapter.fills`: `if (order.orderType() == OrderType.MOC) return true;`를 다음으로 바꾼다.

```java
        if (order.orderType() == OrderType.MOC || order.orderType() == OrderType.MOO) return true; // 시장가 계열은 항상 체결(체결가는 종가 근사)
```

`FillSimulator.java:24`도 같은 방식으로 `|| order.orderType() == OrderType.MOO`를 붙인다.

- [ ] **Step 4: Task 1·2 테스트 통과 확인**

Run:
```
./gradlew :shared:test --tests 'com.kista.shared.kernel.*' \
  && ./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.*' \
  && ./gradlew :test --tests 'com.kista.api.admin.*'
```
Expected: 전부 PASS. `:trading-core:compileTestJava`에서 `new StrategyCapability(` 5-인자 호출이나 StrategyType switch 누락이 나오면 Task 1 지침대로 고친다.

- [ ] **Step 5: 커밋 (Task 1+2)**

```bash
git add shared trading-core/src src/main/java/com/kista/api/admin docs/superpowers/specs/2026-10-13-dca-strategy-design.md
git commit -m "feat(dca): DCA 전략 어휘·런타임 정책과 MOO 주문 브로커 매핑을 추가한다"
```

spec 5.6에서 "`BrokerCapabilitiesPort`에 `supportsOpenAuctionOrders()`"를 "`Broker.supportsOpenAuctionOrders()`(shared.kernel)"로 고치는 것도 이 커밋에 포함한다.

---

### Task 3: 실행일 판정(DcaSchedule)과 캘린더 변환 포트

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/DcaConfig.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/DcaSchedule.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/marketcalendar/application/port/output/MarketCalendarPort.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/marketcalendar/adapter/out/persistence/MarketCalendarPersistenceAdapter.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/strategy/DcaScheduleTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/DcaConfigTest.java`

**Interfaces:**
- Produces:
  - `record DcaConfig(OrderDirection direction, BigDecimal amountUsd, OrderType orderType, DcaFrequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth)`
    - `OrderTiming timing()`: MOO는 AT_OPEN, MOC는 AT_CLOSE
    - `String orderLeg()`: `"DCA_BUY"` 또는 `"DCA_SELL"`
  - `DcaSchedule.isExecutionDay(LocalDate usSession, DcaConfig config, Predicate<LocalDate> isUsSessionOpen): boolean`
  - `DcaSchedule.nextExecutionDay(LocalDate fromUsSession, DcaConfig config, Predicate<LocalDate> isUsSessionOpen): Optional<LocalDate>`
  - `MarketCalendarPort` 추가 메서드: `LocalDate usSessionDateOf(LocalDate kstTradeDate)`, `LocalDate kstTradeDateOf(LocalDate usSessionDate)`, `boolean isUsSessionOpen(LocalDate usSessionDate)`

- [ ] **Step 1: 실패하는 테스트 작성**

`DcaConfigTest.java`:

```java
package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DcaConfigTest {

    private static final BigDecimal AMOUNT = new BigDecimal("300");

    @Test
    void derives_timing_and_leg() {
        DcaConfig moo = new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOO, DcaFrequency.DAILY, null, null);
        assertThat(moo.timing()).isEqualTo(OrderTiming.AT_OPEN);
        assertThat(moo.orderLeg()).isEqualTo("DCA_BUY");
        DcaConfig moc = new DcaConfig(OrderDirection.SELL, AMOUNT, OrderType.MOC, DcaFrequency.DAILY, null, null);
        assertThat(moc.timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(moc.orderLeg()).isEqualTo("DCA_SELL");
    }

    @Test
    void rejects_invalid_combinations() {
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.LOC, DcaFrequency.DAILY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, BigDecimal.ZERO, OrderType.MOC, DcaFrequency.DAILY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.WEEKLY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.WEEKLY, DayOfWeek.SATURDAY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.MONTHLY, DayOfWeek.MONDAY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.MONTHLY, null, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.DAILY, null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`DcaScheduleTest.java`. US 세션일 기준이고, 휴장일은 Set으로 주입한다.

```java
package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class DcaScheduleTest {

    // 주말 + 주입 휴장일 제외
    private static Predicate<LocalDate> openExcept(LocalDate... holidays) {
        Set<LocalDate> closed = Set.of(holidays);
        return d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !closed.contains(d);
    }

    private static DcaConfig weekly(DayOfWeek dow) {
        return new DcaConfig(OrderDirection.BUY, new BigDecimal("100"), OrderType.MOC, DcaFrequency.WEEKLY, dow, null);
    }

    private static DcaConfig monthly(Integer day) {
        return new DcaConfig(OrderDirection.BUY, new BigDecimal("100"), OrderType.MOC, DcaFrequency.MONTHLY, null, day);
    }

    @Test
    void daily_runs_every_open_session() {
        DcaConfig daily = new DcaConfig(OrderDirection.BUY, new BigDecimal("100"), OrderType.MOC, DcaFrequency.DAILY, null, null);
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 10, 13), daily, openExcept())).isTrue();
    }

    @Test
    void weekly_holiday_carries_forward_within_week() {
        // 2026-11-23(월)~27(금): 목요일 11-26 추수감사절 휴장 → 목 지정은 금 11-27
        Predicate<LocalDate> open = openExcept(LocalDate.of(2026, 11, 26));
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 11, 27), weekly(DayOfWeek.THURSDAY), open)).isTrue();
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 11, 25), weekly(DayOfWeek.THURSDAY), open)).isFalse();
    }

    @Test
    void weekly_friday_holiday_pulls_back_to_last_open_day() {
        // 2026-04-03(금) Good Friday 휴장 → 금 지정은 목 04-02
        Predicate<LocalDate> open = openExcept(LocalDate.of(2026, 4, 3));
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 4, 2), weekly(DayOfWeek.FRIDAY), open)).isTrue();
    }

    @Test
    void weekly_fully_closed_week_has_no_execution() {
        Predicate<LocalDate> open = openExcept(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(DcaSchedule.nextExecutionDay(LocalDate.of(2026, 10, 12), weekly(DayOfWeek.WEDNESDAY), open))
                .contains(LocalDate.of(2026, 10, 21));
    }

    @Test
    void monthly_31_in_30_day_month_uses_last_day() {
        // 2026-09-30(수) 개장 → 31일 지정의 9월 실행일
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 9, 30), monthly(31), openExcept())).isTrue();
    }

    @Test
    void monthly_end_of_month_on_weekend_pulls_back() {
        // 2026-10-31은 토요일 → 말일(null) 지정은 10-30(금)
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 10, 30), monthly(null), openExcept())).isTrue();
        assertThat(DcaSchedule.nextExecutionDay(LocalDate.of(2026, 10, 1), monthly(null), openExcept()))
                .contains(LocalDate.of(2026, 10, 30));
    }

    @Test
    void monthly_target_on_weekend_carries_forward_within_month() {
        // 2026-08-15는 토요일 → 15일 지정은 08-17(월)
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 8, 17), monthly(15), openExcept())).isTrue();
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 8, 14), monthly(15), openExcept())).isFalse();
    }

    @Test
    void closed_day_is_never_execution_day() {
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 10, 31), monthly(null), openExcept())).isFalse();
    }

    @Test
    void next_returns_empty_when_calendar_unknown() {
        assertThat(DcaSchedule.nextExecutionDay(LocalDate.of(2026, 10, 1), monthly(1), d -> false)).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.strategy.DcaScheduleTest' --tests 'com.kista.tradingcore.matching.domain.model.DcaConfigTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`DcaConfig.java`:

```java
package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Objects;

// DCA 전략 버전 설정 — strategy_dca_version 1행. 조건(conditions)은 단계 ②에서 추가
public record DcaConfig(
        OrderDirection direction, // 매수/매도 단방향
        BigDecimal amountUsd,     // 회차당 고정 금액 (USD, > 0)
        OrderType orderType,      // MOO(개장 배치) 또는 MOC(마감 배치)
        DcaFrequency frequency,   // 실행 주기
        DayOfWeek dayOfWeek,      // WEEKLY 전용 (월~금)
        Integer dayOfMonth        // MONTHLY 전용 1~31, null이면 말일
) {
    public DcaConfig {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frequency, "frequency");
        if (amountUsd == null || amountUsd.signum() <= 0) throw new IllegalArgumentException("amountUsd는 0보다 커야 합니다");
        if (orderType != OrderType.MOO && orderType != OrderType.MOC) throw new IllegalArgumentException("DCA 주문유형은 MOO 또는 MOC만 허용합니다");
        boolean weekly = frequency == DcaFrequency.WEEKLY;
        boolean monthly = frequency == DcaFrequency.MONTHLY;
        if (weekly != (dayOfWeek != null)) throw new IllegalArgumentException("dayOfWeek는 WEEKLY에서만 필수입니다");
        if (weekly && (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY)) throw new IllegalArgumentException("주말 요일은 지정할 수 없습니다");
        if (!monthly && dayOfMonth != null) throw new IllegalArgumentException("dayOfMonth는 MONTHLY에서만 허용합니다");
        if (dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) throw new IllegalArgumentException("dayOfMonth는 1~31입니다");
    }

    // MOO는 개장 배치(AT_OPEN), MOC는 마감 배치(AT_CLOSE)
    public OrderTiming timing() {
        return orderType == OrderType.MOO ? OrderTiming.AT_OPEN : OrderTiming.AT_CLOSE;
    }

    // 단일 concrete leg — 슬롯 중복 방지(TradingOrderSlots.excludeExisting)의 키
    public String orderLeg() {
        return "DCA_" + direction.name();
    }
}
```

`DcaSchedule.java`:

```java
package com.kista.tradingcore.matching.domain.strategy;

import com.kista.tradingcore.matching.domain.model.DcaConfig;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.function.Predicate;

// DCA 실행일 판정 — US 세션일 기준 순수 계산. KST↔US 변환은 호출부(MarketCalendarPort)가 담당
public final class DcaSchedule {

    private static final int SEARCH_LIMIT_DAYS = 400; // 다음 실행일 탐색 상한 — 휴장 데이터 미존재 연도 대비

    private DcaSchedule() {}

    // usSession이 이 설정의 실행일인지 — 휴장일은 항상 false
    public static boolean isExecutionDay(LocalDate usSession, DcaConfig config, Predicate<LocalDate> isOpen) {
        if (!isOpen.test(usSession)) return false;
        return switch (config.frequency()) {
            case DAILY -> true;
            case WEEKLY -> {
                LocalDate start = usSession.with(DayOfWeek.MONDAY);
                yield usSession.equals(resolve(start.with(config.dayOfWeek()), start, start.plusDays(6), isOpen));
            }
            case MONTHLY -> {
                LocalDate start = usSession.withDayOfMonth(1);
                LocalDate end = usSession.with(TemporalAdjusters.lastDayOfMonth());
                int day = config.dayOfMonth() == null ? end.getDayOfMonth() : Math.min(config.dayOfMonth(), end.getDayOfMonth());
                yield usSession.equals(resolve(start.withDayOfMonth(day), start, end, isOpen));
            }
        };
    }

    // fromUsSession(포함) 이후 첫 실행일 — 상한 내 없으면 empty
    public static Optional<LocalDate> nextExecutionDay(LocalDate fromUsSession, DcaConfig config, Predicate<LocalDate> isOpen) {
        for (int i = 0; i < SEARCH_LIMIT_DAYS; i++) {
            LocalDate d = fromUsSession.plusDays(i);
            if (isExecutionDay(d, config, isOpen)) return Optional.of(d);
        }
        return Optional.empty();
    }

    // 목표일 이후(포함) 기간 내 첫 개장일, 없으면 목표일 이전 기간 내 마지막 개장일, 둘 다 없으면 null
    private static LocalDate resolve(LocalDate target, LocalDate periodStart, LocalDate periodEnd, Predicate<LocalDate> isOpen) {
        for (LocalDate d = target; !d.isAfter(periodEnd); d = d.plusDays(1)) {
            if (isOpen.test(d)) return d;
        }
        for (LocalDate d = target.minusDays(1); !d.isBefore(periodStart); d = d.minusDays(1)) {
            if (isOpen.test(d)) return d;
        }
        return null;
    }
}
```

`MarketCalendarPort.java`에 추가한다.

```java
    // KST 거래일 → US 세션일 (KST 아침 정산일 D ↔ US D-1)
    LocalDate usSessionDateOf(LocalDate kstTradeDate);

    // US 세션일 → KST 거래일
    LocalDate kstTradeDateOf(LocalDate usSessionDate);

    // US 세션일 기준 개장 여부 — 주말 false, 연도 데이터 미존재 시 false (isMarketOpen과 같은 데이터)
    boolean isUsSessionOpen(LocalDate usSessionDate);
```

`MarketCalendarPersistenceAdapter.java`. `isMarketOpen(kstDate)`은 내부에서 `UsTradeDates.toUsTradeDate(date)`를 쓴 뒤 US 날짜 기준으로 판정한다. 그 판정 부분을 `isUsSessionOpen(usDate)`로 추출하고, `isMarketOpen`은 `return isUsSessionOpen(UsTradeDates.toUsTradeDate(date));`로 바꾼다. 나머지 두 메서드는 다음과 같다.

```java
    @Override
    public LocalDate usSessionDateOf(LocalDate kstTradeDate) {
        return UsTradeDates.toUsTradeDate(kstTradeDate);
    }

    @Override
    public LocalDate kstTradeDateOf(LocalDate usSessionDate) {
        return UsTradeDates.toKstTradeDate(usSessionDate);
    }
```

`MarketCalendarPort`를 구현하거나 mock하는 다른 클래스·테스트가 있으면 컴파일 오류로 드러난다(`grep -rn "implements MarketCalendarPort" trading-core/src src`). 테스트 mock은 수정할 필요가 없다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.*' --tests 'com.kista.tradingcore.marketcalendar.*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src
git commit -m "feat(dca): DCA 실행일 판정과 US 세션일 변환 포트를 추가한다"
```

---

### Task 4: 스키마와 영속 어댑터 (DCA 설정·회차 이력)

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V6__dca_strategy.sql`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/DcaExecution.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/port/output/StrategyDcaDetailPort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/port/output/DcaExecutionPort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/StrategyDcaVersionEntity.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/StrategyDcaVersionJpaRepository.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/StrategyDcaDetailPersistenceAdapter.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/DcaExecutionEntity.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/DcaExecutionJpaRepository.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/DcaExecutionPersistenceAdapter.java`
- Modify: `trading-core/src/test/java/com/kista/tradingcore/TradingApplicationContextLoadTest.java` (리포지토리 수 15 → 17)
- Modify: `docs/agents/architecture.md`·`docs/agents/testing.md`의 "trading-core 15" 서술 → 17
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/DcaPersistenceAdapterDbTest.java`

**Interfaces:**
- Consumes: Task 3의 `DcaConfig`
- Produces:
  - `record DcaExecution(UUID id, UUID strategyId, LocalDate tradeDate, Status status, DcaSkipReason skipReason, UUID orderId, Integer quantity, BigDecimal referencePrice, String detail, Instant createdAt)`, `enum Status { ORDERED, SKIPPED }`
    - 팩토리: `DcaExecution.ordered(strategyId, tradeDate, orderId, quantity, referencePrice)`, `DcaExecution.skipped(strategyId, tradeDate, reason, detail, referencePrice)`
  - `StrategyDcaDetailPort`
    - `DcaConfig save(UUID strategyVersionId, DcaConfig config)`
    - `Optional<DcaConfig> findActiveByStrategyId(UUID strategyId)`
    - `Map<UUID, DcaConfig> findActiveByStrategyIds(Collection<UUID> strategyIds)` (키는 strategyId)
  - `DcaExecutionPort`
    - `boolean insertIfAbsent(DcaExecution execution)`: 삽입했으면 true, UNIQUE 충돌이면 false
    - `Optional<DcaExecution> find(UUID strategyId, LocalDate tradeDate)`
    - `Map<UUID, DcaExecution> findLatestByStrategyIds(Collection<UUID> strategyIds)`
    - `List<DcaExecution> findPage(UUID strategyId, int page, int size)` (trade_date DESC)
    - `long count(UUID strategyId)`

- [ ] **Step 1: 마이그레이션 작성**

`V6__dca_strategy.sql`. trading 스키마 소유이며 root 테이블은 참조하지 않는다.

```sql
-- DCA(적립식 분할매매) 전략 — 버전별 설정 + 회차 실행 이력. 조건 테이블(strategy_dca_condition)은 단계 ②에서 추가한다.
-- trading-core 단독 스키마라 root expand/contract 제약 밖. 신규 테이블만 만들어 기존 이미지와 호환된다.

CREATE TABLE trading.strategy_dca_version (
    strategy_version_id uuid NOT NULL,
    direction character varying(20) NOT NULL,
    amount_usd numeric(20,2) NOT NULL,
    order_type character varying(20) NOT NULL,
    frequency character varying(20) NOT NULL,
    day_of_week character varying(20),
    day_of_month integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT strategy_dca_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_dca_version_strategy_version_id_fkey FOREIGN KEY (strategy_version_id)
        REFERENCES trading.strategy_version(id) ON DELETE CASCADE,
    CONSTRAINT strategy_dca_version_amount_usd_check CHECK (amount_usd > 0),
    CONSTRAINT strategy_dca_version_day_of_month_check CHECK (day_of_month BETWEEN 1 AND 31),
    CONSTRAINT strategy_dca_version_weekly_check CHECK ((frequency = 'WEEKLY') = (day_of_week IS NOT NULL)),
    CONSTRAINT strategy_dca_version_monthly_check CHECK (frequency = 'MONTHLY' OR day_of_month IS NULL)
);

CREATE TABLE trading.dca_execution (
    id uuid NOT NULL,
    strategy_id uuid NOT NULL,
    trade_date date NOT NULL,
    status character varying(20) NOT NULL,
    skip_reason character varying(30),
    order_id uuid,
    quantity integer,
    reference_price numeric(12,2),
    detail character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dca_execution_pkey PRIMARY KEY (id),
    CONSTRAINT dca_execution_strategy_id_fkey FOREIGN KEY (strategy_id)
        REFERENCES trading.strategy(id) ON DELETE CASCADE,
    CONSTRAINT uq_dca_execution_strategy_trade_date UNIQUE (strategy_id, trade_date)
);

COMMENT ON COLUMN trading.dca_execution.trade_date IS 'KST 거래일 — orders.trade_date와 동일 기준';
```

- [ ] **Step 2: 실패하는 DB 테스트 작성**

먼저 `OrderPersistenceAdapterDbTest`를 읽고 같은 베이스 클래스·애노테이션(`DataJpaTestBase` 계열, `@Execution(SAME_THREAD)`, `@Import`)과 FK 선행 행 삽입(JdbcTemplate: accounts → strategy → strategy_version) 패턴을 복제한다.

```java
@Execution(ExecutionMode.SAME_THREAD)
@Import({StrategyDcaDetailPersistenceAdapter.class, DcaExecutionPersistenceAdapter.class})
class DcaPersistenceAdapterDbTest extends /* OrderPersistenceAdapterDbTest와 같은 베이스 */ {

    @Autowired JdbcTemplate jdbc;
    @Autowired StrategyDcaDetailPersistenceAdapter dcaDetail;
    @Autowired DcaExecutionPersistenceAdapter executions;

    UUID strategyId;
    UUID versionId;

    @BeforeEach
    void setUp() {
        // OrderPersistenceAdapterDbTest의 accounts → strategy → strategy_version 삽입 SQL 복제
        // (strategy.type='DCA', ticker='UPRO'), 생성 id를 strategyId·versionId에 보관
    }

    @Test
    void saves_and_loads_active_config() {
        DcaConfig config = new DcaConfig(OrderDirection.BUY, new BigDecimal("300.00"), OrderType.MOC,
                DcaFrequency.MONTHLY, null, null);
        dcaDetail.save(versionId, config);
        assertThat(dcaDetail.findActiveByStrategyId(strategyId)).contains(config);
        assertThat(dcaDetail.findActiveByStrategyIds(List.of(strategyId))).containsEntry(strategyId, config);
    }

    @Test
    void insert_if_absent_is_idempotent_per_trade_date() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        assertThat(executions.insertIfAbsent(DcaExecution.skipped(strategyId, day, DcaSkipReason.NO_HOLDINGS, null, null))).isTrue();
        assertThat(executions.insertIfAbsent(DcaExecution.skipped(strategyId, day, DcaSkipReason.NO_HOLDINGS, null, null))).isFalse();
        assertThat(executions.find(strategyId, day)).get().extracting(DcaExecution::skipReason).isEqualTo(DcaSkipReason.NO_HOLDINGS);
        assertThat(executions.count(strategyId)).isEqualTo(1);
    }

    @Test
    void page_and_latest_are_trade_date_desc() {
        executions.insertIfAbsent(DcaExecution.skipped(strategyId, LocalDate.of(2026, 10, 1), DcaSkipReason.NO_HOLDINGS, null, null));
        executions.insertIfAbsent(DcaExecution.ordered(strategyId, LocalDate.of(2026, 10, 2), null, 3, new BigDecimal("87.12")));
        assertThat(executions.findPage(strategyId, 0, 10)).extracting(DcaExecution::tradeDate)
                .containsExactly(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1));
        assertThat(executions.findLatestByStrategyIds(List.of(strategyId)).get(strategyId).tradeDate())
                .isEqualTo(LocalDate.of(2026, 10, 2));
    }
}
```

`/* … */`는 실제 클래스명으로 바꾼다. `OrderPersistenceAdapterDbTest`의 `extends` 대상과 같은 클래스다.

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew :trading-core:test --tests '*DcaPersistenceAdapterDbTest'`
Expected: 컴파일 실패

- [ ] **Step 4: 도메인·포트·어댑터 구현**

`DcaExecution.java`:

```java
package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.DcaSkipReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// DCA 회차 판정 결과 — (strategyId, tradeDate) 1행. 재개·중복 진입 시 이력·알림 중복을 막는 키
public record DcaExecution(
        UUID id,                    // PK (null이면 생성)
        UUID strategyId,            // FK → strategy.id
        LocalDate tradeDate,        // KST 거래일
        Status status,              // ORDERED | SKIPPED
        DcaSkipReason skipReason,   // SKIPPED일 때만
        UUID orderId,               // ORDERED일 때 orders.id
        Integer quantity,           // 계획 수량 (축소 반영)
        BigDecimal referencePrice,  // 판정에 쓴 전일 종가 (데이터 실패 시 null)
        String detail,              // 사람용 사유 상세
        Instant createdAt           // 생성 시각
) {
    public enum Status { ORDERED, SKIPPED }

    public static DcaExecution ordered(UUID strategyId, LocalDate tradeDate, UUID orderId, Integer quantity, BigDecimal referencePrice) {
        return new DcaExecution(null, strategyId, tradeDate, Status.ORDERED, null, orderId, quantity, referencePrice, null, null);
    }

    public static DcaExecution skipped(UUID strategyId, LocalDate tradeDate, DcaSkipReason reason, String detail, BigDecimal referencePrice) {
        return new DcaExecution(null, strategyId, tradeDate, Status.SKIPPED, reason, null, null, referencePrice, detail, null);
    }
}
```

포트 2개는 위 Interfaces의 시그니처 그대로 `application/port/output`에 둔다. 각 메서드에 `//` 한 줄 주석을 단다.

엔티티는 `StrategyVrVersionEntity`의 애노테이션 패턴을 따른다.
- `@Entity @Table(name=..., schema="trading") @Getter @Setter(AccessLevel.PACKAGE) @NoArgsConstructor(access = PROTECTED)`
- `StrategyDcaVersionEntity extends BaseAuditEntity`
  - `@Id strategyVersionId`
  - direction, orderType, frequency, dayOfWeek(DayOfWeek): 모두 `@Enumerated(STRING)`, length 20
  - amountUsd: precision 20, scale 2
  - dayOfMonth: Integer, nullable
- `DcaExecutionEntity extends BaseCreatedAtEntity`
  - `@Id @UuidGenerator(style = VERSION_7) UUID id`
  - strategyId, tradeDate, status(STRING 20), skipReason(STRING 30), orderId, quantity, referencePrice(12,2), detail(length 500)
  - `dca_execution`에는 updated_at이 없다. 그래서 `BaseCreatedAtEntity`를 쓴다.

`StrategyDcaVersionJpaRepository`. `StrategyVrVersionJpaRepository.findActiveByStrategyId`의 native query를 그대로 본떠 활성 버전 조인을 만든다.

```java
interface StrategyDcaVersionJpaRepository extends JpaRepository<StrategyDcaVersionEntity, UUID> {

    // 활성(삭제되지 않은) 최신 버전의 DCA 설정 — strategy_vr_version 조회와 같은 조인
    @Query(value = """
            SELECT sdv.* FROM strategy_dca_version sdv
            JOIN strategy_version sv ON sv.id = sdv.strategy_version_id AND sv.deleted_at IS NULL
            WHERE sv.strategy_id = :strategyId
            ORDER BY sdv.created_at DESC LIMIT 1
            """, nativeQuery = true)
    Optional<StrategyDcaVersionEntity> findActiveByStrategyId(@Param("strategyId") UUID strategyId);

    // 여러 전략의 활성 버전 설정 + strategy_id (배치 조회)
    @Query(value = """
            SELECT DISTINCT ON (sv.strategy_id) sv.strategy_id AS strategyId, sdv.strategy_version_id AS strategyVersionId
            FROM strategy_dca_version sdv
            JOIN strategy_version sv ON sv.id = sdv.strategy_version_id AND sv.deleted_at IS NULL
            WHERE sv.strategy_id IN (:strategyIds)
            ORDER BY sv.strategy_id, sdv.created_at DESC
            """, nativeQuery = true)
    List<StrategyVersionLink> findActiveLinks(@Param("strategyIds") Collection<UUID> strategyIds);

    // native projection — strategyId ↔ 활성 versionId
    interface StrategyVersionLink {
        UUID getStrategyId();
        UUID getStrategyVersionId();
    }
}
```

`StrategyDcaDetailPersistenceAdapter.findActiveByStrategyIds`는 `findActiveLinks`로 versionId를 얻고, `findAllById(versionIds)`로 엔티티를 읽어 strategyId 키 맵으로 조립한다. `save`는 VR 어댑터처럼 `PersistenceSupport.findOrCreate(versionId, jpaRepository, StrategyDcaVersionEntity::new)`를 쓰고 setter로 채운다.

`DcaExecutionJpaRepository`:

```java
interface DcaExecutionJpaRepository extends JpaRepository<DcaExecutionEntity, UUID> {

    Optional<DcaExecutionEntity> findByStrategyIdAndTradeDate(UUID strategyId, LocalDate tradeDate);

    List<DcaExecutionEntity> findByStrategyIdOrderByTradeDateDesc(UUID strategyId, Pageable pageable);

    long countByStrategyId(UUID strategyId);

    // 전략별 최신 1건
    @Query(value = """
            SELECT DISTINCT ON (strategy_id) * FROM dca_execution
            WHERE strategy_id IN (:strategyIds)
            ORDER BY strategy_id, trade_date DESC
            """, nativeQuery = true)
    List<DcaExecutionEntity> findLatestByStrategyIds(@Param("strategyIds") Collection<UUID> strategyIds);

    // UNIQUE(strategy_id, trade_date) 충돌 시 무시 — 반환값 1이면 삽입
    @Modifying
    @Query(value = """
            INSERT INTO dca_execution (id, strategy_id, trade_date, status, skip_reason, order_id, quantity, reference_price, detail)
            VALUES (:id, :strategyId, :tradeDate, :status, :skipReason, :orderId, :quantity, :referencePrice, :detail)
            ON CONFLICT (strategy_id, trade_date) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringConflict(@Param("id") UUID id, @Param("strategyId") UUID strategyId, @Param("tradeDate") LocalDate tradeDate,
                               @Param("status") String status, @Param("skipReason") String skipReason, @Param("orderId") UUID orderId,
                               @Param("quantity") Integer quantity, @Param("referencePrice") BigDecimal referencePrice,
                               @Param("detail") String detail);
}
```

`DcaExecutionPersistenceAdapter.insertIfAbsent`
- `@Transactional`
- native insert에는 JPA `@UuidGenerator`가 동작하지 않는다. 그래서 id는 어댑터에서 만든다: `UUID id = Generators.timeBasedEpochGenerator().generate()`가 classpath에 있으면 그것을 쓰고, 없으면 `UUID.randomUUID()`를 쓴다. `grep -rn "timeBasedEpoch\|UuidCreator" trading-core/src shared/src`로 기존 v7 생성 유틸을 먼저 확인하고 재사용한다.
- `detail`은 500자에서 자른다.
- `return insertIgnoringConflict(...) == 1;`

`TradingApplicationContextLoadTest`의 리포지토리 수 단언을 15에서 17로 올린다. 문서 두 곳의 숫자도 같이 고친다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `docker compose up -d postgres && ./gradlew :trading-core:test --tests '*DcaPersistenceAdapterDbTest' --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest'`
Expected: PASS(Flyway V6 적용, `ddl-auto: validate` 통과)

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src docs/agents/architecture.md docs/agents/testing.md
git commit -m "feat(dca): DCA 설정·회차 이력 스키마와 영속 어댑터를 추가한다"
```

---

### Task 5: 등록·설정 수정·조회 응답과 수동 실행 차단

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/RegisterStrategyCommand.java` (DcaConfig 필드 추가)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/Strategy.java` (`isDca()`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/StrategyDetail.java` (`DcaSummary dca` 필드)
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/DcaSummary.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/DcaManualExecutionUnsupportedException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyCreationService.java:60,73-105,190-194`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyCreationPersister.java:40-75`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaSummaryAssembler.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/usecase/DcaConfigUseCase.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaConfigService.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyService.java:195,225,289` (assemble에 DcaSummary)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/port/output/StrategyPort.java` (`findByAccountIdAndTicker`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/ManualTradingService.java:54`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingPreviewService.java:52,~117`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/TradingCycleController.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/TradingCycleRequest.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConfigRequest.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaResponse.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/TradingCycleResponse.java`
- Modify: `shared/src/main/java/com/kista/shared/platform/web/ErrorCode.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/web/TradingExceptionHandler.java:57-74`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/StrategyCreationServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/DcaSummaryAssemblerTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/ManualTradingServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/TradingCycleControllerTest.java`

**Interfaces:**
- Consumes: `DcaConfig`, `DcaSchedule`, `MarketCalendarPort.usSessionDateOf/kstTradeDateOf/isUsSessionOpen`, `StrategyDcaDetailPort`, `DcaExecutionPort`
- Produces:
  - `Strategy.isDca()`
  - `RegisterStrategyCommand`에 마지막 필드 `DcaConfig dca`를 추가한다(비DCA는 null). 기존 19·20-인자 편의 생성자는 `dca=null`로 위임한다.
  - `record DcaSummary(DcaConfig config, LocalDate nextUsSessionDate, LocalDate nextKstTradeDate, DcaExecution lastExecution)` — next 둘은 함께 null일 수 있다.
  - `StrategyDetail`에 마지막 필드 `DcaSummary dca`를 추가한다.
  - `DcaSummaryAssembler.assemble(DcaConfig config, DcaExecution last, LocalDate fromKstTradeDate): DcaSummary`
  - `DcaConfigUseCase.updateConfig(UUID strategyId, UUID requesterId, DcaConfig config): void`
  - `StrategyPort.findByAccountIdAndTicker(UUID accountId, StrategyTicker ticker): List<Strategy>`
  - `ErrorCode.DCA_MANUAL_EXECUTION_UNSUPPORTED`, `DcaManualExecutionUnsupportedException`

- [ ] **Step 1: 실패하는 테스트 작성**

`StrategyCreationServiceTest.java`에 추가한다. 기존 테스트의 mock 구성과 등록 호출 헬퍼를 재사용하고, `strategyPort.existsByAccountIdAndTicker` stub 대신 `findByAccountIdAndTicker` stub을 쓴다.

```java
    @Test
    void allows_buy_and_sell_dca_on_same_ticker() {
        Strategy buyDca = new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of(buyDca));
        when(dcaDetailPort.findActiveByStrategyIds(List.of(buyDca.id())))
                .thenReturn(Map.of(buyDca.id(), dcaConfig(OrderDirection.BUY)));
        // 기존 테스트의 DCA 정책 stub(enabled=true, ticker UPRO/QLD) + persist stub 재사용
        service.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.SELL)));
        verify(creationPersister).persist(any(), eq(StrategyType.DCA), eq(StrategyTicker.UPRO), any(), anyInt(),
                any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), eq(dcaConfig(OrderDirection.SELL)));
    }

    @Test
    void rejects_same_direction_dca_on_same_ticker() {
        Strategy buyDca = new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.UPRO)).thenReturn(List.of(buyDca));
        when(dcaDetailPort.findActiveByStrategyIds(List.of(buyDca.id())))
                .thenReturn(Map.of(buyDca.id(), dcaConfig(OrderDirection.BUY)));
        assertThatThrownBy(() -> service.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.UPRO, dcaConfig(OrderDirection.BUY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("같은 계좌·종목에 같은 방향의 DCA 전략이 이미 있습니다.");
    }

    @Test
    void non_dca_still_rejects_any_existing_strategy_on_ticker() {
        Strategy infinite = new Strategy(UUID.randomUUID(), ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.SOXL)).thenReturn(List.of(infinite));
        // 기존 INFINITE 등록 커맨드 헬퍼 사용
        assertThatThrownBy(() -> service.register(USER_ID, ACCOUNT_ID, infiniteCommand(StrategyTicker.SOXL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 해당 종목으로 등록된 전략이 있습니다");
    }

    @Test
    void dca_requires_config() {
        assertThatThrownBy(() -> service.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.UPRO, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 헬퍼
    private static DcaConfig dcaConfig(OrderDirection direction) {
        return new DcaConfig(direction, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null);
    }

    private static RegisterStrategyCommand dcaCommand(StrategyTicker ticker, DcaConfig dca) {
        return new RegisterStrategyCommand(StrategyType.DCA, ticker, null, null, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, dca);
    }
```

기존 테스트에 `dcaDetailPort` mock이 없으면 `@Mock StrategyDcaDetailPort dcaDetailPort;`를 추가한다. `persist` 시그니처의 마지막 인자는 Step 3에서 정의하는 `DcaConfig dca`다. 기존 테스트의 `persist` verify·stub에는 마지막 인자에 `any()`를 하나 더 붙인다.

`DcaSummaryAssemblerTest.java`:

```java
package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.domain.model.DcaSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DcaSummaryAssemblerTest {

    private final MarketCalendarPort calendar = mock(MarketCalendarPort.class);
    private final DcaSummaryAssembler assembler = new DcaSummaryAssembler(calendar);

    @Test
    void next_execution_converts_us_session_back_to_kst() {
        // KST 2026-10-14 ↔ US 10-13, 평일 전부 개장
        when(calendar.usSessionDateOf(LocalDate.of(2026, 10, 14))).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenAnswer(inv -> {
            DayOfWeek d = inv.<LocalDate>getArgument(0).getDayOfWeek();
            return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
        });
        when(calendar.kstTradeDateOf(LocalDate.of(2026, 10, 30))).thenReturn(LocalDate.of(2026, 10, 31));
        DcaConfig monthlyEnd = new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.MONTHLY, null, null);

        DcaSummary summary = assembler.assemble(monthlyEnd, null, LocalDate.of(2026, 10, 14));

        assertThat(summary.nextUsSessionDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(summary.nextKstTradeDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(summary.lastExecution()).isNull();
    }

    @Test
    void next_execution_null_when_calendar_unknown() {
        when(calendar.usSessionDateOf(any())).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenReturn(false);
        DcaConfig daily = new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null);

        DcaSummary summary = assembler.assemble(daily, null, LocalDate.of(2026, 10, 14));

        assertThat(summary.nextUsSessionDate()).isNull();
        assertThat(summary.nextKstTradeDate()).isNull();
    }
}
```

`ManualTradingServiceTest.java`에 추가한다. 기존 mock 구성을 재사용한다.

```java
    @Test
    void rejects_dca_manual_execution() {
        Strategy dca = new Strategy(STRATEGY_ID, ACCOUNT_ID, StrategyType.DCA, StrategyStatus.ACTIVE,
                StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(dca);
        // requireOwnedAccount stub은 기존 테스트 패턴
        assertThatThrownBy(() -> service.execute(STRATEGY_ID, USER_ID))
                .isInstanceOf(DcaManualExecutionUnsupportedException.class);
    }
```

`TradingCycleControllerTest.java`에 추가한다. 기존 POST 테스트의 `@WebMvcTest` 설정·인증 패턴(`authentication(new UsernamePasswordAuthenticationToken(UUID, null, List.of()))`, `csrf()`)을 따른다.

```java
    @Test
    void register_dca_maps_dca_body_to_command() throws Exception {
        when(tradingCycle.register(any(), any(), any())).thenReturn(/* 기존 테스트의 StrategyDetail fixture를 DCA로: type=DCA, dca=summary */);
        mockMvc.perform(post("/api/accounts/{accountId}/trading-cycles", ACCOUNT_ID)
                        .with(csrf()).with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DCA","ticker":"UPRO","dca":{"direction":"BUY","amountUsd":300,
                                 "orderType":"MOC","frequency":"MONTHLY","dayOfWeek":null,"dayOfMonth":null,"conditions":[]}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dca.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.dca.nextExecution.kstTradeDate").exists());
        ArgumentCaptor<RegisterStrategyCommand> captor = ArgumentCaptor.forClass(RegisterStrategyCommand.class);
        verify(tradingCycle).register(any(), any(), captor.capture());
        assertThat(captor.getValue().dca().direction()).isEqualTo(OrderDirection.BUY);
    }

    @Test
    void register_dca_with_conditions_is_rejected_in_stage1() throws Exception {
        mockMvc.perform(post("/api/accounts/{accountId}/trading-cycles", ACCOUNT_ID)
                        .with(csrf()).with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DCA","ticker":"UPRO","dca":{"direction":"BUY","amountUsd":300,"orderType":"MOC",
                                 "frequency":"DAILY","conditions":[{"indicator":"RSI","operator":"LTE","threshold":30}]}}
                                """))
                .andExpect(status().isBadRequest());
    }
```

`/* … */` 자리에는 기존 테스트의 `StrategyDetail` 생성 코드를 복제해 `StrategyType.DCA`와 `new DcaSummary(config, LocalDate.of(2026,10,30), LocalDate.of(2026,10,31), null)`을 넣는다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :trading-core:test --tests '*StrategyCreationServiceTest' --tests '*DcaSummaryAssemblerTest' --tests '*ManualTradingServiceTest' --tests '*TradingCycleControllerTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`Strategy.java`:

```java
    public boolean isDca() {
        return type == StrategyType.DCA;
    }
```

`RegisterStrategyCommand`
- 마지막 컴포넌트로 `DcaConfig dca // DCA 전용 설정 (비DCA는 null)`를 추가한다.
- 기존 19-인자 편의 생성자는 `this(..., scheduledStartDate, null, null)`로 바꾼다.
- 20-인자(initialVrValue 포함) 편의 생성자를 새로 두고 `dca=null`로 위임한다. 기존 호출부를 바꾸지 않기 위함이다.

`StrategyPort`와 그 영속 어댑터에 메서드를 추가한다.

```java
    // 같은 계좌·종목의 활성 전략 — 등록 중복 규칙(DCA 방향별 허용) 판정용
    List<Strategy> findByAccountIdAndTicker(UUID accountId, StrategyTicker ticker);
```

어댑터는 기존 `existsByAccountIdAndTicker` 구현 옆에 JPA 파생 쿼리 `findByAccountIdAndTicker`로 구현한다(`@SQLRestriction`이 soft delete를 거른다). `existsByAccountIdAndTicker`는 다른 호출처가 없으면 삭제한다(`grep -rn existsByAccountIdAndTicker trading-core/src`).

`StrategyCreationService`
- 필드에 `StrategyDcaDetailPort dcaDetailPort`를 추가한다.
- `:60`의 `validateUniqueTicker(accountId, resolved.ticker())`를 `validateUniqueTicker(accountId, resolved.ticker(), cmd)`로 바꾼다.

```java
    // 같은 계좌 내 종목 중복 방지 — 종목별 합산 잔고 ↔ 전략 일대일 보장. 예외: DCA끼리는 방향이 다르면 공존(매수 1 + 매도 1)
    private void validateUniqueTicker(UUID accountId, StrategyTicker ticker, RegisterStrategyCommand cmd) {
        List<Strategy> existing = strategyPort.findByAccountIdAndTicker(accountId, ticker);
        if (existing.isEmpty()) return;
        if (cmd.type() != StrategyType.DCA || existing.stream().anyMatch(s -> !s.isDca())) {
            throw new IllegalStateException("이미 해당 종목으로 등록된 전략이 있습니다: " + ticker);
        }
        Map<UUID, DcaConfig> configs = dcaDetailPort.findActiveByStrategyIds(existing.stream().map(Strategy::id).toList());
        if (configs.values().stream().anyMatch(c -> c.direction() == cmd.dca().direction())) {
            throw new IllegalStateException("같은 계좌·종목에 같은 방향의 DCA 전략이 이미 있습니다.");
        }
    }
```

- `register` 초반, `resolveCreationSettings` 직후에 DCA 입력 검증을 넣는다.

```java
        // DCA는 설정 필수, 보유·평단은 브로커 실잔고가 SSOT라 bootstrap 입력을 받지 않는다
        if (cmd.type() == StrategyType.DCA && cmd.dca() == null) {
            throw new IllegalArgumentException("DCA 설정(dca)은 필수입니다");
        }
```

- DCA는 `validateBootstrapPosition`, `validateBalanceIfRequired`, `fetchMarketPrice`를 건너뛴다. 각 호출을 `if (cmd.type() != StrategyType.DCA)` 블록으로 감싼다. `initialHoldings`·`initialUsdDeposit`는 DCA에서 0으로 정규화해 persister에 넘긴다.
- seedType은 VR처럼 DCA도 `NONE`으로 고정한다(`:82-84`의 VR 조건에 `|| cmd.type() == StrategyType.DCA`).
- `creationPersister.persist(...)` 호출 마지막 인자로 `cmd.dca()`를 넘긴다.
- 응답 조립(`:99-105`): DCA면 `new StrategyDetail(persisted.strategy(), BigDecimal.ZERO, persisted.cycle().startDate(), null, false, null, 0, null, dcaSummaryAssembler.assemble(cmd.dca(), null, DstInfo.nextTradeDate()))`. 비DCA 분기는 마지막 인자로 `null`을 추가한다. 필드에 `DcaSummaryAssembler dcaSummaryAssembler`를 추가한다.

`StrategyCreationPersister.persist`
- 마지막 파라미터로 `DcaConfig dca`를 추가하고, 필드에 `StrategyDcaDetailPort dcaDetailPort`를 추가한다.
- `:51-57`의 detail 분기에 다음을 추가한다.

```java
        } else if (saved.isDca()) {
            dcaDetailPort.save(version.id(), dca); // DCA 버전 설정 1행
        }
```

- 사이클과 초기 포지션은 기존 비부트스트랩 경로(`initialSnapshot(cycle.id(), deposit)`, deposit=0)를 그대로 탄다. 배치의 사전 잔고 로더가 `cycle_position` 최신 행을 요구하기 때문이다.

`DcaSummary.java`:

```java
package com.kista.tradingcore.trading.domain.model;

import com.kista.tradingcore.matching.domain.model.DcaConfig;

import java.time.LocalDate;

// DCA 전략 조회 요약 — 설정 + 다음 판정 예정일(보정 반영) + 최근 회차
public record DcaSummary(
        DcaConfig config,              // 활성 버전 설정
        LocalDate nextUsSessionDate,   // 다음 판정 예정 US 세션일 (캘린더 미존재 시 null)
        LocalDate nextKstTradeDate,    // 위와 짝인 KST 거래일 (null 동반)
        DcaExecution lastExecution     // 최근 dca_execution (없으면 null)
) {}
```

`StrategyDetail`: 마지막 컴포넌트로 `DcaSummary dca // DCA 전략만 non-null`를 추가한다. 생성 호출부(StrategyCreationService, StrategyService.assemble, 테스트 fixture)는 모두 컴파일 오류로 드러난다. 비DCA는 `null`을 넣는다.

`DcaSummaryAssembler.java`(trading `application/service`, package-private `@Component`):

```java
// DCA 조회 요약 조립 — 다음 실행일은 US 세션일로 계산해 KST 거래일로 되돌린다
@Component
@RequiredArgsConstructor
class DcaSummaryAssembler {

    private final MarketCalendarPort marketCalendarPort;

    // fromKstTradeDate(포함)부터 첫 실행 예정일
    DcaSummary assemble(DcaConfig config, DcaExecution last, LocalDate fromKstTradeDate) {
        LocalDate fromUs = marketCalendarPort.usSessionDateOf(fromKstTradeDate);
        return DcaSchedule.nextExecutionDay(fromUs, config, marketCalendarPort::isUsSessionOpen)
                .map(us -> new DcaSummary(config, us, marketCalendarPort.kstTradeDateOf(us), last))
                .orElseGet(() -> new DcaSummary(config, null, null, last));
    }
}
```

`fromKstTradeDate`는 `DstInfo.nextTradeDate()`다. 그날 배치가 이미 판정을 끝냈으면(`last != null && last.tradeDate().equals(from)`) 다음 날부터 센다. `StrategyService`에서 호출할 때 이 보정을 적용한다.

```java
    // 오늘 회차 판정이 끝났으면 다음 날부터 센다
    LocalDate from = DstInfo.nextTradeDate();
    if (last != null && last.tradeDate().equals(from)) from = from.plusDays(1);
```

`plusDays(1)`은 KST 달력일을 하루 넘기는 용도이고, KST↔US 변환이 아니므로 `UsTradeDates` 규칙 대상이 아니다. 다음 날이 휴장이어도 `nextExecutionDay`가 개장일까지 탐색한다.

`StrategyService`
- 필드에 `StrategyDcaDetailPort dcaDetailPort`, `DcaExecutionPort dcaExecutionPort`, `DcaSummaryAssembler dcaSummaryAssembler`를 추가한다.
- `toDetail(Strategy)`: `strategy.isDca()`면 `dcaDetailPort.findActiveByStrategyId(id)`와 `dcaExecutionPort.findLatestByStrategyIds(List.of(id)).get(id)`로 요약을 만든다.
- `toDetails(List<Strategy>)`: DCA 전략 id만 모아 `findActiveByStrategyIds` 1회, `findLatestByStrategyIds` 1회로 배치 조회한다.
- `assemble(...)`의 마지막 인자로 DcaSummary(또는 null)를 넘긴다.

`DcaConfigUseCase`(usecase)와 `DcaConfigService`(service, package-private `@Service`):

```java
public interface DcaConfigUseCase {
    // DCA 설정 변경 — 새 strategy_version + DCA 상세 1행, 사이클 유지. ticker·direction 변경 불가
    void updateConfig(UUID strategyId, UUID requesterId, DcaConfig config);
}
```

```java
@Service
@RequiredArgsConstructor
class DcaConfigService implements DcaConfigUseCase {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final StrategyVersionPort strategyVersionPort;
    private final StrategyDcaDetailPort dcaDetailPort;

    @Override
    @Transactional
    public void updateConfig(UUID strategyId, UUID requesterId, DcaConfig config) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        if (!strategy.isDca()) throw new IllegalArgumentException("DCA 전략이 아닙니다: " + strategyId);
        DcaConfig current = dcaDetailPort.findActiveByStrategyId(strategyId)
                .orElseThrow(() -> new IllegalStateException("DCA 설정이 없습니다: " + strategyId));
        if (current.direction() != config.direction()) throw new IllegalArgumentException("DCA 방향은 변경할 수 없습니다");
        // nextVersionNo는 soft delete 전에 — @SQLRestriction이 삭제 버전을 가린다 (CycleSnapshotCreator.reconfigureVrCycle과 동일 순서)
        int nextVersionNo = strategyVersionPort.nextVersionNo(strategyId);
        strategyVersionPort.softDeleteActiveByStrategyId(strategyId, Instant.now());
        StrategyVersion version = strategyVersionPort.save(new StrategyVersion(null, strategyId, nextVersionNo, null, null));
        dcaDetailPort.save(version.id(), config);
    }
}
```

주의: `orders`와 `strategy_cycle`의 `strategy_version_id`는 사이클 생성 시점 버전을 가리킨다. 사이클을 유지하므로 기존 사이클 행의 version FK는 옛(soft-deleted) 버전 그대로다. 실행 경로는 `findActiveByStrategyId`로 설정을 읽어 영향이 없다. 이 결정은 `DcaConfigService`의 클래스 주석에 한 줄로 남긴다.

`DcaManualExecutionUnsupportedException`(domain/model):

```java
package com.kista.tradingcore.trading.domain.model;

// DCA 전략의 바로 주문·단건 미리보기 거부 — 409 DCA_MANUAL_EXECUTION_UNSUPPORTED
public class DcaManualExecutionUnsupportedException extends RuntimeException {
    public DcaManualExecutionUnsupportedException() {
        super("DCA 전략은 바로 주문과 미리보기를 지원하지 않습니다.");
    }
}
```

`ErrorCode.java`의 `ALREADY_ORDERED_TODAY` 다음에 추가한다.

```java
    DCA_MANUAL_EXECUTION_UNSUPPORTED, // 409 DCA 바로 주문·미리보기 미지원
```

`TradingExceptionHandler`
- `MAPPINGS`에 `DcaManualExecutionUnsupportedException.class, new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.DCA_MANUAL_EXECUTION_UNSUPPORTED)`를 추가한다(현재 8쌍이라 `Map.of` 한도 10 이내).
- `@ExceptionHandler({...})` 목록(`:70-74`)에도 클래스를 추가한다.
- 메시지가 사용자 문구이므로 detail은 예외 메시지를 그대로 쓴다. 기존 `Mapping`이 `fixedDetail` 없이 메시지를 쓰는지 확인한다.

`ManualTradingService.execute(..., DstInfo)`의 `:54`(isActive 검사) 직후:

```java
        if (strategy.isDca()) throw new DcaManualExecutionUnsupportedException(); // DCA는 배치 전용(spec 7.5)
```

`TradingPreviewService`
- `preview(strategyId, requesterId)`(`:52`): 전략을 읽은 직후 `isDca()`면 같은 예외를 던진다. 위임 전에 전략을 읽지 않는 구조라면 `strategyPort.findByIdOrThrow(strategyId).isDca()` 검사를 추가한다.
- `previewBatch`의 전략 루프(`CYCLE_ENDED` 판정 근처, `:117`)에서 `if (strategy.isDca()) continue;`로 결과에서 제외한다.

`TradingCycleRequest`
- 마지막 필드로 `@Valid DcaConfigRequest dca`를 추가한다.
- `toRegisterCommand()`가 `dca != null ? dca.toDomain() : null`을 마지막 인자로 넘긴다.

`DcaConfigRequest.java`:

```java
package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

// DCA 등록·수정 요청 본문 — conditions는 단계 ①에서 빈 배열만 허용
public record DcaConfigRequest(
        @NotNull OrderDirection direction,  // 매수/매도
        @NotNull BigDecimal amountUsd,      // 회차당 금액 (USD)
        @NotNull OrderType orderType,       // MOO | MOC
        @NotNull DcaFrequency frequency,    // 주기
        DayOfWeek dayOfWeek,                // WEEKLY 전용
        Integer dayOfMonth,                 // MONTHLY 전용, null=말일
        List<Map<String, Object>> conditions // 단계 ②에서 타입화 — 지금은 비어 있어야 함
) {
    public DcaConfig toDomain() {
        if (conditions != null && !conditions.isEmpty()) {
            throw new IllegalArgumentException("DCA 추가 조건은 아직 지원하지 않습니다.");
        }
        return new DcaConfig(direction, amountUsd, orderType, frequency, dayOfWeek, dayOfMonth);
    }
}
```

`IllegalArgumentException`은 공통 매핑에서 400이 된다. `DcaConfig` 컴팩트 생성자 검증 실패도 같은 경로로 400이 된다.

`DcaResponse.java`:

```java
// DCA 조회 응답 블록 — TradingCycleResponse.dca
public record DcaResponse(
        OrderDirection direction, BigDecimal amountUsd, OrderType orderType, DcaFrequency frequency,
        DayOfWeek dayOfWeek, Integer dayOfMonth,
        List<Object> conditions,          // 단계 ①은 항상 빈 배열
        NextExecution nextExecution,      // 보정 반영 판정 예정일 (null 가능)
        LastExecution lastExecution       // 최근 회차 (null 가능)
) {
    public record NextExecution(LocalDate usSessionDate, LocalDate kstTradeDate) {}

    public record LastExecution(LocalDate kstTradeDate, String status, String skipReason) {}

    public static DcaResponse from(DcaSummary s) {
        DcaConfig c = s.config();
        NextExecution next = s.nextKstTradeDate() == null ? null : new NextExecution(s.nextUsSessionDate(), s.nextKstTradeDate());
        DcaExecution e = s.lastExecution();
        LastExecution last = e == null ? null : new LastExecution(e.tradeDate(), e.status().name(),
                e.skipReason() == null ? null : e.skipReason().name());
        return new DcaResponse(c.direction(), c.amountUsd(), c.orderType(), c.frequency(), c.dayOfWeek(), c.dayOfMonth(),
                List.of(), next, last);
    }
}
```

`TradingCycleResponse`
- `vr` 다음에 `@JsonInclude(JsonInclude.Include.NON_NULL) DcaResponse dca`를 추가한다.
- `from()`에서 `detail.dca() == null ? null : DcaResponse.from(detail.dca())`를 넣는다.
- `forRegistration`의 `todayOpenBatchMissed` 판정에서 DCA도 PRIVACY처럼 제외한다. DCA는 등록 당일 판정 기준이 따로 있기 때문이다.

`TradingCycleController`
- 필드에 `DcaConfigUseCase dcaConfig`를 추가한다.
- VR 엔드포인트 옆에 추가한다.

```java
    @PutMapping("/api/trading-cycles/{id}/dca-config")
    public TradingCycleResponse reconfigureDca(@PathVariable UUID id, @AuthenticationPrincipal UUID userId,
                                               @Valid @RequestBody DcaConfigRequest request) {
        dcaConfig.updateConfig(id, userId, request.toDomain());
        return TradingCycleResponse.from(tradingCycle.getById(id, userId));
    }
```

`TradingCycleControllerTest`에 `@MockitoBean DcaConfigUseCase dcaConfig;`를 추가한다(누락 시 컨텍스트 로드 실패).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.*' --tests 'com.kista.tradingcore.trading.adapter.in.web.*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add shared trading-core/src
git commit -m "feat(dca): DCA 등록·설정 수정·조회 응답과 수동 실행 차단을 추가한다"
```

---

### Task 6: 배치 회차 실행 (판정·주문·예산 축소·이력·skip 알림·브로커 스냅샷)

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategy.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/DcaDecision.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/CycleOrderStrategy.java` (default `snapshotsBrokerBalance()`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/support/CycleStrategyBeanConfig.java` (DCA 빈)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/application/port/output/CandlePort.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossCandleApi.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossBrokerAdapter.java` (CandlePort 위임)
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaRoundPlanner.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaExecutionRecorder.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/event/DcaRoundSkippedEvent.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingCandidatePlanner.java:122-160,226-237,247-283`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingReporter.java:47-64`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/notify/adapter/out/gateway/TradingAlertNotifier.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/notify/application/port/output/TradingUserNotificationPort.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/notify/adapter/out/gateway/TradingUserNotificationAdapter.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategyTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/DcaRoundPlannerTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/DcaExecutionRecorderTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/toss/TossCandleApiTest.java`

**Interfaces:**
- Consumes: `DcaConfig`, `DcaSchedule`, `MarketCalendarPort`, `StrategyDcaDetailPort`, `DcaExecutionPort`, `DcaExecution`, `Strategy.isDca()`, `PriceCapPolicy.capFor(BigDecimal)`(matching 기존)
- Produces:
  - `record DcaInputs(DcaConfig config, StrategyTicker ticker, LocalDate tradeDate, BigDecimal prevClose, Integer sellableQuantity)` (`DcaCycleOrderStrategy`의 nested record)
  - `sealed interface DcaDecision permits Planned, Skipped`
    - `record Planned(PlannedOrder order, BigDecimal referencePrice)`
    - `record Skipped(DcaSkipReason reason, String detail, BigDecimal referencePrice)`
  - `DcaCycleOrderStrategy.decide(DcaInputs): DcaDecision`
  - `CycleOrderStrategy.snapshotsBrokerBalance(): boolean` (default false, DCA true)
  - `CandlePort.getDailyCandlesBefore(String symbol, LocalDate beforeUsSession, int count): List<BrokerCandle>` (확정 봉만, 오름차순)
  - `DcaRoundPlanner.plan(BatchContext ctx, LocalDate tradeDate, Set<OrderTiming> creatableTimings, List<Order> existingOrders): Optional<OrderPlan>`
  - `DcaExecutionRecorder`
    - `recordOrdered(BatchContext ctx, LocalDate tradeDate, Order order, BigDecimal referencePrice): void`
    - `recordSkipped(BatchContext ctx, LocalDate tradeDate, DcaSkipReason reason, String detail, BigDecimal referencePrice): void`
  - `record DcaRoundSkippedEvent(UUID userId, UUID strategyId, String accountNickname, StrategyTicker ticker, OrderDirection direction, LocalDate tradeDate, DcaSkipReason reason, String detail)`
  - `TradingUserNotificationPort.notifyDcaRoundSkipped(TradingUserProfile profile, DcaRoundSkippedEvent event): void`

- [ ] **Step 1: 실패하는 테스트 작성 — 커널**

`DcaCycleOrderStrategyTest.java`:

```java
package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DcaCycleOrderStrategyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private final DcaCycleOrderStrategy strategy = new DcaCycleOrderStrategy();

    private static DcaConfig config(OrderDirection dir, String amount, OrderType type) {
        return new DcaConfig(dir, new BigDecimal(amount), type, DcaFrequency.DAILY, null, null);
    }

    private DcaDecision decide(DcaConfig c, String prevClose, Integer sellable) {
        return strategy.decide(new DcaCycleOrderStrategy.DcaInputs(c, StrategyTicker.UPRO, DAY,
                prevClose == null ? null : new BigDecimal(prevClose), sellable));
    }

    @Test
    void buy_quantity_is_floor_of_amount_over_prev_close_with_cap_price() {
        DcaDecision d = decide(config(OrderDirection.BUY, "300", OrderType.MOC), "87.12", null);
        PlannedOrder o = ((DcaDecision.Planned) d).order();
        assertThat(o.quantity()).isEqualTo(3);
        assertThat(o.price()).isEqualByComparingTo("91.48"); // 87.12 × 1.05 HALF_UP — PriceCapPolicy 결과와 일치해야 함
        assertThat(o.orderType()).isEqualTo(OrderType.MOC);
        assertThat(o.timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(o.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(o.orderLeg()).isEqualTo("DCA_BUY");
    }

    @Test
    void moo_is_at_open() {
        PlannedOrder o = ((DcaDecision.Planned) decide(config(OrderDirection.BUY, "300", OrderType.MOO), "87.12", null)).order();
        assertThat(o.timing()).isEqualTo(OrderTiming.AT_OPEN);
    }

    @Test
    void below_one_share_skips() {
        DcaDecision d = decide(config(OrderDirection.BUY, "50", OrderType.MOC), "87.12", null);
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.BELOW_ONE_SHARE, "회차 금액 50 < 전일 종가 87.12", new BigDecimal("87.12")));
    }

    @Test
    void missing_prev_close_is_data_unavailable() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.BUY, "300", OrderType.MOC), null, null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void sell_caps_to_sellable_and_uses_prev_close_price() {
        PlannedOrder o = ((DcaDecision.Planned) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", 4)).order();
        assertThat(o.quantity()).isEqualTo(4);
        assertThat(o.price()).isEqualByComparingTo("100");
        assertThat(o.orderLeg()).isEqualTo("DCA_SELL");
    }

    @Test
    void sell_with_no_sellable_skips_no_holdings() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", 0)).reason())
                .isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void sell_without_sellable_data_is_data_unavailable() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void fitBuysToBudget_shrinks_by_cap_price() {
        PlannedOrder buy = PlannedOrder.of(DAY, StrategyTicker.UPRO, OrderType.MOC, OrderDirection.BUY, 20,
                new BigDecimal("105"), OrderTiming.AT_CLOSE, "DCA_BUY");
        List<PlannedOrder> fitted = strategy.fitBuysToBudget(List.of(buy), new BigDecimal("1000"));
        assertThat(fitted).singleElement().extracting(PlannedOrder::quantity).isEqualTo(9);
    }

    @Test
    void fitBuysToBudget_returns_empty_below_one_share() {
        PlannedOrder buy = PlannedOrder.of(DAY, StrategyTicker.UPRO, OrderType.MOC, OrderDirection.BUY, 3,
                new BigDecimal("105"), OrderTiming.AT_CLOSE, "DCA_BUY");
        assertThat(strategy.fitBuysToBudget(List.of(buy), new BigDecimal("100"))).isEmpty();
    }

    @Test
    void policy_flags() {
        assertThat(strategy.endsCycleOnLiquidation()).isFalse();
        assertThat(strategy.needsCapCheck(null, null)).isFalse();
        assertThat(strategy.allocationPriority()).isEqualTo(200);
        assertThat(strategy.snapshotsBrokerBalance()).isTrue();
    }
}
```

`91.48`은 `PriceCapPolicy.capFor(87.12)`의 실제 반올림 규칙에 맞춘다. 구현 전에 `PriceCapPolicy`를 읽고, scale·반올림이 다르면 기대값을 그 규칙으로 계산해 고친다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests '*DcaCycleOrderStrategyTest'`
Expected: 컴파일 실패

- [ ] **Step 3: 커널 구현**

`DcaDecision.java`(matching model):

```java
package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaSkipReason;

import java.math.BigDecimal;

// DCA 회차 판정 결과 — 주문 계획 또는 skip(사유·상세)
public sealed interface DcaDecision permits DcaDecision.Planned, DcaDecision.Skipped {

    record Planned(PlannedOrder order, BigDecimal referencePrice) implements DcaDecision {}

    record Skipped(DcaSkipReason reason, String detail, BigDecimal referencePrice) implements DcaDecision {}
}
```

`CycleOrderStrategy.java`에 default 메서드를 추가한다.

```java
    // 리포트 후 cycle_position을 내부 원장(applyExecutions) 대신 브로커 실잔고 스냅샷으로 기록할지 (DCA만 true — 매수·매도 DCA가 한 포지션 공유)
    default boolean snapshotsBrokerBalance() { return false; }
```

`DcaCycleOrderStrategy.java`:

```java
package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.model.PrivacyPlan;
import com.kista.tradingcore.matching.domain.model.VrPosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// DCA 회차 판정 커널 — 순수 계산. 배치 진입은 trading DcaRoundPlanner가 decide()를 직접 호출한다
public class DcaCycleOrderStrategy implements CycleOrderStrategy {

    // DCA 판정 입력 — prevClose: 조회 거래일 US 세션 이전 마지막 확정 종가, sellableQuantity: SELL만 (예약분 차감 후)
    public record DcaInputs(DcaConfig config, StrategyTicker ticker, LocalDate tradeDate,
                            BigDecimal prevClose, Integer sellableQuantity) {}

    @Override
    public StrategyType cycleType() {
        return StrategyType.DCA;
    }

    // DCA는 PlanContext 경로를 쓰지 않는다 — 판정 사유(skip)를 돌려줘야 해서 DcaRoundPlanner → decide()로 진입
    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        throw new UnsupportedOperationException("DCA는 DcaRoundPlanner.decide 경유로만 계획한다");
    }

    // 회차 판정 — 데이터 → 수량 → 잔고 순 (spec 5.2, skip 우선순위 9장)
    public DcaDecision decide(DcaInputs in) {
        BigDecimal prevClose = in.prevClose();
        if (prevClose == null || prevClose.signum() <= 0) {
            return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        }
        DcaConfig c = in.config();
        int quantity = c.amountUsd().divide(prevClose, 0, RoundingMode.FLOOR).intValueExact();
        if (quantity == 0) {
            return new DcaDecision.Skipped(DcaSkipReason.BELOW_ONE_SHARE,
                    "회차 금액 " + c.amountUsd().stripTrailingZeros().toPlainString() + " < 전일 종가 " + prevClose.toPlainString(), prevClose);
        }
        if (c.direction() == OrderDirection.SELL) {
            if (in.sellableQuantity() == null) {
                return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "판매가능수량 조회 실패", prevClose);
            }
            if (in.sellableQuantity() <= 0) {
                return new DcaDecision.Skipped(DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", prevClose);
            }
            quantity = Math.min(quantity, in.sellableQuantity()); // 보유 부족 → 보유 전량
        }
        // BUY는 캡 지정가(LOO/LOC로 접수), SELL은 시장가라 가격은 기준가 기록용
        BigDecimal price = c.direction() == OrderDirection.BUY ? PriceCapPolicy.capFor(prevClose) : prevClose;
        PlannedOrder order = PlannedOrder.of(in.tradeDate(), in.ticker(), c.orderType(), c.direction(),
                quantity, price, c.timing(), c.orderLeg());
        return new DcaDecision.Planned(order, prevClose);
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyPlan privacyPlan, int divisionCount) {
        return null; // 사이클 재등록 없음
    }

    @Override
    public boolean endsCycleOnLiquidation() { return false; } // 영구 사이클

    @Override
    public boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) { return false; } // 계획 시점 캡가 고정

    @Override
    public int allocationPriority() { return 200; } // 기존 전략 예산 우선 보장

    @Override
    public boolean snapshotsBrokerBalance() { return true; }

    // 예산 부족 시 단일 BUY 수량을 floor(budget / 캡가)로 축소 — 브로커가 지정가×수량으로 주문가능금액을 검증하므로 캡가 기준
    @Override
    public List<PlannedOrder> fitBuysToBudget(List<PlannedOrder> buyOrders, BigDecimal budget) {
        return buyOrders.stream()
                .map(o -> o.withQuantity(budget.divide(o.price(), 0, RoundingMode.FLOOR).intValue()))
                .filter(o -> o.quantity() > 0)
                .toList();
    }
}
```

`PriceCapPolicy.capFor`의 실제 시그니처를 확인해(static인지, 인스턴스인지) 호출을 맞춘다. 인스턴스라면 생성자에서 주입받지 말고 기존 VR·INFINITE 전략이 쓰는 방식을 그대로 따른다.

`CycleStrategyBeanConfig`에 빈을 추가한다.

```java
    @Bean
    DcaCycleOrderStrategy dcaCycleOrderStrategy() {
        return new DcaCycleOrderStrategy();
    }
```

- [ ] **Step 4: 커널 테스트 통과 확인**

Run: `./gradlew :trading-core:test --tests '*DcaCycleOrderStrategyTest'`
Expected: PASS

- [ ] **Step 5: 실패하는 테스트 작성 — 일봉 조회**

`TossCandleApiTest.java`에 추가한다. 기존 테스트의 `tossHttpClient.getCommon` stub 방식을 따른다.

```java
    @Test
    void daily_candles_before_excludes_session_and_later_and_sorts_ascending() {
        // stub 응답: 2026-10-14, 10-13, 10-12 봉 (최신순)
        List<TossCandle> result = api.getDailyCandlesBefore("UPRO", LocalDate.of(2026, 10, 14), 5);
        assertThat(result).extracting(TossCandle::date).containsExactly(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13));
    }
```

`TossCandle`의 날짜 접근자 이름이 다르면 그에 맞춘다. 단계 ①은 count ≤ 200만 쓴다(전일 종가용 5봉). 200 초과 페이징은 단계 ②에서 추가한다.

- [ ] **Step 6: 일봉 조회 구현**

`CandlePort.java`에 추가한다.

```java
    // beforeUsSession(미포함) 이전 확정 일봉 최대 count개, 오름차순 — 진행 중인 당일 봉 배제(DCA 전일 종가·지표 공용)
    List<BrokerCandle> getDailyCandlesBefore(String symbol, LocalDate beforeUsSession, int count);
```

`TossCandleApi.java`:

```java
    // beforeUsSession 00:00 UTC 이전 봉만 요청 후 date < beforeUsSession 재필터 — 봉 date()는 US 세션일
    public List<TossCandle> getDailyCandlesBefore(String symbol, LocalDate beforeUsSession, int count) {
        String before = beforeUsSession.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        return fetchCandles(symbol, "1d", Math.min(count, MAX_COUNT), before).stream()
                .filter(c -> c.date().isBefore(beforeUsSession))
                .sorted(Comparator.comparing(TossCandle::date))
                .toList();
    }
```

`fetchCandles`가 이미 오름차순 정렬을 한다면 `sorted`는 생략한다. `TossBrokerAdapter`의 `CandlePort` 구현에 `BrokerCandle` 변환 위임을 추가한다(기존 `getLatestCandles` 위임과 같은 형태). `CandlePort`를 구현하는 다른 클래스(Mock 등)가 있으면 같은 메서드를 구현한다. 없으면 생략한다.

- [ ] **Step 7: 실패하는 테스트 작성 — DcaRoundPlanner·Recorder**

`DcaRoundPlannerTest.java`. Mockito로 모든 협력자를 mock한다.

```java
package com.kista.tradingcore.trading.application.service;

// imports: BatchContext, Strategy, StrategyCycle, TradingAccount, TradingUserProfile, Order, DcaConfig, DcaExecution,
//          MarketCalendarPort, CandlePort, BrokerCandle, SellableQuantityPort, SellableQuantity, OrderPort,
//          StrategyDcaDetailPort, DcaExecutionPort, DcaCycleOrderStrategy, OrderTiming, OrderDirection, OrderType, DcaFrequency,
//          DcaSkipReason, StrategyTicker, StrategyType, StrategyStatus, StrategyCycleSeedType, CycleOrderStrategy.OrderPlan

class DcaRoundPlannerTest {

    static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 14); // KST
    static final LocalDate US_SESSION = LocalDate.of(2026, 10, 13);

    MarketCalendarPort calendar = mock(MarketCalendarPort.class);
    CandlePort candlePort = mock(CandlePort.class);
    SellableQuantityPort sellablePort = mock(SellableQuantityPort.class);
    OrderPort orderPort = mock(OrderPort.class);
    StrategyDcaDetailPort dcaDetailPort = mock(StrategyDcaDetailPort.class);
    DcaExecutionPort executionPort = mock(DcaExecutionPort.class);
    DcaExecutionRecorder recorder = mock(DcaExecutionRecorder.class);
    DcaRoundPlanner planner = new DcaRoundPlanner(calendar, candlePort, sellablePort, orderPort, dcaDetailPort,
            executionPort, recorder, new DcaCycleOrderStrategy());

    BatchContext ctx; // @BeforeEach에서 DCA 전략(UPRO) + 사이클(startDate 2026-10-01) + TradingFixtures.tradingAccount 조립

    @BeforeEach
    void setUp() {
        // ctx 조립 — TradingFixtures(trading-core testFixtures)의 계좌·프로필 팩토리 사용
        when(calendar.usSessionDateOf(TRADE_DATE)).thenReturn(US_SESSION);
        when(calendar.isUsSessionOpen(any())).thenReturn(true);
        when(executionPort.find(any(), eq(TRADE_DATE))).thenReturn(Optional.empty());
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 5)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 12), null, null, null, new BigDecimal("87.12"), 0)));
    }

    @Test
    void plans_buy_when_due_and_timing_matches() {
        dca(OrderDirection.BUY, OrderType.MOC);
        Optional<OrderPlan> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of());
        assertThat(plan).get().extracting(p -> p.orders().getFirst().quantity()).isEqualTo(3);
        verifyNoInteractions(recorder);
    }

    @Test
    void ignores_other_timing_batch() {
        dca(OrderDirection.BUY, OrderType.MOO);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void not_execution_day_records_nothing() {
        when(dcaDetailPort.findActiveByStrategyId(ctx.strategy().id())).thenReturn(Optional.of(
                new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.MONTHLY, null, 1)));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void already_decided_today_skips_without_recording() {
        dca(OrderDirection.BUY, OrderType.MOC);
        when(executionPort.find(ctx.strategy().id(), TRADE_DATE))
                .thenReturn(Optional.of(DcaExecution.skipped(ctx.strategy().id(), TRADE_DATE, DcaSkipReason.NO_HOLDINGS, null, null)));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void existing_dca_order_without_execution_row_recovers_ordered() {
        dca(OrderDirection.BUY, OrderType.MOC);
        Order existing = /* TradingFixtures 또는 Order 생성자로 leg "DCA_BUY", AT_CLOSE, PLANNED, quantity 3 */;
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(existing))).isEmpty();
        verify(recorder).recordOrdered(ctx, TRADE_DATE, existing, null);
    }

    @Test
    void candle_failure_records_data_unavailable() {
        dca(OrderDirection.BUY, OrderType.MOC);
        when(candlePort.getDailyCandlesBefore(any(), any(), anyInt())).thenThrow(new RuntimeException("toss down"));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isEmpty();
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), isNull());
    }

    @Test
    void sell_subtracts_reserved_quantity() {
        dca(OrderDirection.SELL, OrderType.MOC);
        when(sellablePort.getSellableQuantity(StrategyTicker.UPRO, ctx.account().brokerRef())).thenReturn(new SellableQuantity("UPRO", 5));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(ctx.account().id(), TRADE_DATE, StrategyTicker.UPRO)).thenReturn(5);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of());
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(DcaSkipReason.NO_HOLDINGS), anyString(), any());
    }

    @Test
    void before_cycle_start_does_nothing() {
        // ctx 사이클 startDate를 TRADE_DATE 이후로 다시 조립
        // plan → empty, recorder 상호작용 없음
    }

    private void dca(OrderDirection dir, OrderType type) {
        when(dcaDetailPort.findActiveByStrategyId(ctx.strategy().id())).thenReturn(Optional.of(
                new DcaConfig(dir, new BigDecimal("300"), type, DcaFrequency.DAILY, null, null)));
    }
}
```

`/* … */`와 `before_cycle_start_does_nothing`의 본문은 기존 `TradingCandidatePlannerTest`·`TradingFixtures`의 Order·사이클 생성 코드를 복제해 채운다. 단언(`isEmpty`, `verifyNoInteractions(recorder)`)은 반드시 작성한다. `OrderPort`의 예약 SELL 합계 메서드명은 testing.md에 기록된 `sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker`다. 반환 타입(int/long)은 실제 시그니처에 맞춘다.

`DcaExecutionRecorderTest.java`:

```java
class DcaExecutionRecorderTest {

    DcaExecutionPort port = mock(DcaExecutionPort.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    StrategyDcaDetailPort dcaDetailPort = mock(StrategyDcaDetailPort.class);
    DcaExecutionRecorder recorder = new DcaExecutionRecorder(port, publisher, dcaDetailPort);
    BatchContext ctx; // DCA 전략 BatchContext (TradingFixtures)

    @Test
    void publishes_skip_event_only_when_inserted() {
        when(dcaDetailPort.findActiveByStrategyId(any())).thenReturn(Optional.of(
                new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null)));
        when(port.insertIfAbsent(any())).thenReturn(true, false);
        recorder.recordSkipped(ctx, LocalDate.of(2026, 10, 14), DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", null);
        recorder.recordSkipped(ctx, LocalDate.of(2026, 10, 14), DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", null);
        verify(publisher, times(1)).publishEvent(any(DcaRoundSkippedEvent.class));
    }
}
```

- [ ] **Step 8: 실패 확인**

Run: `./gradlew :trading-core:test --tests '*DcaRoundPlannerTest' --tests '*DcaExecutionRecorderTest' --tests '*TossCandleApiTest'`
Expected: 컴파일 실패

- [ ] **Step 9: Planner·Recorder·이벤트 구현**

`DcaRoundSkippedEvent.java`(trading `application/event`):

```java
package com.kista.tradingcore.trading.application.event;

// DCA 회차 미주문 — tradingcore.notify가 사용자 알림. dca_execution 신규 삽입 시에만 발행(EPR 재발행 대상)
public record DcaRoundSkippedEvent(
        UUID userId,             // 알림 수신자
        UUID strategyId,         // 대상 전략
        String accountNickname,  // 알림 표기
        StrategyTicker ticker,   // 종목
        OrderDirection direction, // 매수/매도 DCA
        LocalDate tradeDate,     // KST 거래일
        DcaSkipReason reason,    // 사유
        String detail            // 사유 상세
) {}
```

`DcaExecutionRecorder.java`(package-private `@Component`):

```java
// DCA 회차 이력 기록 — UNIQUE(strategy, tradeDate) 삽입 성공 시에만 skip 이벤트 발행해 재개·중복 진입 알림 중복 차단
@Component
@RequiredArgsConstructor
class DcaExecutionRecorder {

    private final DcaExecutionPort dcaExecutionPort;
    private final ApplicationEventPublisher eventPublisher;
    private final StrategyDcaDetailPort dcaDetailPort;

    @Transactional
    public void recordOrdered(BatchContext ctx, LocalDate tradeDate, Order order, BigDecimal referencePrice) {
        dcaExecutionPort.insertIfAbsent(DcaExecution.ordered(ctx.strategy().id(), tradeDate, order.id(), order.quantity(), referencePrice));
    }

    @Transactional
    public void recordSkipped(BatchContext ctx, LocalDate tradeDate, DcaSkipReason reason, String detail, BigDecimal referencePrice) {
        boolean inserted = dcaExecutionPort.insertIfAbsent(DcaExecution.skipped(ctx.strategy().id(), tradeDate, reason, detail, referencePrice));
        if (!inserted) return;
        OrderDirection direction = dcaDetailPort.findActiveByStrategyId(ctx.strategy().id()).map(DcaConfig::direction).orElse(null);
        eventPublisher.publishEvent(new DcaRoundSkippedEvent(ctx.account().userId(), ctx.strategy().id(), ctx.account().nickname(),
                ctx.strategy().ticker(), direction, tradeDate, reason, detail));
    }
}
```

`order.id()`와 `order.quantity()`의 접근자명은 `Order` record를 보고 맞춘다.

`DcaRoundPlanner.java`(package-private `@Component`):

```java
// DCA 배치 회차 계획 — 실행일·timing·이력 판정 후 데이터 조회 → DcaCycleOrderStrategy.decide → skip이면 이력·알림 기록
@Slf4j
@Component
@RequiredArgsConstructor
class DcaRoundPlanner {

    private static final int PREV_CLOSE_CANDLES = 5; // 전일 종가 확보용 봉 수 (휴장 여유분)

    private final MarketCalendarPort marketCalendarPort;
    private final CandlePort candlePort;
    private final SellableQuantityPort sellableQuantityPort;
    private final OrderPort orderPort;
    private final StrategyDcaDetailPort dcaDetailPort;
    private final DcaExecutionPort dcaExecutionPort;
    private final DcaExecutionRecorder recorder;
    private final DcaCycleOrderStrategy dcaStrategy;

    // empty = 이번 배치에서 DCA 신규 주문 없음 (비실행일·다른 timing·이미 판정·skip)
    Optional<OrderPlan> plan(BatchContext ctx, LocalDate tradeDate, Set<OrderTiming> creatableTimings, List<Order> existingOrders) {
        Strategy strategy = ctx.strategy();
        DcaConfig config = dcaDetailPort.findActiveByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("DCA 설정이 없습니다: " + strategy.id()));
        if (!creatableTimings.contains(config.timing())) return Optional.empty();
        if (tradeDate.isBefore(ctx.currentCycle().startDate())) return Optional.empty();
        LocalDate usSession = marketCalendarPort.usSessionDateOf(tradeDate);
        if (!DcaSchedule.isExecutionDay(usSession, config, marketCalendarPort::isUsSessionOpen)) return Optional.empty();
        if (dcaExecutionPort.find(strategy.id(), tradeDate).isPresent()) return Optional.empty();

        // 주문 저장 후 이력 기록 전 중단된 재개 — 기존 DCA 주문으로 ORDERED 복구
        Optional<Order> existing = existingOrders.stream().filter(o -> config.orderLeg().equals(o.orderLeg())).findFirst();
        if (existing.isPresent()) {
            recorder.recordOrdered(ctx, tradeDate, existing.get(), null);
            return Optional.empty();
        }

        DcaDecision decision = dcaStrategy.decide(new DcaCycleOrderStrategy.DcaInputs(config, strategy.ticker(), tradeDate,
                loadPrevClose(strategy.ticker(), usSession), loadSellable(ctx, config, tradeDate)));
        return switch (decision) {
            case DcaDecision.Planned p -> Optional.of(new OrderPlan(null, null, List.of(p.order())));
            case DcaDecision.Skipped s -> {
                recorder.recordSkipped(ctx, tradeDate, s.reason(), s.detail(), s.referencePrice());
                yield Optional.empty();
            }
        };
    }

    // 조회 US 세션 이전 마지막 확정 종가 — 실패·부족 시 null(DATA_UNAVAILABLE)
    private BigDecimal loadPrevClose(StrategyTicker ticker, LocalDate usSession) {
        try {
            List<BrokerCandle> candles = candlePort.getDailyCandlesBefore(ticker.name(), usSession, PREV_CLOSE_CANDLES);
            return candles.isEmpty() ? null : candles.getLast().close();
        } catch (RuntimeException e) {
            log.warn("[DCA] 일봉 조회 실패: ticker={}, usSession={}", ticker, usSession, e);
            return null;
        }
    }

    // SELL만 — 판매가능수량 − 같은 계좌·거래일·종목 기존 PLANNED/PLACED SELL 예약분, 실패 시 null
    private Integer loadSellable(BatchContext ctx, DcaConfig config, LocalDate tradeDate) {
        if (config.direction() != OrderDirection.SELL) return null;
        try {
            int sellable = sellableQuantityPort.getSellableQuantity(ctx.strategy().ticker(), ctx.account().brokerRef()).quantity();
            int reserved = (int) orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                    ctx.account().id(), tradeDate, ctx.strategy().ticker());
            return Math.max(0, sellable - reserved);
        } catch (RuntimeException e) {
            log.warn("[DCA] 판매가능수량 조회 실패: strategy={}", ctx.strategy().id(), e);
            return null;
        }
    }
}
```

예외 메시지·로그의 접근자명(`o.orderLeg()`, `.quantity()`)은 실제 `Order`, `SellableQuantity` record에 맞춘다. 단계 ①에서 DATA_UNAVAILABLE이 나면 관리자 알림도 보내야 한다(spec 5.7). `DcaExecutionRecorder.recordSkipped`에서 reason이 `DATA_UNAVAILABLE`이고 삽입에 성공했을 때 `TradingErrorEvent(null, "[DCA 데이터 실패] …")`를 발행한다. 기존 `TradingErrorEvent` 생성자 형태(`TradingBatchGuard`가 쓰는 것)를 따른다.

`TradingCandidatePlanner` 수정
- 생성자에 `DcaRoundPlanner dcaRoundPlanner`, `DcaExecutionRecorder dcaExecutionRecorder`를 추가한다. testing.md 규칙대로 `TradingParallelRunner`가 마지막 인자이므로 그 앞에 넣는다.
- `collectCycleCandidate`(`:122-160`)에서 `orderComputer.compute(...)` 호출(`:140`)을 다음으로 감싼다.

```java
        Optional<OrderPlan> planOpt = strategy.isDca()
                ? dcaRoundPlanner.plan(ctx, tradeDate, creatableTimings, existingOrders) // DCA는 판정·이력 기록 경로가 별도
                : orderComputer.compute(balance, strategy, prevClosePrice, tradeDate, ctx.currentCycle(), privacyBase, account.nickname(), price);
```

  - DCA는 `priceCapper.prepareForAllocation`이 no-op여야 한다. `needsCapCheck`가 false이므로 내부에서 거르는지 확인하고, 거르지 않으면 `if (!strategy.isDca())`로 감싼다.
  - `validateConcreteOrderLegs`가 허용 leg 목록을 검사한다면 `DCA_BUY`/`DCA_SELL`을 허용 목록에 추가한다.
- `saveAllocatedOrders`(`:226-237`): `InsufficientBalanceEvent` 발행 루프에서 DCA 컨텍스트를 제외한다. DCA의 `rejectedBuy`에는 `dcaExecutionRecorder.recordSkipped(ctx, tradeDate, DcaSkipReason.INSUFFICIENT_BALANCE, "예수금 부족(주문가능금액 < 1주 캡가)", null)`를 호출한다. `rejectedSell`이면 `NO_HOLDINGS`를 기록한다. 각 호출은 실패가 격리되도록 기존 루프의 try/catch 범위 안에 둔다.
- `allocateAndSave`(`:247-283`): 저장된 주문 중 leg가 `DCA_`로 시작하는 주문마다 `dcaExecutionRecorder.recordOrdered(ctx, tradeDate, savedOrder, savedOrder.price())`를 호출한다. referencePrice가 캡가로 남는 것을 피하려면 Planned의 `referencePrice`가 필요하다. 그 값은 `OrderPlan`으로 전달되지 않으므로, 단계 ①에서는 `dca_execution.reference_price`에 BUY는 캡가 ÷ 1.05를 넣지 말고 **null**을 저장한다. 이 결정을 `recordOrdered` 호출부 주석에 남긴다.

`TradingReporter.recordAndNotify`(`:63`):

```java
        // DCA: 매수·매도 DCA가 한 포지션을 공유해 내부 원장 대신 브로커 실잔고 스냅샷 기록(spec 5.4)
        AccountBalance postBalance = cycleStrategies.of(ctx.strategy().type()).snapshotsBrokerBalance()
                ? liveSnapshot(ctx).orElse(balance.applyExecutions(fills))
                : balance.applyExecutions(fills);
```

`liveSnapshot`은 `LiveBalancePort.getLiveBalance(ctx.account().brokerRef(), ctx.strategy().ticker())`의 `BrokerBalance`를 `new AccountBalance(b.holdings(), b.avgPrice(), b.usdDeposit())`로 변환한다. 실패하면 empty를 반환하고 warn 로그를 남긴다. `TradingReporter`에 `CycleOrderStrategies cycleStrategies`와 `LiveBalancePort liveBalancePort`가 없으면 생성자에 추가하고, `TradingReporterTest`에 `@Mock`을 추가한다.

`TradingUserNotificationPort`에 추가한다.

```java
    // DCA 회차 미주문 사용자 알림
    void notifyDcaRoundSkipped(TradingUserProfile profile, DcaRoundSkippedEvent event);
```

`TradingUserNotificationAdapter`에서 기존 `notifyInsufficientBalance`(`:64`)와 같은 패턴으로 구현한다.

```java
    @Override
    public void notifyDcaRoundSkipped(TradingUserProfile profile, DcaRoundSkippedEvent e) {
        String direction = e.direction() == null ? "" : e.direction().getLabel() + " ";
        String text = "[DCA 미주문] %s %s %sDCA (%s)%n사유: %s%s".formatted(
                e.accountNickname(), e.ticker(), direction, e.tradeDate(), e.reason().getLabel(),
                e.detail() == null ? "" : " — " + e.detail());
        notify(profile, "DCA 미주문", text);
    }
```

`TradingAlertNotifier`에 리스너를 추가한다.

```java
    @TransactionalEventListener(fallbackExecution = true)
    public void onDcaRoundSkipped(DcaRoundSkippedEvent event) {
        userNotificationPort.notifyDcaRoundSkipped(TradingUserProfiles.requireProfile(userProfilePort, event.userId()), event);
    }
```

`tradingcore.notify` 모듈이 `trading.application.event`를 이미 구독하고 있다(NamedInterface "event"). 새 이벤트도 같은 패키지라 Modulith 경계 변경이 없다.

- [ ] **Step 10: 테스트 통과 확인**

Run:
```
./gradlew :trading-core:test --tests '*Dca*' --tests '*TossCandleApiTest' --tests '*TradingCandidatePlannerTest' \
  --tests '*TradingReporterTest' --tests '*TradingServiceTest' --tests '*TradingAlertNotifier*' \
  --tests 'com.kista.tradingcore.architecture.*'
```
Expected: PASS. `TradingServiceTest`·`TradingCandidatePlannerTest`의 생성자 인자 추가는 mock으로 메운다.

- [ ] **Step 11: 커밋**

```bash
git add trading-core/src
git commit -m "feat(dca): 배치에서 DCA 회차를 판정·주문하고 이력과 미주문 알림을 기록한다"
```

---

### Task 7: 개장 배치 22:00 이동과 KIS DCA 개장 전 접수

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/schedule/TradingOpenScheduler.java:31`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/schedule/TradingBatchResumer.java:31`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/DstInfo.java:128`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingService.java:258-323` (`runOpenBatch`)
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/domain/model/DstInfoTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/schedule/TradingBatchResumerTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/TradingServiceTest.java`

**Interfaces:**
- Consumes: `Broker.supportsOpenAuctionOrders()`, `Strategy.isDca()`, `StrategyDcaDetailPort.findActiveByStrategyIds`, `DcaConfig.timing()`, Task 6의 `TradingCandidatePlanner` DCA 경로
- Produces: `TradingService` 내부 `placePreOpenDcaOrders(List<BatchContext> started, LocalDate tradeDate, PriceContext priceCtx)` (private)

- [ ] **Step 1: 실패하는 테스트 작성**

`DstInfoTest.java`. `openBatchMissedAt`는 package-private static이다.

```java
    @Test
    void open_batch_missed_from_22_00() {
        LocalDate start = LocalDate.of(2026, 10, 14); // 수
        assertThat(DstInfo.openBatchMissedAt(start, ZonedDateTime.of(start, LocalTime.of(21, 59), TimeZones.KST))).isFalse();
        assertThat(DstInfo.openBatchMissedAt(start, ZonedDateTime.of(start, LocalTime.of(22, 0), TimeZones.KST))).isTrue();
    }
```

`TradingBatchResumerTest.java`. 기존 개장 재개 테스트의 시각 22:30을 쓰는 케이스를 찾아, 22:00~22:29 사이 기동 시 개장 재개가 일어나는지 검증하는 케이스를 추가한다.

```java
    @Test
    void resumes_open_batch_after_22_00() {
        // 기존 "evening 개장 재개" 테스트와 같은 준비, 현재 시각만 수요일 22:10 KST
        // openScheduler.resume() 호출 검증
    }
```

`TradingServiceTest.java`. `runOpenBatch` 순서를 검증한다. KIS DCA MOO 컨텍스트 1개와 INFINITE 컨텍스트 1개로 `placeOpenOrders(contexts, dst)`를 호출한다. `dst.waitUntilMarketOpen()`이 반환하는 Duration을 0으로 stub하고, `InOrder`로 다음 순서를 확인한다: KIS DCA 주문 `placeAtOpenOrders` 호출 → `waitFor` → 나머지 AT_OPEN 접수.

```java
    @Test
    void kis_dca_moo_is_placed_before_market_open_wait() throws Exception {
        // contexts: kisDcaMooCtx(broker KIS, DCA MOO) + infiniteCtx
        // dst: 개장 대기 Duration.ZERO
        // 대기 시점 마커: runState/waitFor 경로를 기존 테스트가 관찰하는 방식(예: marketEventNotifier.notifyMarketOpen() 호출)으로 대체
        InOrder inOrder = inOrder(orderExecutor, marketEventNotifier);
        inOrder.verify(orderExecutor).placeAtOpenOrders(eq(TRADE_DATE), eq(kisDcaMooCtx.account()), any(), any(), isNull(), isNull(), eq(kisDcaMooCtx.strategy()));
        inOrder.verify(marketEventNotifier).notifyMarketOpen();
    }

    @Test
    void toss_dca_moo_waits_for_market_open() {
        // contexts: tossDcaMooCtx (broker TOSS)
        // notifyMarketOpen() 이후에 placeAtOpenOrders가 호출되는지 InOrder 검증
    }
```

기존 `TradingServiceTest`가 개장 배치 순서를 관찰하는 방식을 먼저 읽는다. 마커는 `notifyMarketOpen()`이 `waitUntilMarketOpen` 직후의 임계구역 안에서 호출된다는 사실(Task 탐색 결과 `runOpenBatch` 5단계)을 이용한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests '*DstInfoTest' --tests '*TradingBatchResumerTest' --tests '*TradingServiceTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

- `TradingOpenScheduler:31`:
  ```java
  @Scheduled(cron = "0 0 22 * * MON-FRI", zone = TimeZones.KST_ID) // 월~금 22:00 KST — DST 개장 30분 전(KIS DCA MOO 개장 전 접수), 비DST는 waitUntilMarketOpen 90분 대기
  ```
- `TradingBatchResumer:31`: `OPEN_CRON = LocalTime.of(22, 0)`. `resumeClose`의 윈도우 끝도 `OPEN_CRON`을 쓰므로 마감 재개 창은 22:00까지로 줄어든다. 마감 접수는 04:30~장마감이라 실질 영향은 없다.
- `DstInfo:128`: `OPEN_BATCH_FIRE_TIME = LocalTime.of(22, 0)`. 주석의 "22:30"도 고친다.
- `TradingService.runOpenBatch`: `waitFor("개장 시각", dst.waitUntilMarketOpen())`(`:286`) 직전에 다음을 추가한다.

```java
        // KIS DCA MOO/LOO는 개장 경매 참여를 위해 개장 전에 접수한다(spec 5.6) — 나머지 AT_OPEN은 개장 후 기존 흐름
        placePreOpenDcaOrders(started, tradeDate, priceCtx);
```

```java
    // 개장 전 접수 대상: KIS 계좌의 DCA AT_OPEN(MOO) 전략 — 계획·배정·저장·접수를 임계구역에서 수행
    private void placePreOpenDcaOrders(List<BatchContext> started, LocalDate tradeDate, PriceContext priceCtx) throws InterruptedException {
        List<BatchContext> targets = started.stream()
                .filter(c -> c.strategy().isDca() && c.account().broker().supportsOpenAuctionOrders())
                .toList();
        if (targets.isEmpty()) return;
        Map<UUID, DcaConfig> configs = dcaDetailPort.findActiveByStrategyIds(targets.stream().map(c -> c.strategy().id()).toList());
        List<BatchContext> moo = targets.stream()
                .filter(c -> configs.containsKey(c.strategy().id()) && configs.get(c.strategy().id()).timing() == OrderTiming.AT_OPEN)
                .toList();
        if (moo.isEmpty()) return;
        runState.critical(() -> {
            checkpoint(TradingBatchJob.OPEN, TradingBatchPhase.PLACING);
            // 이하 기존 개장 임계구역의 collectOpenCandidates → saveAllocatedOrders → placeAtOpenPlannedOrders 호출을 moo 대상으로 동일하게 수행
        });
    }
```

주석 자리에는 기존 `runOpenBatch`의 `:301-323` 블록(`collectOpenCandidates`, `saveAllocatedOrders`, `reloadPlacementPrices`, `placeAtOpenPlannedOrders` per state via `batchGuard.runSafely`)을 `moo` 리스트로 호출하는 코드를 넣는다. 공통부는 private 메서드 `planAndPlaceAtOpen(List<BatchContext> contexts, LocalDate tradeDate, PriceContext priceCtx)`로 추출해 두 곳에서 호출한다(중복 금지). `runState.critical`·`checkpoint`·`PriceContext`의 실제 시그니처와 람다 예외 형태는 기존 코드에 맞춘다. `TradingService`에 `StrategyDcaDetailPort dcaDetailPort` 필드를 추가하고, `TradingServiceTest`에 mock을 추가한다.

개장 후 기존 흐름에서 같은 DCA 컨텍스트를 다시 처리해도 중복은 생기지 않는다. `DcaRoundPlanner`가 이력 행으로 걸러내고, 슬롯 로직이 기존 PLANNED/PLACED 주문을 제외하며, `placeAtOpenOrders`는 AT_OPEN PLANNED만 접수하기 때문이다. 이 사실을 `placePreOpenDcaOrders` 주석 한 줄로 남긴다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :trading-core:test --tests '*DstInfoTest' --tests '*TradingBatchResumerTest' --tests '*TradingServiceTest' --tests '*TradingOpenScheduler*'`
Expected: PASS

- [ ] **Step 5: 문서 갱신**

- `docs/agents/scheduler-time-table.md:12`: "월~금 22:30 KST"를 "월~금 22:00 KST"로 바꾸고, "KIS DCA MOO는 개장 전 접수" 문구를 추가한다.
- `docs/agents/workflow.md`의 개장 경로 단락: "개장 배치(22:00 KST)는 KIS DCA AT_OPEN 주문을 `waitUntilMarketOpen()` 전에 계획·접수한 뒤, 나머지 AT_OPEN을 개장 후 접수한다" 한 문장을 추가한다. "재기동 종료·재개"의 "월~금 22:30~"은 22:00으로 바꾼다.
- `README.md`에 스케줄 시각 서술이 있으면 같이 고친다(`grep -n "22:30" README.md`).

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src docs/agents README.md
git commit -m "feat(dca): 개장 배치를 22:00으로 당기고 KIS DCA 개장 주문을 개장 전에 접수한다"
```

---

### Task 8: 회차 이력 API와 메타 API

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/usecase/DcaExecutionQuery.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaExecutionQueryService.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/DcaExecutionPage.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaExecutionPageResponse.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/TradingCycleController.java`
- Modify: `src/main/java/com/kista/api/web/MetaController.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/DcaExecutionQueryServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/TradingCycleControllerTest.java`
- Test: `src/test/java/com/kista/api/web/MetaControllerTest.java`

**Interfaces:**
- Consumes: `DcaExecutionPort.findPage/count`, `MarketCalendarPort.usSessionDateOf`, `OrderPort`(id 목록으로 주문 조회)
- Produces:
  - `DcaExecutionQuery.list(UUID strategyId, UUID requesterId, int page, int size): DcaExecutionPage`
  - `record DcaExecutionPage(List<Row> content, int page, int size, long totalElements)`
    - `record Row(DcaExecution execution, LocalDate usSessionDate, Order order)` (order는 nullable)

- [ ] **Step 1: 실패하는 테스트 작성**

`DcaExecutionQueryServiceTest.java`:

```java
class DcaExecutionQueryServiceTest {

    StrategyPort strategyPort = mock(StrategyPort.class);
    AccountPort accountPort = mock(AccountPort.class);
    DcaExecutionPort executionPort = mock(DcaExecutionPort.class);
    OrderPort orderPort = mock(OrderPort.class);
    MarketCalendarPort calendar = mock(MarketCalendarPort.class);
    DcaExecutionQueryService service = new DcaExecutionQueryService(strategyPort, accountPort, executionPort, orderPort, calendar);

    @Test
    void joins_orders_and_us_session_dates() {
        UUID strategyId = UUID.randomUUID();
        Strategy dca = new Strategy(strategyId, UUID.randomUUID(), StrategyType.DCA, StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(dca);
        UUID orderId = UUID.randomUUID();
        DcaExecution ordered = DcaExecution.ordered(strategyId, LocalDate.of(2026, 9, 30), orderId, 3, null);
        DcaExecution skipped = DcaExecution.skipped(strategyId, LocalDate.of(2026, 8, 31), DcaSkipReason.CONDITION_NOT_MET, "x", null);
        when(executionPort.findPage(strategyId, 0, 20)).thenReturn(List.of(ordered, skipped));
        when(executionPort.count(strategyId)).thenReturn(2L);
        when(calendar.usSessionDateOf(any())).thenAnswer(inv -> inv.<LocalDate>getArgument(0).minusDays(1));
        Order order = /* TradingFixtures로 id=orderId, status FILLED 주문 */;
        when(orderPort.findAllByIds(List.of(orderId))).thenReturn(List.of(order));

        DcaExecutionPage page = service.list(strategyId, UUID.randomUUID(), 0, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content().get(0).order()).isEqualTo(order);
        assertThat(page.content().get(0).usSessionDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(page.content().get(1).order()).isNull();
    }

    @Test
    void rejects_non_dca_strategy() {
        // INFINITE 전략 → IllegalArgumentException
    }
}
```

`OrderPort`에 id 목록 조회 메서드가 없으면 `List<Order> findAllByIds(Collection<UUID> ids)`를 추가하고 영속 어댑터에서 `findAllById`로 구현한다(기존 메서드가 있으면 그 이름을 쓴다).

`TradingCycleControllerTest.java`에 추가한다.

```java
    @Test
    void lists_dca_executions() throws Exception {
        when(dcaExecutionQuery.list(eq(STRATEGY_ID), any(), eq(0), eq(20))).thenReturn(new DcaExecutionPage(List.of(
                new DcaExecutionPage.Row(DcaExecution.skipped(STRATEGY_ID, LocalDate.of(2026, 8, 31),
                        DcaSkipReason.CONDITION_NOT_MET, "RSI(14) 48.20 > 35", null), LocalDate.of(2026, 8, 30), null)), 0, 20, 1));
        mockMvc.perform(get("/api/trading-cycles/{id}/dca-executions", STRATEGY_ID).with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.content[0].skipReason").value("CONDITION_NOT_MET"))
                .andExpect(jsonPath("$.content[0].usSessionDate").value("2026-08-30"))
                .andExpect(jsonPath("$.content[0].order").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }
```

`MetaControllerTest.java`. 기존 테스트에 다음 단언을 추가한다.

```java
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].orderTypes[0]").value("MOO"))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].divisionCounts").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='INFINITE')].orderTypes[0]").doesNotExist())
                .andExpect(jsonPath("$.orderTypes[?(@.code=='MOO')]").exists())
                .andExpect(jsonPath("$.orderDirections[0].label").value("매수"))
                .andExpect(jsonPath("$.dcaFrequencies[2].code").value("MONTHLY"))
                .andExpect(jsonPath("$.dcaOperators[0].label").value("이하"))
                .andExpect(jsonPath("$.dcaSkipReasons[0].code").value("DATA_UNAVAILABLE"))
                .andExpect(jsonPath("$.dcaIndicators[?(@.code=='SMA')].smaPeriods[0]").value(20))
                .andExpect(jsonPath("$.dcaIndicators[?(@.code=='RSI')].smaPeriods").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())));
```

JsonPath 필터 결과는 배열이라 매처 형태가 기존 테스트 관례와 다를 수 있다. 기존 `MetaControllerTest`의 필터 단언 방식을 따른다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests '*DcaExecutionQueryServiceTest' --tests '*TradingCycleControllerTest' && ./gradlew :test --tests 'com.kista.api.web.MetaControllerTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

`DcaExecutionQueryService`(package-private `@Service`, `@Transactional(readOnly = true)`):
- `strategyPort.findByIdOrThrow` → `accountPort.requireOwnedAccount(strategy.accountId(), requesterId)` → `isDca()` 아니면 `IllegalArgumentException`
- `findPage`와 `count`를 호출한다.
- ORDERED 행의 orderId를 모아 `orderPort.findAllByIds` 1회로 조회하고 id 맵으로 합친다.
- 각 행의 `usSessionDate`는 `marketCalendarPort.usSessionDateOf(tradeDate)`로 구한다.
- `size`는 1~100으로 clamp한다.

`DcaExecutionPageResponse`:

```java
// DCA 회차 이력 페이지 응답 (spec 7.3)
public record DcaExecutionPageResponse(List<Item> content, int page, int size, long totalElements) {

    public record Item(LocalDate kstTradeDate, LocalDate usSessionDate, String status, String skipReason, String detail,
                       BigDecimal referencePrice, Integer quantity, OrderSummary order) {}

    public record OrderSummary(UUID id, String status, Integer filledQuantity, BigDecimal filledPrice) {}

    public static DcaExecutionPageResponse from(DcaExecutionPage p) {
        return new DcaExecutionPageResponse(p.content().stream().map(DcaExecutionPageResponse::item).toList(),
                p.page(), p.size(), p.totalElements());
    }

    private static Item item(DcaExecutionPage.Row r) {
        DcaExecution e = r.execution();
        Order o = r.order();
        return new Item(e.tradeDate(), r.usSessionDate(), e.status().name(),
                e.skipReason() == null ? null : e.skipReason().name(), e.detail(), e.referencePrice(), e.quantity(),
                o == null ? null : new OrderSummary(o.id(), o.status().name(), o.filledQuantity(), o.filledPrice()));
    }
}
```

`order`가 null일 때 JSON에서 키를 빼려면 `Item.order`에 `@JsonInclude(NON_NULL)`을 붙인다. 테스트는 `doesNotExist()`로 검증한다. `Order` 접근자명은 실제 record에 맞춘다.

`TradingCycleController`:

```java
    @GetMapping("/api/trading-cycles/{id}/dca-executions")
    public DcaExecutionPageResponse listDcaExecutions(@PathVariable UUID id, @AuthenticationPrincipal UUID userId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return DcaExecutionPageResponse.from(dcaExecutionQuery.list(id, userId, page, size));
    }
```

필드 `DcaExecutionQuery dcaExecutionQuery`를 추가하고, 테스트에 `@MockitoBean`을 추가한다.

`MetaController`:
- `StrategyTypeMeta`에 `List<String> orderTypes, List<String> directions, List<String> frequencies, List<String> conditionIndicators`를 추가한다(`divisionCounts` 뒤). `from(StrategyType)`에서 `capability()`의 각 목록을 `Enum::name`으로 매핑한다.
- `MetaBundle` 끝에 `List<EnumMeta> orderTypes, List<EnumMeta> orderDirections, List<EnumMeta> dcaFrequencies, List<EnumMeta> dcaOperators, List<EnumMeta> dcaSkipReasons, List<DcaIndicatorMeta> dcaIndicators`를 추가한다.
  - `orderTypes`: `OrderType`에 label이 없으므로 `toEnumMeta(OrderType.values(), Enum::name)`을 쓴다. UI가 label이 필요하면 단계 ②에서 OrderType label을 추가한다(spec 7.4는 키만 확정).
  - 나머지는 `toEnumMeta(X.values(), X::getLabel)`.
  - `record DcaIndicatorMeta(String code, String label, String description, List<Integer> smaPeriods)`를 추가한다. `smaPeriods`는 `i == DcaIndicator.SMA ? DcaIndicator.SMA_PERIODS : List.of()`.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :trading-core:test --tests '*DcaExecutionQueryServiceTest' --tests '*TradingCycleControllerTest' && ./gradlew :test --tests 'com.kista.api.web.*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src src
git commit -m "feat(dca): DCA 회차 이력 조회 API와 메타 응답 필드를 추가한다"
```

---

### Task 9: 문서 동기화와 최종 검증

**Files:**
- Modify: `src/test/resources/architecture/flows.yml`
- Modify: `README.md`
- Modify: `docs/agents/modules/tradingcore/trading.md`
- Modify: `docs/agents/modules/tradingcore/matching.md`
- Modify: `docs/agents/modules/tradingcore/broker.md`
- Modify: `docs/agents/kis-api.md:60-61`
- Modify: `docs/agents/workflow.md` (BuyOrderPriceCapper 단락, 예산 우선순위 문장)

- [ ] **Step 1: 문서 갱신**

- `kis-api.md`
  - `:60`을 "`ORD_DVSN`(`KisOrderApi.resolveOrderDvsn(type, direction)`): LOC=`34`, MOC=매도 `33`/매수 `34`(LOC 캡), MOO=매도 `31`/매수 `32`(LOO 캡), 지정가=`00` — 미국 매수는 MOO/MOC 불가"로 바꾼다.
  - `:61`은 시장가 "0"이 매도 MOO/MOC에만 적용된다고 고친다.
- `workflow.md`
  - 계좌별 예산 배정 문장의 "`VR → INFINITE → PRIVACY`"를 "`VR → INFINITE → PRIVACY → DCA`"로 바꾼다.
  - "DCA는 `fitBuysToBudget`으로 캡가 기준 수량 축소, `needsCapCheck=false`로 접수 직전 재캡 대상 아님" 한 줄을 추가한다.
- `modules/tradingcore/matching.md`: `DcaCycleOrderStrategy`(decide 진입, plan 미사용), `DcaSchedule`, `DcaConfig`, `DcaDecision`.
- `modules/tradingcore/trading.md`
  - `DcaRoundPlanner`, `DcaExecutionRecorder`, `dca_execution` UNIQUE의 역할
  - 계좌+티커 중복 규칙 예외(DCA 방향별)
  - 보유·평단 SSOT가 브로커 실잔고라는 점(`snapshotsBrokerBalance`)
- `modules/tradingcore/broker.md`: `CandlePort.getDailyCandlesBefore`, Toss 매수 캡가·매도 0.01 규칙.
- `flows.yml`
  - 전략 등록 흐름에 DCA를 추가한다.
  - 개장 배치 단계에 "KIS DCA 개장 전 접수" 단계를 추가하고 시각을 22:00으로 바꾼다.
  - 마감 배치에 DCA 회차 판정 단계(`code: DcaRoundPlanner`)와 skip 알림(`DcaRoundSkippedEvent` → `TradingAlertNotifier`)을 추가한다.
  - `landscape`에 DCA 기능과 이력 조회를 추가한다.
  - `code:` 참조는 실제 클래스명이어야 `ArchitectureMapTest`가 통과한다.
- `README.md`: 전략 목록(DCA 추가), 스케줄 시각(22:00)

- [ ] **Step 2: 아키텍처·맵 테스트**

Run: `./gradlew :test --tests 'com.kista.api.architecture.*' && ./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*'`
Expected: PASS(Modulith verify, HexagonalArchitectureTest, ArchitectureMapTest, GradleModuleBoundaryTest)

- [ ] **Step 3: 전체 스위트 1회**

Run: `docker compose up -d postgres && ./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL`. 실패하면 `grep -oP 'failures="\K[^"]+' */build/test-results/test/TEST-*.xml build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 클래스를 찾아 고친다.

- [ ] **Step 4: 커밋**

```bash
git add README.md docs src/test/resources/architecture/flows.yml
git commit -m "docs(dca): DCA 단계 ① 흐름과 모듈 문서를 동기화한다"
```

---

## 배포 메모 (실행자 참고, 구현 범위 아님)

- 런타임 정책 기본값이 `enabled=false`라서, 배포만으로는 사용자에게 DCA가 노출되지 않는다. 운영자가 관리자 설정에서 DCA를 켜야 한다.
- 개장 배치 시각 변경은 kista-trading 재기동 후 적용된다. 배포는 접수창(22:00·04:30 KST 직후)을 피한다.
- spec 12의 Task 0 확인(KIS가 22:00 KST에 31/32 접수 가능한지)은 운영 첫 DCA MOO 회차 전에 끝내야 한다. 불가로 확인되면 KIS DCA MOO를 개장 후 경로로 되돌린다. 되돌릴 때는 `placePreOpenDcaOrders` 필터에서 `Broker.supportsOpenAuctionOrders()`를 false로 바꾸면 된다.
