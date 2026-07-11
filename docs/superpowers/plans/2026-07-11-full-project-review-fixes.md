# 전체 프로젝트 검토 후속 조치 구현 계획 (kista-api + kista-ui)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026-07-11 전체 프로젝트 4렌즈 검토(과잉·취약·누락·구조충돌)에서 확인된 문제를 영향력 순으로 수정한다 — 매매 공식·주문 생성 로직은 동작 불변.

**Architecture:** 돈 경로의 확인된 취약점 4건(좀비 사이클, dead-man's switch 부재, DB 불일치 재시도, role 토큰 갭)을 최소 침습으로 보강하고, UI SSE 인증 처리 1건을 통일한 뒤, 문서·잔재 정리로 유지비를 낮춘다. 기존 헥사고날 구조·아웃바운드 포트·모니터링 스택은 검토 결과 과잉이 아니므로 변경하지 않는다.

**Tech Stack:** Java 21 + Spring Boot 3, JUnit 5 + Mockito, Next.js 16 + vitest, Fly.io / Vercel

---

## 오케스트레이션·모델 라우팅

- **오케스트레이터**: Sonnet — superpowers:subagent-driven-development로 Task별 신규 서브에이전트 디스패치, Task 간 리뷰 게이트 수행
- **구현 서브에이전트**:

| Task | 내용 | 모델 | 리뷰 게이트 |
|------|------|------|------------|
| 1 | 좀비 사이클 가드 | **Sonnet** | 필수 (돈 경로) |
| 2 | Dead-man's switch | **Sonnet** | 필수 (돈 경로) |
| 3 | markPlaced 재시도 | **Sonnet** | 필수 (돈 경로) |
| 4 | role 변경 즉시 무효화 | **Sonnet** | 필수 (인증) |
| 5 | (UI) trades-stream auth-error | **Sonnet** | 권장 |
| 6 | kista-api 문서 정정·아카이브 | **Haiku** | 불필요 |
| 7 | (UI) 문서 중복 해소 | **Haiku** | 불필요 |
| 8 | (UI) 도구 잔재·스킬 경로 정리 | **Haiku** | 불필요 |
| 9 | QueryDSL 제거 (열린 질문 3 승인 시) | **Haiku** | 컴파일+테스트 통과로 갈음 |
| 10 | 백업/복구 런북 | **Haiku** | 불필요 |

- **리뷰 서브에이전트**: Sonnet — Task 1~4는 "매매 공식 불변 + 기존 테스트 전체 통과 + 신규 테스트가 버그 시나리오를 실제로 재현하는지" 확인
- Task 1~4는 순서 무관하지만 같은 파일을 건드리지 않으므로 순차 실행 권장(충돌 방지). Task 5~10은 상호 독립 — 병렬 디스패치 가능(단 6·7·8은 같은 레포 문서라 순차가 안전)

---

## ⚠️ 열린 질문 (실행 전 사용자 답변 필요 — 답 없으면 각 기본값으로 진행)

1. **[Task 2 전제] healthchecks.io 체크 2개 생성** — https://healthchecks.io 에서 `kista-open`(스케쥴 매일 22:30 KST, grace 90분), `kista-close`(화~토 04:30 KST, grace 120분) 체크를 만들고 ping URL 2개를 `fly secrets set HEARTBEAT_OPEN_URL=... HEARTBEAT_CLOSE_URL=...`로 설정해야 감시가 동작합니다. **코드는 URL 미설정이어도 안전(핑만 생략)하므로 Task 2는 먼저 진행 가능.** 다른 감시 서비스를 선호하면 알려주세요.
2. **브로커 주문 접수 자동 재시도는 도입하지 않음 (권장)** — 네트워크 오류 재시도는 "실제로는 접수됐는데 응답만 유실"된 경우 중복 주문 위험이 있고, 브로커가 명시 거부한 주문은 재시도해도 같은 결과입니다. 실패 시 즉시 알림(기존) + 관리자 수동 재주문(AdminReorderService, 기존)으로 충분하다고 판단했습니다. 자동 재시도를 원하면 별도 지시 필요.
3. **QueryDSL 의존성 제거 (Task 9)** — 프로덕션 코드에서 `com.querydsl` import 0건, Q클래스만 매 빌드 생성 중. 향후 동적 쿼리 계획이 없다면 제거를 권장합니다. **기본값: 제거 진행.** 남길 계획이면 Task 9 스킵 지시.
4. **kista-ui 하위 CLAUDE.md 5개(app/entities/features/widgets/shared, 각 7줄)** — 순수 리다이렉트 스텁이지만 디렉토리 진입 시 자동 로드 라우팅 역할을 하므로 **기본값: 유지** (Task 7에서 삭제하지 않음).
5. **kista-ui openapi.json 커밋 관행** — 재생성 가능 산출물이지만 재생성에 로컬 kista-api 서버가 필요하므로 **기본값: 현행 유지** (조치 없음).
6. **잔고 급변 안전장치·브로커 서킷브레이커** — 이번 범위에서 제외했습니다(소수 사용자 + 알림 체계 존재로 우선순위 낮음). 다음 검토 사이클 후보로만 기록.
7. **`.superpowers/sdd/` 작업 리포트(양 레포)** — 1회성 세션 산출물로 **기본값: 삭제** (Task 6·8에 포함). 보존 원하면 해당 스텝 스킵 지시.

---

## 실행 전 필독 (실행자 규칙)

- kista-api 작업 디렉토리: `/Users/phs/workspace/kista/kista-api` / kista-ui 작업 디렉토리: `/Users/phs/workspace/kista/kista-ui` — **서로 독립 git 저장소, 각자 커밋**
- 커밋 전 확인: `git config user.name` = `narafu`, `git config user.email` = `narafu@kakao.com`
- 커밋 메시지는 한글 + Conventional Commit (`fix:`, `feat:`, `test:`, `docs:`, `chore:`)
- **`git push` 금지** — 사용자가 명시 요청할 때만 (push하면 즉시 운영 배포됨)
- bash에서 gradle 실행: `bash gradlew test` (`./gradlew` 아님)
- 파일 수정 시 BOM(`\xef\xbb\xbf`) 삽입 금지 — 수정 후 `bash gradlew compileJava`로 즉시 검증
- **매매 공식·주문 생성 로직(`InfiniteStrategy`, `PrivacyStrategy`, `VrStrategy`, `InfinitePosition`, `VrPosition`) 절대 수정 금지**
- 테스트 실패 진단: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`
- kista-ui 검증: `npm run typecheck && npm run test`

---

## 검토 결과 요약 (실행자 참고용 배경)

| 렌즈 | 발견 | 대응 Task |
|------|------|-----------|
| 취약(높음) | rotation 실패 시 종료된 사이클이 `findLatestByStrategyId`로 재선택돼 **종료 사이클에 주문 접수** — `BatchContextFactory`에 endDate 가드 없음 | Task 1 |
| 누락(높음) | 스케쥴러가 아예 실행되지 않을 때(앱 다운, cron 미등록, scheduler.enabled 오설정) 감지 장치 전무 | Task 2 |
| 취약(높음) | 증권사 접수 성공 후 `markPlaced` DB 기록 실패 시 재시도 없음 → 재실행 시 같은 주문이 PLANNED로 재조회돼 **중복 접수** 가능 | Task 3 |
| 취약(중간) | role 변경 후 기존 AT가 최대 24h 이전 권한 유지 (JwtAuthFilter 주석에 KNOWN GAP 기록) | Task 4 |
| 취약(중간) | (UI) trades-stream 업스트림 401 시 auth-error 이벤트 없이 끊겨 클라이언트가 만료 토큰으로 5초마다 무한 재연결 | Task 5 |
| 과잉/드리프트 | architecture.md에 존재하지 않는 `AdminOrderCorrectionService` 기재(실제: `AdminReorderService`), 미실행 lightsail 계획 방치 | Task 6 |
| 과잉 | (UI) CLAUDE.md ↔ docs/agents ↔ .serena/memories 3중 중복(~200줄), tech_stack.md에 폐기된 "Render URL" | Task 7 |
| 과잉 | (UI) .shrimp-data·.superpowers/brainstorm 잔재, 스킬 파일에 타 개발자 Windows 절대경로(`/d/src/study/...`) 박제 | Task 8 |
| 과잉 | QueryDSL 의존성 실사용 0건 — annotationProcessor 빌드 오버헤드만 존재 | Task 9 |
| 누락 | 실계좌 자금 취급 서비스인데 백업/복구 절차 문서 부재 | Task 10 |

검토에서 **변경 불필요로 확정**된 것: 매매 helper 세분화·CycleOrderStrategy 이중 계층·UseCase 1구현체 22개·admin 서비스 6종·브로커 capability 포트 14개·UI FSD 계층·프록시 구조·Prometheus/Grafana 스택 (모두 정당 판정). UI↔API 계약은 깨진 곳 없음(오탐 2건 검증 완료).

---

### Task 1: 좀비 사이클 가드 — 종료된 사이클 배치 진입 차단 [kista-api / Sonnet]

**배경:** `CyclePositionPersistor.saveCyclePosition`이 `markEnded()`로 사이클을 종료한 직후 `CycleRotationService.rotate()`가 새 사이클을 만드는데, rotate 내부의 USD 잔고 조회가 실패하면(브로커 API 오류) 조용히 return해 **새 사이클이 생성되지 않는다**. 다음 날 `BatchContextFactory.buildAll`이 `requireLatestCycle`(endDate 무시, createdAt 최신 1건)로 **종료된 사이클을 다시 선택**해 주문을 계획·접수한다. 가드를 추가해 skip + 관리자 알림으로 전환한다.

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/schedule/BatchContextFactory.java`
- Test: `src/test/java/com/kista/adapter/in/schedule/BatchContextFactoryTest.java`

**Interfaces:**
- Consumes: `StrategyCycle.endDate()` (기존 record 접근자), `NotifyPort.notifyError(Exception)` (기존)
- Produces: 없음 (동작 가드만 추가 — 시그니처 변경 없음)

- [ ] **Step 1: 실패하는 테스트 작성**

`BatchContextFactoryTest.java`에 테스트 1개 추가 (기존 헬퍼 `mockStrategy`/`mockCycle` 재사용, `mockCycle`과 동일한 생성자 패턴으로 종료 사이클 헬퍼 신설):

```java
    private StrategyCycle mockEndedCycle(UUID strategyId) {
        // mockCycle과 동일 패턴 + endAmount/endDate 채움 — 종료된 사이클
        return new StrategyCycle(UUID.randomUUID(), strategyId, new BigDecimal("1000.00"),
                new BigDecimal("1100.00"), LocalDate.now().minusDays(7), LocalDate.now().minusDays(1), Instant.now(), null);
    }

    @Test
    void buildAll_latestCycleAlreadyEnded_skipsAndNotifiesAdmin() {
        // rotation 실패로 새 사이클이 없는 좀비 상태 — 종료 사이클에 주문이 나가면 안 됨
        Strategy strategy = mockStrategy(ACCOUNT_ID);
        when(strategyCyclePort.findLatestByStrategyId(strategy.id()))
                .thenReturn(Optional.of(mockEndedCycle(strategy.id())));

        List<BatchContext> result = factory.buildAll(List.of(strategy));

        assertThat(result).isEmpty();
        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verifyNoInteractions(accountPort); // 종료 사이클이면 계좌 조회 전에 중단
    }
```

주의: `StrategyCycle` 생성자 인자 개수가 컴파일 오류나면 같은 파일의 `mockCycle` 헬퍼와 동일한 시그니처에 맞출 것 (endAmount는 4번째, endDate는 6번째 위치의 null을 값으로 교체).

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.in.schedule.BatchContextFactoryTest'`
Expected: `buildAll_latestCycleAlreadyEnded_skipsAndNotifiesAdmin` FAIL (현재는 종료 사이클도 컨텍스트에 포함되고 accountPort가 호출됨)

- [ ] **Step 3: BatchContextFactory에 가드 추가**

`BatchContextFactory.java`의 `buildAll` try 블록에서 `requireLatestCycle` 직후에 삽입:

```java
                StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
                // 종료된 사이클 재선택 차단 — rotation 실패(잔고 조회 오류 등) 시 새 사이클 없이 종료 사이클만 남는 좀비 상태
                if (currentCycle.endDate() != null) {
                    IllegalStateException zombie = new IllegalStateException(
                            "[좀비 사이클] 최신 사이클이 이미 종료됨(endDate=" + currentCycle.endDate()
                                    + ") — rotation 실패 추정, 전략 확인 후 수동 재등록 필요: strategyId=" + strategy.id());
                    log.error(zombie.getMessage());
                    notifyPort.notifyError(zombie);
                    continue;
                }
                Account account = accountPort.findByIdOrThrow(strategy.accountId());
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.in.schedule.BatchContextFactoryTest'`
Expected: 전체 PASS (기존 3개 + 신규 1개)

- [ ] **Step 5: 전체 테스트 회귀 확인**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/schedule/BatchContextFactory.java src/test/java/com/kista/adapter/in/schedule/BatchContextFactoryTest.java
git commit -m "$(cat <<'EOF'
fix(schedule): 종료된 사이클 배치 진입 차단 — rotation 실패 좀비 상태 가드

markEnded 후 rotate가 잔고 조회 실패로 중단되면 새 사이클 없이 종료
사이클만 남고, 다음 날 배치가 그 사이클에 주문을 접수할 수 있었다.
endDate가 있는 사이클은 skip + 관리자 알림으로 전환.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Dead-man's switch — 스케쥴러 완료 heartbeat 핑 [kista-api / Sonnet]

**배경:** 개장/마감 스케쥴러가 아예 실행되지 않아도(앱 다운, cron 미등록, `scheduler.enabled=false` 오배포) 아무도 모른다. 텔레그램 "시작/완료" 알림은 "안 온 것"을 사람이 눈치채야 한다. 스케쥴러 실행 완료 시 healthchecks.io로 GET 핑을 보내고, 지정 시간 내 핑이 없으면 healthchecks.io가 알림을 보낸다. URL 미설정 시 핑을 생략하므로 배포에 안전하다.

**Files:**
- Create: `src/main/java/com/kista/domain/port/out/HeartbeatPort.java`
- Create: `src/main/java/com/kista/adapter/out/heartbeat/HeartbeatProperties.java`
- Create: `src/main/java/com/kista/adapter/out/heartbeat/HeartbeatConfig.java`
- Create: `src/main/java/com/kista/adapter/out/heartbeat/HeartbeatAdapter.java`
- Modify: `src/main/java/com/kista/adapter/in/schedule/TradingOpenScheduler.java`
- Modify: `src/main/java/com/kista/adapter/in/schedule/TradingCloseScheduler.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/kista/adapter/out/heartbeat/HeartbeatAdapterTest.java`
- Test(수정): `src/test/java/com/kista/adapter/in/schedule/TradingOpenSchedulerTest.java`, `TradingCloseSchedulerTest.java`

**Interfaces:**
- Produces: `HeartbeatPort { void pingOpen(); void pingClose(); }` — 스케쥴러가 정상 완료 후 호출
- Consumes: 신규 `heartbeatRestTemplate` 빈 (Lombok 필드명 = 빈 이름 일치 규칙 준수)

- [ ] **Step 1: 실패하는 어댑터 테스트 작성**

`src/test/java/com/kista/adapter/out/heartbeat/HeartbeatAdapterTest.java` 생성:

```java
package com.kista.adapter.out.heartbeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HeartbeatAdapterTest {

    @Mock RestTemplate heartbeatRestTemplate;

    @Test
    void pingOpen_urlSet_sendsGet() {
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestTemplate,
                new HeartbeatProperties("https://hc-ping.com/open-uuid", "https://hc-ping.com/close-uuid"));
        adapter.pingOpen();
        verify(heartbeatRestTemplate).getForObject("https://hc-ping.com/open-uuid", String.class);
    }

    @Test
    void pingClose_urlBlank_skipsWithoutCall() {
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestTemplate, new HeartbeatProperties("", ""));
        adapter.pingClose();
        verifyNoInteractions(heartbeatRestTemplate);
    }

    @Test
    void ping_httpFailure_swallowedNotThrown() {
        // 핑 실패가 매매 흐름을 깨면 안 됨 — 로그만 남기고 삼킴
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestTemplate,
                new HeartbeatProperties("https://hc-ping.com/open-uuid", null));
        when(heartbeatRestTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("timeout"));
        assertThatCode(adapter::pingOpen).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `bash gradlew compileTestJava`
Expected: FAIL — `HeartbeatAdapter`, `HeartbeatProperties` 미존재

- [ ] **Step 3: 포트·어댑터 구현 (신규 외부 서비스 어댑터 3파일 패턴 준수)**

`src/main/java/com/kista/domain/port/out/HeartbeatPort.java`:

```java
package com.kista.domain.port.out;

// 스케쥴러 정상 실행 신호 — 외부 감시(healthchecks.io)가 시간 내 신호 없으면 알림 (dead-man's switch)
public interface HeartbeatPort {
    void pingOpen();  // 개장 스케쥴러 실행 완료 신호
    void pingClose(); // 마감 스케쥴러 실행 완료 신호
}
```

`src/main/java/com/kista/adapter/out/heartbeat/HeartbeatProperties.java`:

```java
package com.kista.adapter.out.heartbeat;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 스케쥴러별 healthchecks.io 핑 URL — 미설정(빈 값)이면 핑 생략
@ConfigurationProperties(prefix = "heartbeat")
public record HeartbeatProperties(String openUrl, String closeUrl) {}
```

`src/main/java/com/kista/adapter/out/heartbeat/HeartbeatConfig.java`:

```java
package com.kista.adapter.out.heartbeat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(HeartbeatProperties.class)
class HeartbeatConfig {

    // 핑은 부가 기능 — 짧은 타임아웃으로 매매 흐름 지연 방지
    @Bean
    RestTemplate heartbeatRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }
}
```

`src/main/java/com/kista/adapter/out/heartbeat/HeartbeatAdapter.java`:

```java
package com.kista.adapter.out.heartbeat;

import com.kista.domain.port.out.HeartbeatPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
class HeartbeatAdapter implements HeartbeatPort {

    private final RestTemplate heartbeatRestTemplate; // 빈 이름과 필드명 일치 필수
    private final HeartbeatProperties properties;

    @Override
    public void pingOpen() {
        ping(properties.openUrl(), "open");
    }

    @Override
    public void pingClose() {
        ping(properties.closeUrl(), "close");
    }

    // 핑 실패는 매매에 영향 없어야 함 — 로그만 남기고 삼킴
    private void ping(String url, String name) {
        if (url == null || url.isBlank()) return;
        try {
            heartbeatRestTemplate.getForObject(url, String.class);
            log.info("heartbeat {} 핑 완료", name);
        } catch (Exception e) {
            log.warn("heartbeat {} 핑 실패: {}", name, e.getMessage());
        }
    }
}
```

주의: `RestTemplateBuilder.connectTimeout/readTimeout` 메서드명이 Boot 버전에 따라 `setConnectTimeout`일 수 있음 — 컴파일 오류 시 기존 `TelegramConfig.java`의 RestTemplate 빌드 방식을 그대로 따를 것.

- [ ] **Step 4: application.yml에 프로퍼티 추가**

`src/main/resources/application.yml` 최하단에 추가:

```yaml
# dead-man's switch — 스케쥴러 실행 완료 핑 (미설정 시 생략)
heartbeat:
  open-url: ${HEARTBEAT_OPEN_URL:}
  close-url: ${HEARTBEAT_CLOSE_URL:}
```

- [ ] **Step 5: 어댑터 테스트 통과 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.out.heartbeat.HeartbeatAdapterTest'`
Expected: 3건 PASS

- [ ] **Step 6: 스케쥴러 연결**

`TradingOpenScheduler.java`: 필드 추가 + `runLocked()` 끝에 핑 (cron 경로가 dead-man 판정 기준 — 수동 `runNow`는 핑하지 않음):

```java
    private final HeartbeatPort heartbeatPort; // dead-man's switch 핑
```

```java
    private void runLocked() throws InterruptedException {
        LocalDate today = LocalDate.now(TimeZones.KST);
        jobRunner.run("장 개시 스케쥴러",
                () -> contextFactory.buildAll(guardPrivacyStrategies(strategyPort.findAllActive(), today)),
                useCase::placeOpenOrders);
        heartbeatPort.pingOpen(); // 인터럽트 시 도달 안 함 — 실행 완료 신호만 발송
    }
```

import 추가: `import com.kista.domain.port.out.HeartbeatPort;`

`TradingCloseScheduler.java`도 동일 패턴:

```java
    private final HeartbeatPort heartbeatPort; // dead-man's switch 핑
```

```java
    private void runLocked() throws InterruptedException {
        // 복수종목 현재가 1회 일괄 조회 후 사이클별 순차 실행
        jobRunner.run("마감 매매 스케쥴러",
                () -> contextFactory.buildAll(strategyPort.findAllActive()),
                useCase::executeBatch);
        heartbeatPort.pingClose(); // 인터럽트 시 도달 안 함 — 실행 완료 신호만 발송
    }
```

- [ ] **Step 7: 스케쥴러 테스트 동기화**

`TradingOpenSchedulerTest.java` / `TradingCloseSchedulerTest.java`에 `@Mock HeartbeatPort heartbeatPort` 추가 (서비스 필드 추가 → 테스트 @Mock 추가 규칙). 생성자 직접 호출 방식이면 인자 추가. cron 경로(runLocked를 타는 테스트)에 `verify(heartbeatPort).pingOpen()` / `verify(heartbeatPort).pingClose()` 단언 1개씩 추가.

Run: `bash gradlew test --tests 'com.kista.adapter.in.schedule.*'`
Expected: 전체 PASS

- [ ] **Step 8: 전체 테스트 + 커밋**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/kista/domain/port/out/HeartbeatPort.java src/main/java/com/kista/adapter/out/heartbeat/ src/main/java/com/kista/adapter/in/schedule/TradingOpenScheduler.java src/main/java/com/kista/adapter/in/schedule/TradingCloseScheduler.java src/main/resources/application.yml src/test/java/com/kista/adapter/out/heartbeat/ src/test/java/com/kista/adapter/in/schedule/TradingOpenSchedulerTest.java src/test/java/com/kista/adapter/in/schedule/TradingCloseSchedulerTest.java
git commit -m "$(cat <<'EOF'
feat(schedule): dead-man's switch — 스케쥴러 실행 완료 heartbeat 핑

스케쥴러가 아예 실행되지 않는 상황(앱 다운, cron 미등록, 설정 오배포)을
감지할 장치가 없었다. 실행 완료 시 healthchecks.io로 GET 핑을 보내고
시간 내 핑이 없으면 외부에서 알림. URL 미설정 시 생략되어 배포 안전.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 9: (사용자 액션 안내)** 열린 질문 1의 healthchecks.io 체크 생성 + `fly secrets set` 안내를 완료 보고에 포함할 것. push는 하지 않음.

---

### Task 3: markPlaced DB 기록 실패 1회 재시도 [kista-api / Sonnet]

**배경:** `TradingOrderExecutor.placeEach`에서 증권사 접수 성공 후 `orderPort.markPlaced` DB 기록이 실패하면 알림만 보내고 주문이 DB상 PLANNED로 남는다. 재실행 시 `findPlannedByCycleAndDate`로 재조회돼 **이미 접수된 주문을 또 접수**할 수 있다. 일시적 DB 오류가 대부분이므로 1초 후 1회 재시도로 창을 좁힌다 (Virtual Thread 환경 — `Thread.sleep` 허용).

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderExecutor.java`
- Test: `src/test/java/com/kista/application/service/trading/TradingOrderExecutorTest.java`

**Interfaces:**
- Consumes: `OrderPort.markPlaced(UUID, String)` (기존)
- Produces: 없음 (내부 재시도만 — 시그니처 변경 없음)

- [ ] **Step 1: 실패하는 테스트 작성**

`TradingOrderExecutorTest.java`에 추가 (기존 헬퍼 `planned`/`kisResponse` 재사용):

```java
    @Test
    @DisplayName("markPlaced 1차 실패 시 1회 재시도 후 성공하면 정상 처리")
    void placeOrders_markPlacedFailsOnce_retriesAndSucceeds() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(plannedOrder, ACCOUNT)).thenReturn(kisResponse("KIS-201"));
        doThrow(new RuntimeException("일시적 DB 오류")).doNothing()
                .when(orderPort).markPlaced(orderId, "KIS-201");

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, INFINITE_STRATEGY);

        verify(orderPort, times(2)).markPlaced(orderId, "KIS-201");
        assertThat(result).hasSize(1); // 재시도 성공 → placed 목록 포함
        verify(notifyPort, never()).notifyError(any());
    }

    @Test
    @DisplayName("markPlaced 재시도도 실패하면 DB 불일치 알림 발송")
    void placeOrders_markPlacedFailsTwice_notifiesInconsistency() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerPort.place(plannedOrder, ACCOUNT)).thenReturn(kisResponse("KIS-202"));
        doThrow(new RuntimeException("DB down")).when(orderPort).markPlaced(orderId, "KIS-202");

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION, INFINITE_STRATEGY);

        verify(orderPort, times(2)).markPlaced(orderId, "KIS-202");
        assertThat(result).isEmpty();
        verify(notifyPort).notifyError(any(IllegalStateException.class));
    }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `bash gradlew test --tests 'com.kista.application.service.trading.TradingOrderExecutorTest'`
Expected: 신규 2건 FAIL (`times(2)` 불충족 — 현재는 1회만 호출)

- [ ] **Step 3: 재시도 구현**

`TradingOrderExecutor.java`의 `placeEach` 내 markPlaced try 블록을 교체하고 private 메서드 추가:

```java
            // 증권사 접수 성공 후 DB 동기화 실패 — 브로커에 주문이 남아있는 불일치 상태 (1회 재시도로 창 축소)
            try {
                markPlacedWithRetry(p.id(), placedOrder.externalOrderId());
                placed.add(p.withPlaced(placedOrder.externalOrderId()));
            } catch (Exception e) {
                log.error("[{}] {} {} 증권사 접수 완료됐으나 DB PLACED 기록 실패 — 수동 확인 필요 (externalOrderId={}): {}",
                        account.nickname(), p.direction(), p.ticker(), placedOrder.externalOrderId(), e.getMessage());
                notifyPort.notifyError(new IllegalStateException(
                        "[DB 불일치] 증권사 접수 완료 후 PLACED 기록 실패 — externalOrderId=" + placedOrder.externalOrderId(), e));
            }
```

```java
    // 일시적 DB 오류 흡수 — 1초 후 1회 재시도, 2차 실패는 호출측으로 전파
    private void markPlacedWithRetry(UUID orderId, String externalOrderId) {
        try {
            orderPort.markPlaced(orderId, externalOrderId);
        } catch (Exception first) {
            log.warn("markPlaced 1차 실패 — 1초 후 재시도: {}", first.getMessage());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            orderPort.markPlaced(orderId, externalOrderId);
        }
    }
```

- [ ] **Step 4: 테스트 통과 + 전체 회귀 확인**

Run: `bash gradlew test --tests 'com.kista.application.service.trading.TradingOrderExecutorTest' && bash gradlew test`
Expected: BUILD SUCCESSFUL (참고: 재시도 테스트는 sleep 1초 포함 — 정상)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/TradingOrderExecutor.java src/test/java/com/kista/application/service/trading/TradingOrderExecutorTest.java
git commit -m "$(cat <<'EOF'
fix(trading): 접수 후 PLACED 기록 실패 시 1회 재시도 — 중복 접수 창 축소

증권사 접수 성공 후 DB 기록이 실패하면 주문이 PLANNED로 남아
재실행 시 중복 접수될 수 있었다. 일시 오류 대응으로 1초 후 1회
재시도하고, 재실패 시 기존 DB 불일치 알림을 유지한다.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: role 변경 즉시 무효화 — iat 기반 stale AT 차단 [kista-api / Sonnet]

**배경:** `JwtAuthFilter`에 KNOWN GAP으로 기록된 문제 — role 변경 후에도 기존 AT가 최대 24시간(AT_TTL) 이전 권한을 유지한다. 단순 userId 블랙리스트(`add`)는 새로 발급받은 토큰까지 차단해 24시간 완전 잠금이 되므로 부적합. 대신 **role 변경 시각을 Redis에 기록하고, 그보다 먼저 발급된(iat) AT만 401 처리**한다. `TokenService.refresh`가 DB에서 role을 다시 읽어 새 AT를 발급하므로(TokenService.java:78-79) 사용자는 UI의 자동 refresh로 즉시 새 권한을 받는다.

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/BlacklistPort.java`
- Modify: `src/main/java/com/kista/domain/port/in/BlacklistUseCase.java`
- Modify: `src/main/java/com/kista/application/service/auth/BlacklistService.java`
- Modify: `src/main/java/com/kista/adapter/out/redis/RedisBlacklistAdapter.java`
- Modify: `src/main/java/com/kista/adapter/in/web/security/JwtIssuerService.java` (iat 클레임 추가)
- Modify: `src/main/java/com/kista/adapter/in/web/security/JwtAuthFilter.java` (stale role 차단 + KNOWN GAP 주석 갱신)
- Modify: `src/main/java/com/kista/application/service/admin/AdminService.java` (changeRole에서 기록)
- Test: `src/test/java/com/kista/application/service/admin/AdminServiceTest.java` (없으면 신규 생성)

**Interfaces:**
- Produces: `BlacklistPort.markRoleChanged(UUID userId, Instant changedAt, Duration ttl)` / `Instant roleChangedAt(UUID userId)` (없으면 null), `BlacklistUseCase.roleChangedAt(UUID)` 동일 시그니처
- Consumes: `TokenConstants.AT_TTL` (domain/model/auth), `Jwt.getIssuedAt()` (Spring Security)

- [ ] **Step 1: 포트·유스케이스·어댑터 확장**

`BlacklistPort.java`에 추가:

```java
    void markRoleChanged(UUID userId, Instant changedAt, Duration ttl); // role 변경 시각 기록 — 이전 발급 AT 무효화용
    Instant roleChangedAt(UUID userId); // 기록 없으면 null
```

import 추가: `import java.time.Instant;`

`BlacklistUseCase.java`에 추가 (기존 메서드 형식과 동일하게):

```java
    Instant roleChangedAt(UUID userId); // role 변경 시각 — 없으면 null (JwtAuthFilter stale AT 판정용)
```

`BlacklistService.java`에 위임 구현 추가:

```java
    @Override
    public Instant roleChangedAt(UUID userId) {
        return blacklistPort.roleChangedAt(userId);
    }
```

`RedisBlacklistAdapter.java`에 추가:

```java
    private static final String KEY_PREFIX_ROLE = "blacklist:rolechange:"; // role 변경 시각 키 접두사

    @Override
    public void markRoleChanged(UUID userId, Instant changedAt, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX_ROLE + userId, String.valueOf(changedAt.getEpochSecond()), ttl);
    }

    @Override
    public Instant roleChangedAt(UUID userId) {
        String v = redisTemplate.opsForValue().get(KEY_PREFIX_ROLE + userId);
        return v == null ? null : Instant.ofEpochSecond(Long.parseLong(v));
    }
```

- [ ] **Step 2: JwtIssuerService에 iat 클레임 추가**

`issue()` 빌더 체인의 `.expiration(...)` 앞에 삽입:

```java
                .issuedAt(new Date()) // iat — role 변경 시각과 비교해 stale AT 판정 (JwtAuthFilter)
```

- [ ] **Step 3: JwtAuthFilter에 stale role 차단 추가**

기존 블랙리스트 차단 블록(TOKEN_BLACKLISTED 반환) 직후에 삽입, KNOWN GAP 주석 4줄은 아래 새 주석으로 교체:

```java
                // role 변경 이전 발급 AT 차단 — 강등/승격 즉시 반영 (UI refresh가 DB role로 새 AT 발급)
                // iat 없는 구버전 AT는 발급 시점 확인 불가 → 보수적으로 차단
                java.time.Instant roleChangedAt = blacklistUseCase.roleChangedAt(userId);
                if (roleChangedAt != null
                        && (jwt.getIssuedAt() == null || jwt.getIssuedAt().isBefore(roleChangedAt))) {
                    log.debug("stale role AT 차단: userId={}, iat={}, roleChangedAt={}", userId, jwt.getIssuedAt(), roleChangedAt);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"TOKEN_STALE_ROLE\",\"message\":\"권한이 변경되었습니다. 다시 로그인해 주세요.\"}");
                    return;
                }
```

- [ ] **Step 4: AdminService.changeRole에서 기록**

필드 추가 (`private final AuditLogPort auditLogPort;` 아래):

```java
    private final BlacklistPort blacklistPort;           // role 변경 시 stale AT 무효화 기록
```

import 추가: `import com.kista.domain.port.out.BlacklistPort;`, `import com.kista.domain.model.auth.TokenConstants;`, `import java.time.Instant;`

`changeRole`의 `userPort.save(user.withRole(role));` 직후에 삽입:

```java
        // 기존 AT 무효화 — 변경 시각 이전 발급 토큰은 JwtAuthFilter가 401 처리 (refresh로 새 role AT 발급)
        blacklistPort.markRoleChanged(targetUserId, Instant.now(), TokenConstants.AT_TTL);
```

- [ ] **Step 5: 테스트 작성·실행**

`ls src/test/java/com/kista/application/service/admin/`으로 `AdminServiceTest` 존재 확인. 있으면 changeRole 테스트에 `@Mock BlacklistPort blacklistPort` 추가 + 아래 단언 추가, 없으면 최소 테스트로 신규 생성:

```java
package com.kista.application.service.admin;

import com.kista.application.service.user.UserCascadeDeleter;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.AdminUserViewPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock UserPort userPort;
    @Mock AdminUserViewPort adminUserViewPort;
    @Mock UserCascadeDeleter userCascadeDeleter;
    @Mock UserUseCase userUseCase;
    @Mock AuditLogPort auditLogPort;
    @Mock BlacklistPort blacklistPort;
    @InjectMocks AdminService adminService;

    @Test
    void changeRole_marksRoleChangedForStaleAtInvalidation() {
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = new User(targetId, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.NONE);
        when(userPort.findByIdOrThrow(targetId)).thenReturn(target);

        adminService.changeRole(adminId, targetId, User.UserRole.ADMIN);

        verify(blacklistPort).markRoleChanged(eq(targetId), any(Instant.class), any(Duration.class));
    }
}
```

주의: `User` 생성자 인자가 다르면 `BatchContextFactoryTest.mockUser()` 픽스처와 동일 패턴으로 맞출 것. 기존 AdminServiceTest가 있으면 그 픽스처 스타일을 따를 것.

Run: `bash gradlew test --tests 'com.kista.application.service.admin.AdminServiceTest' && bash gradlew test`
Expected: BUILD SUCCESSFUL (기존 서비스 테스트에 @Mock 누락 NPE 발생 시 해당 테스트에 blacklistPort @Mock 추가)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/domain/port/out/BlacklistPort.java src/main/java/com/kista/domain/port/in/BlacklistUseCase.java src/main/java/com/kista/application/service/auth/BlacklistService.java src/main/java/com/kista/adapter/out/redis/RedisBlacklistAdapter.java src/main/java/com/kista/adapter/in/web/security/JwtIssuerService.java src/main/java/com/kista/adapter/in/web/security/JwtAuthFilter.java src/main/java/com/kista/application/service/admin/AdminService.java src/test/java/com/kista/application/service/admin/AdminServiceTest.java
git commit -m "$(cat <<'EOF'
fix(auth): role 변경 시 이전 발급 AT 즉시 무효화 — 24시간 권한 잔존 갭 해소

role 변경 시각을 Redis에 기록하고 그보다 먼저 발급된(iat) AT는
401 처리한다. refresh가 DB role로 새 AT를 발급하므로 사용자는
자동으로 새 권한을 받는다. userId 블랙리스트 방식(24h 완전 잠금)
대신 iat 비교로 잠금 없이 즉시 반영.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: (UI) trades-stream 업스트림 401/403 → auth-error 이벤트 [kista-ui / Sonnet]

**배경:** `app/api/trades/stream/route.ts`는 토큰이 아예 없을 때는 auth-error SSE 이벤트를 보내지만, **토큰이 있는데 업스트림이 401을 반환**하면(만료 등) 일반 오류 응답을 반환한다. EventSource는 상태 코드를 알 수 없어 5초마다 만료 토큰으로 무한 재연결한다. 클라이언트(`TradeNotificationProvider.tsx:39-42`)는 이미 auth-error 리스너가 있으므로 라우트만 고치면 된다.

**Files:**
- Modify: `app/api/trades/stream/route.ts`

**Interfaces:**
- Consumes: 기존 auth-error SSE 이벤트 계약 (`event: auth-error\ndata: unauthorized`) — `TradeNotificationProvider`가 수신 시 재연결 중단
- Produces: 없음

- [ ] **Step 1: 라우트 수정**

`app/api/trades/stream/route.ts`의 `if (!upstream.ok)` 블록을 교체:

```ts
  if (!upstream.ok) {
    // 업스트림 401/403(토큰 만료·권한 상실) → no-token 분기와 동일하게 auth-error 이벤트로 재연결 중단 유도
    if (upstream.status === 401 || upstream.status === 403) {
      const body = new TextEncoder().encode('event: auth-error\ndata: unauthorized\n\n')
      return new Response(body, {
        headers: {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
        },
      })
    }
    return new Response('Upstream error', { status: upstream.status })
  }
```

- [ ] **Step 2: 타입·기존 테스트 검증**

Run: `cd /Users/phs/workspace/kista/kista-ui && npm run typecheck && npm run test`
Expected: 통과 (이 라우트 전용 테스트는 없음 — 기존 스위트 회귀 확인)

- [ ] **Step 3: 커밋**

```bash
git add app/api/trades/stream/route.ts
git commit -m "$(cat <<'EOF'
fix(sse): trades-stream 업스트림 401/403을 auth-error 이벤트로 변환

토큰 만료 시 EventSource가 상태 코드를 알 수 없어 만료 토큰으로
5초마다 무한 재연결했다. no-token 분기와 동일하게 auth-error
이벤트를 보내 클라이언트가 재연결을 중단하게 한다.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: kista-api 문서 정정 + 미실행 계획 아카이브 [kista-api / Haiku]

**배경:** architecture.md가 존재하지 않는 클래스명 `AdminOrderCorrectionService`를 기재(실제: `AdminReorderService`) — 자동 로드되는 AI 컨텍스트라 후속 작업 판단을 오염시킨다. trading helper 목록도 3개 누락. 2026-07-08 lightsail 마이그레이션 계획은 미실행 폐기 상태(fly.toml 현역, deploy/lightsail/ 미존재)로 확인됐다.

**Files:**
- Modify: `docs/agents/architecture.md`
- Move: `docs/superpowers/plans/2026-07-08-lightsail-migration.md` → `docs/superpowers/plans/archive/`
- Delete: `.superpowers/sdd/task-1-report.md`, `.superpowers/sdd/task-2-report.md` (열린 질문 7 기본값)

- [ ] **Step 1: 잘못된 클래스명 정정**

Run: `grep -n "AdminOrderCorrectionService" docs/agents/*.md CLAUDE.md AGENTS.md`
발견된 모든 위치에서 `AdminOrderCorrectionService` → `AdminReorderService`로 교체.

- [ ] **Step 2: trading helper 목록 갱신**

`docs/agents/architecture.md`의 `package-private helper: TradingBalanceLoader/...` 행에서 목록 끝에 `/CycleSnapshotCreator/SeedResolutionPolicy/TradingDayCounter` 추가.

- [ ] **Step 3: lightsail 계획 아카이브**

```bash
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-07-08-lightsail-migration.md docs/superpowers/plans/archive/
```

파일 최상단에 아래 1줄 삽입:

```markdown
> ⚠️ **미실행 폐기 (2026-07-11 확인)** — Fly.io 배포가 현역 유지 중이며 이 계획은 실행되지 않았다. 참고용 보관.
```

- [ ] **Step 4: sdd 리포트 삭제 + 커밋**

```bash
git rm .superpowers/sdd/task-1-report.md .superpowers/sdd/task-2-report.md
git add docs/agents/architecture.md docs/superpowers/plans/archive/
git commit -m "$(cat <<'EOF'
docs: architecture.md 클래스명 오류 정정 + 미실행 lightsail 계획 아카이브

AdminOrderCorrectionService는 존재하지 않는 이름(실제 AdminReorderService).
trading helper 누락 3개 보충, 폐기된 lightsail 계획은 archive로 이동,
1회성 sdd 작업 리포트 제거.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: (UI) 문서 3중복 해소 + stale 정정 [kista-ui / Haiku]

**배경:** 같은 내용(아키텍처·명령어·배포)이 CLAUDE.md ↔ docs/agents/*.md ↔ .serena/memories/*.md 3벌로 존재해 수정 시 3곳을 고쳐야 한다. SSOT를 docs/agents/로 통일한다. `.serena/memories/tech_stack.md`는 폐기된 "Render URL"을 기재 중. **하위 디렉토리 CLAUDE.md 5개는 삭제하지 않는다** (열린 질문 4 기본값 — 자동 로드 라우팅 역할 유지).

**Files:**
- Modify: `CLAUDE.md` (루트)
- Modify: `.serena/memories/core.md`, `.serena/memories/suggested_commands.md`, `.serena/memories/tech_stack.md`

- [ ] **Step 1: CLAUDE.md 아키텍처 섹션 축약**

`CLAUDE.md`의 "아키텍처" 섹션(약 40-83행 — 인증 라우팅/레이아웃 그룹/FSD 다이어그램/경로 alias/API 계층)을 아래로 교체 (행 번호가 다르면 해당 소제목 기준으로 찾을 것):

```markdown
## 아키텍처

구조 상세(인증 라우팅·레이아웃 그룹·FSD 계층·경로 alias·API 계층)는 `docs/agents/architecture.md`가 SSOT다 — 여기 중복 기재하지 않는다.
```

- [ ] **Step 2: CLAUDE.md 배포/CORS/Docker 섹션 축약**

CLAUDE.md의 CORS·Vercel·Docker 관련 섹션(약 103-148행)을 아래로 교체:

```markdown
## 배포·CORS·Docker

배포(Vercel)·CORS 주의사항·Docker 실행은 `docs/agents/deployment.md`가 SSOT다. 핵심 1줄: Server Component fetch는 CORS 미적용, 클라이언트 fetch만 `CORS_ALLOWED_ORIGINS` 영향.
```

주의: 교체 전 두 섹션에 docs/agents/*.md에 **없는 고유 정보**가 있는지 대조하고, 있으면 해당 docs 파일로 옮긴 후 축약할 것.

- [ ] **Step 3: serena 메모리 축약·정정**

- `.serena/memories/core.md` → 전체를 3줄로 교체: 제목 + "구조 SSOT는 `docs/agents/architecture.md` — 중복 기재 금지" + 프로젝트 한 줄 소개
- `.serena/memories/suggested_commands.md` → 전체를 3줄로 교체: 제목 + "명령어 SSOT는 `docs/agents/commands.md`" + `npm run dev / build / typecheck / test` 1줄
- `.serena/memories/tech_stack.md` → `Render URL` 문구를 `Fly.io URL (https://kista-api.fly.dev)`로 정정

- [ ] **Step 4: 검증 + 커밋**

Run: `grep -rn "Render" .serena/memories/ CLAUDE.md docs/agents/ | grep -vi render라는_무관_단어`
Expected: Render 잔존 0건 (무관한 일반 단어 제외)

```bash
git add CLAUDE.md .serena/memories/core.md .serena/memories/suggested_commands.md .serena/memories/tech_stack.md
git commit -m "$(cat <<'EOF'
docs: 3중복 문서 해소 — docs/agents를 SSOT로 통일

아키텍처·명령어·배포 서술이 CLAUDE.md/docs/agents/.serena 3벌로
존재해 수정 시 드리프트가 반복됐다. docs/agents를 SSOT로 하고
나머지는 참조로 축약. 폐기된 Render URL도 Fly.io로 정정.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: (UI) 도구 잔재 정리 + 스킬 경로 오염 제거 [kista-ui / Haiku]

**배경:** kista-api는 2026-07-09에 정리한 shrimp 잔재가 kista-ui에는 남아 있다. `.superpowers/brainstorm/`은 특정 세션의 임시 서버 산출물. `.agents/skills`의 스킬 3개에는 다른 머신의 Windows 절대경로(`/d/src/study/kista/kista-ui`)가 박제되어 있어 이식성 버그다.

**Files:**
- Delete: `.shrimp-data/` (git 추적), `.superpowers/brainstorm/1919-1780390551/`, `.superpowers/sdd/task-3-report.md`
- Modify: `.agents/skills/dto-sync-check/SKILL.md`, `.agents/skills/fsd-scaffold/SKILL.md`, `.agents/skills/typecheck-and-report/SKILL.md` (경로 오염 제거 + `.claude/skills`와 내용 동기화)

- [ ] **Step 1: 잔재 삭제 전 참조 확인**

Run: `grep -rn "shrimp" --include="*.md" --include="*.json" --include="*.ts" . --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=.next --exclude-dir=.shrimp-data`
Expected: 출력 없음. 참조가 나오면 삭제 중단하고 보고.

- [ ] **Step 2: 잔재 삭제**

```bash
git rm -r .shrimp-data
git rm -r .superpowers/brainstorm/1919-1780390551 2>/dev/null || rm -rf .superpowers/brainstorm/1919-1780390551
git rm .superpowers/sdd/task-3-report.md 2>/dev/null || true
```

(untracked면 `rm -rf`로 처리)

- [ ] **Step 3: 스킬 경로 오염 제거 + 동기화**

Run: `grep -rln "/d/src/study" .agents .claude`
발견된 각 파일에서 `/d/src/study/kista/kista-ui` 절대경로를 "프로젝트 루트" 상대 표현으로 교체 (예: `/d/src/study/kista/kista-ui/lib/AGENTS.md` → `lib/AGENTS.md`). 이후 두 디렉토리 동기화:

```bash
diff -rq .agents/skills .claude/skills
```

내용이 다른 스킬은 최신 쪽(.claude/skills 기준) 내용으로 `.agents/skills`를 맞춘다. `.agents/skills`에만 있는 `source-command-update-roadmap`은 그대로 둔다.

- [ ] **Step 4: 검증 + 커밋**

Run: `grep -rn "/d/src/study" .agents .claude; npm run typecheck`
Expected: 경로 잔존 0건, typecheck 통과

```bash
git add .agents .claude
git commit -m "$(cat <<'EOF'
chore: 도구 잔재 제거 + 스킬 파일의 타 머신 절대경로 오염 정정

kista-api에서 이미 정리한 shrimp 잔재를 동일 적용하고, 세션 1회성
brainstorm 산출물·sdd 리포트를 제거. 스킬 3개에 박제된 Windows
절대경로(/d/src/study/...)는 상대 경로로 교체해 이식성 확보.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: QueryDSL 의존성 제거 (열린 질문 3 승인 시) [kista-api / Haiku]

**배경:** 프로덕션 코드에서 `com.querydsl` import 0건 — annotationProcessor가 매 빌드 Q클래스 20개를 생성만 하고 아무도 참조하지 않는다. 의존성 4줄 + sourceSet 설정 제거로 빌드 시간을 줄인다.

**Files:**
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: 실사용 0건 재확인 (안전핀)**

Run: `grep -rn "com.querydsl\|JPAQueryFactory\|QuerydslPredicateExecutor" src/main src/test --include="*.java"`
Expected: 출력 없음. **1건이라도 나오면 이 Task 전체 중단하고 보고.**

- [ ] **Step 2: build.gradle.kts + 버전 카탈로그에서 제거**

`build.gradle.kts`에서 제거 (2026-07-11 기준 위치 — 다르면 `grep -n "querydsl" build.gradle.kts`로 재확인):
- 39행: `implementation("${libs.querydsl.jpa.get()}:jakarta")`
- 40행: `annotationProcessor("${libs.querydsl.apt.get()}:jakarta")`
- 83행 `val querydslDir = ...`부터 94행 `options.generatedSourceOutputDirectory = ...`까지의 querydsl sourceSet/컴파일 출력 설정 블록 (88행 `srcDir(querydslDir)` 포함 — 블록 경계는 grep 결과 기준으로 판단)

`gradle/libs.versions.toml`에서 제거:
- 4행: `querydsl = "5.1.0"` (versions)
- 26-27행: `querydsl-jpa` / `querydsl-apt` 라이브러리 선언

- [ ] **Step 3: 클린 빌드 + 전체 테스트**

Run: `bash gradlew clean compileJava compileTestJava && bash gradlew test`
Expected: BUILD SUCCESSFUL (Q클래스 참조가 없으므로 컴파일 영향 없음)

- [ ] **Step 4: 커밋**

```bash
git add build.gradle.kts gradle/libs.versions.toml
git commit -m "$(cat <<'EOF'
chore: 미사용 QueryDSL 의존성 제거 — annotationProcessor 빌드 오버헤드 정리

프로덕션·테스트 코드에서 com.querydsl 참조 0건. Q클래스 생성만
반복되던 의존성과 sourceSet 설정을 제거해 빌드를 가볍게 한다.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: 백업/복구 런북 문서 추가 [kista-api / Haiku]

**배경:** 실계좌 자금을 다루는 서비스인데 DB 백업/복구 절차 문서가 없다. Supabase(운영 DB)와 암호화 키 백업 절차를 docs/agents/docker-infra.md에 기록한다.

**Files:**
- Modify: `docs/agents/docker-infra.md`

- [ ] **Step 1: 런북 섹션 추가**

`docs/agents/docker-infra.md` 최하단에 추가:

```markdown
## 백업/복구 런북

### DB 백업 (Supabase 운영)
- Supabase 자동 백업: 대시보드 → Database → Backups에서 플랜별 보존 기간 확인 (Free: 없음, Pro: 일 1회 7일 보존)
- 수동 백업: `supabase db dump --linked -f backup-$(date +%Y%m%d).sql` — 중요 스키마 변경(마이그레이션 배포) 직전 필수 실행
- 백업 파일은 레포 밖 안전한 위치에 보관 (git 커밋 금지 — 사용자 데이터 포함)

### 복구
1. 신규/기존 프로젝트에 복원: `psql "$DB_URL" < backup-YYYYMMDD.sql`
2. 복원 후 `flyway_schema_history` 최신 버전이 배포 코드의 마이그레이션 버전과 일치하는지 확인 — 불일치 시 앱 기동 실패
3. 앱 재기동 후 `/actuator/health` 200 확인 + 텔레그램 시작 알림 수신 확인

### 키 백업 (분실 시 복구 불가 — DB 백업과 별도 보관 필수)
- `AES_ENCRYPTION_KEY` — 분실 시 accounts의 암호화 컬럼(계좌번호·API 키) 전체 복호화 불가 → 사용자 재등록 필요
- `JWT_SIGNING_KEY` — 분실 시 전체 사용자 재로그인 (치명적이지 않음)
- 확인: `fly secrets list -a kista-api` (값은 안 보임 — 원본을 별도 보관해야 함)
```

- [ ] **Step 2: 커밋**

```bash
git add docs/agents/docker-infra.md
git commit -m "$(cat <<'EOF'
docs: 백업/복구 런북 추가 — DB 백업·복원·암호화 키 보관 절차

실계좌 자금 취급 서비스인데 백업/복구 절차 문서가 없었다.
Supabase 백업, 복원 후 Flyway 정합성 확인, AES/JWT 키 별도
보관 필요성을 docker-infra.md에 기록.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## 최종 검증 체크리스트 (모든 Task 완료 후)

- [ ] kista-api: `bash gradlew clean compileJava compileTestJava && bash gradlew test` → BUILD SUCCESSFUL
- [ ] kista-api: `bash gradlew test --tests 'com.kista.architecture.*'` → ArchUnit PASS (신규 HeartbeatPort/Adapter가 레이어 규칙 준수 확인)
- [ ] kista-ui: `npm run typecheck && npm run test` → 통과
- [ ] 양 레포 `git log --oneline -8` → Task별 커밋 확인, author `narafu <narafu@kakao.com>`
- [ ] push는 하지 않았음을 확인 (사용자 요청 시에만)
- [ ] 사용자 보고에 포함: ① 열린 질문 1(healthchecks.io 체크 생성 + fly secrets 설정)은 사용자 액션 필요 ② 열린 질문 중 기본값으로 처리한 항목 목록 ③ 다음 검토 사이클 후보(잔고 급변 안전장치·서킷브레이커·CycleState sealed interface 전환·스케쥴러 인터럽트 시 영향 사용자 알림)
