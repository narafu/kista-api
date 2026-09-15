# kista-trading 2단계(읽기+admin 쓰기 결합 끊기) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** stats·admin이 trading의 persistence 포트를 Java 메서드로 직접 호출하던 것을 끊고, `/api/internal/**` 내부 HTTP API로 교체한다. admin의 실주문 재정렬·수동 정정 쓰기 경로도 포함한다. DB는 아직 공유 상태 — 이 단계는 순수 코드 경계 작업이며 배포 형태는 무변경(단일 bootJar, 단일 서버).

**Architecture:** 판정 기준 하나: "소비자가 kista-api에 남는가, kista-trading(트레이딩 도메인, 물리적으로 `:trading-core` 서브프로젝트)으로 옮겨가는가." 옮겨가는 코드는 그냥 패키지 이동(HTTP 불필요). api에 남는 소비자(admin, stats의 benchmark 절반)가 trading 데이터를 필요로 하는 지점만 `InternalTokenAuthFilter`로 보호된 `/api/internal/**` 엔드포인트로 전환한다. `:api → :trading-core` Gradle 컴파일 의존(`implementation(project(":trading-core"))`)은 이 단계에서 안 끊긴다(3단계 게이트) — 따라서 admin은 `Order`/`Strategy`/`StrategySummary`/`OrderTiming` 등 trading 타입을 내부 API 요청/응답으로 그대로 재사용한다(Jackson 기본 직렬화, own-type 신설 없음). 예외는 방향이 반대인 두 타입(`ReorderCommand`/`ReorderResult`, `ManualTradeCorrectionCommand`/`ManualTradeCorrectionResult`) — trading-core는 `:api`(admin)에 대한 Gradle 의존이 전혀 없어(`GradleModuleBoundaryTest`) admin의 `AdminReorderCommand` 등을 참조할 수 없으므로, 이 4개는 trading 쪽에 구조적으로 동일한 자체 타입을 새로 정의한다(constraints.md own-type 게이트 (a) 순환 불가피).

**Tech Stack:** Java 21, Spring Boot 4, Spring RestClient(내부 HTTP 호출), Gradle 멀티프로젝트(`:api`, `:trading-core`), Spring Modulith(`ApplicationModules.verify()`), JUnit5/Mockito.

**Spec:** `docs/superpowers/specs/2026-09-11-kista-trading-service-split-design.md` (2단계 섹션 + 소유권 표 + 미해결 항목) — 이 계획을 실행하는 사람은 스펙의 "2단계" 절 전체를 먼저 읽을 것.

## Global Constraints

- 각 태스크는 별도 커밋. 태스크 완료 시 `./gradlew test` 전체 그린 + `./gradlew test --tests 'com.kista.architecture.*'`(ArchUnit + `ApplicationModules.verify()`) 그린 확인 후 다음 태스크로 진행 — 스펙의 "각 단계 `./gradlew test` 전체 그린" 게이트.
- 커밋 메시지: 한글, Conventional Commit 접두사, `narafu <narafu@kakao.com>` author, 끝에 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_017gKzpVJLczfr1sTuMU2n2V`.
- `git push`는 사용자가 명시적으로 요청할 때만.
- 신규 파일은 `application/{usecase,port/output,service}` + `adapter/{in,out}` 서브구조 준수, 포트는 `*Port` 접미사.
- 내부 API 컨트롤러는 기존 `FidaOrderController` 패턴(`@RestController @RequestMapping("/api/internal")`, `@Tag(name="내부 API")`, Swagger 응답 문서화)을 그대로 따른다.
- `InternalTokenAuthFilter`는 이미 `X-Internal-Token` 헤더로 `/api/internal/**` 전체를 보호한다 — 신규 컨트롤러는 별도 Security 설정 불필요.
- 매매 시간대 배포 가드 없음(코드 전용 작업, 배포 무관) — 다만 최종 태스크의 스테이징 리허설은 매매 시간대 회피.

---

### Task 1: 내부 API 호출 공용 인프라 (RestClient + 설정)

admin·stats가 앞으로 만들 모든 내부 API 어댑터가 공유할 RestClient 빈 하나. `platform`은 이미 api·trading-core 양쪽에서 참조 가능한 leaf 인프라 모듈(`BaseAuditEntity`를 `UserEntity`(api)·`AccountEntity`(trading-core)가 공유하는 것과 동일 패턴)이라 여기 둔다.

**Files:**
- Create: `trading-core/src/main/java/com/kista/platform/internalapi/InternalApiProperties.java`
- Create: `trading-core/src/main/java/com/kista/platform/internalapi/InternalApiClientConfig.java`
- Create: `trading-core/src/test/java/com/kista/platform/internalapi/InternalApiClientConfigTest.java`
- Modify: `src/main/resources/application.yml` (internal.api 섹션에 base-url 추가)

**Interfaces:**
- Produces: `@Bean RestClient internalApiRestClient(InternalApiProperties)` — `X-Internal-Token` 헤더가 기본 헤더로 이미 설정된 RestClient. 이후 태스크의 모든 내부 API 어댑터가 이 빈을 주입받아 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.kista.platform.internalapi;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiClientConfigTest {

    @Test
    void 헤더에_내부토큰이_설정된다() {
        InternalApiProperties props = new InternalApiProperties("http://localhost:8080", "test-token");
        InternalApiClientConfig config = new InternalApiClientConfig();

        RestClient client = config.internalApiRestClient(props);

        assertThat(client).isNotNull();
        // RestClient는 헤더 검증용 introspection API가 없으므로, 실제 헤더 전달은
        // Task 5의 TradingInternalHttpAdapterTest(MockWebServer 등)에서 통합 검증한다.
        // 여기서는 baseUrl/token 둘 다 blank면 예외를 던지는 생성 검증만 한다.
    }

    @Test
    void baseUrl이_비어있으면_예외() {
        InternalApiProperties props = new InternalApiProperties("", "test-token");
        InternalApiClientConfig config = new InternalApiClientConfig();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> config.internalApiRestClient(props));
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.platform.internalapi.InternalApiClientConfigTest'`
Expected: FAIL (클래스 없음, 컴파일 에러)

- [ ] **Step 3: 최소 구현 작성**

```java
// InternalApiProperties.java
package com.kista.platform.internalapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "internal.api")
public record InternalApiProperties(
        String baseUrl,   // 내부 API 호출 대상 — 단일 배포 상태에선 자기 자신(localhost:서버포트)
        String token      // X-Internal-Token 값 — INTERNAL_API_TOKEN과 동일 소스
) {}
```

```java
// InternalApiClientConfig.java
package com.kista.platform.internalapi;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalApiClientConfig {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Bean
    public RestClient internalApiRestClient(InternalApiProperties props) {
        if (props.baseUrl() == null || props.baseUrl().isBlank()) {
            throw new IllegalArgumentException("internal.api.base-url이 설정되지 않았습니다");
        }
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(INTERNAL_TOKEN_HEADER, props.token())
                .build();
    }
}
```

`application.yml`의 `internal.api.token` 옆에 `base-url` 추가:

```yaml
internal:
  api:
    token: ${INTERNAL_API_TOKEN:}
    base-url: ${INTERNAL_API_BASE_URL:http://localhost:8080}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.platform.internalapi.InternalApiClientConfigTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src/main/java/com/kista/platform/internalapi trading-core/src/test/java/com/kista/platform/internalapi src/main/resources/application.yml
git commit -m "feat(platform): 내부 API 호출용 RestClient 공용 빈 신설"
```

---

### Task 2: InvestmentPoint/BenchmarkGranularity + MonthlyReturnCalculator를 trading.stats로 이관

`MonthlyReturnCalculator`는 입력(`CyclePosition`/`StrategyCycle`)도 출력(`InvestmentPoint`)도 전부 trading 데이터 기반 순수 계산이다. `InvestmentPoint`/`BenchmarkGranularity`가 api쪽 `com.kista.stats.domain.model`에 남아있으면 이 계산기가 trading-core로 이동할 때 `:trading-core → :api` 역방향 참조가 생겨 컴파일이 깨진다(`GradleModuleBoundaryTest`). 두 타입 자체를 trading 소유로 옮기고 api는 그대로 import해서 쓴다(컴파일 의존 방향이 api→trading-core라 문제 없음).

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/stats/domain/model/InvestmentPoint.java`
- Create: `trading-core/src/main/java/com/kista/trading/stats/domain/model/BenchmarkGranularity.java`
- Create: `trading-core/src/main/java/com/kista/trading/stats/application/MonthlyReturnCalculator.java`
- Delete: `src/main/java/com/kista/stats/domain/model/InvestmentPoint.java`
- Delete: `src/main/java/com/kista/stats/domain/model/BenchmarkGranularity.java`
- Delete: `src/main/java/com/kista/stats/application/service/MonthlyReturnCalculator.java`
- Delete: `src/test/java/com/kista/stats/application/service/MonthlyReturnCalculatorTest.java` (있다면 trading-core로 이동)
- Modify: `com.kista.stats.domain.model` 및 `com.kista.stats.application.service` 전체에서 두 타입 import 경로를 `com.kista.trading.stats.domain.model.*`로 일괄 교체 — 영향 범위: `StatsService`, `HousingBenchmarkComparisonBuilder`, `EquityPoint`/`CyclePerformance` 등 관련 없음 확인, `adapter/in/web/dto` 쪽 DTO 중 `InvestmentPoint`/`BenchmarkGranularity`를 직접 참조하는 파일 없음(둘 다 서비스 내부 계산 전용 — `grep -rl "stats.domain.model.InvestmentPoint\|stats.domain.model.BenchmarkGranularity" src`로 확인 후 전부 교체)

**Interfaces:**
- Produces: `com.kista.trading.stats.domain.model.InvestmentPoint`(record, 필드 동일), `BenchmarkGranularity`(enum, 값 동일), `com.kista.trading.stats.application.MonthlyReturnCalculator`(package-private였던 걸 public으로 승격 — api의 `StatsService`가 Task 5에서 이 계산 결과를 내부 API로만 받게 되므로 실제로는 이 클래스 자체를 api가 직접 import할 일은 없어지지만, 같은 이유로 여기서는 우선 이동만 하고 가시성은 유지한다).

- [ ] **Step 1: 영향 범위 확정**

```bash
grep -rl "com\.kista\.stats\.domain\.model\.InvestmentPoint\|com\.kista\.stats\.domain\.model\.BenchmarkGranularity" src/main/java src/test/java
```

나온 파일 목록을 기록해둔다(다음 스텝에서 전부 import 경로 교체 대상).

- [ ] **Step 2: 파일 이동 + 패키지 선언 변경**

```bash
mkdir -p trading-core/src/main/java/com/kista/trading/stats/domain/model
mkdir -p trading-core/src/main/java/com/kista/trading/stats/application
git mv src/main/java/com/kista/stats/domain/model/InvestmentPoint.java trading-core/src/main/java/com/kista/trading/stats/domain/model/InvestmentPoint.java
git mv src/main/java/com/kista/stats/domain/model/BenchmarkGranularity.java trading-core/src/main/java/com/kista/trading/stats/domain/model/BenchmarkGranularity.java
git mv src/main/java/com/kista/stats/application/service/MonthlyReturnCalculator.java trading-core/src/main/java/com/kista/trading/stats/application/MonthlyReturnCalculator.java
```

세 파일의 `package` 선언을 각각 `com.kista.trading.stats.domain.model`, `com.kista.trading.stats.domain.model`, `com.kista.trading.stats.application`으로 수정. `MonthlyReturnCalculator`의 `final class MonthlyReturnCalculator`를 `public final class MonthlyReturnCalculator`로, 생성자·`calculate(...)` 메서드도 `public`으로 승격(패키지가 바뀌어 package-private 접근이 끊기므로).

- [ ] **Step 3: Step 1에서 찾은 모든 소비 파일의 import 경로 교체**

각 파일에서
```java
import com.kista.stats.domain.model.InvestmentPoint;
import com.kista.stats.domain.model.BenchmarkGranularity;
```
→
```java
import com.kista.trading.stats.domain.model.InvestmentPoint;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
```
`StatsService`의 `new MonthlyReturnCalculator()` 호출부는 import만 `com.kista.trading.stats.application.MonthlyReturnCalculator`로 교체(생성자 시그니처 불변).

- [ ] **Step 4: 이동한 테스트가 있으면 같은 방식으로 trading-core/src/test로 이동, 패키지 수정**

```bash
find src/test -iname "MonthlyReturnCalculatorTest.java" -o -iname "InvestmentPointTest.java"
```
있으면 `trading-core/src/test/java/com/kista/trading/stats/...`로 `git mv` 후 패키지 선언 수정.

- [ ] **Step 5: 전체 테스트 실행 → 그린 확인**

Run: `./gradlew test`
Expected: PASS (컴파일 에러 없음, 기존 테스트 그대로 통과 — 로직 변경 없는 순수 이동이므로 실패하면 import 누락)

- [ ] **Step 6: ArchUnit/Modulith 검증**

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS — 특히 `GradleModuleBoundaryTest`(trading-core→api 역방향 금지)와 `ApplicationModules.verify()`

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor(stats): InvestmentPoint/BenchmarkGranularity/MonthlyReturnCalculator를 trading.stats로 이관"
```

---

### Task 3: com.kista.stats 모듈 분리 — trading 몫을 com.kista.trading.stats로 흡수

`AccountStatisticsService`/`PortfolioService`/`BacktestService`+`BacktestEngine`+`FillSimulator`/`TossStatisticsService`/`BrokerStatisticsRouter`와 이들이 쓰는 usecase·컨트롤러(`DashboardController`, `StatisticsController`, `TossStatisticsController`)를 전부 `com.kista.trading.stats`로 옮긴다. `StatsService`는 summary/equity-curve/cycles 부분만 분리해서 같이 옮기고, housing/ETF 벤치마크 부분은 api의 `StatsService`에 남긴다. 순수 이동 + 클래스 하나 쪼개기라 이번 태스크에 논리 변경은 없다(Task 5에서 벤치마크 쪽 investment-points 내부 API 연동만 추가).

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/stats/application/usecase/TradingStatsUseCase.java` (신규 인터페이스 — `UserStatsUseCase`의 summary/equity-curve/cycles 3개 메서드)
- Create: `trading-core/src/main/java/com/kista/trading/stats/application/service/TradingStatsService.java` (기존 `StatsService`의 trading 몫 로직 이식)
- Move (git mv + package 변경, `com.kista.stats.*` → `com.kista.trading.stats.*`):
  - `application/usecase/AccountStatisticsUseCase.java`, `PortfolioUseCase.java`
  - `application/service/AccountStatisticsService.java`, `PortfolioService.java`, `BacktestService.java`, `TossStatisticsService.java`, `BrokerStatisticsRouter.java`
  - `application/usecase/TossStatisticsUseCase.java`
  - `domain/backtest/BacktestEngine.java`, `FillSimulator.java`
  - `domain/model/backtest/*.java` (BacktestCommand/BacktestPoint/BacktestResult/BacktestSummary/DailyCandle)
  - `adapter/in/web/DashboardController.java`, `StatisticsController.java`, `TossStatisticsController.java`, `BacktestController.java`
  - 위 컨트롤러가 참조하는 `adapter/in/web/dto/` 중 trading 전용 DTO(`PortfolioSummaryResponse`, `MarginResponse`, `DailyTransactionResponse`, `MultiPriceResponse`, `CycleHistoryPageResponse`, `CycleHistoryResponse`, `TossAccountInfoResponse`, `TossCandleResponse`, `TossExchangeRateResponse`, `TossStockInfoResponse`, `TossMarketSessionResponse`) — `CycleHistoryPageResponse`/`CycleHistoryResponse`, `TossCandleResponse`는 constraints.md에 이미 기록된 own-type 이중복제(stats/trading, market/stats) 대상이라 **stats 쪽 사본은 옮기고 trading 쪽 기존 사본과 별개로 유지**(합치지 않는다 — 각자 다른 HTTP 계약).
- Modify: `src/main/java/com/kista/stats/application/service/StatsService.java` (summary/equity-curve/cycles 3개 메서드 + 관련 private 헬퍼(`loadCycles`, `CycleView`, `toCycleView`, `compatibleVrStartAmount`, `unrealizedByCycle`, `toTypeStats`, `buildPoints`, `assetOf`, `toPerformance`, `sum`) 및 그것들이 쓰는 `StrategyPort`/`StrategyCyclePort`/`CyclePositionPort` 필드 전부 삭제 — Task 2에서 이관한 것 제외)
- Modify: `src/main/java/com/kista/stats/application/usecase/UserStatsUseCase.java` (`getSummary`/`getEquityCurve`/`getCyclePerformances` 3개 메서드 시그니처 삭제, `Strategy` import 삭제)
- Modify: `src/main/java/com/kista/stats/adapter/in/web/StatsController.java` — Task 4에서 처리(이번 태스크는 서비스/유스케이스 레이어만)

**Interfaces:**
- Consumes: 없음(신규 파일들은 각자 이동 전 원본 시그니처 그대로 유지)
- Produces: `TradingStatsUseCase.getSummary(UUID)`/`getEquityCurve(UUID, StrategyType, LocalDate, LocalDate)`/`getCyclePerformances(UUID, StrategyType, Instant, int)` — Task 4의 신규 trading 쪽 컨트롤러가 소비.

- [ ] **Step 1: TradingStatsUseCase 인터페이스 작성**

```java
package com.kista.trading.stats.application.usecase;

import com.kista.sharedkernel.StrategyType;
import com.kista.trading.stats.domain.model.CyclePerformancePage;
import com.kista.trading.stats.domain.model.EquityCurve;
import com.kista.trading.stats.domain.model.StatsSummary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface TradingStatsUseCase {
    StatsSummary getSummary(UUID userId);

    EquityCurve getEquityCurve(UUID userId, StrategyType type, LocalDate from, LocalDate to);

    CyclePerformancePage getCyclePerformances(UUID userId, StrategyType type, Instant cursor, int size);
}
```

`StatsSummary`/`EquityCurve`/`EquityPoint`/`CyclePerformancePage`/`CyclePerformance`/`StrategyTypeStats`는 현재 `com.kista.stats.domain.model`에 있다 — 이 5개도 함께 `com.kista.trading.stats.domain.model`로 옮긴다(summary/equity-curve/cycles 전용 타입이라 housing/ETF 쪽에서 참조 없음, `grep -rl "StatsSummary\|EquityCurve\|EquityPoint\|CyclePerformancePage\|CyclePerformance\|StrategyTypeStats" src/main/java/com/kista/stats`로 api 쪽 잔존 참조 없음을 먼저 확인).

- [ ] **Step 2: TradingStatsService 작성 — 기존 StatsService에서 3개 메서드 + 헬퍼 통째로 이식**

기존 `StatsService.java`(`src/main/java/com/kista/stats/application/service/StatsService.java`)에서 `getSummary`/`computeSummary`/`getEquityCurve`/`computeEquityCurve`/`getCyclePerformances`/`loadCycles`(2개 오버로드)/`CycleView`/`toCycleView`/`compatibleVrStartAmount`/`unrealizedByCycle`/`toTypeStats`/`buildPoints`/`assetOf`/`toPerformance`/`sum`/`SummaryKey`/`EquityCurveKey`를 그대로 복사해 새 파일 작성. 필드는 `accountPort`/`strategyPort`/`strategyCyclePort`/`cyclePositionPort`/`statsResultCache`(Task 4에서 별도 인스턴스로 분리 예정 — 우선은 그대로 주입받는 걸로 시작)만 남긴다. `CURVE_CACHE_TTL` 상수도 함께 복사.

```java
package com.kista.trading.stats.application.service;

import com.kista.sharedkernel.TimeZones;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.stats.domain.model.*;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.stats.application.usecase.TradingStatsUseCase;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.CyclePositionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.kista.sharedkernel.StrategyType;

@Service
@RequiredArgsConstructor
class TradingStatsService implements TradingStatsUseCase {

    private static final Duration CURVE_CACHE_TTL = Duration.ofMinutes(5);

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final TradingStatsResultCache statsResultCache; // Task 4에서 신설

    // [이하 getSummary/computeSummary/getEquityCurve/computeEquityCurve/getCyclePerformances/
    //  loadCycles/CycleView/toCycleView/compatibleVrStartAmount/unrealizedByCycle/toTypeStats/
    //  buildPoints/assetOf/toPerformance/sum/SummaryKey/EquityCurveKey는
    //  원본 StatsService.java 그대로 복사 — 로직 변경 없음]
}
```

`statsResultCache`는 Task 4에서 `TradingStatsResultCache`로 분리하기 전까지는 컴파일이 안 되므로, 이 스텝에서는 임시로 기존 `StatsResultCache`(아직 api 쪽에 있는 원본)를 그대로 참조하지 말고 **Task 4를 먼저 하지 않고는 이 파일이 완성되지 않는다** — 따라서 Task 3와 Task 4는 순서를 바꿔 캐시 분리(Task 4의 캐시 부분)를 먼저 처리한다. 실행자는 아래 "순서 조정" 노트를 따를 것.

> **순서 조정 노트**: Step 2를 시작하기 전에 Task 4의 "Step A: StatsResultCache 분리"만 먼저 실행한다(`TradingStatsResultCache` 생성). Task 4의 나머지(컨트롤러 분리)는 원래 순서대로 이 태스크 이후에 진행.

- [ ] **Step 3: 나머지 클래스 이동 (git mv + 패키지 선언 변경, 로직 무변경)**

```bash
mkdir -p trading-core/src/main/java/com/kista/trading/stats/{application/usecase,application/service,domain/backtest,domain/model/backtest,adapter/in/web/dto}

for f in AccountStatisticsUseCase PortfolioUseCase TossStatisticsUseCase; do
  git mv src/main/java/com/kista/stats/application/usecase/$f.java \
         trading-core/src/main/java/com/kista/trading/stats/application/usecase/$f.java
done

for f in AccountStatisticsService PortfolioService BacktestService TossStatisticsService BrokerStatisticsRouter; do
  git mv src/main/java/com/kista/stats/application/service/$f.java \
         trading-core/src/main/java/com/kista/trading/stats/application/service/$f.java
done

git mv src/main/java/com/kista/stats/domain/backtest/BacktestEngine.java trading-core/src/main/java/com/kista/trading/stats/domain/backtest/BacktestEngine.java
git mv src/main/java/com/kista/stats/domain/backtest/FillSimulator.java trading-core/src/main/java/com/kista/trading/stats/domain/backtest/FillSimulator.java

for f in BacktestCommand BacktestPoint BacktestResult BacktestSummary DailyCandle; do
  git mv src/main/java/com/kista/stats/domain/model/backtest/$f.java \
         trading-core/src/main/java/com/kista/trading/stats/domain/model/backtest/$f.java
done

for f in DashboardController StatisticsController TossStatisticsController BacktestController; do
  git mv src/main/java/com/kista/stats/adapter/in/web/$f.java \
         trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/$f.java
done

for f in PortfolioSummaryResponse MarginResponse DailyTransactionResponse MultiPriceResponse \
         CycleHistoryPageResponse CycleHistoryResponse TossAccountInfoResponse TossCandleResponse \
         TossExchangeRateResponse TossStockInfoResponse TossMarketSessionResponse; do
  git mv src/main/java/com/kista/stats/adapter/in/web/dto/$f.java \
         trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/dto/$f.java
done
```

각 파일에서:
1. `package com.kista.stats.*` → `package com.kista.trading.stats.*` 일괄 변경 (경로와 동일하게)
2. `import com.kista.stats.domain.model.*`(위 5개 summary/equity-curve/cycles 전용 타입 및 Task 2에서 옮긴 `InvestmentPoint`/`BenchmarkGranularity`) → `com.kista.trading.stats.domain.model.*`로 교체
3. `StrategyTypeStats`/`HousingBenchmarkComparison` 등 벤치마크 전용 타입을 참조하는 파일이 있으면(예: `HousingBenchmarkComparisonBuilder`를 옮긴 클래스가 참조하는 경우) — 없음을 사전에 `grep -l "HousingBenchmarkComparisonBuilder\|HousingBenchmarkComparison" trading-core에 옮길 예정 파일들`로 확인. 있으면 안 옮기고 api에 남긴다(벤치마크 쪽은 이 태스크 대상 아님).

- [ ] **Step 4: api 쪽 StatsService/UserStatsUseCase 축소**

`src/main/java/com/kista/stats/application/service/StatsService.java`에서 Step 2가 이식해간 메서드·헬퍼·필드(`strategyPort`, `strategyCyclePort`, `cyclePositionPort`, `CURVE_CACHE_TTL`, `SummaryKey`, `EquityCurveKey`, `CycleView` 등)를 전부 삭제. 남는 건 `getHousingBenchmarkComparison`/`getEtfBenchmarkComparison`/`getHousingBenchmarkSeries`/`getHousingPriceIndexSeries`/`getEtfPriceSeries`/`getHousingBenchmarkRegions`와 그 헬퍼(`authorizeIfStrategyScope`, `comparisonWithExchangeRate`, `join`, `InvestmentContext`, `buildInvestmentContext`, `validateComparisonRequest`, `validateScopeAndRange`, `completedMonthEnd`, `fetchCurrentExchangeRate`, `EffectiveRange`). `accountPort`/`strategyPort`/`strategyCyclePort`/`cyclePositionPort` 필드는 **Task 5에서 investment-points 내부 API 호출로 교체**되므로 이번 태스크에서는 우선 그대로 둔다(컴파일 유지 목적) — `authorizeIfStrategyScope`와 `buildInvestmentContext`가 여전히 이 포트들을 직접 쓰기 때문. `UserStatsUseCase.java`에서 `getSummary`/`getEquityCurve`/`getCyclePerformances` 시그니처와 `import com.kista.trading.domain.model.Strategy;`를 삭제(남는 6개 메서드는 `Strategy` 미참조 확인됨).

- [ ] **Step 5: 전체 테스트 실행 → 그린 확인**

Run: `./gradlew test`
Expected: PASS. 기존 `StatsServiceTest`/`AccountStatisticsServiceTest` 등은 클래스 분리에 맞춰 테스트 파일도 함께 `git mv` + 대상 클래스에 맞게 테스트 메서드를 분배해야 한다 — `TradingStatsServiceTest`(신규, trading-core로) / `StatsServiceTest`(api 잔류, 벤치마크 메서드만) 두 파일로 쪼갠다.

- [ ] **Step 6: ArchUnit/Modulith 검증**

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor(stats): trading 소유 통계 코드를 com.kista.trading.stats로 흡수, StatsService를 summary/equity-curve/cycles(trading) vs housing/ETF(api)로 분리"
```

---

### Task 4: StatsResultCache 분리 + StatsController 분리

`StatsResultCache`는 요약·equity-curve용 캐시 키(`SummaryKey`/`EquityCurveKey`, TTL 5분)와 벤치마크 비교용 캐시 키(`BenchmarkComparisonKey`, TTL 10분)를 한 빈에서 같이 관리한다 — Task 3에서 로직이 두 서비스로 갈라졌으니 캐시도 갈라야 한다. `StatsController`도 3개 라우트(trading)/6개 라우트(api)로 쪼개고, trading 쪽은 새 컨트롤러로 옮긴다(URL 경로는 그대로 유지 — 단일 bootJar 배포 중엔 어느 클래스가 서빙하든 UI 입장에서 무차이).

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/stats/application/service/TradingStatsResultCache.java`
- Create: `trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/TradingStatsController.java`
- Modify: `src/main/java/com/kista/stats/application/service/StatsResultCache.java` (벤치마크 키만 남기고 클래스명은 유지 — 소비자가 `StatsService` 하나뿐이라 리네임 불필요)
- Modify: `src/main/java/com/kista/stats/adapter/in/web/StatsController.java` (`getSummary`/`getEquityCurve`/`getCycles` 3개 메서드 + `userStats.getSummary/getEquityCurve/getCyclePerformances` 호출 삭제, 관련 import(`StatsSummaryResponse`, `EquityCurveResponse`, `CyclePerformancePageResponse`, `Strategy`) 삭제)
- Modify: `trading-core/src/main/java/com/kista/trading/stats/application/service/TradingStatsService.java` (Task 3 Step 2에서 임시로 비워둔 `statsResultCache` 필드 타입을 `TradingStatsResultCache`로 확정)

**Interfaces:**
- Consumes: `TradingStatsUseCase`(Task 3) — `TradingStatsController`가 주입.
- Produces: 없음(캐시는 내부 구현 디테일).

- [ ] **Step 1: TradingStatsResultCache 작성 — 원본 StatsResultCache에서 summary/equity-curve 키만 추출**

원본 `StatsResultCache.java`의 캐시 구현(TTL 기반 `getOrCompute`/`peek` 제네릭 메서드 — 구체 구현은 원본 파일 그대로 복사)을 재사용하되, 캐시 대상 키 타입만 `Object`로 범용화되어 있다면(원본 확인 필요 — `StatsResultCache`가 이미 제네릭 캐시라면 별도 인스턴스 빈 2개로 나누는 것만으로 충분) 로직 복제 없이 **같은 `StatsResultCache` 클래스를 그대로 두 모듈에서 각자 새 빈으로 등록**하는 방식을 우선 검토한다. 원본이 제네릭 범용 캐시(키 타입이 `Object`/제네릭 파라미터)면:

```java
package com.kista.trading.stats.application.service;

// 원본 com.kista.stats.application.service.StatsResultCache와 동일 구현 —
// TTL 기반 제네릭 get-or-compute 캐시. 모듈 경계상 각자 인스턴스가 필요해 복제하되
// 구현 자체는 순수 캐시 유틸(Spring 의존 최소, 도메인 타입 의존 없음)이라 own-type 게이트
// 대상이 아니다 — 캐시 정책(TTL, 키 shape)이 다른 두 소비자를 위한 정당한 별도 인스턴스.
@org.springframework.stereotype.Component
class TradingStatsResultCache {
    // [원본 StatsResultCache.java의 필드·getOrCompute·peek 구현을 그대로 복사]
}
```

- [ ] **Step 2: 원본 StatsResultCache에서 summary/equity-curve 전용 키 타입 제거 여부 확인**

`StatsResultCache`가 캐시 정책(TTL) 자체를 소유하는 게 아니라 호출부가 TTL을 넘기는 범용 캐시라면(Task 3 Step 2에서 본 `statsResultCache.getOrCompute(key, CURVE_CACHE_TTL, ...)` 호출 형태로 보아 그렇다) `StatsResultCache` 자체의 코드 변경은 불필요 — **호출부(`StatsService`)가 이미 벤치마크 키만 쓰게 됐으므로 클래스는 그대로 두고 이름도 유지**한다. `TradingStatsResultCache`는 완전히 별개의 새 빈(같은 구현을 복제)일 뿐, 원본을 수정할 필요가 없으면 이 스텝은 "변경 없음, 확인만"으로 종료.

- [ ] **Step 3: TradingStatsService의 캐시 필드 타입 확정**

`TradingStatsService`(Task 3에서 생성)의 `statsResultCache` 필드 타입을 `TradingStatsResultCache`로 지정.

- [ ] **Step 4: TradingStatsController 작성**

```java
package com.kista.trading.stats.adapter.in.web;

import com.kista.trading.stats.application.usecase.TradingStatsUseCase;
import com.kista.trading.stats.adapter.in.web.dto.CyclePerformancePageResponse;
import com.kista.trading.stats.adapter.in.web.dto.EquityCurveResponse;
import com.kista.trading.stats.adapter.in.web.dto.StatsSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.kista.sharedkernel.StrategyType;

@Tag(name = "통계", description = "사용자 전략 수익 통계 (DB 근사 집계)")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
class TradingStatsController {

    private final TradingStatsUseCase tradingStats;

    @Operation(summary = "수익 통계 요약", description = "실현·미실현 손익과 전략 타입별 사이클 성과 집계.")
    @GetMapping("/summary")
    public StatsSummaryResponse getSummary(@AuthenticationPrincipal UUID userId) {
        return StatsSummaryResponse.from(tradingStats.getSummary(userId));
    }

    @Operation(summary = "누적 자산 곡선", description = "일별 전략 운용 자산·원금.")
    @GetMapping("/equity-curve")
    public EquityCurveResponse getEquityCurve(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) StrategyType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return EquityCurveResponse.from(tradingStats.getEquityCurve(userId, type, from, to));
    }

    @Operation(summary = "사이클 성과 목록", description = "종료·진행 중 사이클의 손익/수익률/소요일 (커서 페이지네이션).")
    @GetMapping("/cycles")
    public CyclePerformancePageResponse getCycles(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) StrategyType type,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        return CyclePerformancePageResponse.from(
                tradingStats.getCyclePerformances(userId, type, cursorInstant, Math.clamp(size, 1, 200)));
    }
}
```

`StatsSummaryResponse`/`EquityCurveResponse`/`CyclePerformancePageResponse` DTO도 Task 3에서 놓쳤다면 여기서 함께 `git mv` — `adapter/in/web/dto`에서 이 3개 파일을 `com.kista.stats`→`com.kista.trading.stats`로 이동.

- [ ] **Step 5: api 쪽 StatsController에서 3개 메서드 제거**

`getSummary`/`getEquityCurve`/`getCycles` 메서드와 관련 import(`StatsSummaryResponse`, `EquityCurveResponse`, `CyclePerformancePageResponse`, `Strategy`) 삭제. `@RequestMapping("/api/stats")`는 두 컨트롤러가 같은 prefix, 다른 sub-path를 쓰므로 Spring이 정상적으로 매핑 분산 처리한다(경로 충돌 없음 — `/api/stats/summary`는 trading 쪽, `/api/stats/housing-benchmark`는 api 쪽).

- [ ] **Step 6: 전체 테스트 + ArchUnit 실행**

Run: `./gradlew test && ./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor(stats): StatsResultCache/StatsController를 trading(summary·equity-curve·cycles)/api(benchmark) 경계로 분리"
```

---

### Task 5: investment-points 내부 API — stats의 유일한 진짜 경계

`StatsService`(api, 벤치마크 쪽)의 `buildInvestmentContext`가 `strategyPort`/`accountPort`/`strategyCyclePort`/`cyclePositionPort`를 직접 호출하는 걸 끊고, trading-core가 계산까지 마친 `InvestmentPoint` 시리즈를 내부 API로 받는다.

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/dto/InvestmentPointsResponse.java`
- Create: `trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/TradingStatsInternalController.java`
- Create: `trading-core/src/main/java/com/kista/trading/stats/application/service/InvestmentPointsQueryService.java` (기존 `StatsService.buildInvestmentContext` 로직 이식)
- Modify: `src/main/java/com/kista/stats/application/service/StatsService.java` (`buildInvestmentContext`를 내부 API 호출로 교체, `accountPort`/`strategyPort`/`strategyCyclePort`/`cyclePositionPort` 필드 삭제)
- Create: `src/main/java/com/kista/stats/application/port/output/InvestmentPointsPort.java`
- Create: `src/main/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapter.java`
- Test: `src/test/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapterTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/stats/adapter/in/web/TradingStatsInternalControllerTest.java`

**Interfaces:**
- Produces (trading-core 내부 엔드포인트): `GET /api/internal/trading/stats/investment-points?userId=&scope=&strategyId=&from=&to=&granularity=` → `InvestmentPointsResponse(List<InvestmentPoint> points, LocalDate effectiveFrom, LocalDate effectiveTo, Strategy selectedStrategy)` — `selectedStrategy`는 `scope=STRATEGY`일 때만 non-null, `HousingBenchmarkComparisonBuilder.build()`가 그대로 받는 타입이라 admin과 마찬가지로 `Strategy`를 있는 그대로 직렬화한다(own-type 불필요 — Task의 Architecture 절 참고).
- Consumes (api 쪽): `InvestmentPointsPort.fetch(UUID userId, BenchmarkScope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity) : InvestmentPointsResult`

- [ ] **Step 1: 응답 DTO 작성 (trading-core)**

```java
package com.kista.trading.stats.adapter.in.web.dto;

import com.kista.trading.domain.model.Strategy;
import com.kista.trading.stats.domain.model.InvestmentPoint;

import java.time.LocalDate;
import java.util.List;

// api의 벤치마크 비교(HousingBenchmarkComparisonBuilder)가 필요로 하는 투자 성과 시리즈 —
// STRATEGY scope일 때만 selectedStrategy가 채워진다
public record InvestmentPointsResponse(
        List<InvestmentPoint> points,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Strategy selectedStrategy
) {}
```

- [ ] **Step 2: InvestmentPointsQueryService 작성 — StatsService.buildInvestmentContext 로직 이식**

`src/main/java/com/kista/stats/application/service/StatsService.java`의 `buildInvestmentContext` 메서드 + `InvestmentContext` record를 그대로 복사해 새 클래스로 만든다. `validateScopeAndRange`도 함께 옮긴다(순수 검증 로직, 도메인 의존 없음 — trading 쪽에서 먼저 검증 후 api 쪽에서도 동일 검증을 유지할지는 Step 5에서 결정).

```java
package com.kista.trading.stats.application.service;

import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.stats.adapter.in.web.dto.InvestmentPointsResponse;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import com.kista.trading.stats.domain.model.InvestmentPoint;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.CyclePositionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InvestmentPointsQueryService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final MonthlyReturnCalculator monthlyReturnCalculator = new MonthlyReturnCalculator();

    public enum Scope { STRATEGY, PORTFOLIO }

    // 원본 StatsService.buildInvestmentContext(private)를 그대로 이식 — 소유권 검증까지
    // 이 메서드가 담당하므로(authorizeIfStrategyScope 대체) api 쪽은 사전검증을 하지 않는다.
    public InvestmentPointsResponse fetch(UUID userId, Scope scope, UUID strategyId,
                                          LocalDate from, LocalDate to, BenchmarkGranularity granularity) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(com.kista.sharedkernel.TimeZones.KST);
        Strategy selectedStrategy = null;
        List<Strategy> strategies;
        if (scope == Scope.STRATEGY) {
            selectedStrategy = strategyPort.findByIdOrThrow(strategyId);
            accountPort.findByIdOrThrow(selectedStrategy.accountId()).verifyOwnedBy(userId);
            strategies = List.of(selectedStrategy);
        } else {
            List<UUID> accountIds = accountPort.findByUserId(userId).stream()
                    .filter(a -> a.broker() != Broker.MOCK)
                    .map(Account::id)
                    .toList();
            strategies = accountIds.isEmpty() ? List.of() : strategyPort.findByAccountIds(accountIds).values().stream()
                    .flatMap(List::stream)
                    .toList();
        }

        Set<UUID> strategyIds = strategies.stream().map(Strategy::id).collect(Collectors.toSet());
        List<StrategyCycle> cycles = strategyIds.isEmpty()
                ? List.of() : strategyCyclePort.findByStrategyIds(strategyIds);
        LocalDate effectiveFrom = from != null
                ? (granularity != BenchmarkGranularity.MONTHLY ? from : from.withDayOfMonth(1))
                : cycles.stream().map(StrategyCycle::startDate).min(LocalDate::compareTo)
                        .orElse(effectiveTo).withDayOfMonth(1);
        Instant toInstant = effectiveTo.plusDays(1).atStartOfDay(com.kista.sharedkernel.TimeZones.KST).toInstant();
        List<CyclePosition> positions = scope == Scope.STRATEGY
                ? cyclePositionPort.findByStrategyAndRange(strategyId, Instant.EPOCH, toInstant)
                : cyclePositionPort.findByUserAndRange(userId, Instant.EPOCH, toInstant);

        List<InvestmentPoint> investmentPoints = monthlyReturnCalculator.calculate(
                cycles, positions, effectiveFrom, effectiveTo, granularity);

        return new InvestmentPointsResponse(investmentPoints, effectiveFrom, effectiveTo, selectedStrategy);
    }
}
```

(원본의 `completedMonthEnd`(MONTHLY clamp)는 벤치마크 쪽 관심사라 api의 `StatsService`에 그대로 남기고, 여기서는 호출부(api)가 이미 clamp된 `to`를 넘겨준다고 가정한다 — Step 5에서 api 쪽 `computeHousingComparisonBody`/`computeEtfComparisonBody`가 `completedMonthEnd` 호출 후 그 결과를 `to`로 넘기도록 배선.)

- [ ] **Step 3: 내부 API 컨트롤러 작성**

```java
package com.kista.trading.stats.adapter.in.web;

import com.kista.trading.stats.application.service.InvestmentPointsQueryService;
import com.kista.trading.stats.adapter.in.web.dto.InvestmentPointsResponse;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/stats")
@RequiredArgsConstructor
public class TradingStatsInternalController {

    private final InvestmentPointsQueryService investmentPointsQueryService;

    @Operation(summary = "투자 성과 시리즈 조회", description = "벤치마크 비교용 InvestmentPoint 시리즈. X-Internal-Token 헤더 필수.")
    @GetMapping("/investment-points")
    public InvestmentPointsResponse getInvestmentPoints(
            @RequestParam UUID userId,
            @RequestParam InvestmentPointsQueryService.Scope scope,
            @RequestParam(required = false) UUID strategyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam BenchmarkGranularity granularity) {
        return investmentPointsQueryService.fetch(userId, scope, strategyId, from, to, granularity);
    }
}
```

- [ ] **Step 4: api 쪽 포트 + HTTP 어댑터 작성**

```java
package com.kista.stats.application.port.output;

import com.kista.trading.domain.model.Strategy;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import com.kista.trading.stats.domain.model.InvestmentPoint;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvestmentPointsPort {
    record Result(List<InvestmentPoint> points, LocalDate effectiveFrom, LocalDate effectiveTo, Strategy selectedStrategy) {}

    Result fetch(UUID userId, Scope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity);

    enum Scope { STRATEGY, PORTFOLIO }
}
```

```java
package com.kista.stats.adapter.out.internal;

import com.kista.stats.application.port.output.InvestmentPointsPort;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class InvestmentPointsHttpAdapter implements InvestmentPointsPort {

    private final RestClient internalApiRestClient;

    @Override
    public Result fetch(UUID userId, Scope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity) {
        return internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/trading/stats/investment-points")
                        .queryParam("userId", userId)
                        .queryParam("scope", scope)
                        .queryParamIfPresent("strategyId", java.util.Optional.ofNullable(strategyId))
                        .queryParamIfPresent("from", java.util.Optional.ofNullable(from))
                        .queryParamIfPresent("to", java.util.Optional.ofNullable(to))
                        .queryParam("granularity", granularity)
                        .build())
                .retrieve()
                .body(Result.class);
    }
}
```

(`Result`가 record라 Jackson 기본 역직렬화가 되지만 필드명이 응답 `InvestmentPointsResponse`와 동일해야 매핑된다 — `points`/`effectiveFrom`/`effectiveTo`/`selectedStrategy`로 맞춰뒀으므로 그대로 매핑됨.)

- [ ] **Step 5: api StatsService가 새 포트를 쓰도록 배선**

`StatsService`의 `buildInvestmentContext`를 삭제하고, `computeHousingComparisonBody`/`computeEtfComparisonBody`가 `investmentPointsPort.fetch(...)`를 직접 호출하도록 교체. `accountPort`/`strategyPort`/`strategyCyclePort`/`cyclePositionPort` 필드와 `authorizeIfStrategyScope`(소유권 검증이 trading 쪽으로 이전됐으므로 삭제) 제거, `InvestmentPointsPort investmentPointsPort` 필드 추가.

- [ ] **Step 6: 통합 테스트 작성 — MockWebServer로 InvestmentPointsHttpAdapter 검증**

```java
package com.kista.stats.adapter.out.internal;

import com.kista.stats.application.port.output.InvestmentPointsPort;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentPointsHttpAdapterTest {

    private MockWebServer server;
    private InvestmentPointsHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).build();
        adapter = new InvestmentPointsHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void 응답을_역직렬화한다() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"points":[],"effectiveFrom":"2026-01-01","effectiveTo":"2026-09-01","selectedStrategy":null}
                    """)
                .build());

        InvestmentPointsPort.Result result = adapter.fetch(
                UUID.randomUUID(), InvestmentPointsPort.Scope.PORTFOLIO, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1),
                com.kista.trading.stats.domain.model.BenchmarkGranularity.MONTHLY);

        assertThat(result.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.points()).isEmpty();
    }
}
```

`mockwebserver3`가 프로젝트에 없으면 `okhttp3:mockwebserver3` 테스트 의존성을 `build.gradle.kts`의 `testImplementation`에 추가.

- [ ] **Step 7: 전체 테스트 + ArchUnit 실행**

Run: `./gradlew test && ./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 8: 값 비교 검증 (임시 스크립트, 커밋 대상 아님)**

DB 공유 상태이므로 옛 경로(직접 포트 호출로 계산한 investment points)와 새 경로(내부 API 응답)를 같은 (userId, scope, from, to)로 호출해 값이 일치하는지 로컬에서 1회 수동 확인 — 스테이징/로컬 DB에 실제 사용자 데이터가 있는 계정으로 `/api/stats/housing-benchmark` 응답을 변경 전후 커밋에서 각각 호출해 diff.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "feat(stats): investment-points 내부 API 신설 — StatsService 벤치마크 비교가 trading 데이터를 HTTP로 조회하도록 전환"
```

---

### Task 6: AdminQueryService 분리 + trading-core 내부 읽기 컨트롤러

`AdminQueryService`의 order/strategy 관련 6개 메서드를 제거하고, trading-core에 대응하는 읽기 전용 내부 엔드포인트를 신설한다. `listPrivacyBases`는 별도로 privacy 모듈에 내부 엔드포인트를 추가한다(스펙 C의 privacy-bases 특이사항 — privacy는 통째 이동이지만 소비자인 admin이 api에 남아 seam이 성립).

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/web/TradingInternalQueryController.java`
- Create: `trading-core/src/main/java/com/kista/privacy/adapter/in/web/PrivacyInternalQueryController.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminQueryService.java` (order/strategy 관련 메서드 6개를 내부 API 호출로 교체)
- Modify: `src/main/java/com/kista/admin/application/usecase/AdminQueryUseCase.java` (시그니처는 그대로 — 인터페이스 계약 불변, 구현만 교체)
- Create: `src/main/java/com/kista/admin/application/port/output/TradingQueryPort.java`
- Create: `src/main/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapter.java`
- Test: `src/test/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapterTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/in/web/TradingInternalQueryControllerTest.java`

**Interfaces:**
- Produces (trading-core):
  - `GET /api/internal/trading/orders?from=&to=` → `List<Order>`
  - `GET /api/internal/trading/orders/distinct-account-ids?from=&to=` → `List<UUID>`
  - `GET /api/internal/trading/accounts/{accountId}/strategies` → `List<Strategy>`
  - `POST /api/internal/trading/strategies/by-account-ids` body `Set<UUID>` → `Map<UUID, List<Strategy>>`
  - `POST /api/internal/trading/strategy-summaries` body `Set<UUID>` → `Map<UUID, StrategySummary>`
  - `GET /api/internal/trading/accounts/{accountId}/strategies/{strategyId}/orders?tradeDate=` → `List<Order>`
  - `GET /api/internal/trading/accounts/{accountId}/strategies/{strategyId}/trade-dates` → `List<LocalDate>`
- Produces (privacy): `GET /api/internal/privacy/trade-bases?fromReleaseDate=` → `List<PrivacyTradeBaseView>`
- Consumes: `TradingQueryPort`(admin이 정의) — `AdminQueryService`가 주입.

- [ ] **Step 1: TradingInternalQueryController 작성**

```java
package com.kista.trading.adapter.in.web;

import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategySummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading")
@RequiredArgsConstructor
public class TradingInternalQueryController {

    private final OrderPort orderPort;
    private final StrategyPort strategyPort;

    @Operation(summary = "기간 내 전체 주문 조회", description = "관리자 거래내역 조회용. X-Internal-Token 필수.")
    @GetMapping("/orders")
    public List<Order> listOrders(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return orderPort.findAll(from, to);
    }

    @Operation(summary = "기간 내 distinct 계좌 ID", description = "이상징후 감지용.")
    @GetMapping("/orders/distinct-account-ids")
    public List<UUID> listDistinctAccountIds(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return orderPort.findDistinctAccountIdsByTradeDateBetween(from, to);
    }

    @Operation(summary = "계좌 단건 전략 목록")
    @GetMapping("/accounts/{accountId}/strategies")
    public List<Strategy> listStrategiesByAccount(@PathVariable UUID accountId) {
        return strategyPort.findByAccountId(accountId);
    }

    @Operation(summary = "계좌 다건 전략 배치 조회", description = "N+1 방지 배치 조회.")
    @PostMapping("/strategies/by-account-ids")
    public Map<UUID, List<Strategy>> listStrategiesByAccountIds(@RequestBody Set<UUID> accountIds) {
        return strategyPort.findByAccountIds(accountIds);
    }

    @Operation(summary = "사이클 ID 기준 전략 요약 배치 조회")
    @PostMapping("/strategy-summaries")
    public Map<UUID, StrategySummary> getStrategySummariesByCycleIds(@RequestBody Set<UUID> cycleIds) {
        return strategyPort.findSummariesByCycleIds(cycleIds);
    }

    @Operation(summary = "전략 단건 주문 조회")
    @GetMapping("/accounts/{accountId}/strategies/{strategyId}/orders")
    public List<Order> listStrategyOrders(
            @PathVariable UUID accountId, @PathVariable UUID strategyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        requireStrategyOwnedByAccount(accountId, strategyId);
        return orderPort.findByStrategyId(strategyId, tradeDate, tradeDate);
    }

    @Operation(summary = "전략 단건 거래일 목록")
    @GetMapping("/accounts/{accountId}/strategies/{strategyId}/trade-dates")
    public List<LocalDate> listStrategyTradeDates(@PathVariable UUID accountId, @PathVariable UUID strategyId) {
        requireStrategyOwnedByAccount(accountId, strategyId);
        return orderPort.findTradeDatesByStrategyId(strategyId);
    }

    // 경로 계층 정합성 검증 — 기존 AdminQueryService.requireStrategyOwnedByAccount와 동일 규칙,
    // 소유권 검증이 데이터를 가진 trading 쪽으로 이전됐다
    private void requireStrategyOwnedByAccount(UUID accountId, UUID strategyId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        if (!strategy.accountId().equals(accountId)) {
            throw new java.util.NoSuchElementException("전략이 해당 계좌에 속하지 않습니다");
        }
    }
}
```

- [ ] **Step 2: PrivacyInternalQueryController 작성**

```java
package com.kista.privacy.adapter.in.web;

import com.kista.privacy.application.usecase.PrivacyUseCase;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/privacy")
@RequiredArgsConstructor
public class PrivacyInternalQueryController {

    private final PrivacyUseCase privacy;

    @Operation(summary = "기준 매매표 조회", description = "admin 조회 전용. X-Internal-Token 필수.")
    @GetMapping("/trade-bases")
    public List<PrivacyTradeBaseView> listTradeBases(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromReleaseDate) {
        return privacy.findBasesFromTradeDate(fromReleaseDate);
    }
}
```

(`PrivacyUseCase`에 `findBasesFromTradeDate`가 없으면 `AdminQueryService.listPrivacyBases`가 실제로 호출하던 `privacyTradePort.findBasesFromTradeDate`를 그대로 노출하도록 `PrivacyUseCase`에 메서드 추가 — 기존 `PrivacyTradePort`는 internal이므로 usecase 경유가 맞다.)

- [ ] **Step 3: admin 쪽 TradingQueryPort 작성**

```java
package com.kista.admin.application.port.output;

import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategySummary;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface TradingQueryPort {
    List<Order> findAllOrders(LocalDate from, LocalDate to);
    List<UUID> findDistinctAccountIds(LocalDate from, LocalDate to);
    List<Strategy> findStrategiesByAccountId(UUID accountId);
    Map<UUID, List<Strategy>> findStrategiesByAccountIds(Set<UUID> accountIds);
    Map<UUID, StrategySummary> findStrategySummariesByCycleIds(Set<UUID> cycleIds);
    List<Order> findStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate);
    List<LocalDate> findStrategyTradeDates(UUID accountId, UUID strategyId);
}
```

- [ ] **Step 4: admin 쪽 TradingQueryHttpAdapter 작성**

```java
package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.TradingQueryPort;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class TradingQueryHttpAdapter implements TradingQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<Order> findAllOrders(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/orders").queryParam("from", from).queryParam("to", to).build())
                .retrieve().body(new ParameterizedTypeReference<List<Order>>() {});
    }

    @Override
    public List<UUID> findDistinctAccountIds(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/orders/distinct-account-ids").queryParam("from", from).queryParam("to", to).build())
                .retrieve().body(new ParameterizedTypeReference<List<UUID>>() {});
    }

    @Override
    public List<Strategy> findStrategiesByAccountId(UUID accountId) {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/accounts/{accountId}/strategies", accountId)
                .retrieve().body(new ParameterizedTypeReference<List<Strategy>>() {});
    }

    @Override
    public Map<UUID, List<Strategy>> findStrategiesByAccountIds(Set<UUID> accountIds) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/strategies/by-account-ids")
                .body(accountIds)
                .retrieve().body(new ParameterizedTypeReference<Map<UUID, List<Strategy>>>() {});
    }

    @Override
    public Map<UUID, StrategySummary> findStrategySummariesByCycleIds(Set<UUID> cycleIds) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/strategy-summaries")
                .body(cycleIds)
                .retrieve().body(new ParameterizedTypeReference<Map<UUID, StrategySummary>>() {});
    }

    @Override
    public List<Order> findStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/orders")
                        .queryParam("tradeDate", tradeDate).build(accountId, strategyId))
                .retrieve().body(new ParameterizedTypeReference<List<Order>>() {});
    }

    @Override
    public List<LocalDate> findStrategyTradeDates(UUID accountId, UUID strategyId) {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/trade-dates", accountId, strategyId)
                .retrieve().body(new ParameterizedTypeReference<List<LocalDate>>() {});
    }
}
```

privacy-bases 호출은 같은 어댑터에 메서드를 추가하거나 별도 `PrivacyQueryPort`+어댑터로 분리 — 이미 admin에 `PrivacyTradePort` 직접 참조가 있었으니 여기선 `TradingQueryPort`와 별개로 `com.kista.admin.application.port.output.PrivacyQueryPort`(단일 메서드)를 만들어 어댑터도 별도 클래스(`PrivacyQueryHttpAdapter`)로 분리한다(모듈 개념상 trading과 privacy는 별개이므로 포트도 분리 유지).

- [ ] **Step 5: AdminQueryService 배선 교체**

`orderPort`/`strategyPort`/`privacyTradePort` 필드를 삭제하고 `TradingQueryPort tradingQueryPort`, `PrivacyQueryPort privacyQueryPort`로 교체. `listTrades`/`getAnomalies`(order/strategy 조회분)/`getStrategySummariesByCycleIds`/`listStrategyOrders`/`listStrategyTradeDates`/`listStrategies`/`listStrategiesByAccountIds`/`listPrivacyBases` 구현을 각각 대응 포트 메서드 호출로 교체(`requireStrategyOwnedByAccount`는 trading 쪽으로 이전됐으므로 삭제, 대신 어댑터가 그대로 예외를 전파하면 `GlobalExceptionHandler`가 `NoSuchElementException`을 동일하게 404로 매핑한다 — RestClient의 4xx 응답을 `HttpClientErrorException`으로 감싸지 않고 상태 코드까지 그대로 전달하려면 `RestClient`의 기본 예외 변환을 확인해 필요시 `.onStatus(...)`로 원래 예외 타입 재구성 로직 추가).

- [ ] **Step 6: 예외 매핑 처리 확인**

`RestClient`는 4xx/5xx를 기본적으로 `RestClientResponseException` 계열로 던진다 — 기존 `AdminQueryService`가 던지던 `NoSuchElementException`(→404) 계약을 유지하려면 `TradingQueryHttpAdapter`에 `.onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { throw new NoSuchElementException(...); })` 같은 변환을 추가하거나, admin 쪽에서 `RestClientResponseException`을 잡아 상태 코드별로 재매핑하는 걸 `GlobalExceptionHandler`에 추가한다 — 이번 태스크에서는 후자를 택한다: `GlobalExceptionHandler`에 `RestClientResponseException` 핸들러를 추가해 원본 상태 코드를 그대로 응답에 반영(`ResponseEntity.status(e.getStatusCode())`).

- [ ] **Step 7: 전체 테스트 + ArchUnit 실행**

Run: `./gradlew test && ./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 8: 값 비교 검증**

DB 공유 상태 — 변경 전/후 커밋에서 admin 화면의 거래내역/이상징후/전략목록/기준매매표 조회 응답을 같은 파라미터로 호출해 값 일치 확인(1회 수동).

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "feat(admin): AdminQueryService의 order/strategy/privacy 조회를 trading-core 내부 API 호출로 전환"
```

---

### Task 7: AdminSelectionChain/AdminCycleCloser를 trading-core로 이관 (User 의존 소멸)

`AdminSelectionChain.validate()`는 `user.id()`만 쓴다 — trading 쪽 버전은 `User` 객체 없이 `UUID userId`만 받는다. `AdminCycleCloser`는 이미 trading 타입만 쓰므로 그대로 이동.

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/application/service/SelectionChain.java`
- Create: `trading-core/src/main/java/com/kista/trading/application/service/CycleCloser.java`
- Delete: `src/main/java/com/kista/admin/application/service/AdminSelectionChain.java`
- Delete: `src/main/java/com/kista/admin/application/service/AdminCycleCloser.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/SelectionChainTest.java`

**Interfaces:**
- Produces: `SelectionChain.resolveAndValidate(AccountPort, StrategyPort, UUID accountId, UUID strategyId, UUID userId) : Selection(Account, Strategy)`, `SelectionChain.validate(UUID userId, Account, Strategy)`, `SelectionChain.validate(UUID userId, Account, Strategy, Order)`, `CycleCloser.closeIfExhausted(StrategyCyclePort, StrategyPort, Strategy, StrategyCycle, AccountBalance, LocalDate) : CycleEndResult` — Task 8이 소비.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.kista.trading.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelectionChainTest {

    @Test
    void account가_userId에_속하지_않으면_예외() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();

        AccountPort accountPort = mock(AccountPort.class);
        StrategyPort strategyPort = mock(StrategyPort.class);
        Account account = mock(Account.class);
        Strategy strategy = mock(Strategy.class);
        when(account.userId()).thenReturn(otherUserId);
        when(account.id()).thenReturn(accountId);
        when(strategy.accountId()).thenReturn(accountId);
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        assertThatThrownBy(() ->
                SelectionChain.resolveAndValidate(accountPort, strategyPort, accountId, strategyId, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account가 user에 속하지 않습니다");
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.application.service.SelectionChainTest'`
Expected: FAIL (클래스 없음)

- [ ] **Step 3: SelectionChain 작성 — User 의존 제거**

```java
package com.kista.trading.application.service;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.StrategyPort;

import java.util.UUID;

// 관리자 작업 대상 선택 체인 검증 — account→strategy→order 소속 관계 확인.
// user는 신원 대조(UUID 비교)만 필요해 User 객체 전체를 들고 있지 않는다 —
// 호출자(admin, api에 남음)가 user 존재 자체는 자기 쪽에서 이미 검증했다는 전제.
public final class SelectionChain {

    private SelectionChain() {}

    public record Selection(Account account, Strategy strategy) {}

    public static Selection resolveAndValidate(AccountPort accountPort, StrategyPort strategyPort,
                                                UUID accountId, UUID strategyId, UUID userId) {
        Account account = accountPort.findByIdOrThrow(accountId);
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        validate(userId, account, strategy);
        return new Selection(account, strategy);
    }

    public static void validate(UUID userId, Account account, Strategy strategy) {
        if (!account.userId().equals(userId)) {
            throw new IllegalArgumentException("account가 user에 속하지 않습니다");
        }
        if (!strategy.accountId().equals(account.id())) {
            throw new IllegalArgumentException("strategy가 account에 속하지 않습니다");
        }
    }

    public static void validate(UUID userId, Account account, Strategy strategy, Order order) {
        validate(userId, account, strategy);
        if (!order.accountId().equals(account.id())) {
            throw new IllegalArgumentException("order가 account에 속하지 않습니다");
        }
    }
}
```

- [ ] **Step 4: CycleCloser 작성 — 순수 이동, 로직 무변경**

```bash
git mv src/main/java/com/kista/admin/application/service/AdminCycleCloser.java trading-core/src/main/java/com/kista/trading/application/service/CycleCloser.java
```

패키지 선언 `com.kista.trading.application.service`로 변경, 클래스명 `AdminCycleCloser`→`CycleCloser`, `final class` → `public final class`(admin 쓰기 로직이 Task 8에서 같은 패키지가 아니게 되므로 접근 범위 확대 필요 — 실제로는 Task 8의 `ReorderService`/`ManualTradeCorrectionService`가 같은 `com.kista.trading.application.service` 패키지에 위치하므로 package-private 유지 가능. `public` 불필요, `final class CycleCloser`로 유지).

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.application.service.SelectionChainTest'`
Expected: PASS

- [ ] **Step 6: 기존 admin 쪽 파일 삭제 확인 + 전체 테스트**

```bash
git rm src/main/java/com/kista/admin/application/service/AdminSelectionChain.java
```
(AdminCycleCloser는 Step 4에서 이미 `git mv`로 처리됨)

Run: `./gradlew test`
Expected: 아직 `AdminReorderService`/`AdminTradeCorrectionService`가 삭제된 `AdminSelectionChain`/`AdminCycleCloser`를 참조하므로 컴파일 실패 — 정상. Task 8에서 이 두 서비스 자체를 옮기며 함께 해결한다. **이 태스크의 커밋은 Task 8과 함께 묶는다** (별도 커밋 시 컴파일이 깨진 중간 상태가 되므로 Global Constraints의 "태스크마다 전체 테스트 그린" 원칙에 따라 Task 7+8을 하나의 커밋 단위로 처리).

- [ ] **Step 7: (Task 8 완료 후) 커밋** — Task 8의 Step 마지막에서 함께 커밋한다.

---

### Task 8: reorder/trade-correction을 trading-core 내부 API로 이관

`ReorderCommand`/`ReorderResult`/`ManualTradeCorrectionCommand`/`ManualTradeCorrectionResult`는 trading-core가 admin 타입을 참조할 수 없어서 신설하는 own-type이다(constraints.md 게이트 (a) — trading-core는 `:api`에 대한 Gradle 의존이 전혀 없음). admin의 `AdminReorderService`/`AdminTradeCorrectionService`는 요청을 매핑해서 내부 API를 부르는 얇은 어댑터로 바뀐다.

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/domain/model/ReorderCommand.java`
- Create: `trading-core/src/main/java/com/kista/trading/domain/model/ReorderResult.java`
- Create: `trading-core/src/main/java/com/kista/trading/domain/model/ManualTradeCorrectionCommand.java`
- Create: `trading-core/src/main/java/com/kista/trading/domain/model/ManualTradeCorrectionResult.java`
- Create: `trading-core/src/main/java/com/kista/trading/application/usecase/ReorderUseCase.java`
- Create: `trading-core/src/main/java/com/kista/trading/application/usecase/ManualTradeCorrectionUseCase.java`
- Create: `trading-core/src/main/java/com/kista/trading/application/service/ReorderService.java` (기존 `AdminReorderService` 로직 이식, `User user` 파라미터 제거)
- Create: `trading-core/src/main/java/com/kista/trading/application/service/ManualTradeCorrectionService.java` (기존 `AdminTradeCorrectionService` 로직 이식)
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/web/TradingInternalCommandController.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminReorderService.java` (내부 API 호출 프록시로 축소)
- Modify: `src/main/java/com/kista/admin/application/service/AdminTradeCorrectionService.java` (내부 API 호출 프록시로 축소)
- Create: `src/main/java/com/kista/admin/application/port/output/TradingCommandPort.java`
- Create: `src/main/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapter.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/ReorderServiceTest.java` (기존 `AdminReorderServiceTest`를 옮기고 `User` 관련 stub 제거)
- Test: `trading-core/src/test/java/com/kista/trading/application/service/ManualTradeCorrectionServiceTest.java` (기존 `AdminTradeCorrectionServiceTest` 이동)
- Test: `src/test/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapterTest.java`

**Interfaces:**
- Produces (trading-core):
  - `POST /api/internal/trading/reorder` body `ReorderCommand` → `ReorderResult`
  - `POST /api/internal/trading/trade-corrections` body `ManualTradeCorrectionCommand` → `ManualTradeCorrectionResult`
  - `GET /api/internal/trading/reorder-timing-availability` → `{"atOpen":bool,"atClose":bool,"immediate":bool}`(기존 `DstInfo.ReorderTimingAvailability` 그대로 직렬화 — admin은 이미 이 타입을 import 가능하므로 그대로 역직렬화)
- Consumes: `TradingCommandPort`(admin이 정의) — `AdminReorderService`/`AdminTradeCorrectionService`가 주입.

- [ ] **Step 1: trading 쪽 own-type 4개 작성**

```java
package com.kista.trading.domain.model;

import com.kista.matching.domain.model.OrderTiming;
import com.kista.sharedkernel.OrderDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// AdminReorderCommand와 구조적으로 동일 — trading-core는 :api에 대한 Gradle 의존이 없어
// com.kista.admin.domain.model.AdminReorderCommand를 참조할 수 없으므로 own-type으로 신설한다
// (constraints.md 게이트 (a) 순환 불가피). 호출자(admin)가 자기 타입에서 필드 그대로 매핑한다.
public record ReorderCommand(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID orderId,
        OrderTiming timing,
        LocalDate tradeDate,
        OrderDirection direction,
        Integer quantity,
        BigDecimal price,
        String memo
) {}
```

```java
package com.kista.trading.domain.model;

import java.util.UUID;

public record ReorderResult(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID sourceOrderId,
        Order.OrderStatus originalStatus,
        Order.OrderStatus resultingStatus,
        String newOrderExternalId
) {}
```

```java
package com.kista.trading.domain.model;

import com.kista.sharedkernel.OrderDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ManualTradeCorrectionCommand(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        List<Fill> fills
) {
    public record Fill(
            LocalDate tradeDate,
            OrderDirection direction,
            int quantity,
            BigDecimal price,
            String externalOrderId,
            String memo
    ) {}
}
```

```java
package com.kista.trading.domain.model;

import com.kista.sharedkernel.StrategyStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ManualTradeCorrectionResult(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        int processedCount,
        int finalHoldings,
        BigDecimal finalAvgPrice,
        BigDecimal finalUsdDeposit,
        StrategyStatus strategyStatus,
        boolean cycleEnded,
        LocalDate cycleEndDate
) {}
```

- [ ] **Step 2: ReorderUseCase/ManualTradeCorrectionUseCase 인터페이스**

```java
package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;

public interface ReorderUseCase {
    ReorderResult reorder(ReorderCommand command);
}
```

```java
package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ManualTradeCorrectionResult;

public interface ManualTradeCorrectionUseCase {
    ManualTradeCorrectionResult correctManualFills(ManualTradeCorrectionCommand command);
}
```

- [ ] **Step 3: ReorderService 작성 — AdminReorderService 로직 이식, User 제거**

기존 `AdminReorderService`(`src/main/java/com/kista/admin/application/service/AdminReorderService.java`)를 기반으로:
- `userPort`/`AuditLogPort` 필드 삭제(감사 로그는 api에 남는다 — Task의 스펙 C 참고. admin이 응답을 받은 뒤 자기 쪽에서 기록)
- `AdminSelectionChain.resolveAndValidate(userPort, accountPort, strategyPort, command.userId(), command.accountId(), command.strategyId())` → `SelectionChain.resolveAndValidate(accountPort, strategyPort, command.accountId(), command.strategyId(), command.userId())`
- `AdminReorderCommand`/`AdminReorderResult` → `ReorderCommand`/`ReorderResult`로 타입 교체
- `adminId` 파라미터 삭제(감사 로그가 admin 쪽으로 넘어갔으므로 trading은 adminId를 몰라도 됨)
- 나머지 로직(`cancelIfNeeded`, `placeOrSave`, `requirePrice`, `requireQuantity`) 그대로

```java
package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.sharedkernel.TimeZones;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.application.usecase.ReorderUseCase;
import com.kista.account.application.port.output.AccountPort;
import com.kista.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.sharedkernel.OrderDirection;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class ReorderService implements ReorderUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final BrokerAdapterRegistry brokerAdapterRegistry;
    private final MarketCalendarPort marketCalendarPort;

    @Override
    public ReorderResult reorder(ReorderCommand command) {
        return reorder(command, DstInfo.calculate(), Instant.now());
    }

    // 테스트 주입용 — DstInfo + 판정 시각 직접 지정
    ReorderResult reorder(ReorderCommand command, DstInfo dst, Instant now) {
        SelectionChain.Selection sel = SelectionChain.resolveAndValidate(
                accountPort, strategyPort, command.accountId(), command.strategyId(), command.userId());
        Account account = sel.account();
        Strategy strategy = sel.strategy();
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
        Order sourceOrder = orderPort.findById(command.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + command.orderId()));

        SelectionChain.validate(command.userId(), account, strategy, sourceOrder);
        if (!sourceOrder.strategyCycleId().equals(currentCycle.id())) {
            throw new IllegalArgumentException("현재 전략 사이클 주문만 재주문할 수 있습니다");
        }

        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        OrderDirection direction = command.direction() != null ? command.direction() : sourceOrder.direction();
        LocalDate tradeDate = command.tradeDate() != null ? command.tradeDate() : sourceOrder.tradeDate();

        cancelIfNeeded(sourceOrder, account);

        if (!marketCalendarPort.isMarketOpen(LocalDate.now(TimeZones.KST))) {
            throw new IllegalArgumentException("휴장일에는 재주문할 수 없습니다");
        }
        DstInfo.ReorderTimingAvailability avail = dst.reorderTimingAvailabilityAt(now);
        boolean timingOk = switch (command.timing()) {
            case AT_OPEN -> avail.atOpen();
            case AT_CLOSE -> avail.atClose();
            case IMMEDIATE -> avail.immediate();
        };
        if (!timingOk) {
            throw new IllegalArgumentException("현재 시장 단계에서 " + command.timing() + " 접수가 불가합니다");
        }

        Order newOrder = Order.reorder(sourceOrder, tradeDate, direction, quantity, price, command.timing());
        PlacementResult placement = placeOrSave(newOrder, account, command.timing());

        return new ReorderResult(command.userId(), command.accountId(), command.strategyId(),
                sourceOrder.id(), sourceOrder.status(), placement.status(), placement.externalOrderId());
    }

    private void cancelIfNeeded(Order order, Account account) {
        switch (order.status()) {
            case PLANNED -> orderPort.markCancelled(order.id());
            case PLACED -> {
                brokerAdapterRegistry.require(account.toBrokerRef(), BrokerOrderCorrectionPort.class)
                        .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account.toBrokerRef());
                orderPort.markCancelled(order.id());
            }
            default -> {}
        }
    }

    private PlacementResult placeOrSave(Order newOrder, Account account, com.kista.matching.domain.model.OrderTiming timing) {
        if (timing == com.kista.matching.domain.model.OrderTiming.IMMEDIATE) {
            BrokerOrderCorrectionPort broker = brokerAdapterRegistry.require(account.toBrokerRef(), BrokerOrderCorrectionPort.class);
            try {
                OrderInstruction instruction = new OrderInstruction(newOrder.ticker(), newOrder.direction(),
                        newOrder.orderType(), newOrder.quantity(), newOrder.price());
                OrderResult result = broker.place(instruction, account.toBrokerRef());
                Order placed = newOrder.withPlaced(result.externalOrderId());
                orderPort.saveAll(List.of(placed));
                return new PlacementResult(Order.OrderStatus.PLACED, placed.externalOrderId());
            } catch (Exception e) {
                log.warn("재주문 즉시 접수 실패 — FAILED 기록: error={}", e.getMessage());
                orderPort.saveAll(List.of(newOrder.withFailed()));
                return new PlacementResult(Order.OrderStatus.FAILED, null);
            }
        }
        orderPort.saveAll(List.of(newOrder));
        return new PlacementResult(Order.OrderStatus.PLANNED, null);
    }

    private record PlacementResult(Order.OrderStatus status, String externalOrderId) {}

    private static BigDecimal requirePrice(ReorderCommand command) {
        if (command.price() == null || command.price().signum() <= 0) {
            throw new IllegalArgumentException("price는 양수여야 합니다");
        }
        return command.price();
    }

    private static int requireQuantity(ReorderCommand command) {
        if (command.quantity() == null || command.quantity() <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다");
        }
        return command.quantity();
    }
}
```

(감사 로그 payload 조립 로직(`auditPayload`)은 admin 쪽으로 옮긴다 — Step 6 참고.)

- [ ] **Step 4: ManualTradeCorrectionService 작성 — AdminTradeCorrectionService 로직 이식, User 제거**

기존 로직에서 `userPort`/`AuditLogPort`/`user` 변수 제거, `AdminSelectionChain`→`SelectionChain`, `AdminCycleCloser`→`CycleCloser`, `AdminManualTradeCorrectionCommand`→`ManualTradeCorrectionCommand`, `AdminTradeCorrectionResult`→`ManualTradeCorrectionResult`, `adminId` 파라미터 삭제. `eventPublisher.publishEvent(new CycleEndedEvent(...))`는 그대로 유지(trading 자기 이벤트를 trading이 발행 — 오히려 더 자연스러워짐, `sel.account().id()` 사용, `user.id()` 대신 `command.userId()` 사용).

```java
package com.kista.trading.application.service;

import com.kista.trading.application.event.CycleEndedEvent;
import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.trading.domain.model.Order;
import com.kista.matching.domain.model.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.application.usecase.ManualTradeCorrectionUseCase;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
class ManualTradeCorrectionService implements ManualTradeCorrectionUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public ManualTradeCorrectionResult correctManualFills(ManualTradeCorrectionCommand command) {
        SelectionChain.Selection sel = SelectionChain.resolveAndValidate(
                accountPort, strategyPort, command.accountId(), command.strategyId(), command.userId());
        Account account = sel.account();
        Strategy strategy = sel.strategy();
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
        CyclePosition latest = cyclePositionPort.findLatestOne(currentCycle.id())
                .orElseThrow(() -> new IllegalStateException("최신 cycle_position이 없습니다: cycleId=" + currentCycle.id()));
        if (currentCycle.endDate() != null) {
            throw new IllegalStateException("이미 종료된 사이클은 수동 체결 보정을 지원하지 않습니다");
        }

        AccountBalance balance = latest.toBalance();
        Strategy updatedStrategy = strategy;
        boolean cycleEnded = false;
        List<Order> manualOrders = new ArrayList<>();

        for (int i = 0; i < command.fills().size(); i++) {
            ManualTradeCorrectionCommand.Fill fill = command.fills().get(i);
            boolean isLastFill = i == command.fills().size() - 1;

            validateSellQuantity(fill, balance);
            manualOrders.add(toManualOrder(fill, account, currentCycle, strategy));
            balance = applyFillAndSnapshot(fill, strategy, balance, currentCycle);

            if (balance.holdings() == 0) {
                if (!isLastFill) {
                    throw new IllegalArgumentException("청산 이후 추가 체결은 같은 요청에서 처리할 수 없습니다");
                }
                updatedStrategy = CycleCloser.closeIfExhausted(strategyCyclePort, strategyPort,
                        updatedStrategy, currentCycle, balance, fill.tradeDate()).strategy();
                cycleEnded = true;
            }
        }

        if (cycleEnded) {
            eventPublisher.publishEvent(new CycleEndedEvent(command.userId(), account.id(), updatedStrategy));
        }
        orderPort.saveAll(manualOrders);

        return buildResult(account, strategy, command, balance, updatedStrategy, cycleEnded);
    }

    private static void validateSellQuantity(ManualTradeCorrectionCommand.Fill fill, AccountBalance balance) {
        if (fill.direction() == OrderDirection.SELL && fill.quantity() > balance.holdings()) {
            throw new IllegalArgumentException("SELL quantity가 현재 holdings를 초과합니다");
        }
    }

    private static Order toManualOrder(ManualTradeCorrectionCommand.Fill fill, Account account,
                                       StrategyCycle currentCycle, Strategy strategy) {
        return Order.filledManual(account.id(), currentCycle.id(), fill.tradeDate(),
                strategy.ticker(), OrderTiming.AT_CLOSE, fill.direction(),
                fill.quantity(), fill.price(), fill.externalOrderId());
    }

    private AccountBalance applyFillAndSnapshot(ManualTradeCorrectionCommand.Fill fill, Strategy strategy,
                                                AccountBalance balance, StrategyCycle currentCycle) {
        Execution execution = Execution.ofManualFill(fill.tradeDate(), strategy.ticker(),
                fill.direction(), fill.quantity(), fill.price(), fill.externalOrderId());
        AccountBalance.Fill f = new AccountBalance.Fill() {
            @Override public OrderDirection direction() { return execution.direction(); }
            @Override public int quantity() { return execution.quantity(); }
            @Override public BigDecimal amountUsd() { return execution.amountUsd(); }
        };
        AccountBalance updated = balance.applyExecutions(List.of(f));
        cyclePositionPort.save(CyclePosition.tradeSnapshot(currentCycle.id(), updated, fill.price()));
        return updated;
    }

    private ManualTradeCorrectionResult buildResult(Account account, Strategy strategy,
                                                     ManualTradeCorrectionCommand command, AccountBalance balance,
                                                     Strategy updatedStrategy, boolean cycleEnded) {
        return new ManualTradeCorrectionResult(
                command.userId(), account.id(), strategy.id(),
                command.fills().size(), balance.holdings(), balance.avgPrice(), balance.usdDeposit(),
                updatedStrategy.status(), cycleEnded,
                cycleEnded ? command.fills().getLast().tradeDate() : null);
    }
}
```

- [ ] **Step 5: TradingInternalCommandController 작성**

```java
package com.kista.trading.adapter.in.web;

import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;
import com.kista.trading.application.usecase.ManualTradeCorrectionUseCase;
import com.kista.trading.application.usecase.ReorderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading")
@RequiredArgsConstructor
public class TradingInternalCommandController {

    private final ReorderUseCase reorderUseCase;
    private final ManualTradeCorrectionUseCase manualTradeCorrectionUseCase;

    @Operation(summary = "관리자 재주문 접수/취소")
    @PostMapping("/reorder")
    public ReorderResult reorder(@RequestBody @Valid ReorderCommand command) {
        return reorderUseCase.reorder(command);
    }

    @Operation(summary = "관리자 수동 체결 보정")
    @PostMapping("/trade-corrections")
    public ManualTradeCorrectionResult correctManualFills(@RequestBody @Valid ManualTradeCorrectionCommand command) {
        return manualTradeCorrectionUseCase.correctManualFills(command);
    }

    @Operation(summary = "재주문 시점 가용성 조회")
    @GetMapping("/reorder-timing-availability")
    public DstInfo.ReorderTimingAvailability reorderTimingAvailability() {
        return DstInfo.calculate().reorderTimingAvailability();
    }
}
```

- [ ] **Step 6: admin 쪽 TradingCommandPort + HTTP 어댑터 + AdminReorderService/AdminTradeCorrectionService 축소**

```java
package com.kista.admin.application.port.output;

import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;

public interface TradingCommandPort {
    ReorderResult reorder(ReorderCommand command);
    ManualTradeCorrectionResult correctManualFills(ManualTradeCorrectionCommand command);
    DstInfo.ReorderTimingAvailability reorderTimingAvailability();
}
```

```java
package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
class TradingCommandHttpAdapter implements TradingCommandPort {

    private final RestClient internalApiRestClient;

    @Override
    public ReorderResult reorder(ReorderCommand command) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/reorder")
                .body(command)
                .retrieve()
                .body(ReorderResult.class);
    }

    @Override
    public ManualTradeCorrectionResult correctManualFills(ManualTradeCorrectionCommand command) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/trade-corrections")
                .body(command)
                .retrieve()
                .body(ManualTradeCorrectionResult.class);
    }

    @Override
    public DstInfo.ReorderTimingAvailability reorderTimingAvailability() {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/reorder-timing-availability")
                .retrieve()
                .body(DstInfo.ReorderTimingAvailability.class);
    }
}
```

`AdminReorderService`를 아래로 교체(감사 로그는 여기 남는다 — `sourceOrder`/`account`/`strategy` 조회가 없어졌으므로 감사 payload는 응답(`ReorderResult`)에서 구성 가능한 값으로 축소):

```java
package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.application.usecase.AdminReorderUseCase;
import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class AdminReorderService implements AdminReorderUseCase {

    private static final String AUDIT_ACTION = "REORDER";
    private static final String AUDIT_TARGET_TYPE = "ORDER";

    private final TradingCommandPort tradingCommandPort;
    private final AuditLogPort auditLogPort;

    @Override
    public AdminReorderResult reorder(UUID adminId, AdminReorderCommand command) {
        ReorderCommand tradingCommand = new ReorderCommand(
                command.userId(), command.accountId(), command.strategyId(), command.orderId(),
                command.timing(), command.tradeDate(), command.direction(),
                command.quantity(), command.price(), command.memo());

        ReorderResult result = tradingCommandPort.reorder(tradingCommand);

        auditLogPort.log(adminId, AUDIT_ACTION, AUDIT_TARGET_TYPE, result.sourceOrderId(),
                auditPayload(command, result));

        return new AdminReorderResult(result.userId(), result.accountId(), result.strategyId(),
                result.sourceOrderId(), result.originalStatus(), result.resultingStatus(), result.newOrderExternalId());
    }

    private static Map<String, Object> auditPayload(AdminReorderCommand command, ReorderResult result) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("timing", command.timing().name());
        payload.put("strategyId", command.strategyId().toString());
        payload.put("accountId", command.accountId().toString());
        payload.put("orderId", result.sourceOrderId().toString());
        payload.put("originalStatus", result.originalStatus().name());
        payload.put("resultingStatus", result.resultingStatus().name());
        if (command.memo() != null && !command.memo().isBlank()) {
            payload.put("memo", command.memo());
        }
        return payload;
    }
}
```

`AdminTradeCorrectionService`도 동일 패턴으로 교체(필드를 `TradingCommandPort`+`AuditLogPort`로 축소, `command`→`ManualTradeCorrectionCommand` 매핑, 결과 매핑, 감사 로그는 응답의 `processedCount`/`cycleEnded` 등으로 구성).

- [ ] **Step 7: 기존 AdminReorderServiceTest/AdminTradeCorrectionServiceTest를 trading-core로 이동 + admin 쪽엔 매핑/감사로그만 검증하는 얇은 테스트 신설**

`src/test/java/com/kista/admin/application/service/AdminReorderServiceTest.java`의 시나리오(가용성 판단, cancelIfNeeded 분기, placeOrSave 분기 등)를 `trading-core/src/test/java/com/kista/trading/application/service/ReorderServiceTest.java`로 옮기고 `User user` 관련 stub·assertion을 제거, `AdminReorderCommand`→`ReorderCommand` 등으로 타입 교체. admin 쪽에는 `AdminReorderServiceTest`를 새로 작성해 `TradingCommandPort`를 mock하고 (1) 요청 매핑이 정확한지, (2) 응답 매핑이 정확한지, (3) `auditLogPort.log`가 올바른 인자로 호출되는지만 검증.

- [ ] **Step 8: 전체 테스트 + ArchUnit 실행 (Task 7 포함)**

Run: `./gradlew test && ./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS — 이 시점에 Task 7에서 깨졌던 컴파일도 함께 해결됨.

- [ ] **Step 9: 커밋 (Task 7 + Task 8 통합)**

```bash
git add -A
git commit -m "feat(trading): 관리자 재정렬·수동 정정 로직을 trading-core로 이관, admin은 내부 API 프록시로 축소

AdminSelectionChain/AdminCycleCloser도 함께 이동(User 의존 소멸 — userId 대조만 필요).
ReorderCommand/ReorderResult/ManualTradeCorrectionCommand/ManualTradeCorrectionResult는
trading-core가 :api를 참조할 수 없어 신설한 own-type(constraints.md 게이트 (a))."
```

---

### Task 9: 검증 게이트 — MOCK 브로커 통합테스트 + 스테이징 리허설 + 전체 스위트

**Files:**
- Create: `src/test/java/com/kista/admin/AdminInternalApiIntegrationTest.java` (`@SpringBootTest`, MOCK 브로커 계좌로 reorder/trade-correction 왕복 검증)
- Modify: 없음(검증 전용 태스크)

**Interfaces:**
- 없음(테스트 전용 태스크)

- [ ] **Step 1: MOCK 브로커 계좌 통합테스트 작성**

```java
package com.kista.admin;

import com.kista.admin.application.usecase.AdminReorderUseCase;
import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.matching.domain.model.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DB 공유 상태에서 admin의 reorder가 실제로 내부 API를 왕복해 MOCK 브로커까지 도달하는지 확인 —
// 값 비교(옛 경로 vs 새 경로) 대체 검증: 쓰기는 이중 실행이 불가능하므로 MOCK 계좌로 동등성만 확인
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminInternalApiIntegrationTest {

    @Autowired
    private AdminReorderUseCase adminReorderUseCase;

    @Test
    void MOCK_계좌_재주문이_내부_API를_왕복해_PLANNED로_접수된다() {
        // given: 테스트 지원 데이터(사용자·MOCK 계좌·전략·사이클·원본 주문)를 픽스처로 준비
        //        (com.kista.support 헬퍼 또는 JdbcTemplate 직접 삽입 — testing.md "타 패키지 FK 삽입 패턴" 참고)
        // ... 픽스처 준비 코드 ...

        AdminReorderCommand command = new AdminReorderCommand(
                /* userId */ null, /* accountId */ null, /* strategyId */ null, /* orderId */ null,
                OrderTiming.AT_CLOSE, LocalDate.now(), OrderDirection.BUY, 1, BigDecimal.TEN, "통합테스트");

        AdminReorderResult result = adminReorderUseCase.reorder(UUID.randomUUID(), command);

        assertThat(result.resultingStatus().name()).isEqualTo("PLANNED");
    }
}
```

(실행자 주: 픽스처 준비는 기존 `AdminReorderServiceTest`/`AdminTradeCorrectionServiceTest`가 Mockito로 흉내내던 걸 실제 DB 행으로 준비해야 한다 — `testing.md`의 "통합 테스트에서 타 패키지 FK 삽입 패턴" 참고. 서버가 자기 자신에게 내부 API를 호출하므로 `@SpringBootTest(webEnvironment = RANDOM_PORT)`가 필수이고, `internal.api.base-url` 테스트 프로퍼티를 `http://localhost:${local.server.port}`로 오버라이드해야 한다 — `@DynamicPropertySource`로 `local.server.port`를 읽어 주입.)

- [ ] **Step 2: 전체 테스트 스위트 최종 1회 실행**

Run: `./gradlew test`
Expected: PASS — Global Constraints의 "최종 1회" 원칙(전역 CLAUDE.md 1.1)에 따라 이 태스크 전까지는 태스크별 부분 실행(`--tests`)만 쓰고, 여기서 최초로 전체를 돈다.

- [ ] **Step 3: ApplicationModules.verify() 최종 확인**

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 4: 값 비교 검증 요약 문서화 (커밋 메시지에 포함, 별도 파일 생성 안 함)**

Task 5/6에서 수동 확인한 값 비교 결과(investment-points, 거래내역, 이상징후, 전략목록, 기준매매표)를 커밋 메시지 본문에 한 줄씩 기록 — 스펙의 "2단계 전용: DB 공유 상태에서 옛 경로·새 경로 응답을 같은 입력으로 비교" 게이트 충족 증거.

- [ ] **Step 5: 스테이징 리허설 1회 (매매 시간대 회피)**

스테이징 환경에 이 브랜치 배포 → admin UI에서 재정렬·수동 정정·거래내역 조회를 실제로 수행해 회귀 없는지 확인. 매매 시간대(22:30~04:30 KST MON-SAT) 회피 — 평일 낮 또는 일요일 진행.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "test(admin): 2단계 완료 검증 — MOCK 브로커 통합테스트, 전체 스위트/ApplicationModules.verify() 그린"
```

---

## 실행자 참고

- 스펙의 "제외" 목록(`TradingCycleController`/preview, `AccountStatisticsService` 등 통째 이동분)에 대해 이 계획은 손대지 않는다 — Task 3에서 이미 물리적으로 이동하지만 HTTP 경계는 만들지 않는다.
- own-type 판단은 태스크마다 다르다: Task 6/일반 읽기는 admin이 여전히 trading 타입을 그대로 쓴다(own-type 없음). Task 8의 reorder/trade-correction 4개 타입만 방향이 반대라 own-type이 필요하다 — 이 차이를 헷갈리면 안 된다.
- 3단계(`:api → :trading-core` 컴파일 의존 0)에 들어가면 이 계획에서 "그대로 재사용"했던 `Order`/`Strategy`/`StrategySummary`/`OrderTiming` 등 약 20개 참조가 전부 own-type 전환 대상이 된다 — 스펙의 3단계 절 참고.
