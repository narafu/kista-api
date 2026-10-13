# DCA 단계 ② 지표 조건 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DCA 회차에 AVG_PRICE·SMA·RSI 조건(AND)을 평가해 미충족이면 `SKIPPED(CONDITION_NOT_MET)`로 기록·알림한다.

**Architecture:** 지표 계산·조건 평가는 matching 커널의 순수 코드(`DcaIndicators`, `DcaCondition`, `DcaCycleOrderStrategy.decide`)가 맡는다. I/O(250봉 페이징 조회·신선도 검증·프로세스 메모 캐시·실잔고 조회)는 trading `DcaRoundPlanner`가 맡는다. 조건은 V7 `strategy_dca_condition` 테이블에 버전별로 저장하고, API는 spec 7장 계약 그대로 타입화한다.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JPA, Flyway, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-10-13-dca-strategy-design.md` — 3.2(조건 검증), 5.2 3~4단계, 6.1, 7.1·7.2·7.4, 9장. 브랜치 `feat/dca-stage2`(feat/dca-stage1 위).

## Global Constraints

- skip 사유 우선순위: `DATA_UNAVAILABLE` → `CONDITION_NOT_MET` → `NO_HOLDINGS`(SELL) → `BELOW_ONE_SHARE` → `INSUFFICIENT_BALANCE`(allocator)
- 모든 조건의 지표값을 먼저 계산하고, 하나라도 계산할 수 없으면 다른 조건과 무관하게 `DATA_UNAVAILABLE`
- 조건 최대 10개, 같은 indicator 중복 허용, AND 결합, 입력 순서(sort_order) 보존
- SMA: `smaPeriod ∈ DcaIndicator.SMA_PERIODS`(20/50/120/200), threshold NULL. RSI: 0~100. AVG_PRICE: threshold > -100. FEAR_GREED: 단계 ②에서는 API 경계에서 400
- RSI는 14 고정(Wilder 평활). 봉은 항상 250봉 요청, Toss `adjusted=true`(신규 경로만)
- 마지막 봉 날짜 ≠ 직전 US 개장일이면 `DATA_UNAVAILABLE`
- 지표값·AVG_PRICE 기준가는 소수 둘째 자리 HALF_UP으로 반올림한 뒤 비교한다(detail 표시값과 판정값 일치)
- detail 형식: `RSI(14) 48.20 > 35`, `전일 종가 92.10 ≥ SMA(200) 88.40`, `전일 종가 90.00 > 평단 85.00 × (1 − 5%) = 80.75` — 연산자는 뒤집어 표시(LTE→`>`, LT→`≥`, GTE→`<`, GT→`≤`), 기준값은 `stripTrailingZeros().toPlainString()`
- 메타 `strategyTypes[DCA].conditionIndicators` = `[AVG_PRICE, SMA, RSI]`, 최상위 `dcaIndicators`는 4개 유지
- 이미 적용된 마이그레이션(V1~V6) 수정 금지. 신규는 `trading-core/src/main/resources/db/migration-trading/V7__dca_condition.sql`
- enum 컬럼은 VARCHAR(20), `CREATE TYPE` 금지
- 주석: 신규 코드에 `//` 인라인 한국어 주석, Javadoc·블록 주석 금지. 4-space, record, package-private 우선
- 예외 메시지는 사용자에게 그대로 보일 수 있는 한국어 완결 문장("~습니다.")
- 테스트는 `./gradlew :trading-core:test --tests '<FQCN>'`처럼 서브프로젝트를 지정해 좁혀 실행. 전체 `./gradlew test`는 Task 6에서 1회만
- DB 테스트 전 `docker compose up -d postgres`. `DataJpaTestBase` 상속 테스트는 `@Execution(ExecutionMode.SAME_THREAD)`
- 커밋: author `narafu <narafu@kakao.com>`, 한글 Conventional Commit 제목, 트레일러 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. push 금지
- Gradle 출력은 `2>&1 | grep -E "FAILED|BUILD|ERROR|Tests? "`로 걸러 받는다

## Review Focus

1. 신규 상장 등으로 봉이 200개 미만인데 SMA(200) 조건이 있으면 → 다른 조건이 거짓이어도 `DATA_UNAVAILABLE`(Task 1 테스트 `insufficient_candles_win_over_false_condition`)
2. 종가가 계속 같거나 계속 오르는 구간(avgLoss = 0) → 0으로 나누기 없이 RSI 50 또는 100(Task 1 `rsi_flat_series_is_50`, `rsi_only_gains_is_100`)
3. 같은 버전 행을 다시 저장해 조건이 바뀌는 경우 → (version, sort_order) UNIQUE 충돌 없이 교체(Task 3 `resave_replaces_conditions`)
4. Toss 페이지 경계에서 같은 날짜 봉이 중복되거나 첫 페이지에 진행 중 봉만 있는 경우 → 날짜 중복 없이 오름차순 count개(Task 2 `paging_dedups_boundary_and_returns_last_count`)
5. 병렬 배치에서 같은 티커 첫 조회가 실패한 경우 → 실패는 캐시되지 않아 다음 전략이 재조회(Task 4 `candle_failure_is_not_cached`)

---

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `trading-core/.../matching/domain/model/DcaIndicators.java` (신규) | SMA·Wilder RSI 순수 계산 | 1 |
| `trading-core/.../matching/domain/model/DcaCondition.java` (신규) | 조건 값 + 검증 | 1 |
| `trading-core/.../matching/domain/model/DcaConfig.java` | `conditions` 컴포넌트 추가 | 1 |
| `trading-core/.../matching/domain/strategy/DcaCycleOrderStrategy.java` | `DcaInputs` 확장, 조건 평가 | 1 |
| `trading-core/.../broker/adapter/out/toss/TossCandleApi.java` | 페이징·adjusted | 2 |
| `trading-core/src/main/resources/db/migration-trading/V7__dca_condition.sql` (신규) | 조건 테이블 | 3 |
| `trading-core/.../trading/adapter/out/persistence/StrategyDcaCondition{Entity,JpaRepository}.java` (신규) | 조건 영속 | 3 |
| `trading-core/.../trading/adapter/out/persistence/StrategyDcaDetailPersistenceAdapter.java` | 조건 저장·일괄 로드 | 3 |
| `trading-core/.../trading/application/service/DcaRoundPlanner.java` | 250봉·신선도·캐시·실잔고 | 4 |
| `trading-core/.../trading/adapter/in/web/dto/{DcaConditionRequest,DcaConditionResponse}.java` (신규), `DcaConfigRequest.java`, `DcaResponse.java` | API 타입화 | 5 |
| `shared/src/main/java/com/kista/shared/kernel/StrategyType.java` | 메타 지표 축소 | 5 |
| `flows.yml`, `docs/agents/modules/tradingcore/*.md`, spec 상태 | 문서 | 6 |

병렬 가능성: Task 1·2는 파일이 겹치지 않아 병렬 가능. Task 3·4·5는 Task 1 완료 후 서로 병렬 가능(파일 비중첩). Task 6은 마지막.

---

### Task 1: matching 커널 — 지표 계산·조건 모델·판정

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/DcaIndicators.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/DcaCondition.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/DcaConfig.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategy.java`
- Test (create): `trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/DcaIndicatorsTest.java`
- Test (create): `trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/DcaConditionTest.java`
- Test (modify): `trading-core/src/test/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategyTest.java`
- Modify(컴파일 정합만): `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaRoundPlanner.java`의 `new DcaInputs(...)` 호출 1곳

**Interfaces:**
- Produces:
  - `DcaIndicators.sma(List<BigDecimal> closes, int period) : Optional<BigDecimal>` (scale 2)
  - `DcaIndicators.rsiWilder(List<BigDecimal> closes, int period) : Optional<BigDecimal>` (scale 2), `DcaIndicators.RSI_PERIOD = 14`
  - `record DcaCondition(DcaIndicator indicator, DcaOperator operator, Integer smaPeriod, BigDecimal threshold)`
  - `DcaConfig` 7번째 컴포넌트 `List<DcaCondition> conditions` + 기존 6-인자 생성자 유지(조건 없음) + `boolean hasIndicator(DcaIndicator)`
  - `DcaCycleOrderStrategy.DcaInputs(DcaConfig config, StrategyTicker ticker, LocalDate tradeDate, List<BigDecimal> closes, Integer sellableQuantity, Holding holding)`, `DcaInputs.Holding(int quantity, BigDecimal avgPrice)`
  - `closes`: 오름차순 종가, null이면 조회 실패. `holding`: AVG_PRICE 조건이 있을 때만 의미, null이면 조회 실패

- [ ] **Step 1: `DcaIndicatorsTest` 작성 (실패 확인용)**

```java
package com.kista.tradingcore.matching.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DcaIndicatorsTest {

    private static List<BigDecimal> closes(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    void sma_averages_last_period_closes() {
        assertThat(DcaIndicators.sma(closes("1", "2", "3", "4", "5"), 3)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("4.00"));
    }

    @Test
    void sma_rounds_half_up_to_two_decimals() {
        assertThat(DcaIndicators.sma(closes("1", "1", "2"), 3)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.33"));
    }

    @Test
    void sma_insufficient_is_empty() {
        assertThat(DcaIndicators.sma(closes("1", "2"), 3)).isEmpty();
    }

    @Test
    void rsi_matches_wilder_reference() {
        // StockCharts RSI 예제(Wilder) — 15번째 종가에서 70.53, 다음 종가 46.00에서 66.32
        List<BigDecimal> base = closes("44.34", "44.09", "44.15", "43.61", "44.33", "44.83", "45.10", "45.42",
                "45.84", "46.08", "45.89", "46.03", "45.61", "46.28", "46.28");
        assertThat(DcaIndicators.rsiWilder(base, 14).orElseThrow().doubleValue()).isCloseTo(70.53, within(0.01));
        List<BigDecimal> next = new java.util.ArrayList<>(base);
        next.add(new BigDecimal("46.00"));
        assertThat(DcaIndicators.rsiWilder(next, 14).orElseThrow().doubleValue()).isCloseTo(66.32, within(0.01));
    }

    @Test
    void rsi_only_gains_is_100() {
        List<BigDecimal> rising = IntStream.rangeClosed(1, 20).mapToObj(BigDecimal::valueOf).toList();
        assertThat(DcaIndicators.rsiWilder(rising, 14)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("100.00"));
    }

    @Test
    void rsi_only_losses_is_0() {
        List<BigDecimal> falling = IntStream.rangeClosed(1, 20).mapToObj(i -> BigDecimal.valueOf(100 - i)).toList();
        assertThat(DcaIndicators.rsiWilder(falling, 14)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.00"));
    }

    @Test
    void rsi_flat_series_is_50() {
        assertThat(DcaIndicators.rsiWilder(Collections.nCopies(20, new BigDecimal("10")), 14))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("50.00"));
    }

    @Test
    void rsi_needs_period_plus_one_closes() {
        assertThat(DcaIndicators.rsiWilder(Collections.nCopies(14, BigDecimal.ONE), 14)).isEmpty();
        assertThat(DcaIndicators.rsiWilder(Collections.nCopies(15, BigDecimal.ONE), 14)).isPresent();
    }
}
```

참조값(70.53/66.32)이 구현과 맞지 않으면 알고리즘을 값에 맞추지 말고 손계산으로 재확인한 뒤 보고한다.

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.model.DcaIndicatorsTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패(`DcaIndicators` 없음)

- [ ] **Step 3: `DcaIndicators` 구현**

```java
package com.kista.tradingcore.matching.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

// DCA 지표 순수 계산 — 결과는 소수 둘째 자리 HALF_UP (detail 표시값과 판정값 일치)
public final class DcaIndicators {

    public static final int RSI_PERIOD = 14; // RSI 기간 고정 (spec 1장 RSI(14))

    private DcaIndicators() {}

    // 마지막 period개 종가 평균 — 부족하면 empty
    public static Optional<BigDecimal> sma(List<BigDecimal> closes, int period) {
        if (closes.size() < period) return Optional.empty();
        BigDecimal sum = closes.subList(closes.size() - period, closes.size()).stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP));
    }

    // Wilder 평활 RSI — 첫 평균은 처음 period개 변화량의 단순평균, 이후 (이전×(period−1)+현재)/period
    public static Optional<BigDecimal> rsiWilder(List<BigDecimal> closes, int period) {
        if (closes.size() < period + 1) return Optional.empty();
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes.get(i).subtract(closes.get(i - 1)).doubleValue();
            if (change > 0) avgGain += change; else avgLoss -= change;
        }
        avgGain /= period;
        avgLoss /= period;
        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i).subtract(closes.get(i - 1)).doubleValue();
            avgGain = (avgGain * (period - 1) + Math.max(change, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-change, 0)) / period;
        }
        // 변화 없음 → 50, 하락 없음 → 100 (0 나눗셈 방지)
        double rsi = avgLoss == 0 ? (avgGain == 0 ? 50 : 100) : 100 - 100 / (1 + avgGain / avgLoss);
        return Optional.of(BigDecimal.valueOf(rsi).setScale(2, RoundingMode.HALF_UP));
    }
}
```

- [ ] **Step 4: 통과 확인** — Step 2 명령 → PASS

- [ ] **Step 5: `DcaConditionTest` 작성**

```java
package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DcaConditionTest {

    private static DcaCondition of(DcaIndicator i, Integer sma, String threshold) {
        return new DcaCondition(i, DcaOperator.LTE, sma, threshold == null ? null : new BigDecimal(threshold));
    }

    @Test
    void valid_conditions() {
        assertThatCode(() -> of(DcaIndicator.SMA, 200, null)).doesNotThrowAnyException();
        assertThatCode(() -> of(DcaIndicator.RSI, null, "35")).doesNotThrowAnyException();
        assertThatCode(() -> of(DcaIndicator.AVG_PRICE, null, "-5.5")).doesNotThrowAnyException();
        assertThatCode(() -> of(DcaIndicator.FEAR_GREED, null, "25")).doesNotThrowAnyException(); // 도메인은 허용, 단계 ② 차단은 API 경계
    }

    @Test
    void sma_requires_supported_period_and_no_threshold() {
        assertThatThrownBy(() -> of(DcaIndicator.SMA, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.SMA, 60, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.SMA, 20, "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_sma_requires_threshold_and_no_period() {
        assertThatThrownBy(() -> of(DcaIndicator.RSI, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.RSI, 20, "30")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ranges() {
        assertThatThrownBy(() -> of(DcaIndicator.RSI, null, "100.01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.RSI, null, "-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.AVG_PRICE, null, "-100")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(DcaIndicator.AVG_PRICE, null, "100000000")).isInstanceOf(IllegalArgumentException.class); // numeric(10,2) 정수부 8자리 초과
        assertThatThrownBy(() -> of(DcaIndicator.RSI, null, "30.123")).isInstanceOf(IllegalArgumentException.class); // 소수 3자리
        assertThatCode(() -> of(DcaIndicator.RSI, null, "30.100")).doesNotThrowAnyException(); // trailing zero는 허용
    }

    @Test
    void null_indicator_or_operator_is_iae_not_npe() {
        assertThatThrownBy(() -> new DcaCondition(null, DcaOperator.LT, null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaCondition(DcaIndicator.RSI, null, null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 6: `DcaCondition` 구현**

```java
package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;

import java.math.BigDecimal;

// DCA 회차 조건 1개 — strategy_dca_condition 1행. 검증 실패는 IAE(400)
public record DcaCondition(
        DcaIndicator indicator, // 지표
        DcaOperator operator,   // 비교 연산자
        Integer smaPeriod,      // SMA 전용 기간
        BigDecimal threshold    // AVG_PRICE: n% / RSI·FEAR_GREED: 0~100 / SMA: null
) {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public DcaCondition {
        if (indicator == null || operator == null) throw new IllegalArgumentException("조건의 지표와 연산자는 필수입니다.");
        if (indicator == DcaIndicator.SMA) {
            if (smaPeriod == null || !DcaIndicator.SMA_PERIODS.contains(smaPeriod)) {
                throw new IllegalArgumentException("이동평균 기간은 " + DcaIndicator.SMA_PERIODS + " 중 하나여야 합니다.");
            }
            if (threshold != null) throw new IllegalArgumentException("이동평균 조건에는 기준값을 지정할 수 없습니다.");
        } else {
            if (smaPeriod != null) throw new IllegalArgumentException("이동평균 기간은 이동평균 조건에서만 지정할 수 있습니다.");
            if (threshold == null) throw new IllegalArgumentException("조건 기준값이 필요합니다.");
            // numeric(10,2) — 소수 2자리, 정수부 8자리 이하
            BigDecimal t = threshold.stripTrailingZeros();
            if (t.scale() > 2 || t.precision() - t.scale() > 8) throw new IllegalArgumentException("조건 기준값 형식이 올바르지 않습니다.");
            boolean percentScale = indicator == DcaIndicator.RSI || indicator == DcaIndicator.FEAR_GREED;
            if (percentScale && (threshold.signum() < 0 || threshold.compareTo(HUNDRED) > 0)) {
                throw new IllegalArgumentException("기준값은 0~100 사이여야 합니다.");
            }
            if (indicator == DcaIndicator.AVG_PRICE && threshold.compareTo(HUNDRED.negate()) <= 0) {
                throw new IllegalArgumentException("평단가 조건 기준값은 -100%보다 커야 합니다.");
            }
        }
    }
}
```

- [ ] **Step 7: `DcaConfig`에 `conditions` 추가**

레코드 컴포넌트 마지막에 `List<DcaCondition> conditions // 추가 조건 (AND, 입력 순서 보존, 최대 10개)`를 추가한다. 컴팩트 생성자 끝에:

```java
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.size() > MAX_CONDITIONS) throw new IllegalArgumentException("DCA 조건은 최대 " + MAX_CONDITIONS + "개입니다.");
```

본문에 추가:

```java
    public static final int MAX_CONDITIONS = 10; // 전략당 조건 상한

    // 조건 없는 설정 — 기존 호출부(테스트·단계 ① 경로) 호환
    public DcaConfig(OrderDirection direction, BigDecimal amountUsd, OrderType orderType, DcaFrequency frequency,
                     DayOfWeek dayOfWeek, Integer dayOfMonth) {
        this(direction, amountUsd, orderType, frequency, dayOfWeek, dayOfMonth, List.of());
    }

    // 해당 지표 조건 존재 여부 — planner가 실잔고 조회 여부 판단에 사용
    public boolean hasIndicator(DcaIndicator indicator) {
        return conditions.stream().anyMatch(c -> c.indicator() == indicator);
    }
```

파일 상단 주석 "조건(conditions)은 단계 ②에서 추가"를 지운다. `DcaConfigTest`가 있으면(`find trading-core/src/test -name DcaConfigTest.java`) 11개 조건 → IAE 테스트를 추가하고, 없으면 `DcaConditionTest`에 `config_rejects_more_than_ten_conditions`로 추가한다.

```java
    @Test
    void config_rejects_more_than_ten_conditions() {
        List<DcaCondition> eleven = java.util.Collections.nCopies(11, of(DcaIndicator.RSI, null, "30"));
        assertThatThrownBy(() -> new DcaConfig(com.kista.shared.kernel.OrderDirection.BUY, BigDecimal.TEN,
                com.kista.shared.kernel.OrderType.MOC, com.kista.shared.kernel.DcaFrequency.DAILY, null, null, eleven))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

(FQN 대신 import를 써도 된다 — 파일 import 스타일을 따른다.)

- [ ] **Step 8: `DcaCycleOrderStrategyTest` 수정·추가**

헬퍼를 새 `DcaInputs`로 바꾼다. 기존 테스트 본문은 그대로 둔다(`prevClose`만 넘기던 호출은 `closes = List.of(prevClose)`).

```java
    private DcaDecision decide(DcaConfig c, String prevClose, Integer sellable) {
        return decide(c, prevClose == null ? null : List.of(new BigDecimal(prevClose)), sellable, null);
    }

    private DcaDecision decide(DcaConfig c, List<BigDecimal> closes, Integer sellable, DcaCycleOrderStrategy.DcaInputs.Holding holding) {
        return strategy.decide(new DcaCycleOrderStrategy.DcaInputs(c, StrategyTicker.UPRO, DAY, closes, sellable, holding));
    }

    private static DcaConfig withConditions(OrderDirection dir, DcaCondition... conds) {
        return new DcaConfig(dir, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null, List.of(conds));
    }

    private static DcaCondition rsi(DcaOperator op, String t) { return new DcaCondition(DcaIndicator.RSI, op, null, new BigDecimal(t)); }
    private static DcaCondition sma(DcaOperator op, int p) { return new DcaCondition(DcaIndicator.SMA, op, p, null); }
    private static DcaCondition avg(DcaOperator op, String n) { return new DcaCondition(DcaIndicator.AVG_PRICE, op, null, new BigDecimal(n)); }

    // n봉 완만한 상승(80.0부터 0.1씩) + 마지막 종가 — RSI 100, 마지막 종가 > SMA
    private static List<BigDecimal> rising(int n, String last) {
        List<BigDecimal> list = new java.util.ArrayList<>(IntStream.range(0, n - 1).mapToObj(i -> BigDecimal.valueOf(80 + i * 0.1)).toList());
        list.add(new BigDecimal(last));
        return list;
    }

    @Test
    void all_conditions_true_plans() {
        // 상승 시계열 → RSI 100, 종가 87.12 > SMA(20)
        DcaDecision d = decide(withConditions(OrderDirection.BUY, rsi(DcaOperator.GTE, "70"), sma(DcaOperator.GT, 20)),
                rising(30, "87.12"), null, null);
        assertThat(d).isInstanceOf(DcaDecision.Planned.class);
    }

    @Test
    void first_false_condition_is_reported_with_negated_operator() {
        DcaDecision d = decide(withConditions(OrderDirection.BUY, rsi(DcaOperator.LTE, "35")), rising(30, "87.12"), null, null);
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.CONDITION_NOT_MET, "RSI(14) 100.00 > 35", new BigDecimal("87.12")));
    }

    @Test
    void sma_detail_format() {
        DcaDecision d = decide(withConditions(OrderDirection.BUY, sma(DcaOperator.LT, 20)), rising(30, "87.12"), null, null);
        DcaDecision.Skipped s = (DcaDecision.Skipped) d;
        assertThat(s.reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
        assertThat(s.detail()).startsWith("전일 종가 87.12 ≥ SMA(20) ");
    }

    @Test
    void insufficient_candles_win_over_false_condition() {
        // RSI 조건은 거짓(100 > 35)이지만 SMA(200)이 봉 부족 → DATA_UNAVAILABLE 우선
        DcaDecision d = decide(withConditions(OrderDirection.BUY, rsi(DcaOperator.LTE, "35"), sma(DcaOperator.LT, 200)),
                rising(30, "87.12"), null, null);
        assertThat(((DcaDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void avg_price_condition_compares_rounded_threshold() {
        // 평단 85 × (1 − 5%) = 80.75, 종가 90 > 80.75 → LTE 미충족
        DcaDecision d = decide(withConditions(OrderDirection.BUY, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")), null,
                new DcaCycleOrderStrategy.DcaInputs.Holding(10, new BigDecimal("85.00")));
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.CONDITION_NOT_MET,
                "전일 종가 90.00 > 평단 85.00 × (1 − 5%) = 80.75", new BigDecimal("90.00")));
    }

    @Test
    void avg_price_positive_threshold_detail() {
        DcaDecision d = decide(withConditions(OrderDirection.SELL, avg(DcaOperator.GTE, "10")), List.of(new BigDecimal("90.00")), 5,
                new DcaCycleOrderStrategy.DcaInputs.Holding(10, new BigDecimal("85.00")));
        assertThat(((DcaDecision.Skipped) d).detail()).isEqualTo("전일 종가 90.00 < 평단 85.00 × (1 + 10%) = 93.50");
    }

    @Test
    void avg_price_buy_with_zero_holdings_passes() {
        DcaDecision d = decide(withConditions(OrderDirection.BUY, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")), null,
                new DcaCycleOrderStrategy.DcaInputs.Holding(0, null));
        assertThat(d).isInstanceOf(DcaDecision.Planned.class);
    }

    @Test
    void avg_price_sell_with_zero_holdings_reports_no_holdings() {
        DcaDecision d = decide(withConditions(OrderDirection.SELL, avg(DcaOperator.GTE, "10")), List.of(new BigDecimal("90.00")), 0,
                new DcaCycleOrderStrategy.DcaInputs.Holding(0, null));
        assertThat(((DcaDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void avg_price_without_holding_data_is_data_unavailable() {
        DcaDecision d = decide(withConditions(OrderDirection.BUY, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")), null, null);
        assertThat(((DcaDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void condition_not_met_wins_over_no_holdings_and_below_one_share() {
        // SELL 판매가능 0 + 금액 < 종가지만 RSI 조건 거짓이 먼저
        DcaConfig c = new DcaConfig(OrderDirection.SELL, new BigDecimal("50"), OrderType.MOC, DcaFrequency.DAILY, null, null,
                List.of(rsi(DcaOperator.LTE, "35")));
        DcaDecision d = decide(c, rising(30, "87.12"), 0, null);
        assertThat(((DcaDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
    }
```

`rising()`은 `IntStream` import가 필요하다. `sma_detail_format`의 SMA 값은 시계열에 따라 정해지므로 접두사만 단언한다.

- [ ] **Step 9: `decide` 구현**

`DcaInputs`를 교체한다.

```java
    // DCA 판정 입력 — closes: 조회 US 세션 이전 확정 종가 오름차순(null=조회 실패), sellableQuantity: SELL만(예약분 차감 후),
    // holding: AVG_PRICE 조건이 있을 때만 브로커 실잔고(null=조회 실패·미조회)
    public record DcaInputs(DcaConfig config, StrategyTicker ticker, LocalDate tradeDate,
                            List<BigDecimal> closes, Integer sellableQuantity, Holding holding) {
        public record Holding(int quantity, BigDecimal avgPrice) {} // 계좌+티커 합산 보유
    }

    // 조건 1개 평가 결과 — passed=false면 detail이 skip 사유
    private record Evaluation(boolean passed, String detail) {}
```

`decide` 본문:

```java
    public DcaDecision decide(DcaInputs in) {
        List<BigDecimal> closes = in.closes();
        BigDecimal prevClose = closes == null || closes.isEmpty() ? null : closes.getLast();
        if (prevClose == null || prevClose.signum() <= 0) {
            return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        }
        DcaConfig c = in.config();
        boolean sell = c.direction() == OrderDirection.SELL;
        // skip 우선순위(spec 9장): DATA_UNAVAILABLE → CONDITION_NOT_MET → NO_HOLDINGS(SELL) → BELOW_ONE_SHARE
        if (sell && in.sellableQuantity() == null) {
            return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "판매가능수량 조회 실패", prevClose);
        }
        // 모든 조건 지표값을 먼저 계산 — 하나라도 계산 불가면 다른 조건과 무관하게 DATA_UNAVAILABLE
        List<Evaluation> evaluations = new ArrayList<>();
        for (DcaCondition cond : c.conditions()) {
            Optional<Evaluation> e = evaluate(cond, closes, prevClose, in.holding());
            if (e.isEmpty()) return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, dataFailure(cond), prevClose);
            evaluations.add(e.get());
        }
        Optional<Evaluation> failed = evaluations.stream().filter(e -> !e.passed()).findFirst();
        if (failed.isPresent()) return new DcaDecision.Skipped(DcaSkipReason.CONDITION_NOT_MET, failed.get().detail(), prevClose);
        // (이하 기존 NO_HOLDINGS / BELOW_ONE_SHARE / 주문 생성 코드 그대로)
```

평가 헬퍼:

```java
    // 조건 1개 평가 — 지표 계산 불가면 empty
    private static Optional<Evaluation> evaluate(DcaCondition cond, List<BigDecimal> closes, BigDecimal prevClose, DcaInputs.Holding holding) {
        String close = "전일 종가 " + money(prevClose);
        return switch (cond.indicator()) {
            case SMA -> DcaIndicators.sma(closes, cond.smaPeriod())
                    .map(v -> compare(cond.operator(), prevClose, v, close, "SMA(" + cond.smaPeriod() + ") " + money(v)));
            case RSI -> DcaIndicators.rsiWilder(closes, DcaIndicators.RSI_PERIOD)
                    .map(v -> compare(cond.operator(), v, cond.threshold(), "RSI(" + DcaIndicators.RSI_PERIOD + ") " + money(v), plain(cond.threshold())));
            case AVG_PRICE -> evaluateAvgPrice(cond, prevClose, holding, close);
            case FEAR_GREED -> Optional.empty(); // 단계 ③ — API 경계에서 400이라 도달하지 않음, 도달하면 fail-safe
        };
    }

    // 보유 0주는 통과 처리 — BUY는 첫 매수 허용, SELL은 뒤 단계 NO_HOLDINGS로 보고 (spec 5.2)
    private static Optional<Evaluation> evaluateAvgPrice(DcaCondition cond, BigDecimal prevClose, DcaInputs.Holding holding, String close) {
        if (holding == null) return Optional.empty();
        if (holding.quantity() <= 0) return Optional.of(new Evaluation(true, null));
        if (holding.avgPrice() == null || holding.avgPrice().signum() <= 0) return Optional.empty();
        BigDecimal n = cond.threshold();
        BigDecimal target = holding.avgPrice().multiply(BigDecimal.ONE.add(n.movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
        String sign = n.signum() < 0 ? "−" : "+";
        String rhs = "평단 " + money(holding.avgPrice()) + " × (1 " + sign + " " + plain(n.abs()) + "%) = " + money(target);
        return Optional.of(compare(cond.operator(), prevClose, target, close, rhs));
    }

    private static Evaluation compare(DcaOperator op, BigDecimal left, BigDecimal right, String leftLabel, String rightLabel) {
        boolean passed = op.test(left, right);
        return new Evaluation(passed, passed ? null : leftLabel + " " + negatedSymbol(op) + " " + rightLabel);
    }

    // 미충족 표시용 — 조건의 반대 관계
    private static String negatedSymbol(DcaOperator op) {
        return switch (op) {
            case LTE -> ">";
            case LT -> "≥";
            case GTE -> "<";
            case GT -> "≤";
        };
    }

    private static String dataFailure(DcaCondition cond) {
        return switch (cond.indicator()) {
            case SMA -> "SMA(" + cond.smaPeriod() + ") 계산용 일봉 부족";
            case RSI -> "RSI(" + DcaIndicators.RSI_PERIOD + ") 계산용 일봉 부족";
            case AVG_PRICE -> "보유 평단 조회 실패";
            case FEAR_GREED -> "공포탐욕지수 미지원";
        };
    }

    private static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    private static String plain(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
```

import 추가: `DcaIndicator`(switch 대상이라 불필요할 수 있음), `DcaOperator`, `DcaCondition`, `DcaIndicators`, `ArrayList`. 기존 `money`류 포맷과 겹치는 코드(`BELOW_ONE_SHARE` detail)는 그대로 둔다.

- [ ] **Step 10: `DcaRoundPlanner` 컴파일 정합** — `new DcaCycleOrderStrategy.DcaInputs(config, strategy.ticker(), tradeDate, loadPrevClose(...), loadSellable(...))`를 임시로 `prevClose == null ? null : List.of(prevClose)`, `holding = null`로 바꾼다(Task 4가 대체). 동작은 단계 ①과 같다.

- [ ] **Step 11: 테스트 통과 확인**

```bash
./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.*' --tests 'com.kista.tradingcore.trading.application.service.DcaRoundPlannerTest' 2>&1 | grep -E "FAILED|BUILD|error:"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 12: 커밋**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/matching trading-core/src/test/java/com/kista/tradingcore/matching trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaRoundPlanner.java
git commit -m "feat(dca): 평단가·이평선·RSI 조건 판정을 matching 커널에 추가한다"
```

---

### Task 2: Toss 일봉 페이징·adjusted

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossCandleApi.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/toss/TossCandleApiTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `CandlePort.getDailyCandlesBefore(symbol, beforeUsSession, count)` 계약 유지 — count가 200을 넘어도 최대 count개, `date < beforeUsSession`, 오름차순, 날짜 중복 없음. 요청에 `adjusted=true`

- [ ] **Step 1: 기존 테스트 보정 + 신규 테스트**

기존 `getDailyCandlesBefore_excludesSessionAndLater`는 첫 페이지가 count(5)를 못 채워 2페이지 요청이 생긴다. 스텁을 `.thenReturn(page1, emptyPage)`로 바꾸고, `verify(tossHttpClient, times(2))` 후 `paramsCaptor.getAllValues().getFirst()`로 첫 요청의 before·count를 단언하며 `adjusted`가 `"true"`인지도 단언한다. 새 테스트:

```java
    private static TossResult<TossCandleApi.CandlesResult> page(String... dates) {
        return new TossResult<>(new TossCandleApi.CandlesResult(java.util.Arrays.stream(dates)
                .map(d -> new TossCandleApi.CandleItem(d + "T00:00:00Z", "1", "1", "1", "1", "1")).toList()));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("일봉 이전 조회는 200봉 단위로 페이징하고 경계 중복을 제거해 마지막 count개를 오름차순으로 준다")
    void paging_dedups_boundary_and_returns_last_count() {
        // 1페이지: 10-14(진행 중) 10-13 10-12, 2페이지: 10-12(경계 중복) 10-09 10-08, 3페이지: 빈 응답
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(page("2026-10-14", "2026-10-13", "2026-10-12"),
                        page("2026-10-12", "2026-10-09", "2026-10-08"),
                        page());

        var result = tossCandleApi.getDailyCandlesBefore("UPRO", LocalDate.of(2026, 10, 14), 4);

        // 1페이지 필터 후 {10-12, 10-13}, 2페이지(before=10-12)는 10-12 제외 {10-08, 10-09} → 4개로 count 충족, 3페이지 미요청
        assertThat(result).extracting(c -> c.date()).containsExactly(
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13));
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient, org.mockito.Mockito.atLeast(2)).getCommon(eq("/api/v1/candles"), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getAllValues().get(1).getFirst("before")).isEqualTo("2026-10-12T00:00Z");
        assertThat(captor.getAllValues()).allSatisfy(p -> assertThat(p.getFirst("adjusted")).isEqualTo("true"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("count가 200을 넘으면 요청당 count는 200 이하다")
    void page_size_is_clamped_to_200() {
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class))).thenReturn(page());
        tossCandleApi.getDailyCandlesBefore("UPRO", LocalDate.of(2026, 10, 14), 250);
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient).getCommon(eq("/api/v1/candles"), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().getFirst("count")).isEqualTo("200");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("다른 캔들 조회는 adjusted를 보내지 않는다")
    void other_queries_do_not_send_adjusted() {
        when(tossHttpClient.getCommon(any(), any(), any(ParameterizedTypeReference.class))).thenReturn(page());
        tossCandleApi.getLatestCandles("UPRO", "1d", 5);
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient).getCommon(eq("/api/v1/candles"), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().containsKey("adjusted")).isFalse();
    }
```

3페이지 스텁은 쓰이지 않는다(연속 `thenReturn` 미사용은 strict stubbing 위반이 아니다).

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.toss.TossCandleApiTest' 2>&1 | grep -E "FAILED|BUILD"` → FAILED

- [ ] **Step 3: 구현**

`fetchCandles`에 `boolean adjusted` 인자를 추가하고 true일 때만 `params.add("adjusted", "true")`. 기존 호출 3곳(`getCandles`, `getLatestCandles`, `getCandleBefore`)은 `false`. `getDailyCandlesBefore` 교체:

```java
    // 200봉 단위 페이징 — 다음 페이지 before = 직전 페이지 가장 오래된 봉일 00:00 UTC, 날짜 기준 중복 제거.
    // 봉 date()는 US 세션일, adjusted=true(분할·배당 조정 — DCA 지표용, 이 메서드만)
    public List<TossCandle> getDailyCandlesBefore(String symbol, LocalDate beforeUsSession, int count) {
        TreeMap<LocalDate, TossCandle> byDate = new TreeMap<>();
        LocalDate before = beforeUsSession;
        int maxPages = count / MAX_COUNT + 2; // 첫 페이지 진행 중 봉 필터 여유
        for (int page = 0; page < maxPages && byDate.size() < count; page++) {
            LocalDate pageBefore = before;
            List<TossCandle> fetched = fetchCandles(symbol, "1d", Math.clamp(count - byDate.size(), 1, MAX_COUNT),
                    pageBefore.atStartOfDay(ZoneOffset.UTC).toString(), true).stream()
                    .filter(c -> c.date().isBefore(pageBefore))
                    .toList();
            if (fetched.isEmpty()) break;
            fetched.forEach(c -> byDate.putIfAbsent(c.date(), c));
            before = fetched.getFirst().date(); // fetchCandles는 오름차순
        }
        List<TossCandle> all = List.copyOf(byDate.values());
        return all.subList(Math.max(0, all.size() - count), all.size());
    }
```

`java.util.TreeMap` import. 클래스 상단 주석에 페이징 한 줄 추가.

- [ ] **Step 4: 통과 확인** — Step 2 명령 → BUILD SUCCESSFUL. `CandlePort` 구현 위임 어댑터(`grep -rn "getDailyCandlesBefore" trading-core/src/main`)가 count를 200으로 clamp하지 않는지 확인한다.

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/broker trading-core/src/test/java/com/kista/tradingcore/broker
git commit -m "feat(dca): Toss 일봉 이전 조회를 200봉 단위로 페이징하고 adjusted를 요청한다"
```

---

### Task 3: 조건 영속 (V7 + 어댑터)

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V7__dca_condition.sql`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/StrategyDcaConditionEntity.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/StrategyDcaConditionJpaRepository.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/StrategyDcaDetailPersistenceAdapter.java`
- Modify: `trading-core/src/test/java/com/kista/tradingcore/TradingApplicationContextLoadTest.java` (리포지토리 17 → 18)
- Modify: `docs/agents/architecture.md`, `docs/agents/testing.md` (trading-core 리포지토리 수 17 → 18 — `grep -n "17" `로 해당 줄만)
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/DcaPersistenceAdapterDbTest.java`

**Interfaces:**
- Consumes: Task 1 `DcaCondition`, `DcaConfig.conditions()`
- Produces: `StrategyDcaDetailPort`의 시그니처는 그대로, `save`·`findActiveByStrategyId`·`findActiveByStrategyIds`가 조건을 저장·복원(입력 순서)

- [ ] **Step 1: 마이그레이션**

```sql
-- DCA 회차 조건 — 버전별(strategy_dca_version 1:N), AND 결합, sort_order로 입력 순서 보존.
-- trading-core 단독 스키마라 root expand/contract 제약 밖. 신규 테이블만 만들어 기존 이미지와 호환된다.

CREATE TABLE trading.strategy_dca_condition (
    id uuid NOT NULL,
    strategy_version_id uuid NOT NULL,
    sort_order integer NOT NULL,
    indicator character varying(20) NOT NULL,
    operator character varying(20) NOT NULL,
    sma_period integer,
    threshold numeric(10,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT strategy_dca_condition_pkey PRIMARY KEY (id),
    CONSTRAINT strategy_dca_condition_strategy_version_id_fkey FOREIGN KEY (strategy_version_id)
        REFERENCES trading.strategy_dca_version(strategy_version_id) ON DELETE CASCADE,
    CONSTRAINT uq_strategy_dca_condition_version_sort UNIQUE (strategy_version_id, sort_order),
    CONSTRAINT strategy_dca_condition_sma_period_check CHECK ((indicator = 'SMA') = (sma_period IS NOT NULL)),
    CONSTRAINT strategy_dca_condition_threshold_check CHECK ((indicator = 'SMA') = (threshold IS NULL))
);
```

- [ ] **Step 2: 엔티티·리포지토리**

`BaseCreatedAtEntity`(created_at만) 상속, UUID PK는 `@UuidGenerator(style = UuidGenerator.Style.VERSION_7)` — 같은 패키지 `DcaExecutionEntity`의 id 선언 방식을 그대로 따른다(`@GeneratedValue` 금지). 필드 순서 = 컬럼 순서(id, strategyVersionId, sortOrder, indicator, operator, smaPeriod, threshold). enum은 `@Enumerated(EnumType.STRING) @Column(length = 20)`, threshold는 `precision = 10, scale = 2`. Lombok은 `StrategyDcaVersionEntity`와 같이 `@Getter @Setter(AccessLevel.PACKAGE) @NoArgsConstructor(access = AccessLevel.PROTECTED)`.

```java
interface StrategyDcaConditionJpaRepository extends JpaRepository<StrategyDcaConditionEntity, UUID> {

    // 여러 버전 조건 일괄 조회 — 입력 순서
    List<StrategyDcaConditionEntity> findByStrategyVersionIdInOrderBySortOrderAsc(Collection<UUID> strategyVersionIds);

    // 버전 조건 교체용 즉시 삭제 — 파생 delete는 flush 시 insert보다 늦게 실행돼 (version, sort_order) UNIQUE와 충돌한다
    @Modifying(flushAutomatically = true)
    @Query("delete from StrategyDcaConditionEntity c where c.strategyVersionId = :strategyVersionId")
    void deleteAllByStrategyVersionId(@Param("strategyVersionId") UUID strategyVersionId);
}
```

- [ ] **Step 3: DbTest 추가 (실패 확인용)**

`DcaPersistenceAdapterDbTest`의 기존 선행 행 삽입 헬퍼(accounts → strategy → strategy_version)를 재사용한다. 추가 테스트:

```java
    @Test
    void conditions_round_trip_in_input_order() {
        UUID versionId = /* 기존 헬퍼로 strategy + strategy_version 생성 */;
        List<DcaCondition> conds = List.of(
                new DcaCondition(DcaIndicator.RSI, DcaOperator.LTE, null, new BigDecimal("35")),
                new DcaCondition(DcaIndicator.SMA, DcaOperator.LT, 200, null),
                new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5.5")));
        adapter.save(versionId, configWith(conds));
        assertThat(adapter.findActiveByStrategyId(strategyId).orElseThrow().conditions()).containsExactlyElementsOf(conds);
        assertThat(adapter.findActiveByStrategyIds(List.of(strategyId)).get(strategyId).conditions()).containsExactlyElementsOf(conds);
    }

    @Test
    void resave_replaces_conditions() {
        // 같은 version 재저장 — 조건 2개 → 1개(순서 0 재사용)로 교체되어도 UNIQUE 충돌 없음
        adapter.save(versionId, configWith(List.of(rsi("30"), rsi("40"))));
        adapter.save(versionId, configWith(List.of(rsi("50"))));
        assertThat(adapter.findActiveByStrategyId(strategyId).orElseThrow().conditions()).containsExactly(rsi("50"));
    }

    @Test
    void config_without_conditions_loads_empty_list() {
        adapter.save(versionId, configWith(List.of()));
        assertThat(adapter.findActiveByStrategyId(strategyId).orElseThrow().conditions()).isEmpty();
    }
```

`DcaCondition` equals는 BigDecimal scale까지 비교한다. DB에서 `35.00`으로 돌아오므로 toDomain에서 `threshold.stripTrailingZeros()`를 하지 말고, 테스트 기대값을 `new BigDecimal("35.00")`, `"-5.50"`, `"50.00"`처럼 scale 2로 쓴다. 이 결정을 테스트에 주석 한 줄로 남긴다.

- [ ] **Step 4: 실패 확인** — `docker compose up -d postgres` 후 `./gradlew :trading-core:test --tests '*DcaPersistenceAdapterDbTest' 2>&1 | grep -E "FAILED|BUILD"` → FAILED(조건 미저장)

- [ ] **Step 5: 어댑터 구현**

```java
    private final StrategyDcaVersionJpaRepository jpaRepository;
    private final StrategyDcaConditionJpaRepository conditionRepository; // 버전별 조건

    @Override
    @Transactional
    public DcaConfig save(UUID strategyVersionId, DcaConfig config) {
        // (기존 버전 행 upsert 그대로)
        StrategyDcaVersionEntity saved = jpaRepository.save(entity);
        // 조건 교체 — 즉시 삭제 후 입력 순서대로 insert
        conditionRepository.deleteAllByStrategyVersionId(strategyVersionId);
        List<StrategyDcaConditionEntity> conditions = conditionRepository.saveAll(
                IntStream.range(0, config.conditions().size())
                        .mapToObj(i -> StrategyDcaConditionEntity.of(strategyVersionId, i, config.conditions().get(i)))
                        .toList());
        return toDomain(saved, conditions);
    }
```

`StrategyDcaConditionEntity.of(versionId, sortOrder, DcaCondition)` 정적 팩토리(package-private)와 `toDomain()`을 엔티티에 둔다. `findActiveByStrategyId`는 버전 조회 후 `findByStrategyVersionIdInOrderBySortOrderAsc(List.of(id))`. `findActiveByStrategyIds`는 버전 id 목록으로 조건을 1회 일괄 조회 후 `Collectors.groupingBy(getStrategyVersionId)`로 묶는다(N+1 금지). `toDomain(StrategyDcaVersionEntity, List<StrategyDcaConditionEntity>)`는 7-인자 생성자를 쓴다. `@Transactional`은 `org.springframework.transaction.annotation.Transactional`.

- [ ] **Step 6: 리포지토리 수 갱신** — `TradingApplicationContextLoadTest.TRADING_JPA_REPOSITORIES = 18`(주석: "DCA 설정·조건·회차 리포지토리 3개 포함"), `docs/agents/architecture.md`의 "trading-core 17"과 `docs/agents/testing.md`의 "리포지토리 17"을 18로.

- [ ] **Step 7: 통과 확인**

```bash
./gradlew :trading-core:test --tests '*DcaPersistenceAdapterDbTest' --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: BUILD SUCCESSFUL (Flyway V7 적용 + `ddl-auto: validate` 통과 포함)

- [ ] **Step 8: 커밋**

```bash
git add trading-core/src/main/resources/db/migration-trading/V7__dca_condition.sql trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence trading-core/src/test/java/com/kista/tradingcore docs/agents/architecture.md docs/agents/testing.md
git commit -m "feat(dca): DCA 조건을 버전별 strategy_dca_condition 테이블에 저장한다"
```

---

### Task 4: DcaRoundPlanner — 250봉·신선도·캐시·실잔고

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaRoundPlanner.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/DcaRoundPlannerTest.java`

**Interfaces:**
- Consumes: Task 1 `DcaInputs(config, ticker, tradeDate, closes, sellableQuantity, holding)`, `DcaInputs.Holding`, `DcaConfig.hasIndicator`; Task 2 `getDailyCandlesBefore` 250봉; `LiveBalancePort.getLiveBalance(BrokerAccountRef, StrategyTicker) : BrokerBalance(holdings, avgPrice, usdDeposit)`; `MarketCalendarPort.isUsSessionOpen`
- Produces: 생성자 인자에 `LiveBalancePort` 추가(마지막 위치가 아니라 `SellableQuantityPort` 다음). 다른 태스크가 의존하지 않음

- [ ] **Step 1: 테스트 수정·추가**

`setUp`: planner 생성에 `liveBalancePort = mock(LiveBalancePort.class)` 추가, 봉 스텁 count를 `5` → `250`. 추가 테스트:

```java
    @Test
    void stale_feed_is_data_unavailable() {
        // 직전 개장일은 10-12인데 마지막 봉이 10-09 → 피드 지연
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 9), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isEmpty();
        verify(recorder).recordSkipped(any(), eq(TRADE_DATE), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), isNull());
    }

    @Test
    void previous_open_session_skips_holidays() {
        // 10-12 휴장 → 직전 개장일 10-09, 마지막 봉 10-09면 정상
        when(calendar.isUsSessionOpen(LocalDate.of(2026, 10, 12))).thenReturn(false);
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 9), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isPresent();
    }

    @Test
    void candles_are_cached_per_ticker_and_session() {
        dca(OrderDirection.BUY, OrderType.MOC);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of());
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of());
        verify(candlePort, org.mockito.Mockito.times(1)).getDailyCandlesBefore("UPRO", US_SESSION, 250);
    }

    @Test
    void candle_failure_is_not_cached() {
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250))
                .thenThrow(new RuntimeException("toss down"))
                .thenReturn(List.of(new BrokerCandle(LocalDate.of(2026, 10, 12), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isEmpty();
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isPresent();
    }

    @Test
    void avg_price_condition_loads_live_balance() {
        dcaWith(OrderDirection.BUY, OrderType.MOC, new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5")));
        when(liveBalancePort.getLiveBalance(ctx.account().brokerRef(), StrategyTicker.UPRO))
                .thenReturn(new BrokerBalance(10, new BigDecimal("80.00"), BigDecimal.ZERO)); // 80×0.95=76 < 87.12 → 미충족
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isEmpty();
        verify(recorder).recordSkipped(any(), eq(TRADE_DATE), eq(DcaSkipReason.CONDITION_NOT_MET), anyString(), any());
    }

    @Test
    void no_avg_price_condition_skips_live_balance() {
        dca(OrderDirection.BUY, OrderType.MOC);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of());
        verifyNoInteractions(liveBalancePort);
    }

    @Test
    void live_balance_failure_is_data_unavailable() {
        dcaWith(OrderDirection.BUY, OrderType.MOC, new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5")));
        when(liveBalancePort.getLiveBalance(any(), any())).thenThrow(new RuntimeException("broker down"));
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of());
        verify(recorder).recordSkipped(any(), eq(TRADE_DATE), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), any());
    }
```

`dcaWith(dir, type, DcaCondition...)` 헬퍼는 기존 `dca(dir, type)` 헬퍼와 같은 방식으로 `dcaDetailPort.findActiveByStrategyId` 스텁을 만들되 7-인자 `DcaConfig`를 쓴다. `recordSkipped` 시그니처는 기존 `candle_failure_records_data_unavailable` 테스트의 verify 형태를 따른다(인자 순서가 다르면 그에 맞춘다).

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.DcaRoundPlannerTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패/FAILED

- [ ] **Step 3: 구현**

```java
    private static final int CANDLE_COUNT = 250;            // SMA 최대 200 + RSI 안정화 (spec 6.1)
    private static final int PREVIOUS_SESSION_SCAN_DAYS = 10; // 직전 개장일 역탐색 상한 (연휴 여유)

    private final LiveBalancePort liveBalancePort;          // AVG_PRICE 조건용 브로커 실잔고
    // (ticker, US 세션) → 검증된 종가 — 세션 이전 확정 봉은 불변이라 배치·전략 간 공유, 실패는 캐시하지 않음
    private final Map<CandleKey, List<BigDecimal>> closeCache = new ConcurrentHashMap<>();

    private record CandleKey(StrategyTicker ticker, LocalDate usSession) {}
```

`@RequiredArgsConstructor`라 `closeCache`는 초기화식이 있는 final 필드라 생성자 인자에 포함되지 않는다. `LiveBalancePort` 필드는 `SellableQuantityPort` 바로 다음에 선언한다(생성자 인자 순서 = 필드 순서).

`decide` 호출부:

```java
        DcaDecision decision = dcaStrategy.decide(new DcaCycleOrderStrategy.DcaInputs(config, strategy.ticker(), tradeDate,
                loadCloses(strategy.ticker(), usSession), loadSellable(ctx, config, tradeDate), loadHolding(ctx, config)));
```

`loadPrevClose`를 `loadCloses`로 교체:

```java
    // 조회 US 세션 이전 확정 종가 250봉 — 실패·피드 지연(마지막 봉 ≠ 직전 개장일)이면 null(DATA_UNAVAILABLE)
    private List<BigDecimal> loadCloses(StrategyTicker ticker, LocalDate usSession) {
        CandleKey key = new CandleKey(ticker, usSession);
        List<BigDecimal> cached = closeCache.get(key);
        if (cached != null) return cached;
        try {
            List<BrokerCandle> candles = candlePort.getDailyCandlesBefore(ticker.name(), usSession, CANDLE_COUNT);
            LocalDate expected = previousOpenSession(usSession);
            if (candles.isEmpty() || expected == null || !candles.getLast().date().equals(expected)) {
                log.warn("[DCA] 일봉 피드 지연·부족: ticker={}, usSession={}, 기대={}, 마지막={}",
                        ticker, usSession, expected, candles.isEmpty() ? null : candles.getLast().date());
                return null;
            }
            List<BigDecimal> closes = candles.stream().map(BrokerCandle::close).toList();
            closeCache.keySet().removeIf(k -> !k.usSession().equals(usSession)); // 지난 세션 제거 — 맵 크기 ≤ 티커 수
            closeCache.put(key, closes);
            return closes;
        } catch (RuntimeException e) {
            log.warn("[DCA] 일봉 조회 실패: ticker={}, usSession={}", ticker, usSession, e);
            return null;
        }
    }

    // usSession 직전 US 개장일 — 휴장 데이터 미존재 등으로 못 찾으면 null
    private LocalDate previousOpenSession(LocalDate usSession) {
        for (int i = 1; i <= PREVIOUS_SESSION_SCAN_DAYS; i++) {
            LocalDate d = usSession.minusDays(i);
            if (marketCalendarPort.isUsSessionOpen(d)) return d;
        }
        return null;
    }

    // AVG_PRICE 조건이 있을 때만 브로커 실잔고(계좌+티커 합산) — 실패 시 null(DATA_UNAVAILABLE)
    private DcaCycleOrderStrategy.DcaInputs.Holding loadHolding(BatchContext ctx, DcaConfig config) {
        if (!config.hasIndicator(DcaIndicator.AVG_PRICE)) return null;
        try {
            BrokerBalance b = liveBalancePort.getLiveBalance(ctx.account().brokerRef(), ctx.strategy().ticker());
            return new DcaCycleOrderStrategy.DcaInputs.Holding(b.holdings(), b.avgPrice());
        } catch (RuntimeException e) {
            log.warn("[DCA] 실잔고 조회 실패: strategy={}", ctx.strategy().id(), e);
            return null;
        }
    }
```

`PREV_CLOSE_CANDLES` 상수와 Task 1 Step 10 임시 코드를 지운다. 클래스 주석의 "전일 종가·잔고를 조회" 서술을 "250봉 종가(신선도 검증·세션 캐시)·잔고를 조회"로 바꾼다. import: `LiveBalancePort`, `BrokerBalance`, `DcaIndicator`, `Map`, `ConcurrentHashMap`.

- [ ] **Step 4: 통과 확인** — Step 2 명령 → BUILD SUCCESSFUL. `TradingCandidatePlannerTest` 등 `new DcaRoundPlanner(` 직접 생성처가 있으면(`grep -rn "new DcaRoundPlanner(" trading-core/src/test`) 인자를 맞추고 그 테스트도 실행한다.

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaRoundPlanner.java trading-core/src/test/java/com/kista/tradingcore/trading/application/service
git commit -m "feat(dca): 회차 판정에 250봉 종가·피드 신선도 검증·세션 캐시·실잔고 평단을 연결한다"
```

---

### Task 5: API 타입화·FEAR_GREED 차단·메타 지표 축소

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConditionRequest.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConditionResponse.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConfigRequest.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaResponse.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyType.java` (DCA `conditionIndicators`)
- Test: `TradingCycleControllerTest`(trading-core), 메타·capability 테스트(`grep -rln "conditionIndicators" src/test trading-core/src/test shared/src/test`)

**Interfaces:**
- Consumes: Task 1 `DcaCondition`, 7-인자 `DcaConfig`
- Produces: 요청 `conditions[]` = `{indicator, operator, smaPeriod, threshold}`, 응답 `dca.conditions[]` 같은 필드(spec 7.1·7.2). FEAR_GREED 요청 → 400

- [ ] **Step 1: 테스트 작성**

`TradingCycleControllerTest`에서 단계 ① "conditions 비어 있지 않으면 400" 테스트(`grep -n "아직 지원하지 않습니다\|conditions" `)를 찾아 아래로 교체·추가한다. 기존 DCA 등록 테스트의 요청 JSON·스텁 방식을 그대로 따른다.

- `register_dca_with_conditions_passes_typed_conditions`: `conditions: [{indicator: RSI, operator: LTE, threshold: 35}, {indicator: SMA, operator: LT, smaPeriod: 200}]` → 201, 유스케이스에 전달된 `DcaConfig.conditions()`가 두 `DcaCondition`과 같음(ArgumentCaptor)
- `register_dca_with_fear_greed_is_400`: `{indicator: FEAR_GREED, operator: LTE, threshold: 25}` → 400
- `register_dca_with_invalid_sma_period_is_400`: `{indicator: SMA, operator: LT, smaPeriod: 60}` → 400
- `get_dca_returns_conditions`: 조회 응답 `$.dca.conditions[0].indicator == "RSI"`, `$.dca.conditions[0].threshold == 35`, `$.dca.conditions[1].smaPeriod == 200`
- `PUT /dca-config`에도 FEAR_GREED 400 1건

메타·capability 테스트에서 DCA `conditionIndicators` 기대값을 `["AVG_PRICE","SMA","RSI"]`로 바꾸고, 최상위 `dcaIndicators`가 4개(FEAR_GREED 포함)인 단언이 있으면 유지한다(없으면 1건 추가).

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*TradingCycleControllerTest' 2>&1 | grep -E "FAILED|BUILD"` → FAILED

- [ ] **Step 3: 구현**

```java
// DCA 조건 요청 1건 — 검증은 DcaCondition(IAE→400), FEAR_GREED는 단계 ③ 전까지 차단
public record DcaConditionRequest(
        DcaIndicator indicator, // 지표
        DcaOperator operator,   // 비교 연산자
        Integer smaPeriod,      // SMA 전용
        BigDecimal threshold    // SMA 외 기준값
) {
    DcaCondition toDomain() {
        if (indicator == DcaIndicator.FEAR_GREED) throw new IllegalArgumentException("공포탐욕지수 조건은 아직 지원하지 않습니다.");
        return new DcaCondition(indicator, operator, smaPeriod, threshold);
    }
}
```

```java
// DCA 조건 응답 1건 — TradingCycleResponse.dca.conditions
public record DcaConditionResponse(DcaIndicator indicator, DcaOperator operator, Integer smaPeriod, BigDecimal threshold) {
    static DcaConditionResponse from(DcaCondition c) {
        return new DcaConditionResponse(c.indicator(), c.operator(), c.smaPeriod(), c.threshold());
    }
}
```

`DcaConfigRequest`: `List<DcaConditionRequest> conditions`로 바꾸고 `toDomain()`:

```java
    public DcaConfig toDomain() {
        List<DcaCondition> domainConditions = conditions == null ? List.of()
                : conditions.stream().map(DcaConditionRequest::toDomain).toList();
        return new DcaConfig(direction, amountUsd, orderType, frequency, dayOfWeek, dayOfMonth, domainConditions);
    }
```

리스트 원소가 JSON `null`이면 NPE가 나므로 `conditions.contains(null)`이면 `IllegalArgumentException("조건 항목이 비어 있습니다.")`. 레코드 주석에서 "단계 ①에서 빈 배열만 허용"을 지우고 `Map` import를 제거한다.

`DcaResponse`: `List<DcaConditionResponse> conditions`, `from()`에서 `c.conditions().stream().map(DcaConditionResponse::from).toList()`.

`StrategyType` DCA: `List.of(DcaIndicator.values())` → `List.of(DcaIndicator.AVG_PRICE, DcaIndicator.SMA, DcaIndicator.RSI) // FEAR_GREED는 단계 ③(CNN 어댑터)에서 추가`.

`TradingExceptionHandler`가 `IllegalArgumentException`의 메시지를 detail로 내보내는지 확인한다(`grep -n "IllegalArgumentException" trading-core/src/main/java/com/kista/tradingcore/web`). 내보내지 않으면 400만 확인하고 넘어간다(범위 밖).

- [ ] **Step 4: 통과 확인**

```bash
./gradlew :trading-core:test --tests '*TradingCycleControllerTest' 2>&1 | grep -E "FAILED|BUILD"
./gradlew :test --tests '*MetaControllerTest' :shared:test 2>&1 | grep -E "FAILED|BUILD"
```
(메타·capability 테스트가 다른 위치면 그 FQCN으로.) Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto shared/src/main/java/com/kista/shared/kernel/StrategyType.java trading-core/src/test src/test shared/src/test
git commit -m "feat(dca): DCA 조건 요청·응답을 타입화하고 공포탐욕지수 조건은 400으로 막는다"
```

---

### Task 6: 문서 동기화 + 전체 테스트 1회

**Files:**
- Modify: `src/test/resources/architecture/flows.yml` (DCA 회차 판정 step, `code:` 목록 유지)
- Modify: `docs/agents/modules/tradingcore/matching.md`, `docs/agents/modules/tradingcore/trading.md`, `docs/agents/modules/tradingcore/broker.md` 중 DCA·CandlePort 서술이 있는 곳(`grep -n "DCA\|getDailyCandlesBefore\|조건" `)
- Modify: `docs/superpowers/specs/2026-10-13-dca-strategy-design.md` 상단 "상태" 줄

- [ ] **Step 1: flows.yml** — `title: DCA 회차 판정` step의 desc를 다음 의미로 바꾼다: "기존 주문·timing·실행일·이력을 확인하고, 티커·세션별로 캐시한 250봉 종가(마지막 봉이 직전 개장일이 아니면 데이터 실패)와 판매가능수량·실잔고 평단을 조회해 평단가·이평선·RSI 조건을 AND로 평가한다. 데이터 실패 → 조건 미충족 → 보유 없음 → 1주 미만 순으로 skip 사유를 정하고 이력·알림을 남긴다." 기존 문장 톤·길이를 맞춘다. `code:` 참조는 바꾸지 않는다.
- [ ] **Step 2: 모듈 문서** — matching.md에 `DcaIndicators`(SMA·Wilder RSI 14)·`DcaCondition`(검증 규칙)·판정 순서 한 단락, trading.md에 `DcaRoundPlanner` 캐시·신선도·실잔고 조회와 `strategy_dca_condition`, broker.md에 `getDailyCandlesBefore` 페이징·adjusted. "단계 ②에서" 같은 미래형 서술이 남아 있으면 현재형으로 고친다.
- [ ] **Step 3: spec 상태** — "상태: 설계 확정 대기(구현 전)"를 "상태: 단계 ①·② 구현 완료(feat/dca-stage2), 단계 ③ 미착수"로.
- [ ] **Step 4: 전체 테스트 1회**

```bash
docker compose up -d postgres
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
grep -l 'failures="[1-9]' build/test-results/test/TEST-*.xml trading-core/build/test-results/test/TEST-*.xml 2>/dev/null
```
Expected: BUILD SUCCESSFUL, grep 결과 없음. `ArchitectureMapTest`·`HexagonalArchitectureTest`·`ModulithArchitectureTest` 포함.

- [ ] **Step 5: 커밋**

```bash
git add src/test/resources/architecture/flows.yml docs/agents/modules/tradingcore docs/superpowers/specs/2026-10-13-dca-strategy-design.md
git commit -m "docs(dca): 단계 ② 지표 조건 흐름과 모듈 문서를 동기화한다"
```

---

## 배포 메모 (실행 범위 밖)

- spec 11장 0번: Toss 캔들 `adjusted` 파라미터 실측(분할 이력이 있는 티커로 adjusted true/false 종가 비교). 무시되는 파라미터면 지표 왜곡 위험을 spec 12장에 추가한다.
- kista-ui에 알림: 메타 `strategyTypes[DCA].conditionIndicators`가 `[AVG_PRICE, SMA, RSI]`로 바뀜, 조건 등록이 열림(FEAR_GREED는 400), 응답 `dca.conditions[]` 필드 `{indicator, operator, smaPeriod, threshold}`(SMA 외 smaPeriod null, SMA는 threshold null).
- kista-api·kista-trading 동시 배포 원칙은 단계 ①과 같다. V7은 trading 스키마 신규 테이블이라 단독으로 호환된다.
