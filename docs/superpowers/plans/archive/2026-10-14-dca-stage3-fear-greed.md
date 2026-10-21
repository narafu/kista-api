# DCA 단계 ③ 공포탐욕지수 조건 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DCA 회차 조건에 CNN 공포탐욕지수(FEAR_GREED)를 실제로 평가할 수 있게 한다. 대상은 trading-core의 CNN 어댑터, 판정, 등록 400 해제, 메타 노출이다.

**Architecture:**
- trading 모듈에 출력 포트 `FearGreedIndexPort`와 CNN 어댑터를 둔다.
- `DcaRoundPlanner`가 직전 US 개장일 값을 세션 메모 캐시로 조회해 `DcaInputs.fearGreed`로 넘긴다.
- matching 커널 `DcaCycleOrderStrategy`가 `value op threshold`로 순수 판정한다.

**Tech Stack:** Java 25, Spring Boot 4, RestClient, Jackson, JUnit 5, Mockito, MockRestServiceServer

**Spec:** `docs/superpowers/specs/2026-10-13-dca-strategy-design.md`

- 이번 단계 대상은 6.2, 5.2의 3·4단계 FEAR_GREED 항목, 7.4, 11의 3번이다.
- 이 계획은 위 절들을 근거로 한다. 실행자는 이 계획과 함께 스펙도 읽는다.

## Global Constraints

- 브랜치는 `feat/dca-stage3`(stage2 위)이다. 커밋 author는 `narafu <narafu@kakao.com>`이고, push는 금지한다.
- 커밋 메시지는 한글 + Conventional Commit 접두사이고, 다음 두 줄로 끝낸다.
  - `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  - `Claude-Session: https://claude.ai/code/session_011k2XNN7PihEAufZyPCU5fZ`
- 주석 규칙
  - `//` 인라인 주석만 쓴다(Javadoc·블록 주석 금지).
  - 필드는 `// 역할 한 줄`로 단다.
  - 비즈니스 블록 직전에 단계 설명 한 줄을 단다.
- 4-space 들여쓰기, 생성자 주입(`@RequiredArgsConstructor`), 가능하면 package-private으로 쓴다.
- 테스트는 `--tests` 필터와 서브프로젝트 접두사를 붙여 좁게 돌린다(`./gradlew :trading-core:test --tests '...'`, root는 `./gradlew :test --tests '...'`).
  - 전체 스위트는 마지막 태스크에서 1회만 돌린다.
  - 출력은 `2>&1 | grep -E "FAILED|BUILD|tests completed|ERROR"`로 거른다.
- 테스트 DB는 `docker compose up -d postgres`로 띄운다(이 계획의 단위 테스트는 DB를 쓰지 않는다).
- CNN 엔드포인트는 `https://production.dataviz.cnn.io/index/fearandgreed/graphdata`다.
- RestClient 빈 이름은 `fearGreedRestClient`이고, 연결 3초·읽기 5초(`HttpClientTimeouts.timeouts(3_000, 5_000)`)다.
- 날짜 판정은 `x`(epoch ms)가 00:00 UTC 정각이고 UTC 날짜가 요청 날짜와 같은 점만 쓴다.
  - `America/New_York` 변환은 금지한다.
  - `UsTradeDates` allowlist에 추가하지 않는다.
- 값은 `BigDecimal` scale 2 HALF_UP이다.
  - 미충족 detail 형식: `공포탐욕지수 45.09 > 25`
  - 데이터 실패 detail: `공포탐욕지수 조회 실패`
- `@Transactional` 안에서 외부 HTTP를 호출하지 않는다(`DcaRoundPlanner`는 비트랜잭션이다).
- Java 파일에 BOM을 넣지 않는다(`.claude/rules/java-encoding.md`).

## Review Focus

1. **장중 실시간 점**: 배열 끝 장중 점(예: `x=1791920042000`, 19:34 UTC)의 UTC 날짜가 요청일과 같아도 쓰면 안 된다. 00:00 UTC 정각 점만 고른다. → Task 1 테스트 `ignores_intraday_live_point_on_same_utc_date`
2. **피드 지연**: 요청일(직전 개장일) 점이 아직 없으면 바로 전 점으로 대체하지 않고 empty를 반환한다. 그러면 판정은 DATA_UNAVAILABLE이 된다. → Task 1 `returns_empty_when_requested_date_missing`, Task 2 `fear_greed_null_is_data_unavailable`
3. **CNN 장애**: 403(Cloudflare)·5xx·깨진 JSON·`fear_and_greed_historical` 누락이면 예외를 전파하지 않고 empty를 반환해, 배치가 다른 전략을 계속 처리하게 한다. → Task 1 `returns_empty_on_http_error`, `returns_empty_on_missing_historical`
4. **불필요 호출**: FEAR_GREED 조건이 없는 DCA 전략은 CNN을 0회 호출한다. → Task 2 `no_fear_greed_condition_skips_cnn_call`
5. **캐시 의미**: 같은 세션의 전략 N개는 CNN을 1회만 호출한다. 실패(empty)는 캐시하지 않아 다음 전략이 다시 조회한다. → Task 2 `fear_greed_cached_per_session_and_failure_not_cached`

---

### Task 1: FearGreedIndexPort + CNN 어댑터 + 설정

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/port/output/FearGreedIndexPort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/feargreed/CnnFearGreedIndexAdapter.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/feargreed/TradingFearGreedConfig.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/feargreed/CnnFearGreedIndexAdapterTest.java`

**Interfaces:**
- Consumes: `com.kista.shared.platform.http.HttpClientTimeouts.timeouts(int, int)`
- Produces: `FearGreedIndexPort.closeOn(LocalDate usSessionDate): Optional<BigDecimal>`(public interface). Task 2가 이것을 주입받는다.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.kista.tradingcore.trading.adapter.out.feargreed;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CnnFearGreedIndexAdapterTest {

    private static final String URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";

    // 2026-10-14 실측 응답 축약 — 일별 점은 세션일 00:00 UTC, 마지막은 장중 실시간 점(19:34 UTC)
    private static final String BODY = """
            {
              "fear_and_greed": {"score": 45.0857142857143, "rating": "neutral"},
              "fear_and_greed_historical": {
                "score": 45.0857142857143,
                "data": [
                  {"x": 1791763200000.0, "y": 46.3428571428571, "rating": "neutral"},
                  {"x": 1791849600000.0, "y": 45.1714285714286, "rating": "neutral"},
                  {"x": 1791920042000.0, "y": 45.0857142857143, "rating": "neutral"}
                ]
              }
            }
            """;

    private final RestClient.Builder builder = RestClient.builder()
            .requestInterceptor(TradingFearGreedConfig.fearGreedHeaderInterceptor());
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CnnFearGreedIndexAdapter adapter = new CnnFearGreedIndexAdapter(builder.build());

    @Test
    void returns_daily_point_of_requested_utc_date_scaled_to_two_decimals() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Referer", "https://edition.cnn.com/markets/fear-and-greed"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 12))).contains(new BigDecimal("46.34"));
        server.verify();
    }

    @Test
    void ignores_intraday_live_point_on_same_utc_date() {
        // 10-13 일별 점(45.17)을 써야 하고 같은 UTC 날짜의 장중 점(45.09)은 무시
        server.expect(requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 13))).contains(new BigDecimal("45.17"));
    }

    @Test
    void returns_empty_when_requested_date_missing() {
        server.expect(requestTo(URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 14))).isEmpty();
    }

    @Test
    void returns_empty_on_http_error() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 13))).isEmpty();
    }

    @Test
    void returns_empty_on_missing_historical() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"fear_and_greed\":{\"score\":45.0}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 13))).isEmpty();
    }

    @Test
    void returns_empty_on_malformed_json() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>blocked</html>", MediaType.APPLICATION_JSON));

        assertThat(adapter.closeOn(LocalDate.of(2026, 10, 13))).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.out.feargreed.*' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`CnnFearGreedIndexAdapter`, `TradingFearGreedConfig` 없음)

- [ ] **Step 3: 포트 작성**

```java
package com.kista.tradingcore.trading.application.port.output;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

// CNN 공포탐욕지수 — DCA FEAR_GREED 조건용 (spec 6.2)
public interface FearGreedIndexPort {

    // usSessionDate 세션 마감 기준 지수(소수 2자리) — 조회 실패·해당 일자 점 없음이면 empty
    Optional<BigDecimal> closeOn(LocalDate usSessionDate);
}
```

- [ ] **Step 4: 설정 작성**

`TradingFearGreedConfig`의 헤더 인터셉터는 root `src/main/java/com/kista/api/market/adapter/out/feargreed/FearGreedConfig.java`와 같은 값이다. 프로세스가 달라 공유할 수 없어 복제한다.

```java
package com.kista.tradingcore.trading.adapter.out.feargreed;

import com.kista.shared.platform.http.HttpClientTimeouts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

// CNN 공포탐욕지수 RestClient — root FearGreedConfig와 같은 헤더(프로세스가 달라 복제, spec 6.2)
@Configuration
class TradingFearGreedConfig {

    @Bean
    RestClient fearGreedRestClient() {
        return RestClient.builder()
                .requestFactory(HttpClientTimeouts.timeouts(3_000, 5_000)) // 연결 3초, 읽기 5초 — 배치 지연 상한
                .requestInterceptor(fearGreedHeaderInterceptor())
                .build();
    }

    // package-private — CnnFearGreedIndexAdapterTest에서 재사용
    static ClientHttpRequestInterceptor fearGreedHeaderInterceptor() {
        // CNN Cloudflare 봇 감지 우회 — 브라우저 헤더 모방, Accept-Encoding은 강제하지 않는다(자동 해제 실패 방지)
        return (request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
            headers.set(HttpHeaders.REFERER, "https://edition.cnn.com/markets/fear-and-greed");
            headers.set("Origin", "https://edition.cnn.com");
            return execution.execute(request, body);
        };
    }
}
```

- [ ] **Step 5: 어댑터 작성**

```java
package com.kista.tradingcore.trading.adapter.out.feargreed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.trading.application.port.output.FearGreedIndexPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

// CNN 공포탐욕지수 일별 점 조회 — 세션일 00:00 UTC로 찍힌 점만 사용, 장중 실시간 점 제외 (spec 6.2, 2026-10-14 실측)
@Slf4j
@Component
@RequiredArgsConstructor
class CnnFearGreedIndexAdapter implements FearGreedIndexPort {

    private static final String URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";
    private static final long DAY_MS = 86_400_000L; // 일별 점 판별 — x가 UTC 자정 정각

    private final RestClient fearGreedRestClient;

    @Override
    public Optional<BigDecimal> closeOn(LocalDate usSessionDate) {
        try {
            CnnResponse response = fearGreedRestClient.get().uri(URL).retrieve().body(CnnResponse.class);
            if (response == null || response.historical() == null || response.historical().data() == null) {
                log.warn("[DCA] CNN 공포탐욕지수 historical 없음");
                return Optional.empty();
            }
            // UTC 자정 정각 점 중 요청 세션일과 같은 날짜 — CNN 일별 점은 세션일 00:00 UTC로 찍힌다
            Optional<BigDecimal> value = response.historical().data().stream()
                    .filter(p -> (long) p.x() % DAY_MS == 0)
                    .filter(p -> Instant.ofEpochMilli((long) p.x()).atZone(ZoneOffset.UTC).toLocalDate().equals(usSessionDate))
                    .map(p -> BigDecimal.valueOf(p.y()).setScale(2, RoundingMode.HALF_UP))
                    .reduce((first, second) -> second);
            if (value.isEmpty()) log.warn("[DCA] CNN 공포탐욕지수 {} 일별 점 없음(피드 지연)", usSessionDate);
            return value;
        } catch (RuntimeException e) {
            log.warn("[DCA] CNN 공포탐욕지수 조회 실패: usSessionDate={}", usSessionDate, e);
            return Optional.empty();
        }
    }

    // CNN 응답 중 fear_and_greed_historical 블록만 사용
    record CnnResponse(@JsonProperty("fear_and_greed_historical") Historical historical) {}

    record Historical(@JsonProperty("data") List<Point> data) {}

    // x: epoch ms(double), y: 지수
    record Point(@JsonProperty("x") double x, @JsonProperty("y") double y) {}
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.out.feargreed.*' 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL`, 6 tests

- [ ] **Step 7: 아키텍처·컨텍스트 회귀 확인**

신규 `@Configuration`과 `RestClient` 빈이 기존 RestClient 주입 지점과 모호성 충돌을 일으키지 않는지 확인한다. 기존 trading-core RestClient 주입은 필드명으로 구분된다.

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*' --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL`

`NoUniqueBeanDefinitionException`이 나면, 실패 메시지에 나온 주입 지점의 필드명이 빈 이름과 맞는지 확인한다. 신규 빈 이름은 바꾸지 않는다.

- [ ] **Step 8: 커밋**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/trading/application/port/output/FearGreedIndexPort.java \
        trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/feargreed \
        trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/feargreed
git -c user.name=narafu -c user.email=narafu@kakao.com commit -m "feat(dca): trading-core에 CNN 공포탐욕지수 일별 점 어댑터를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011k2XNN7PihEAufZyPCU5fZ"
```

---

### Task 2: FEAR_GREED 판정 + 플래너 조회·세션 캐시

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategy.java`
  - `DcaInputs` record(29-32행)
  - `decide`의 evaluate 호출(약 63행)
  - `evaluate`(약 87-97행)
  - `dataFailure`(약 125-132행)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaRoundPlanner.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategyTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/DcaRoundPlannerTest.java`

**Interfaces:**
- Consumes: Task 1의 `FearGreedIndexPort.closeOn(LocalDate): Optional<BigDecimal>`
- Produces:
  - `DcaInputs(DcaConfig config, StrategyTicker ticker, LocalDate tradeDate, List<BigDecimal> closes, Integer sellableQuantity, Holding holding, BigDecimal fearGreed)`. 마지막 인자가 추가된다.
  - `DcaRoundPlanner` 생성자 인자 순서는 `(calendar, candlePort, sellablePort, liveBalancePort, fearGreedIndexPort, orderPort, dcaDetailPort, executionPort, recorder, dcaStrategy)`다. `liveBalancePort` 바로 뒤에 `FearGreedIndexPort`가 들어간다.

- [ ] **Step 1: 판정 실패 테스트 작성** (`DcaCycleOrderStrategyTest`)

먼저 기존 헬퍼 `decide(DcaConfig, List<BigDecimal>, Integer, Holding)`가 새 7번째 인자를 `null`로 넘기도록 바꾸고, FEAR_GREED용 오버로드를 추가한다.

```java
    private DcaDecision decide(DcaConfig c, List<BigDecimal> closes, Integer sellable, DcaCycleOrderStrategy.DcaInputs.Holding holding) {
        return decideWithFearGreed(c, closes, sellable, holding, null);
    }

    private DcaDecision decideWithFearGreed(DcaConfig c, List<BigDecimal> closes, Integer sellable,
                                            DcaCycleOrderStrategy.DcaInputs.Holding holding, BigDecimal fearGreed) {
        return strategy.decide(new DcaCycleOrderStrategy.DcaInputs(c, StrategyTicker.UPRO, DAY, closes, sellable, holding, fearGreed));
    }

    private static DcaConfig fearGreedConfig(DcaOperator op, String threshold) {
        return new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null,
                List.of(new DcaCondition(DcaIndicator.FEAR_GREED, op, null, new BigDecimal(threshold))));
    }

    @Test
    void fear_greed_condition_met_plans_order() {
        DcaDecision d = decideWithFearGreed(fearGreedConfig(DcaOperator.LTE, "25"), List.of(new BigDecimal("100")), null, null,
                new BigDecimal("20.50"));
        assertThat(d).isInstanceOf(DcaDecision.Planned.class);
    }

    @Test
    void fear_greed_condition_not_met_reports_negated_detail() {
        DcaDecision d = decideWithFearGreed(fearGreedConfig(DcaOperator.LTE, "25"), List.of(new BigDecimal("100")), null, null,
                new BigDecimal("45.09"));
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.CONDITION_NOT_MET, "공포탐욕지수 45.09 > 25", new BigDecimal("100")));
    }

    @Test
    void fear_greed_equal_to_threshold_passes_lte() {
        DcaDecision d = decideWithFearGreed(fearGreedConfig(DcaOperator.LTE, "25"), List.of(new BigDecimal("100")), null, null,
                new BigDecimal("25.00"));
        assertThat(d).isInstanceOf(DcaDecision.Planned.class);
    }

    @Test
    void fear_greed_null_is_data_unavailable() {
        DcaDecision d = decideWithFearGreed(fearGreedConfig(DcaOperator.GTE, "75"), List.of(new BigDecimal("100")), null, null, null);
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "공포탐욕지수 조회 실패", new BigDecimal("100")));
    }
```

필요한 import(이미 있으면 생략)는 `DcaCondition`, `DcaIndicator`, `DcaOperator`, `DcaSkipReason`, `DcaFrequency`다. 기존 테스트 클래스가 `DcaConfig` 7인자 생성자를 쓰지 않았다면, 시그니처는 `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/DcaConfig.java`에서 확인한다. `TradingCycleControllerTest`의 `new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.MONTHLY, null, null, List.of(...))` 사용례를 참고한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategyTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`DcaInputs` 7인자 생성자 없음)

- [ ] **Step 3: 판정 구현** (`DcaCycleOrderStrategy`)

`DcaInputs`에 필드를 추가하고 주석을 갱신한다.

```java
    // DCA 판정 입력 — closes: 조회 US 세션 이전 확정 종가 오름차순(null=조회 실패), sellableQuantity: SELL만(예약분 차감 후),
    // holding: AVG_PRICE 조건이 있을 때만 브로커 실잔고(null=조회 실패·미조회), fearGreed: FEAR_GREED 조건이 있을 때만 직전 개장일 CNN 지수(null=조회 실패·미조회)
    public record DcaInputs(DcaConfig config, StrategyTicker ticker, LocalDate tradeDate,
                            List<BigDecimal> closes, Integer sellableQuantity, Holding holding, BigDecimal fearGreed) {
        public record Holding(int quantity, BigDecimal avgPrice) {} // 계좌+티커 합산 보유
    }
```

`decide` 안의 evaluate 호출은 `evaluate(cond, closes, prevClose, in.holding())`에서 `evaluate(cond, closes, prevClose, in)`로 바꾸고, `evaluate` 시그니처와 FEAR_GREED 분기를 고친다.

```java
    // 조건 1개 평가 — 지표 계산 불가면 empty
    private static Optional<Evaluation> evaluate(DcaCondition cond, List<BigDecimal> closes, BigDecimal prevClose, DcaInputs in) {
        String close = "전일 종가 " + money(prevClose);
        return switch (cond.indicator()) {
            case SMA -> DcaIndicators.sma(closes, cond.smaPeriod())
                    .map(v -> compare(cond.operator(), prevClose, v, close, "SMA(" + cond.smaPeriod() + ") " + money(v)));
            case RSI -> DcaIndicators.rsiWilder(closes, DcaIndicators.RSI_PERIOD)
                    .map(v -> compare(cond.operator(), v, cond.threshold(), "RSI(" + DcaIndicators.RSI_PERIOD + ") " + money(v), plain(cond.threshold())));
            case AVG_PRICE -> evaluateAvgPrice(cond, prevClose, in.holding(), close);
            case FEAR_GREED -> Optional.ofNullable(in.fearGreed())
                    .map(v -> compare(cond.operator(), v, cond.threshold(), "공포탐욕지수 " + money(v), plain(cond.threshold())));
        };
    }
```

`dataFailure`의 FEAR_GREED 문구를 바꾼다.

```java
            case FEAR_GREED -> "공포탐욕지수 조회 실패";
```

- [ ] **Step 4: 판정 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategyTest' 2>&1 | grep -E "FAILED|BUILD|tests completed|error:"`
Expected: 컴파일 실패는 `DcaRoundPlanner`(main)의 6인자 `DcaInputs` 호출뿐이다. Step 6까지 진행한 뒤 함께 확인한다.

- [ ] **Step 5: 플래너 실패 테스트 작성** (`DcaRoundPlannerTest`)

필드와 생성자를 바꾼다.

```java
    FearGreedIndexPort fearGreedPort = mock(FearGreedIndexPort.class);
    DcaRoundPlanner planner = new DcaRoundPlanner(calendar, candlePort, sellablePort, liveBalancePort, fearGreedPort, orderPort,
            dcaDetailPort, executionPort, recorder, new DcaCycleOrderStrategy());
```

`setUp`에서 `calendar.isUsSessionOpen(any())`는 이미 true다. 따라서 직전 개장일은 `US_SESSION.minusDays(1)` = 2026-10-12이고, 이 날이 기존 캔들 stub의 마지막 봉 날짜와 같다. 이 사실을 이용해 테스트를 추가한다.

```java
    static final LocalDate PREV_OPEN = LocalDate.of(2026, 10, 12); // US_SESSION 직전 개장일 (setUp: 모든 날 개장)

    private void fearGreedDca(String threshold) {
        when(dcaDetailPort.findActiveByStrategyId(ctx.strategy().id())).thenReturn(Optional.of(
                new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null,
                        List.of(new DcaCondition(DcaIndicator.FEAR_GREED, DcaOperator.LTE, null, new BigDecimal(threshold))))));
    }

    @Test
    void fear_greed_condition_queries_previous_open_session() {
        fearGreedDca("25");
        when(fearGreedPort.closeOn(PREV_OPEN)).thenReturn(Optional.of(new BigDecimal("20.00")));

        Optional<OrderPlan> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of());

        assertThat(plan).isPresent();
        verify(fearGreedPort).closeOn(PREV_OPEN);
    }

    @Test
    void fear_greed_unavailable_records_data_unavailable() {
        fearGreedDca("25");
        when(fearGreedPort.closeOn(PREV_OPEN)).thenReturn(Optional.empty());

        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of())).isEmpty();
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(DcaSkipReason.DATA_UNAVAILABLE), eq("공포탐욕지수 조회 실패"), any());
    }

    @Test
    void no_fear_greed_condition_skips_cnn_call() {
        dca(OrderDirection.BUY, OrderType.MOC);

        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of());

        verifyNoInteractions(fearGreedPort);
    }

    @Test
    void fear_greed_cached_per_session_and_failure_not_cached() {
        fearGreedDca("25");
        when(fearGreedPort.closeOn(PREV_OPEN)).thenReturn(Optional.empty(), Optional.of(new BigDecimal("20.00")));

        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of()); // 실패 — 캐시 안 됨
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of()); // 재조회 성공 — 캐시
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of()); // 캐시 적중

        verify(fearGreedPort, times(2)).closeOn(PREV_OPEN);
    }
```

`dca(...)` 헬퍼는 테스트 파일에 이미 있다(조건 없는 DcaConfig stub). 세 번 연속 `plan` 호출은 `executionPort.find`가 매번 empty를 반환하므로(setUp) 매번 판정까지 진행한다. 추가 import는 `FearGreedIndexPort`, `DcaCondition`, `DcaIndicator`, `DcaOperator`, `DcaSkipReason`, `Mockito.times`, `Mockito.verify`, `ArgumentMatchers.eq`/`any`다(이미 있으면 생략).

- [ ] **Step 6: 플래너 구현** (`DcaRoundPlanner`)

`liveBalancePort` 필드 바로 아래에 필드 2개를 추가한다.

```java
    private final FearGreedIndexPort fearGreedIndexPort;    // FEAR_GREED 조건용 CNN 지수
```

```java
    // US 세션 → 직전 개장일 CNN 지수 — 세션당 1개, 실패는 캐시하지 않음 (spec 6.2)
    private final Map<LocalDate, BigDecimal> fearGreedCache = new ConcurrentHashMap<>();
```

필드 선언 순서는 `marketCalendarPort, candlePort, sellableQuantityPort, liveBalancePort, fearGreedIndexPort, orderPort, ...`이고, `@RequiredArgsConstructor` 순서가 Task Interfaces와 같아야 한다. `fearGreedCache`는 final 초기화 필드라 생성자 인자가 아니다.

`decide` 호출에 인자를 추가한다.

```java
        DcaDecision decision = dcaStrategy.decide(new DcaCycleOrderStrategy.DcaInputs(config, strategy.ticker(), tradeDate,
                loadCloses(strategy.ticker(), usSession), loadSellable(ctx, config, tradeDate), loadHolding(ctx, config),
                loadFearGreed(config, usSession)));
```

메서드를 추가한다(`loadHolding` 위).

```java
    // FEAR_GREED 조건이 있을 때만 직전 개장일 CNN 지수 — 세션 메모 캐시, 실패·직전 개장일 미확인이면 null(DATA_UNAVAILABLE)
    private BigDecimal loadFearGreed(DcaConfig config, LocalDate usSession) {
        if (!config.hasIndicator(DcaIndicator.FEAR_GREED)) return null;
        BigDecimal cached = fearGreedCache.get(usSession);
        if (cached != null) return cached;
        LocalDate previousOpen = previousOpenSession(usSession);
        if (previousOpen == null) return null;
        Optional<BigDecimal> value = fearGreedIndexPort.closeOn(previousOpen);
        if (value.isEmpty()) return null;
        fearGreedCache.keySet().removeIf(k -> !k.equals(usSession)); // 지난 세션 제거 — 맵 크기 ≤ 1
        fearGreedCache.put(usSession, value.get());
        return value.get();
    }
```

import `com.kista.tradingcore.trading.application.port.output.FearGreedIndexPort`를 추가한다. 클래스 상단 주석의 "250봉 종가(신선도 검증·세션 캐시)·잔고 조회"는 "250봉 종가(신선도 검증·세션 캐시)·잔고·CNN 지수 조회"로 바꾼다.

다른 `new DcaRoundPlanner(` 호출이 없는지 확인한다.

Run: `grep -rn "new DcaRoundPlanner(" trading-core/src`
Expected: `DcaRoundPlannerTest`만 나온다(다른 테스트가 있으면 같은 순서로 `mock(FearGreedIndexPort.class)`를 추가한다).

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategyTest' --tests 'com.kista.tradingcore.trading.application.service.Dca*' --tests 'com.kista.tradingcore.trading.application.service.TradingCandidatePlannerDcaTest' 2>&1 | grep -E "FAILED|BUILD|tests completed|error:"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategy.java \
        trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaRoundPlanner.java \
        trading-core/src/test/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategyTest.java \
        trading-core/src/test/java/com/kista/tradingcore/trading/application/service/DcaRoundPlannerTest.java
git -c user.name=narafu -c user.email=narafu@kakao.com commit -m "feat(dca): 공포탐욕지수 조건을 직전 개장일 CNN 값으로 판정한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011k2XNN7PihEAufZyPCU5fZ"
```

---

### Task 3: 등록 400 해제 + 메타 노출 + 문서 + 전체 스위트

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConditionRequest.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyType.java:33`
- Modify: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/TradingCycleControllerTest.java:466-474, 494-502`
- Modify: `src/test/java/com/kista/api/web/MetaControllerTest.java:90`
- Modify: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/DcaConditionTest.java:23` (주석만)
- Modify: `docs/agents/modules/tradingcore/trading.md` ("DCA 회차 실행 (단계 ①)" 절)
- Modify: `docs/agents/modules/tradingcore/matching.md` ("DCA 전략" 절의 `DcaCondition` 검증 줄)
- Modify(해당 시): `src/test/resources/architecture/flows.yml`

**Interfaces:**
- Consumes: Task 2의 FEAR_GREED 판정(등록된 조건이 실제로 평가됨)
- Produces: 메타 `strategyTypes[DCA].conditionIndicators` = `["AVG_PRICE","SMA","RSI","FEAR_GREED"]`

- [ ] **Step 1: 테스트를 새 기대로 변경**

`TradingCycleControllerTest`에서 `register_dca_with_fear_greed_is_400`를 아래로 교체한다.

```java
    @Test
    void register_dca_with_fear_greed_passes_condition() throws Exception {
        when(tradingCycle.register(any(), any(), any())).thenReturn(dcaDetail());

        mockMvc.perform(post("/api/accounts/{accountId}/trading-cycles", ACCOUNT_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DCA_PREFIX + "[{\"indicator\":\"FEAR_GREED\",\"operator\":\"LTE\",\"threshold\":25}]}}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<RegisterStrategyCommand> captor = ArgumentCaptor.forClass(RegisterStrategyCommand.class);
        verify(tradingCycle).register(any(), any(), captor.capture());
        assertThat(captor.getValue().dca().conditions()).containsExactly(
                new DcaCondition(DcaIndicator.FEAR_GREED, DcaOperator.LTE, null, new BigDecimal("25")));
    }

    @Test
    void register_dca_with_fear_greed_threshold_over_100_is_400() throws Exception {
        mockMvc.perform(post("/api/accounts/{accountId}/trading-cycles", ACCOUNT_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DCA_PREFIX + "[{\"indicator\":\"FEAR_GREED\",\"operator\":\"LTE\",\"threshold\":101}]}}"))
                .andExpect(status().isBadRequest());
    }
```

`reconfigureDca_with_fear_greed_is_400`는 삭제한다. 재설정 경로도 같은 `DcaConditionRequest.toDomain()`을 거치므로, 위 등록 테스트 2건이 해제와 범위 검증을 대표한다.

`MetaControllerTest` 90행의 `contains("AVG_PRICE", "SMA", "RSI")`를 `contains("AVG_PRICE", "SMA", "RSI", "FEAR_GREED")`로 바꾼다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.in.web.TradingCycleControllerTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `register_dca_with_fear_greed_passes_condition` FAILED (400)

- [ ] **Step 3: 구현**

`DcaConditionRequest`

```java
// DCA 조건 요청 1건 — 검증은 DcaCondition(IAE→400)
public record DcaConditionRequest(
        DcaIndicator indicator, // 지표
        DcaOperator operator,   // 비교 연산자
        Integer smaPeriod,      // SMA 전용
        BigDecimal threshold    // SMA 외 기준값
) {
    DcaCondition toDomain() {
        return new DcaCondition(indicator, operator, smaPeriod, threshold);
    }
}
```

`DcaIndicator` import가 더 이상 쓰이지 않아도 레코드 필드 타입이라 유지된다.

`StrategyType` DCA 분기

```java
                    List.of(DcaFrequency.values()), List.of(DcaIndicator.values()));
```

이 값이 `["AVG_PRICE","SMA","RSI","FEAR_GREED"]` 순서인지 `shared/src/main/java/com/kista/shared/kernel/DcaIndicator.java`의 선언 순서로 확인한다. 순서가 다르면 `List.of(DcaIndicator.AVG_PRICE, DcaIndicator.SMA, DcaIndicator.RSI, DcaIndicator.FEAR_GREED)`로 명시한다.

`DcaConditionTest` 23행 주석의 `// 도메인은 허용, 단계 ② 차단은 API 경계`는 `// FEAR_GREED 0~100 허용`으로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.in.web.TradingCycleControllerTest' --tests 'com.kista.tradingcore.matching.domain.model.DcaConditionTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"` 그리고 `./gradlew :test --tests 'com.kista.api.web.MetaControllerTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: 둘 다 `BUILD SUCCESSFUL`

- [ ] **Step 5: 문서 동기화**

- `docs/agents/modules/tradingcore/trading.md` "DCA 회차 실행 (단계 ①)" 절
  - 첫 줄 끝 "실잔고(`LiveBalancePort`)는 AVG_PRICE 조건이 있을 때만 조회한다." 뒤에 다음 문장을 추가한다: "CNN 공포탐욕지수(`FearGreedIndexPort` → `adapter.out.feargreed.CnnFearGreedIndexAdapter`, 빈 `fearGreedRestClient`)는 FEAR_GREED 조건이 있을 때만 직전 US 개장일 값(00:00 UTC 일별 점)을 조회하며, US 세션 단위로 메모 캐시하고 실패는 캐시하지 않는다."
  - 둘째 줄의 "메타 `strategyTypes[DCA].conditionIndicators`는 `[AVG_PRICE, SMA, RSI]`다"를 "`[AVG_PRICE, SMA, RSI, FEAR_GREED]`다"로 바꾼다.
- `docs/agents/modules/tradingcore/matching.md` "DCA 전략" 절
  - "지표는 `AVG_PRICE`·`SMA`·`RSI`(`FEAR_GREED`는 단계 ③ 전까지 API에서 400)"를 "지표는 `AVG_PRICE`·`SMA`·`RSI`·`FEAR_GREED`(0~100, 값은 `DcaInputs.fearGreed` — 플래너가 직전 개장일 CNN 값을 넣는다)"로 바꾼다.
- `flows.yml`: `grep -n -i "dca\|fear" src/test/resources/architecture/flows.yml`로 DCA 흐름 단계를 찾는다.
  - DCA 판정 단계의 `desc`가 데이터 확보 항목(일봉·잔고)을 나열하면 "CNN 공포탐욕지수(FEAR_GREED 조건 시)"를 추가한다.
  - 외부 시스템 목록(landscape)에 CNN이 root에만 연결돼 있으면 trading-core 연결도 추가한다.
  - 해당 항목이 없으면 수정하지 않는다.
  - 수정했으면 `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"`로 확인한다.
- `README.md`: `grep -n -i "fear\|CNN" README.md`. trading-core가 CNN을 직접 조회한다는 사실이 아키텍처 다이어그램과 어긋나면 고친다. 없으면 생략한다.

- [ ] **Step 6: 전체 스위트 1회**

Run: `docker compose up -d postgres && ./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL`

실패가 나면 `grep -l 'failures="[1-9]' build/test-results/test/*.xml trading-core/build/test-results/test/*.xml`로 실패 파일을 찾아 원인을 보고한다.

- [ ] **Step 7: 커밋**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConditionRequest.java \
        shared/src/main/java/com/kista/shared/kernel/StrategyType.java \
        trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/TradingCycleControllerTest.java \
        trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/DcaConditionTest.java \
        src/test/java/com/kista/api/web/MetaControllerTest.java \
        docs/agents/modules/tradingcore/trading.md docs/agents/modules/tradingcore/matching.md
# flows.yml·README.md를 수정했으면 함께 add
git -c user.name=narafu -c user.email=narafu@kakao.com commit -m "feat(dca): 공포탐욕지수 조건 등록을 열고 메타 conditionIndicators에 노출한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011k2XNN7PihEAufZyPCU5fZ"
```
