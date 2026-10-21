# 레거시 `com.kista.adapter`/`com.kista.application` shim 해소 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 최상위 shim 패키지 `com.kista.adapter`/`com.kista.application`를 소멸시키고 내용물을 `com.kista.web`(앱셸)·`com.kista.platform`(인프라)·각 소유 모듈로 재배치해, `ApplicationModules.verify()`가 이 45개 파일의 크로스모듈 의존을 실제로 검사하게 만든다.

**Architecture:** 세 방향 재배치 — (1) 진짜 앱 레벨 관심사(enum 집계·전역 예외 매핑·오류 로그 AOP·메트릭)만 담는 `com.kista.web` CLOSED inbound sink 모듈, (2) outbound-zero를 ArchUnit로 증명하는 `com.kista.platform` OPEN 인프라 모듈(persistence base·crypto·scheduler 골격), (3) 단일 소유자가 명확한 컨트롤러·포트·SSE는 해당 모듈로. 컨트롤러는 아무도 import하지 않아 sink 모듈은 모든 모듈로 fan-out해도 순환 불가. SSE는 notify(발송 채널)로 통합.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5 + Mockito + AssertJ, ArchUnit, PostgreSQL(로컬 docker-compose 테스트 DB)

**Spec:** `docs/superpowers/specs/2026-09-04-modulith-legacy-adapter-dissolution-design.md`

## Global Constraints

- 코드 철학: 4-space 들여쓰기, 불변 값은 `record`, 생성자 주입, 가능하면 package-private. 반복 코드보다 재사용·가독성.
- 주석: 신규 코드에 주석 필수. 필드 `// 역할 한 줄`, 로직 블록 직전 단계 설명 한 줄, `//` 인라인만 — Javadoc·블록 주석 금지. 주석은 한글.
- 커밋: 한글 메시지, Conventional Commit 접두사(`refactor(modulith):`/`test:`/`docs:` 등) + 명령형 제목. author `narafu <narafu@kakao.com>`. `git push` 금지(사용자 명시 요청 시만). 커밋 메시지 끝에:
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
  ```
- Virtual Thread 활성 — `@Async`·`CompletableFuture` 사용 금지.
- 아웃바운드 포트 인터페이스는 `*Port` 접미사, `application/port/output/`에 위치. `*Repository` 금지.
- `@Enumerated(EnumType.STRING)` 단독, VARCHAR — enum 변경 없음(이 플랜은 타입 이동만).
- 게이트: `./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'` + `--tests 'com.kista.architecture.ModulithArchitectureTest'` 둘 다 통과. 최종 태스크에서 전체 스위트 1회.
- **kista-ui 불변 계약:** 패키지 이동은 JSON 직렬화에 불가시. DTO 필드·`from()` 반환 형태·REST 경로를 절대 바꾸지 않는다. SSE 엔드포인트 경로(`/api/auth/status-stream`, `/api/trades/stream`)·FCM 경로(`/api/fcm/tokens`) 불변.
- 테스트 파일을 옮길 때 `package` 선언 + `@WebMvcTest(X.class)` 참조 + import만 갱신하고 테스트 본문 로직은 유지한다.
- BOM 주의: import 수정 후 `grep -rl $'\xef\xbb\xbf' src --include="*.java"`로 확인, 있으면 `sed -i '' '1s/^\xef\xbb\xbf//'`.
- 커밋 전 리뷰어 검수(리뷰어 서브에이전트 또는 `/code-review`) — 각 태스크 diff 단위. 실제 결함은 커밋 전 수정·재검증.

---

## 현황 실측 (플랜 작성 시점 grep 결과)

- **죽은 와일드카드 import 6곳:** `trading/application/service/{TradingService,TradingReporter,ManualTradingService,CyclePositionPersistor,CycleRotationService,VrCycleRolloverService}.java`가 `import com.kista.application.port.output.*;`를 갖고 있으나 `RealtimeNotificationPort`를 이름으로 참조하는 곳은 0건.
- **`SchedulerJobRunner`**: `NotifyPort` 주입 + `run(String, Supplier<List<BatchContext>>, Action)` 오버로드에서 `trading.domain.model.BatchContext`를 제네릭 타입 인자로 참조. 소비자 9곳(user/finance/market×2/trading×2/stats×3 스케쥴러). `run(String, Runnable)` 오버로드는 finance/market×2/stats×3/user가 사용, `BatchContext` 오버로드는 trading×2만 사용.
- **`SchedulerLockService`**: `JdbcTemplate`만 주입 — 이미 outbound-zero.
- **`platform` 이동 대상 소비자**: crypto(2)·persistence base(3)·scheduler(2) 합쳐 `com.kista.adapter.out.crypto`/`com.kista.adapter.out.persistence`/`com.kista.adapter.in.schedule` import를 가진 파일 59개(main+test).
- **`stats → broker` 모듈 엣지 이미 존재** (TossStatisticsService/StatsService/BrokerStatisticsRouter). `broker → stats` 없음. broker "domain" 소비 DTO를 stats로 옮겨도 순환 아님.
- **`stats → trading` 이미 존재**, `trading → stats`·`trading → strategyconfig` 없음 — `TradingCycleController`를 trading에 넣으면 `stats↔trading` 순환 발생하므로 `web`에 둔다.
- **DTO 이중 소유 2건:**
  - `CycleHistoryPageResponse` + 중첩용 `CycleHistoryResponse` — `DashboardController`(→stats) + `TradingCycleController`(→web). 둘 다 `trading.domain.model.{CycleHistoryPage,CyclePositionHistoryEntry}`만 소비(양쪽 가시).
  - `TossCandleResponse` — `TossStatisticsController`(→stats) + `market/MarketHolidayController`. `broker.domain.model.toss.TossCandle` 소비.
  - `MarketSessionResponse` — `MarketHolidayController` **단독** (스펙 §5의 "market+stats 이중"은 오기, 단순 이동).
- **`SseEmitterRegistry`**: `implements RealtimeNotificationPort` + `@TransactionalEventListener`로 user `UserApprovedEvent`/`UserRejectedEvent` 구독 + `TradeEvent`(trading) import + `TradeSseEmitterRegistry` 주입. notify로 옮기면 `notify→user`(event, 이미 존재)·`notify→trading`(이미 존재) — 순환 아님.
- **`AuthController`**(`com.kista.user.adapter.in.web`): `SseEmitterRegistry`를 concrete 주입, `@GetMapping("/status-stream")` → `sseEmitterRegistry.connect(userId)` 한 줄. 나머지 인증 핸들러는 user 잔류.
- **기존 테스트 파일**: `SchedulerJobRunnerTest`, `{Dashboard,Statistics,TossStatistics,Fcm,TradeStream,Meta,TradingCycle}ControllerTest`, `GlobalExceptionHandlerTest`, `ErrorLogAspect{,Pointcut}Test`, `{SseEmitterRegistry,TradeSseEmitterRegistry}Test`, `AuthController{,Token}Test`, `MarketHolidayControllerTest`, `AesCryptoServiceTest`, persistence adapter 테스트들.

---

## 파일 구조 (최종 상태)

```
com.kista/
  web/
    package-info.java                    (신규) @ApplicationModule(type = CLOSED)
    GlobalExceptionHandler.java          (이동 from adapter.in.web)
    MetaController.java                  (이동)
    TradingCycleController.java          (이동)
    config/MetricsConfig.java            (이동 from application.config)
    aop/ErrorLogAspect.java              (이동 from adapter.in.aop)
    dto/                                 (이동) TradingCycleRequest, TradingCycleResponse, VrConfigRequest,
                                          NextOrdersResponse, ExecuteOrdersResponse, CancelOrdersResponse,
                                          StrategyOrdersResponse, StrategySeedPreviewResponse, EnumMeta,
                                          MetaBundle, StrategyTypeMeta, TickerMeta,
                                          CycleHistoryPageResponse, CycleHistoryResponse (web 소유 사본)
  platform/
    package-info.java                    (신규) @ApplicationModule(type = OPEN)
    persistence/                         BaseAuditEntity, BaseCreatedAtEntity, JpaAuditingConfig  (이동)
    crypto/                              AesCryptoService, AccountNoHasher  (이동)
    scheduling/                          SchedulerJobRunner, SchedulerLockService, SchedulerLifecycleEvent(신규)  (이동)
  notify/
    adapter/in/web/
      package-info.java                  (신규 패키지 — NamedInterface 없음, internal)
      FcmController.java                 (이동 from adapter.in.web)
      TradeStreamController.java         (이동)
      StatusStreamController.java        (신규 — AuthController.statusStream 분리 이관)
      dto/FcmTokenRequest.java           (이동)
    adapter/out/sse/                     SseEmitterRegistry, TradeSseEmitterRegistry  (이동 from adapter.out.sse)
    adapter/out/gateway/
      SchedulerNotifier.java             (신규 — SchedulerLifecycleEvent 구독)
    application/port/output/
      RealtimeNotificationPort.java      (이동 from application.port.output — "port" NamedInterface 합류)
  stats/
    adapter/in/web/
      DashboardController.java           (이동)
      StatisticsController.java          (이동)
      TossStatisticsController.java      (이동)
      openapi/HousingBenchmarkOpenApiCustomizer.java  (이동)
      dto/                               DailyTransactionResponse, MarginResponse, MultiPriceResponse,
                                          PortfolioSummaryResponse, TossStockInfoResponse, TossExchangeRateResponse,
                                          TossMarketSessionResponse, TossAccountInfoResponse,
                                          TossCandleResponse (stats 소유 사본),
                                          CycleHistoryPageResponse, CycleHistoryResponse (stats 소유 사본)
  broker/
    adapter/out/marketdata/CommonMarketPriceFeed.java  (이동 from adapter.out.marketdata)
  market/
    adapter/in/web/dto/
      MarketSessionResponse.java         (이동 — 단독 소유)
      TossCandleResponse.java            (market 소유 사본)
  user/
    adapter/in/web/AuthController.java   (수정 — statusStream 메서드 + SseEmitterRegistry 필드 제거)

삭제: com.kista.adapter/package-info.java, com.kista.application/package-info.java,
      com.kista.adapter (디렉토리 전체), com.kista.application (디렉토리 전체)
유지: com.kista.common (무변경), com.kista.sharedkernel (무변경)
```

---

## Task 1: `SchedulerJobRunner` notify 디커플링 + 제네릭화

**Files:**
- Create: `src/main/java/com/kista/adapter/in/schedule/SchedulerLifecycleEvent.java` (Task 2에서 platform으로 함께 이동)
- Create: `src/main/java/com/kista/notify/adapter/out/gateway/SchedulerNotifier.java`
- Modify: `src/main/java/com/kista/adapter/in/schedule/SchedulerJobRunner.java`
- Test: `src/test/java/com/kista/adapter/in/schedule/SchedulerJobRunnerTest.java` (재작성)
- Test: `src/test/java/com/kista/notify/adapter/out/gateway/SchedulerNotifierTest.java` (신규)

**Interfaces:**
- Consumes: `com.kista.notify.application.port.output.NotifyPort` (기존, `notifyInfo(String)`/`notifyError(Exception)`), `org.springframework.context.ApplicationEventPublisher`
- Produces:
  - `SchedulerLifecycleEvent(String jobName, Phase phase, String errorMessage)` — `enum Phase { STARTED, COMPLETED, FAILED }`. `errorMessage`는 `phase == FAILED`일 때만 non-null.
  - `SchedulerJobRunner`: 기존 public 시그니처를 제네릭화 — `void run(String name, Runnable job)` (불변), `<T> void run(String name, Supplier<List<T>> contextSupplier, Action<T> action) throws InterruptedException`, `@FunctionalInterface interface Action<T> { void accept(List<T> contexts) throws Exception; }`. 생성자: `SchedulerJobRunner(ApplicationEventPublisher events)` — `NotifyPort` 제거.
  - `SchedulerNotifier`: `@TransactionalEventListener(fallbackExecution = true) void onSchedulerLifecycle(SchedulerLifecycleEvent event)`

- [ ] **Step 1: 이벤트 클래스 작성**

`src/main/java/com/kista/adapter/in/schedule/SchedulerLifecycleEvent.java`:

```java
package com.kista.adapter.in.schedule;

// 스케쥴러 공통 골격의 생명주기 알림 — SchedulerJobRunner→notify 직접 호출을 끊기 위한 이벤트
// (market FearGreedFetchFailedEvent / privacy PrivacyAlertRaisedEvent와 동일 패턴, EPR 추적).
// Exception 자체는 EPR 직렬화 부적합(스택트레이스·cause 체인)이라 errorMessage(String)만 담는다 —
// 전체 스택은 SchedulerJobRunner의 log.error가 이미 남긴다.
public record SchedulerLifecycleEvent(String jobName, Phase phase, String errorMessage) {

    // 스케쥴러 실행 단계 — 발행 측(SchedulerJobRunner) 분기와 소비 측(SchedulerNotifier) 라우팅에 함께 쓰인다
    public enum Phase { STARTED, COMPLETED, FAILED }

    public static SchedulerLifecycleEvent started(String jobName) {
        return new SchedulerLifecycleEvent(jobName, Phase.STARTED, null);
    }

    public static SchedulerLifecycleEvent completed(String jobName) {
        return new SchedulerLifecycleEvent(jobName, Phase.COMPLETED, null);
    }

    public static SchedulerLifecycleEvent failed(String jobName, Throwable error) {
        return new SchedulerLifecycleEvent(jobName, Phase.FAILED, error.getMessage());
    }
}
```

- [ ] **Step 2: `SchedulerJobRunnerTest` 재작성 (실패 확인용)**

`src/test/java/com/kista/adapter/in/schedule/SchedulerJobRunnerTest.java` 전체 교체:

```java
package com.kista.adapter.in.schedule;

import com.kista.adapter.in.schedule.SchedulerLifecycleEvent.Phase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SchedulerJobRunnerTest {

    @Mock ApplicationEventPublisher events;
    SchedulerJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SchedulerJobRunner(events);
    }

    @Test
    void 정상_완료_시_STARTED와_COMPLETED_이벤트를_발행한다() throws InterruptedException {
        runner.run("장 개시 스케쥴러", List::of, contexts -> {});

        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(SchedulerLifecycleEvent::phase)
                .containsExactly(Phase.STARTED, Phase.COMPLETED);
        assertThat(captor.getAllValues()).allSatisfy(e -> assertThat(e.jobName()).isEqualTo("장 개시 스케쥴러"));
    }

    @Test
    void 일반_예외_시_FAILED_이벤트만_발행하고_COMPLETED는_발행하지_않는다() throws InterruptedException {
        runner.run("마감 매매 스케쥴러", List::of,
                contexts -> { throw new IllegalStateException("boom"); });

        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(SchedulerLifecycleEvent::phase)
                .containsExactly(Phase.STARTED, Phase.FAILED);
        assertThat(captor.getAllValues().get(1).errorMessage()).isEqualTo("boom");
    }

    @Test
    void 인터럽트_발생_시_FAILED_이벤트_발행_후_rethrow한다() {
        // 인터럽트를 삼키면 SchedulerLockService가 성공으로 간주해 락을 2~3h 유지 → 수동 복구 불가
        assertThatThrownBy(() -> runner.run("마감 매매 스케쥴러", List::of,
                contexts -> { throw new InterruptedException("배포 재시작"); }))
                .isInstanceOf(InterruptedException.class);

        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues().get(1).phase()).isEqualTo(Phase.FAILED);
    }

    @Test
    void Runnable_작업_예외_시_FAILED_이벤트만_발행한다() {
        runner.run("FearGreed 수집", () -> { throw new IllegalStateException("boom"); });

        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(SchedulerLifecycleEvent::phase)
                .containsExactly(Phase.STARTED, Phase.FAILED);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.kista.adapter.in.schedule.SchedulerJobRunnerTest'`
Expected: 컴파일 실패 (`SchedulerJobRunner` 생성자가 아직 `NotifyPort` 요구, `SchedulerLifecycleEvent` 미참조 상태에선 통과할 수도 있으니 컴파일 에러 우선)

- [ ] **Step 4: `SchedulerJobRunner` 구현 교체**

`src/main/java/com/kista/adapter/in/schedule/SchedulerJobRunner.java` 전체 교체:

```java
package com.kista.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

// 스케쥴러 공통 실행 골격 — "STARTED 이벤트 → try(contexts 빌드 → 실행) → 인터럽트/예외 처리 → COMPLETED/FAILED 이벤트"
// notify 직접 호출 대신 SchedulerLifecycleEvent를 발행하고 notify가 SchedulerNotifier로 구독한다
// (모듈 순환 제거 — market/privacy/stats AlertNotifier와 동일 패턴). com.kista.platform.scheduling으로 이동 예정.
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerJobRunner {

    private final ApplicationEventPublisher events;

    // BatchContext 없이 단순 Runnable 작업 실행 — FearGreed·MarketCalendar 스케쥴러용
    public void run(String name, Runnable job) {
        events.publishEvent(SchedulerLifecycleEvent.started(name));
        log.info("{} 시작", name);
        try {
            job.run();
            log.info("{} 완료", name);
            events.publishEvent(SchedulerLifecycleEvent.completed(name));
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            events.publishEvent(SchedulerLifecycleEvent.failed(name, e));
        }
    }

    // name: 스케쥴러 표시명 (e.g., "장 개시 스케쥴러", "마감 매매 스케쥴러 수동")
    // contexts 타입은 호출 모듈 소유 — 골격은 size()만 로그에 쓰고 그대로 Action에 넘긴다 (모듈 경계상 제네릭)
    public <T> void run(String name, Supplier<List<T>> contextSupplier, Action<T> action) throws InterruptedException {
        events.publishEvent(SchedulerLifecycleEvent.started(name));
        try {
            List<T> contexts = contextSupplier.get(); // try 안 — 조회 실패도 FAILED로 잡히도록
            log.info("{} 시작 — 대상 {}개", name, contexts.size());
            action.accept(contexts);
            log.info("{} 완료", name);
            events.publishEvent(SchedulerLifecycleEvent.completed(name));
        } catch (InterruptedException e) {
            // 배포·재기동 강제 종료 — 이벤트 발행(동기) 후 rethrow해 SchedulerLockService가 락을 즉시 해제하도록 함
            log.warn("{} 인터럽트: {}", name, e.getMessage());
            events.publishEvent(SchedulerLifecycleEvent.failed(name, e));
            throw e;
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            events.publishEvent(SchedulerLifecycleEvent.failed(name, e));
        }
    }

    @FunctionalInterface
    public interface Action<T> {
        void accept(List<T> contexts) throws Exception;
    }
}
```

- [ ] **Step 5: `TradingOpenScheduler`/`TradingCloseScheduler`의 `Action` raw 타입 참조 갱신**

두 파일에서 `SchedulerJobRunner.Action`을 구현/참조하는 지점을 `SchedulerJobRunner.Action<BatchContext>`로 명시(또는 람다면 무변경 — 컴파일 확인). `run(name, supplier, lambda)` 호출부는 `Supplier<List<BatchContext>>` 추론되므로 대개 무변경.

Run: `./gradlew compileJava`
Expected: SUCCESS

- [ ] **Step 6: `SchedulerNotifier` 작성**

`src/main/java/com/kista/notify/adapter/out/gateway/SchedulerNotifier.java`:

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.adapter.in.schedule.SchedulerLifecycleEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// SchedulerJobRunner가 발행하는 생명주기 이벤트를 구독해 기존 NotifyPort로 중계한다
// (MarketAlertNotifier/PrivacyAlertNotifier/StatsAlertNotifier와 동일 패턴 — 4번째 인스턴스).
// 스케쥴러는 @Transactional 밖에서 실행되므로 fallbackExecution=true로 발행 시점에 동기 실행되게 한다.
@Component
@RequiredArgsConstructor
public class SchedulerNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onSchedulerLifecycle(SchedulerLifecycleEvent event) {
        switch (event.phase()) {
            case STARTED -> notifyPort.notifyInfo(event.jobName() + " 시작");
            case COMPLETED -> notifyPort.notifyInfo(event.jobName() + " 완료");
            // 전체 스택은 SchedulerJobRunner.log.error가 남기므로 여기선 message만 래핑 (EPR 직렬화 안전)
            case FAILED -> notifyPort.notifyError(new RuntimeException("[" + event.jobName() + "] " + event.errorMessage()));
        }
    }
}
```

- [ ] **Step 7: `SchedulerNotifierTest` 작성**

`src/test/java/com/kista/notify/adapter/out/gateway/SchedulerNotifierTest.java`:

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.adapter.in.schedule.SchedulerLifecycleEvent;
import com.kista.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SchedulerNotifierTest {

    @Mock NotifyPort notifyPort;

    @Test
    void STARTED는_시작_정보_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(SchedulerLifecycleEvent.started("장 개시 스케쥴러"));
        verify(notifyPort).notifyInfo("장 개시 스케쥴러 시작");
    }

    @Test
    void COMPLETED는_완료_정보_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(SchedulerLifecycleEvent.completed("장 개시 스케쥴러"));
        verify(notifyPort).notifyInfo("장 개시 스케쥴러 완료");
    }

    @Test
    void FAILED는_jobName_prefix를_붙여_오류_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(
                SchedulerLifecycleEvent.failed("마감 매매 스케쥴러", new IllegalStateException("boom")));
        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(notifyPort).notifyError(captor.capture());
        assertThat(captor.getValue().getMessage()).isEqualTo("[마감 매매 스케쥴러] boom");
    }
}
```

- [ ] **Step 8: 관련 테스트 실행**

Run: `./gradlew test --tests 'com.kista.adapter.in.schedule.SchedulerJobRunnerTest' --tests 'com.kista.notify.adapter.out.gateway.SchedulerNotifierTest' --tests 'com.kista.*.adapter.in.schedule.*SchedulerTest'`
Expected: PASS (스케쥴러 테스트들이 `SchedulerJobRunner` mock을 쓰면 무영향, 실제 주입하면 생성자 인자 갱신 필요 — 실패 시 해당 테스트의 `new SchedulerJobRunner(...)` 인자를 `mock(ApplicationEventPublisher.class)`로 교체)

- [ ] **Step 9: 아키텍처 게이트**

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS (아직 `SchedulerJobRunner`는 shim에 있으나 `NotifyPort`·`BatchContext` 의존이 사라져 나중 CLOSED 전환 준비 완료)

- [ ] **Step 10: 리뷰어 검수 후 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): SchedulerJobRunner의 NotifyPort 직접 호출 → SchedulerLifecycleEvent 팬아웃 + 제네릭화

notify가 SchedulerNotifier로 구독 (Market/Privacy/StatsAlertNotifier 4번째 인스턴스).
run() BatchContext 오버로드를 <T> 제네릭으로 좁혀 trading 타입 참조 제거.
platform 인프라 모듈 이관(Task 2) 선결.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
EOF
)"
```

---

## Task 2: `com.kista.platform` 인프라 모듈 신설 + 이동

**Files:**
- Create: `src/main/java/com/kista/platform/package-info.java`
- Move: `com/kista/adapter/out/crypto/{AesCryptoService,AccountNoHasher}.java` → `com/kista/platform/crypto/`
- Move: `com/kista/adapter/out/persistence/{BaseAuditEntity,BaseCreatedAtEntity,JpaAuditingConfig}.java` → `com/kista/platform/persistence/`
- Move: `com/kista/adapter/in/schedule/{SchedulerJobRunner,SchedulerLockService,SchedulerLifecycleEvent}.java` → `com/kista/platform/scheduling/`
- Modify: `src/test/java/com/kista/architecture/HexagonalArchitectureTest.java` (새 불변식 테스트 추가)
- Modify: ~59개 소비자 파일의 import
- Move tests: `AesCryptoServiceTest`, `SchedulerJobRunnerTest` → 새 패키지. `SchedulerNotifierTest` import 갱신.

**Interfaces:**
- Consumes: (없음 — 순수 인프라)
- Produces:
  - `com.kista.platform.crypto.AesCryptoService` — `String encrypt(String)`, `String decrypt(String)` (시그니처 불변)
  - `com.kista.platform.crypto.AccountNoHasher` — `String hash(String)` (불변)
  - `com.kista.platform.persistence.BaseAuditEntity` (`@MappedSuperclass`, `createdAt`/`updatedAt` + package setter), `BaseCreatedAtEntity`, `JpaAuditingConfig` (`@Configuration @EnableJpaAuditing`, package-private)
  - `com.kista.platform.scheduling.{SchedulerJobRunner,SchedulerLockService,SchedulerLifecycleEvent}` (Task 1 시그니처)

- [ ] **Step 1: 새 불변식 테스트 추가 (실패 확인용)**

`HexagonalArchitectureTest.java`에 `sharedkernel_must_not_depend_on_other_modules` 바로 아래 추가:

```java
    @Test
    @DisplayName("platform은 common 외 다른 com.kista 모듈에 의존하지 않는다 — 인프라 leaf 불변식")
    void platform_must_not_depend_on_other_modules() {
        // platform은 persistence base·crypto·scheduler 골격 등 순수 인프라만 담는다는 전제로 OPEN 선언됨 —
        // 이 패키지가 다른 애그리게이트 모듈을 참조하는 순간 인프라 leaf 전제가 깨진다 (sharedkernel과 동일 강제).
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.platform..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.kista..")
                                .and(resideOutsideOfPackage("com.kista.platform.."))
                                .and(resideOutsideOfPackage("com.kista.common..")));
        rule.check(classes);
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest.platform은*'`
Expected: FAIL — `com.kista.platform..` 패키지에 클래스가 없어 ArchUnit이 "Rule ... was violated (0 times)" 대신 빈 집합 통과할 수 있음. 이 경우 Step 이후 재확인. (ArchUnit은 매칭 클래스 0개면 기본 통과 — `allowEmptyShould(true)` 기본. 실질 검증은 Step 8 이후.)

- [ ] **Step 3: `platform` package-info 작성**

`src/main/java/com/kista/platform/package-info.java`:

```java
// 전역 인프라 leaf — persistence base entity, 대칭키 암호화, 스케쥴러 공통 골격.
// Spring/JPA 바인딩이 있어 com.kista.common(순수 유틸)과 분리한다. Type.OPEN이되
// HexagonalArchitectureTest.platform_must_not_depend_on_other_modules가 outbound-zero(→common만 허용)를 강제한다
// — sharedkernel과 동일하게 "OPEN은 outbound-zero를 증명할 때만 안전" 원칙.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.platform;
```

- [ ] **Step 4: crypto 이동**

`git mv src/main/java/com/kista/adapter/out/crypto/AesCryptoService.java src/main/java/com/kista/platform/crypto/AesCryptoService.java` (AccountNoHasher 동일). 두 파일의 `package com.kista.adapter.out.crypto;` → `package com.kista.platform.crypto;`.
테스트: `git mv src/test/java/com/kista/adapter/out/crypto/AesCryptoServiceTest.java src/test/java/com/kista/platform/crypto/AesCryptoServiceTest.java`, package 선언 갱신.

- [ ] **Step 5: persistence base 이동**

`git mv` 3개 파일 → `src/main/java/com/kista/platform/persistence/`. `package` 선언 갱신. `JpaAuditingConfig`는 package-private 유지(`class JpaAuditingConfig`).
주의: `BaseAuditEntity`의 `@Setter(AccessLevel.PACKAGE)` — setter 접근 범위가 `com.kista.platform.persistence`로 바뀐다. 기존에도 하위 패키지 Entity는 `@Setter(PACKAGE)`로 자체 생성했으므로([[testing]] "Lombok @MappedSuperclass 상속 주의") 영향 없음. `git grep 'setCreatedAt\|setUpdatedAt' src/main` 으로 외부 직접 호출 없음 재확인.

- [ ] **Step 6: scheduler 골격 이동**

`git mv` `SchedulerJobRunner.java`, `SchedulerLockService.java`, `SchedulerLifecycleEvent.java` → `src/main/java/com/kista/platform/scheduling/`. `package` 선언 갱신.
테스트: `SchedulerJobRunnerTest` → `src/test/java/com/kista/platform/scheduling/`, package 갱신.

- [ ] **Step 7: 소비자 import 전량 재작성**

기계적 치환 (로직 0):

```bash
cd "$(git rev-parse --show-toplevel)"
grep -rl 'com\.kista\.adapter\.out\.crypto\.' src --include='*.java' | xargs sed -i '' 's/com\.kista\.adapter\.out\.crypto\./com.kista.platform.crypto./g'
grep -rl 'com\.kista\.adapter\.out\.persistence\.BaseAuditEntity\|com\.kista\.adapter\.out\.persistence\.BaseCreatedAtEntity\|com\.kista\.adapter\.out\.persistence\.JpaAuditingConfig' src --include='*.java' | xargs sed -i '' -e 's/com\.kista\.adapter\.out\.persistence\.BaseAuditEntity/com.kista.platform.persistence.BaseAuditEntity/g' -e 's/com\.kista\.adapter\.out\.persistence\.BaseCreatedAtEntity/com.kista.platform.persistence.BaseCreatedAtEntity/g' -e 's/com\.kista\.adapter\.out\.persistence\.JpaAuditingConfig/com.kista.platform.persistence.JpaAuditingConfig/g'
grep -rl 'com\.kista\.adapter\.in\.schedule\.' src --include='*.java' | xargs sed -i '' 's/com\.kista\.adapter\.in\.schedule\./com.kista.platform.scheduling./g'
# SchedulerNotifier import 갱신
sed -i '' 's/com\.kista\.adapter\.in\.schedule\.SchedulerLifecycleEvent/com.kista.platform.scheduling.SchedulerLifecycleEvent/g' src/main/java/com/kista/notify/adapter/out/gateway/SchedulerNotifier.java src/test/java/com/kista/notify/adapter/out/gateway/SchedulerNotifierTest.java
grep -rl $'\xef\xbb\xbf' src --include='*.java' | while read f; do sed -i '' '1s/^\xef\xbb\xbf//' "$f"; done
```

- [ ] **Step 8: 컴파일 + 아키텍처 게이트**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS. `platform_must_not_depend_on_other_modules`가 이제 실제 클래스를 매칭하고 통과(crypto/persistence/scheduling 전부 outbound-zero). `SchedulerLockService`가 `TimeZones`를 쓴다면 `→common`만이라 허용.

- [ ] **Step 9: crypto·persistence·scheduler 소비 테스트 회귀 확인**

Run: `./gradlew test --tests 'com.kista.platform.*' --tests 'com.kista.*.adapter.out.persistence.*' --tests 'com.kista.*.adapter.in.schedule.*'`
Expected: PASS

- [ ] **Step 10: 리뷰어 검수 후 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): 레거시 shim의 crypto·persistence base·scheduler 골격 → com.kista.platform 인프라 모듈

Type.OPEN이되 platform_must_not_depend_on_other_modules ArchUnit 불변식으로 outbound-zero 강제
(sharedkernel 패턴). 소비자 ~59개 파일 import 기계 치환, 로직 무변경.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
EOF
)"
```

---

## Task 3: `CommonMarketPriceFeed` → broker

**Files:**
- Move: `com/kista/adapter/out/marketdata/CommonMarketPriceFeed.java` → `com/kista/broker/adapter/out/marketdata/CommonMarketPriceFeed.java`
- Modify: `com/kista/broker/adapter/out/toss/TossPriceApi.java`, `com/kista/broker/adapter/out/mock/MockBrokerAdapter.java` (import)

**Interfaces:**
- Consumes: 없음 (인터페이스 정의만 이동)
- Produces: `com.kista.broker.adapter.out.marketdata.CommonMarketPriceFeed` — 메서드 시그니처 불변 (`getPrice`/`getPrices`/`getPrevClose`/`getPrevCloses`/`getClosingPrice`/`getPriceSnapshot`/`getPriceSnapshots`). broker-internal, NamedInterface 미노출.

- [ ] **Step 1: 파일 이동 + package 갱신**

```bash
git mv src/main/java/com/kista/adapter/out/marketdata/CommonMarketPriceFeed.java src/main/java/com/kista/broker/adapter/out/marketdata/CommonMarketPriceFeed.java
```
`package com.kista.adapter.out.marketdata;` → `package com.kista.broker.adapter.out.marketdata;`

- [ ] **Step 2: 소비자 import 갱신**

```bash
grep -rl 'com\.kista\.adapter\.out\.marketdata\.CommonMarketPriceFeed' src --include='*.java' | xargs sed -i '' 's/com\.kista\.adapter\.out\.marketdata\.CommonMarketPriceFeed/com.kista.broker.adapter.out.marketdata.CommonMarketPriceFeed/g'
```

- [ ] **Step 3: 컴파일 + broker 테스트**

Run: `./gradlew compileJava && ./gradlew test --tests 'com.kista.broker.adapter.out.*' --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 4: 리뷰어 검수 후 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): CommonMarketPriceFeed → com.kista.broker.adapter.out.marketdata

구현자(TossPriceApi)·소비자(MockBrokerAdapter) 모두 broker 내부 — NamedInterface 불필요.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
EOF
)"
```

---

## Task 4: 통계 컨트롤러 3종 + DTO → `com.kista.stats.adapter.in.web`

**Files:**
- Move: `com/kista/adapter/in/web/{DashboardController,StatisticsController,TossStatisticsController}.java` → `com/kista/stats/adapter/in/web/`
- Move: `com/kista/adapter/in/web/openapi/HousingBenchmarkOpenApiCustomizer.java` → `com/kista/stats/adapter/in/web/openapi/`
- Move: `com/kista/adapter/in/web/dto/{DailyTransactionResponse,MarginResponse,MultiPriceResponse,PortfolioSummaryResponse,TossStockInfoResponse,TossExchangeRateResponse,TossMarketSessionResponse,TossAccountInfoResponse}.java` → `com/kista/stats/adapter/in/web/dto/`
- Create (stats 소유 사본): `com/kista/stats/adapter/in/web/dto/{TossCandleResponse,CycleHistoryPageResponse,CycleHistoryResponse}.java`
- Move tests: `{Dashboard,Statistics,TossStatistics}ControllerTest` → `com/kista/stats/adapter/in/web/`. DTO 테스트(`NextOrdersResponseTest` 등은 web 잔류) 중 위 이동 DTO 대상 테스트가 있으면 동반 이동.
- Modify: `com/kista/stats/adapter/in/web/dto/` 신규 패키지에 이미 있는 `package-info.java` 확인 (없으면 internal 유지 — NamedInterface 없음)

**Interfaces:**
- Consumes: stats "usecase" (`AccountStatisticsUseCase`, `TossStatisticsUseCase`), broker "domain" (`DailyTransactionResult`/`MarginItem`/`PresentBalanceResult`/`TossCandle`), trading "domain" (`CycleHistoryPage`/`CyclePositionHistoryEntry`), sharedkernel (`StrategyTicker`)
- Produces: (없음 — 컨트롤러는 sink) stats 소유 `TossCandleResponse`/`CycleHistoryPageResponse`/`CycleHistoryResponse` — web/market 사본과 필드·`from()` byte-identical

- [ ] **Step 1: DTO 이동 (stats 단독 소유 8개)**

```bash
cd "$(git rev-parse --show-toplevel)"
for d in DailyTransactionResponse MarginResponse MultiPriceResponse PortfolioSummaryResponse TossStockInfoResponse TossExchangeRateResponse TossMarketSessionResponse TossAccountInfoResponse; do
  git mv "src/main/java/com/kista/adapter/in/web/dto/$d.java" "src/main/java/com/kista/stats/adapter/in/web/dto/$d.java"
done
sed -i '' 's/package com\.kista\.adapter\.in\.web\.dto;/package com.kista.stats.adapter.in.web.dto;/' src/main/java/com/kista/stats/adapter/in/web/dto/{DailyTransactionResponse,MarginResponse,MultiPriceResponse,PortfolioSummaryResponse,TossStockInfoResponse,TossExchangeRateResponse,TossMarketSessionResponse,TossAccountInfoResponse}.java
```

- [ ] **Step 2: 이중 소유 DTO를 stats 사본으로 생성**

`git mv` 하지 않고 **복사** — 원본은 web(`CycleHistoryPageResponse`/`CycleHistoryResponse`)·market(`TossCandleResponse`)이 Task 5/7에서 각자 소유한다.

```bash
for d in TossCandleResponse CycleHistoryPageResponse CycleHistoryResponse; do
  cp "src/main/java/com/kista/adapter/in/web/dto/$d.java" "src/main/java/com/kista/stats/adapter/in/web/dto/$d.java"
done
sed -i '' 's/package com\.kista\.adapter\.in\.web\.dto;/package com.kista.stats.adapter.in.web.dto;/' src/main/java/com/kista/stats/adapter/in/web/dto/{TossCandleResponse,CycleHistoryPageResponse,CycleHistoryResponse}.java
```

`CycleHistoryPageResponse.java` 내부에서 `CycleHistoryResponse`를 참조 — 같은 패키지라 import 불필요, 무변경. 두 사본은 `com.kista.trading.domain.model.*`만 import(trading "domain" — stats에서 가시).

- [ ] **Step 3: 컨트롤러 + openapi customizer 이동**

```bash
for c in DashboardController StatisticsController TossStatisticsController; do
  git mv "src/main/java/com/kista/adapter/in/web/$c.java" "src/main/java/com/kista/stats/adapter/in/web/$c.java"
done
git mv src/main/java/com/kista/adapter/in/web/openapi/HousingBenchmarkOpenApiCustomizer.java src/main/java/com/kista/stats/adapter/in/web/openapi/HousingBenchmarkOpenApiCustomizer.java
```

각 파일 편집:
- `package com.kista.adapter.in.web;` → `package com.kista.stats.adapter.in.web;` (openapi는 `.openapi`)
- `import com.kista.adapter.in.web.dto.*;` → `import com.kista.stats.adapter.in.web.dto.*;`
- 다른 shim import 없음 확인

- [ ] **Step 4: 컨트롤러 테스트 이동**

```bash
for c in DashboardControllerTest StatisticsControllerTest TossStatisticsControllerTest; do
  git mv "src/test/java/com/kista/adapter/in/web/$c.java" "src/test/java/com/kista/stats/adapter/in/web/$c.java"
done
```
각 테스트: `package` 선언 → `com.kista.stats.adapter.in.web`, `@WebMvcTest(X.class)` 참조는 동일 패키지라 무변경, `import com.kista.adapter.in.web.dto.*` → `com.kista.stats.adapter.in.web.dto.*`. `SecurityConfig`/`JwtAuthFilter`/`JwtDecoder`/`BlacklistUseCase`/`AppErrorLogPort` import는 절대경로라 무변경.

- [ ] **Step 5: 컴파일 + 대상 테스트**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.kista.stats.adapter.in.web.*' --tests 'com.kista.architecture.*'`
Expected: PASS. `verify()`는 아직 shim이 OPEN이라 무영향. `HexagonalArchitectureTest`의 `rest_controllers_must_not_depend_on_application_implementations` 통과 확인(usecase만 참조).

- [ ] **Step 6: 리뷰어 검수 후 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): 통계 컨트롤러 3종·openapi customizer·전용 DTO → com.kista.stats.adapter.in.web

Dashboard/Statistics/TossStatistics + HousingBenchmarkOpenApiCustomizer. broker/trading "domain" 소비.
이중 소유 DTO(TossCandleResponse, CycleHistoryPage/Response)는 stats 사본으로 복제 — own-type 복제 선례.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
EOF
)"
```

---

## Task 5: `market` DTO 자체 소유 (`MarketSessionResponse` 이동 + `TossCandleResponse` 사본)

**Files:**
- Move: `com/kista/adapter/in/web/dto/MarketSessionResponse.java` → `com/kista/market/adapter/in/web/dto/MarketSessionResponse.java`
- Create: `com/kista/market/adapter/in/web/dto/TossCandleResponse.java` (market 소유 사본)
- Modify: `com/kista/market/adapter/in/web/MarketHolidayController.java` (import 2줄)

**Interfaces:**
- Consumes: `broker.domain.model.toss.TossCandle` (broker "domain" — `market → broker` 신규 엣지? 확인 필요, 없으면 forward)
- Produces: market 소유 `MarketSessionResponse`(record, com.kista import 없음), `TossCandleResponse`(stats/web 사본과 byte-identical)

- [ ] **Step 1: `market → broker` 엣지 확인**

Run: `grep -rn 'import com.kista.broker' src/main/java/com/kista/market --include='*.java' | head` 및 `grep -rn 'import com.kista.market' src/main/java/com/kista/broker --include='*.java'`
Expected: `market → broker` 이미 존재하거나(정상), 없으면 신규 forward 엣지 — `broker → market` 없으면 순환 아님. `broker → market` 있으면 STOP, 이 태스크 재설계 필요(`TossCandleResponse.from`이 받는 `TossCandle`을 market 자체 record로 복제하거나 `MarketHolidayController`가 캔들을 안 쓰게).

> **주의:** `MarketHolidayController`가 `TossCandleResponse`를 실제로 어떤 엔드포인트에서 쓰는지 먼저 읽는다. 캔들 조회가 market 책임이 맞는지(아니면 stats로 갈 엔드포인트인지) 확인 — 만약 그 핸들러도 통계성이면 Task 4로 흡수하고 이 사본을 만들지 않는다.

- [ ] **Step 2: `MarketSessionResponse` 이동**

```bash
git mv src/main/java/com/kista/adapter/in/web/dto/MarketSessionResponse.java src/main/java/com/kista/market/adapter/in/web/dto/MarketSessionResponse.java
sed -i '' 's/package com\.kista\.adapter\.in\.web\.dto;/package com.kista.market.adapter.in.web.dto;/' src/main/java/com/kista/market/adapter/in/web/dto/MarketSessionResponse.java
```

- [ ] **Step 3: `TossCandleResponse` market 사본 생성** (Step 1에서 market 소유가 맞다고 확인된 경우만)

```bash
cp src/main/java/com/kista/adapter/in/web/dto/TossCandleResponse.java src/main/java/com/kista/market/adapter/in/web/dto/TossCandleResponse.java
sed -i '' 's/package com\.kista\.adapter\.in\.web\.dto;/package com.kista.market.adapter.in.web.dto;/' src/main/java/com/kista/market/adapter/in/web/dto/TossCandleResponse.java
```

- [ ] **Step 4: `MarketHolidayController` import 갱신**

```bash
sed -i '' -e 's/com\.kista\.adapter\.in\.web\.dto\.MarketSessionResponse/com.kista.market.adapter.in.web.dto.MarketSessionResponse/' -e 's/com\.kista\.adapter\.in\.web\.dto\.TossCandleResponse/com.kista.market.adapter.in.web.dto.TossCandleResponse/' src/main/java/com/kista/market/adapter/in/web/MarketHolidayController.java
```

- [ ] **Step 5: 컴파일 + market 테스트**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.kista.market.adapter.in.web.*' --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 6: 리뷰어 검수 후 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): market DTO 자체 소유 — MarketSessionResponse 이동 + TossCandleResponse 사본

MarketHolidayController가 shim dto를 참조하던 것을 market.adapter.in.web.dto로 이관.
TossCandleResponse는 stats 사본과 별개로 market이 복제 소유 (own-type 복제 선례).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
EOF
)"
```

---

## Task 6: SSE → `notify` 통합

**Files:**
- Move: `com/kista/adapter/out/sse/{SseEmitterRegistry,TradeSseEmitterRegistry}.java` → `com/kista/notify/adapter/out/sse/`
- Move: `com/kista/application/port/output/RealtimeNotificationPort.java` → `com/kista/notify/application/port/output/RealtimeNotificationPort.java`
- Move: `com/kista/adapter/in/web/{FcmController,TradeStreamController}.java` → `com/kista/notify/adapter/in/web/`
- Move: `com/kista/adapter/in/web/dto/FcmTokenRequest.java` → `com/kista/notify/adapter/in/web/dto/`
- Create: `com/kista/notify/adapter/in/web/StatusStreamController.java`
- Create: `com/kista/notify/adapter/in/web/package-info.java` (필요 시 — 아래 확인)
- Modify: `com/kista/user/adapter/in/web/AuthController.java` (statusStream 메서드·`SseEmitterRegistry` 필드·import·SSE import 제거)
- Modify: `com/kista/notify/adapter/out/gateway/TradingReportNotifier.java` (`RealtimeNotificationPort` import)
- Modify: 6개 trading 서비스 (죽은 와일드카드 import 제거)
- Modify: `HexagonalArchitectureTest.java` (`sse_emitter_registry_must_not_be_used_in_application_layer` 규칙 경로)
- Move tests: `{Fcm,TradeStream}ControllerTest`, `{SseEmitterRegistry,TradeSseEmitterRegistry}Test` → notify 패키지. `AuthControllerTest`/`AuthControllerTokenTest` 수정.
- Create test: `StatusStreamControllerTest`

**Interfaces:**
- Consumes: `notify.application.port.output.{FcmDeviceTokenPort,NotifyPort}`, user "event" (`UserApprovedEvent`/`UserRejectedEvent`), trading "domain" (`TradeEvent`), sharedkernel (`UserStatus`)
- Produces:
  - `com.kista.notify.application.port.output.RealtimeNotificationPort` — `void notifyStatusChange(UUID, UserStatus)`, `void notifyTrade(UUID, TradeEvent)` (불변). notify "port" NamedInterface에 합류.
  - `com.kista.notify.adapter.out.sse.SseEmitterRegistry` — `SseEmitter connect(UUID)` (public, 컨트롤러가 concrete 주입)
  - `com.kista.notify.adapter.out.sse.TradeSseEmitterRegistry` — `SseEmitter connect(UUID)`, `void send(UUID, TradeEvent)`
  - `StatusStreamController` — `@GetMapping("/api/auth/status-stream")` → `SseEmitter`

- [ ] **Step 1: `RealtimeNotificationPort` 이동**

```bash
git mv src/main/java/com/kista/application/port/output/RealtimeNotificationPort.java src/main/java/com/kista/notify/application/port/output/RealtimeNotificationPort.java
sed -i '' 's/package com\.kista\.application\.port\.output;/package com.kista.notify.application.port.output;/' src/main/java/com/kista/notify/application/port/output/RealtimeNotificationPort.java
```
notify `application/port/output/package-info.java`가 "port" NamedInterface를 선언 중인지 확인 — 이미 `NotifyPort` 등이 노출되므로 새 파일은 자동 편입.

- [ ] **Step 2: 죽은 와일드카드 import 제거 (6개 trading 서비스)**

각 파일에서 `import com.kista.application.port.output.*;` 줄 삭제 (`RealtimeNotificationPort` 미사용 프로브 확인됨):

```bash
for f in TradingService TradingReporter ManualTradingService CyclePositionPersistor CycleRotationService VrCycleRolloverService; do
  sed -i '' '/^import com\.kista\.application\.port\.output\.\*;/d' "src/main/java/com/kista/trading/application/service/$f.java"
done
```
`TradingReporter`/`ManualTradingService` 등은 같은 줄에 `com.kista.trading.application.port.output.*`도 있을 수 있음 — 프리뷰 후 `com.kista.application.port.output.*` 토큰만 제거. `./gradlew compileJava`로 검증.

- [ ] **Step 3: `TradingReportNotifier` import 갱신**

```bash
sed -i '' 's/com\.kista\.application\.port\.output\.RealtimeNotificationPort/com.kista.notify.application.port.output.RealtimeNotificationPort/' src/main/java/com/kista/notify/adapter/out/gateway/TradingReportNotifier.java
```

- [ ] **Step 4: SSE 레지스트리 이동**

```bash
git mv src/main/java/com/kista/adapter/out/sse/SseEmitterRegistry.java src/main/java/com/kista/notify/adapter/out/sse/SseEmitterRegistry.java
git mv src/main/java/com/kista/adapter/out/sse/TradeSseEmitterRegistry.java src/main/java/com/kista/notify/adapter/out/sse/TradeSseEmitterRegistry.java
sed -i '' 's/package com\.kista\.adapter\.out\.sse;/package com.kista.notify.adapter.out.sse;/' src/main/java/com/kista/notify/adapter/out/sse/*.java
sed -i '' 's/com\.kista\.application\.port\.output\.RealtimeNotificationPort/com.kista.notify.application.port.output.RealtimeNotificationPort/' src/main/java/com/kista/notify/adapter/out/sse/SseEmitterRegistry.java
```
`sse` 경로 세그먼트 유지 — `HexagonalArchitectureTest.sse_emitter_registry_*` 규칙(`com.kista..adapter.out.sse..`)이 계속 매칭.

- [ ] **Step 5: `sse_emitter_registry_must_not_be_used_in_application_layer` 규칙 확인**

규칙 패턴이 `com.kista..adapter.out.sse..` 와일드카드라 경로 유지 시 **무변경**. `HexagonalArchitectureTest`를 열어 하드코딩된 `com.kista.adapter.out.sse` 절대 경로가 있으면 `com.kista.notify.adapter.out.sse`로 갱신. (현재 코드는 `com.kista..adapter.out.sse..` 와일드카드 — 변경 불필요, 이 스텝은 확인만.)

- [ ] **Step 6: `FcmController` + `TradeStreamController` + `FcmTokenRequest` 이동**

```bash
git mv src/main/java/com/kista/adapter/in/web/FcmController.java src/main/java/com/kista/notify/adapter/in/web/FcmController.java
git mv src/main/java/com/kista/adapter/in/web/TradeStreamController.java src/main/java/com/kista/notify/adapter/in/web/TradeStreamController.java
git mv src/main/java/com/kista/adapter/in/web/dto/FcmTokenRequest.java src/main/java/com/kista/notify/adapter/in/web/dto/FcmTokenRequest.java
```
편집:
- 세 파일 `package` → `com.kista.notify.adapter.in.web` / `...web.dto`
- `FcmController`: `import com.kista.adapter.in.web.dto.FcmTokenRequest;` → `import com.kista.notify.adapter.in.web.dto.FcmTokenRequest;`
- `TradeStreamController`: `import com.kista.adapter.out.sse.TradeSseEmitterRegistry;` → `import com.kista.notify.adapter.out.sse.TradeSseEmitterRegistry;`

- [ ] **Step 7: `StatusStreamController` 신설 + `AuthController`에서 분리**

`src/main/java/com/kista/notify/adapter/in/web/StatusStreamController.java`:

```java
package com.kista.notify.adapter.in.web;

import com.kista.notify.adapter.out.sse.SseEmitterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

// 사용자 상태 변경(승인/반려) 실시간 SSE 스트림 — 경로는 /api/auth 유지 (kista-ui 무영향)
@Tag(name = "Auth", description = "인증 상태 스트림")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class StatusStreamController {

    private final SseEmitterRegistry sseEmitterRegistry; // 사용자 상태 SSE 연결 등록

    @Operation(summary = "사용자 상태 변경 실시간 스트림", description = "PENDING 사용자가 승인/반려 결과를 실시간 수신")
    @GetMapping(value = "/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream(@AuthenticationPrincipal UUID userId) {
        return sseEmitterRegistry.connect(userId);
    }
}
```

`AuthController.java` 편집:
- `import com.kista.adapter.out.sse.SseEmitterRegistry;` 줄 삭제
- `import ...SseEmitter;` 줄 삭제 (다른 곳에서 안 쓰면)
- `import ...MediaType;` — statusStream만 쓰면 삭제
- `private final SseEmitterRegistry sseEmitterRegistry;` 필드 삭제
- `statusStream` 메서드 + 그 위 `@GetMapping("/status-stream")`·`@Operation` 삭제

- [ ] **Step 8: notify `adapter/in/web` package-info 확인**

`com.kista.notify` 모듈이 CLOSED이고 `adapter.in.web`이 NamedInterface 미노출(internal)이어야 함 — `notify` 다른 adapter 하위에 package-info가 없으면(관례상 internal 자동) 새로 만들 필요 없음. `com/kista/notify/adapter/` 하위 package-info 존재 여부 확인, 패턴 따름.

- [ ] **Step 9: 테스트 이동/수정**

```bash
git mv src/test/java/com/kista/adapter/in/web/FcmControllerTest.java src/test/java/com/kista/notify/adapter/in/web/FcmControllerTest.java
git mv src/test/java/com/kista/adapter/in/web/TradeStreamControllerTest.java src/test/java/com/kista/notify/adapter/in/web/TradeStreamControllerTest.java
git mv src/test/java/com/kista/adapter/out/sse/SseEmitterRegistryTest.java src/test/java/com/kista/notify/adapter/out/sse/SseEmitterRegistryTest.java
git mv src/test/java/com/kista/adapter/out/sse/TradeSseEmitterRegistryTest.java src/test/java/com/kista/notify/adapter/out/sse/TradeSseEmitterRegistryTest.java
```
- 네 파일 `package` 선언 갱신
- `FcmControllerTest`: `import com.kista.adapter.in.web.dto...` → notify. `@Import({SecurityConfig.class,...})` 있으면 절대경로 무변경
- `SseEmitterRegistryTest`: `com.kista.application.port.output.RealtimeNotificationPort` 참조 시 → notify
- `AuthControllerTest`/`AuthControllerTokenTest`: `SseEmitterRegistry` `@MockitoBean`·import 삭제, statusStream 테스트 케이스가 있으면 잘라내 `StatusStreamControllerTest`로 이전

`src/test/java/com/kista/notify/adapter/in/web/StatusStreamControllerTest.java` — `TradeStreamControllerTest` 패턴 복제(`@WebMvcTest(StatusStreamController.class)` + `@Import({SecurityConfig, JwtAuthFilter, InternalTokenAuthFilter})` + `@MockitoBean SseEmitterRegistry` + `/api/auth/status-stream` GET이 200·SSE content-type 반환하는지, 미인증 시 401).

- [ ] **Step 10: 컴파일 + 아키텍처 + SSE/인증 테스트**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.kista.notify.adapter.*' --tests 'com.kista.user.adapter.in.web.*' --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 11: 리뷰어 검수 후 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): SSE 3종(레지스트리·RealtimeNotificationPort·엔드포인트) → com.kista.notify

notify가 Telegram·FCM에 이어 SSE까지 사용자 대면 발송을 일관 소유. status-stream/trades/stream 경로 불변.
AuthController.statusStream → notify StatusStreamController 분리. 죽은 와일드카드 import 6개 제거.
sse 경로 세그먼트 유지(ArchUnit 규칙 키잉).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
EOF
)"
```

---

## Task 7: `com.kista.web` 앱셸 모듈 신설 + shim 소멸

**Files:**
- Create: `src/main/java/com/kista/web/package-info.java`
- Move: `com/kista/adapter/in/web/{GlobalExceptionHandler,MetaController,TradingCycleController}.java` → `com/kista/web/`
- Move: `com/kista/adapter/in/aop/ErrorLogAspect.java` → `com/kista/web/aop/`
- Move: `com/kista/application/config/MetricsConfig.java` → `com/kista/web/config/`
- Move: `com/kista/adapter/in/web/dto/{TradingCycleRequest,TradingCycleResponse,VrConfigRequest,NextOrdersResponse,ExecuteOrdersResponse,CancelOrdersResponse,StrategyOrdersResponse,StrategySeedPreviewResponse,EnumMeta,MetaBundle,StrategyTypeMeta,TickerMeta,CycleHistoryPageResponse,CycleHistoryResponse}.java` → `com/kista/web/dto/`
- Delete: `com/kista/adapter/package-info.java`, `com/kista/application/package-info.java`, 빈 디렉토리 `com/kista/adapter`, `com/kista/application`
- Move tests: `{Meta,TradingCycle}ControllerTest`, `GlobalExceptionHandlerTest`, `ErrorLogAspect{,Pointcut}Test`, 잔여 dto 테스트(`NextOrdersResponseTest`/`TradingCycleResponseTest`) → `com/kista/web/...`

**Interfaces:**
- Consumes: 전 모듈의 공개 NamedInterface — account/finance/user/broker/trading/privacy "domain", admin "port", strategyconfig/stats/trading "usecase", sharedkernel
- Produces: (없음 — `web`은 순수 sink, NamedInterface 0개)

- [ ] **Step 1: `web` package-info 작성**

`src/main/java/com/kista/web/package-info.java`:

```java
// 앱셸 — 여러 모듈을 집계하는 진짜 앱 레벨 inbound 관심사만 담는다:
// enum 메타 번들(MetaController), 전역 예외→HTTP 매핑(GlobalExceptionHandler),
// NotifyPort.notifyError AOP 오류 로깅(ErrorLogAspect), 메트릭 설정(MetricsConfig),
// 3모듈 오케스트레이터(TradingCycleController).
// CLOSED이되 NamedInterface 0개 — 컨트롤러/@ControllerAdvice/@Aspect는 아무 모듈도 참조하지 않으므로
// 모든 모듈 NamedInterface로 fan-out해도 순환에 참여 불가(sink 모듈).
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package com.kista.web;
```

- [ ] **Step 2: web 전용 DTO 이동 (12개) + 이중 소유 사본 유지 (2개)**

```bash
cd "$(git rev-parse --show-toplevel)"
for d in TradingCycleRequest TradingCycleResponse VrConfigRequest NextOrdersResponse ExecuteOrdersResponse CancelOrdersResponse StrategyOrdersResponse StrategySeedPreviewResponse EnumMeta MetaBundle StrategyTypeMeta TickerMeta CycleHistoryPageResponse CycleHistoryResponse; do
  git mv "src/main/java/com/kista/adapter/in/web/dto/$d.java" "src/main/java/com/kista/web/dto/$d.java"
done
sed -i '' 's/package com\.kista\.adapter\.in\.web\.dto;/package com.kista.web.dto;/' src/main/java/com/kista/web/dto/*.java
```
`CycleHistoryPageResponse`/`CycleHistoryResponse`는 stats 사본(Task 4)과 별개로 web이 소유. 두 파일은 `com.kista.trading.domain.model.*`만 import — web(CLOSED)에서 trading "domain" 가시.

- [ ] **Step 3: `dto/` 내부 상호 참조 확인**

`MetaBundle`이 `EnumMeta`/`StrategyTypeMeta`/`TickerMeta`를 참조 — 같은 패키지라 무변경. `NextOrdersResponse`가 `StrategySeedPreviewResponse` 등 참조 시 동일. `git grep 'com.kista.adapter.in.web.dto' src/main/java/com/kista/web` 으로 잔여 참조 0 확인.

- [ ] **Step 4: 컨트롤러 + Aspect + Config 이동**

```bash
git mv src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java src/main/java/com/kista/web/GlobalExceptionHandler.java
git mv src/main/java/com/kista/adapter/in/web/MetaController.java src/main/java/com/kista/web/MetaController.java
git mv src/main/java/com/kista/adapter/in/web/TradingCycleController.java src/main/java/com/kista/web/TradingCycleController.java
git mv src/main/java/com/kista/adapter/in/aop/ErrorLogAspect.java src/main/java/com/kista/web/aop/ErrorLogAspect.java
git mv src/main/java/com/kista/application/config/MetricsConfig.java src/main/java/com/kista/web/config/MetricsConfig.java
```
편집:
- `GlobalExceptionHandler`, `MetaController`, `TradingCycleController`: `package com.kista.adapter.in.web;` → `package com.kista.web;`, `import com.kista.adapter.in.web.dto.*;` → `import com.kista.web.dto.*;`
- `MetaController`: 죽은 `import com.kista.strategyconfig.domain.model.Strategy;` 삭제 (본문 미사용)
- `ErrorLogAspect`: `package com.kista.adapter.in.aop;` → `package com.kista.web.aop;`
- `MetricsConfig`: `package com.kista.application.config;` → `package com.kista.web.config;` (내부 import 확인)

- [ ] **Step 5: shim package-info + 빈 디렉토리 삭제**

```bash
git rm src/main/java/com/kista/adapter/package-info.java src/main/java/com/kista/application/package-info.java
find src/main/java/com/kista/adapter src/main/java/com/kista/application -type d -empty -delete 2>/dev/null
find src/test/java/com/kista/adapter -type d -empty -delete 2>/dev/null
# 잔존 파일 확인 — 있으면 STOP
find src/main/java/com/kista/adapter src/main/java/com/kista/application src/test/java/com/kista/adapter -type f 2>/dev/null
```
Expected: 마지막 `find` 출력 없음 (전부 이동 완료). 남은 파일 있으면 목적지 재검토.

- [ ] **Step 6: 테스트 이동**

```bash
git mv src/test/java/com/kista/adapter/in/web/MetaControllerTest.java src/test/java/com/kista/web/MetaControllerTest.java
git mv src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java src/test/java/com/kista/web/TradingCycleControllerTest.java
git mv src/test/java/com/kista/adapter/in/web/GlobalExceptionHandlerTest.java src/test/java/com/kista/web/GlobalExceptionHandlerTest.java
git mv src/test/java/com/kista/adapter/in/aop/ErrorLogAspectTest.java src/test/java/com/kista/web/aop/ErrorLogAspectTest.java
git mv src/test/java/com/kista/adapter/in/aop/ErrorLogAspectPointcutTest.java src/test/java/com/kista/web/aop/ErrorLogAspectPointcutTest.java
# 잔여 dto 테스트
for t in NextOrdersResponseTest TradingCycleResponseTest; do
  [ -f "src/test/java/com/kista/adapter/in/web/dto/$t.java" ] && git mv "src/test/java/com/kista/adapter/in/web/dto/$t.java" "src/test/java/com/kista/web/dto/$t.java"
done
find src/test/java/com/kista/adapter -type f 2>/dev/null   # 잔존 확인
```
각 테스트 `package` 선언 갱신, `import com.kista.adapter.in.web.dto.*` → `com.kista.web.dto.*`, `@WebMvcTest(X.class)` 동일 패키지라 무변경. `ErrorLogAspectPointcutTest`의 pointcut 문자열은 `com.kista.notify.application.port.output.NotifyPort` 절대 참조라 무변경.

- [ ] **Step 7: 컴파일**

Run: `./gradlew compileJava compileTestJava`
Expected: SUCCESS. BOM 확인: `grep -rl $'\xef\xbb\xbf' src --include='*.java'` → 빈 출력.

- [ ] **Step 8: CLOSED 프로브 재확인 (shim 소멸 후 첫 전체 verify)**

Run: `./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest' --tests 'com.kista.architecture.HexagonalArchitectureTest'`
Expected: PASS. `com.kista.adapter`/`com.kista.application` 슬라이스가 사라지고 `web`(CLOSED)·`platform`(OPEN) 슬라이스가 등장. `web`은 sink라 순환 0. 실패 시 위반 목록의 각 엣지를 §설계로 대조 — 예상 못한 전이 순환이면 STOP하고 보고(market~strategyconfig 전례).

- [ ] **Step 9: 리뷰어 검수 후 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): com.kista.web 앱셸 모듈 신설 + 레거시 adapter/application shim 소멸

GlobalExceptionHandler/MetaController/TradingCycleController/ErrorLogAspect/MetricsConfig + 전용 DTO 이관.
web은 CLOSED sink(NamedInterface 0개) — 컨트롤러는 아무도 참조 안 하므로 전 모듈 fan-out해도 순환 불가.
com.kista.adapter/application package-info 3개 삭제 — verify()가 이제 이 45개 파일의 크로스모듈 의존을 실제 검사.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
EOF
)"
```

---

## Task 8: 전체 게이트 + 문서 갱신

**Files:**
- Modify: `docs/agents/architecture.md`, `docs/agents/constraints.md` (레거시 shim 서술 → `web`/`platform` 최종 상태)
- Modify: `README.md` (아키텍처 다이어그램에 shim/adapter 언급이 있으면)
- Modify (메모리): `~/.claude/projects/-Users-phs-workspace-kista-kista-api/memory/project_modulith_post_migration_backlog.md` + `MEMORY.md`

- [ ] **Step 1: 전체 테스트 스위트**

Run: `docker-compose up -d postgres && ./gradlew test 2>&1 | grep -E "FAILED|BUILD|Tests"`
Expected: `BUILD SUCCESSFUL`. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 특정.

- [ ] **Step 2: `architecture.md` 갱신**

"Spring Modulith 점진 도입" 절의 "레거시 최상위 shim 패키지(`common`/`application`/`adapter` ...)" 서술을 수정:
- `application`/`adapter` shim 소멸 완료 명시
- `com.kista.web` (CLOSED sink, NamedInterface 0개) 추가 서술 — 담긴 것: GlobalExceptionHandler/MetaController/TradingCycleController/ErrorLogAspect/MetricsConfig
- `com.kista.platform` (OPEN + `platform_must_not_depend_on_other_modules` 불변식) 추가 — persistence base/crypto/scheduling
- `com.kista.common`만 잔존 shim(순수 유틸)
- SSE 뭉치가 notify로, `CommonMarketPriceFeed`가 broker로, 통계 컨트롤러가 stats로 이동한 사실
- `SchedulerJobRunner` → `SchedulerLifecycleEvent` 팬아웃(SchedulerNotifier, AlertNotifier 4번째)
- `adapter/out/crypto`, `adapter/out/sse`, `adapter/in/schedule`, `adapter/in/web` 등을 참조하는 기존 서술의 경로 갱신

- [ ] **Step 3: `constraints.md` 갱신**

"Spring Modulith 이전 중 신규 파일 배치" 절에 `web`/`platform` 배치 규칙 추가:
- 신규 크로스모듈 컨트롤러·전역 예외 핸들러는 `com.kista.web`
- 신규 persistence base·대칭키 암호화·스케쥴러 골격은 `com.kista.platform` (outbound-zero 유지)
- "adapter/out 간 JpaRepository 접근 제한" 절의 `com.kista.adapter.out.crypto` 예시 경로 → `com.kista.platform.crypto`
- "@EnableJpaAuditing 위치" 절: `adapter/out/persistence/` → `com.kista.platform.persistence`
- "SSE (SecurityConfig)" 및 관련 절: `adapter/out/sse` → `notify.adapter.out.sse`

- [ ] **Step 4: `README.md` 드리프트 확인**

`grep -n 'com.kista.adapter\|com.kista.application\|shim' README.md` — 아키텍처 다이어그램·패키지 목록에 언급 있으면 갱신. 없으면 skip.

- [ ] **Step 5: 메모리 갱신**

`project_modulith_post_migration_backlog.md` — #4 완료 표시, 커밋 해시 기입. 남은 백로그 0이면 그 사실 명시. `MEMORY.md` 해당 줄 hook 문구 갱신.

- [ ] **Step 6: 최종 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
docs(modulith): 레거시 adapter/application shim 해소 반영 — architecture/constraints 갱신

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UtpueJcnTxUoiRFBwiXPoK
EOF
)"
```

- [ ] **Step 7: 최종 브랜치 리뷰 판단**

각 태스크가 diff 단위로 리뷰·수정을 거쳤고 파일 분리가 커 교차 통합 위험이 낮다(공유 편집은 `HexagonalArchitectureTest`/`architecture.md` 정도). 전역 지침대로 최종 전체 브랜치 리뷰는 **생략 기본** — 사용자에게 "태스크별 리뷰 완료, 최종 전체 리뷰 생략 가능 여부" 확인. `verify()` + 전체 스위트가 통합 게이트 역할.

---

## Self-Review

**1. Spec coverage:**

| 스펙 항목 | 태스크 |
|---|---|
| §설계 1 `com.kista.web` CLOSED sink | Task 7 |
| §설계 2 `com.kista.platform` OPEN + 불변식 | Task 2 |
| §설계 3 SSE → notify (레지스트리·포트·엔드포인트 2개·`sse` 경로·죽은 import 6개) | Task 6 |
| §설계 4 단일 소유자 이동 (통계 ×3+openapi, CommonMarketPriceFeed, FcmController) | Task 3(broker), Task 4(stats), Task 6(Fcm) |
| §설계 5 DTO 이중 소유 (CycleHistoryPage/Response, TossCandleResponse) — MarketSessionResponse 단독 이동 정정 | Task 4(stats 사본), Task 5(market 사본+MarketSessionResponse 이동), Task 7(web 사본) |
| §선결과제 SchedulerJobRunner 디커플링 (이벤트 + 제네릭) | Task 1 |
| §시퀀스 게이트 (Hexagonal + verify() + 전체 스위트 + 리뷰어) | 각 태스크 Step + Task 8 |
| §kista-ui 영향 0 (경로·DTO 형태 불변) | Global Constraints + Task 6 Step 7 (경로 유지) |
| §비-목표 (common/sharedkernel 무변경 등) | 파일 구조 "유지" 명시 |
| `MetricsConfig` → web.config | Task 7 |
| `RealtimeNotificationPort` → notify "port" | Task 6 Step 1 |

빈틈: 없음. `application/package-info.java` 삭제는 Task 7 Step 5 포함.

**2. Placeholder scan:** "TBD"/"적절히"/"등 처리" 없음. 각 코드 스텝에 실제 코드 블록 포함. Task 5는 조건부(Step 1 확인 결과에 따라 분기)지만 양 갈래 다 명시.

**3. Type consistency:**
- `SchedulerLifecycleEvent(String jobName, Phase phase, String errorMessage)` — Task 1 정의, Task 6에서 미참조(별개), Task 2에서 platform 이동 시 import 경로만 갱신. `Phase{STARTED,COMPLETED,FAILED}` 일관.
- `SchedulerJobRunner.Action<T>` — Task 1 제네릭화, Task 2 이동. `run(String, Runnable)` / `<T> run(String, Supplier<List<T>>, Action<T>)` 일관.
- `RealtimeNotificationPort.notifyStatusChange(UUID, UserStatus)` / `notifyTrade(UUID, TradeEvent)` — Task 6 시그니처 불변 명시.
- `SseEmitterRegistry.connect(UUID): SseEmitter` — Task 6 / Task 7(StatusStreamController) 일관.
- DTO `from()` 시그니처 — 전 태스크에서 "불변" 명시, byte-identical 사본.
