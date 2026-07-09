# 종합 건강진단 후속 조치 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 개인 운영 중인 kista-api의 4렌즈 검토(과잉·취약·누락·구조충돌)에서 발견된 문제를 영향력 순으로 수정한다 — 매매 로직(공식·주문 생성)은 동작 불변.

**Architecture:** 스케쥴러는 in-process `Thread.sleep` 기반이라 push-to-main 자동 배포와 충돌한다. 재설계 대신 (1) 인터럽트 시 분산 락 즉시 해제로 복구 가능성 확보, (2) 배포 워크플로에 테스트 게이트·매매 시간대 가드 추가라는 저비용 접근을 택한다. 나머지는 미검증 돈 경로 테스트 보강, 문서 드리프트 수정, 저장소 청소.

**Tech Stack:** Java 21 + Spring Boot 3, JUnit 5 + Mockito, GitHub Actions, Fly.io

---

## ⚠️ 실행 전 필독 (실행자 규칙)

- 작업 디렉토리: `kista-api` 레포 루트 (git 저장소는 이 디렉토리에 있음, 상위 `/kista` 아님)
- 커밋 전 확인: `git config user.name` = `narafu`, `git config user.email` = `narafu@kakao.com`
- 커밋 메시지는 한글 + Conventional Commit (`fix:`, `test:`, `docs:`, `chore:`, `ci:`)
- **`git push` 금지** — 사용자가 명시 요청할 때만 (push하면 즉시 운영 배포됨)
- bash에서 gradle 실행: `bash gradlew test` (`./gradlew` 아님)
- 파일 수정 시 BOM(`\xef\xbb\xbf`) 삽입 금지 — 수정 후 `bash gradlew compileJava`로 즉시 검증
- 매매 공식·주문 생성 로직(`InfiniteStrategy`, `PrivacyStrategy`, `VrStrategy`, `InfinitePosition`, `VrPosition`)은 **절대 수정 금지**

---

## 🔓 열린 질문 (사용자 답변 필요 — 답 없으면 각 항목의 기본값으로 진행)

1. **`qodana.yaml` 삭제 여부** — CI 워크플로에는 미연동. IntelliJ에서 로컬 Qodana 분석에 쓰고 있다면 유지해야 함. *기본값: Task 6에서 삭제하지 않고 보류 (shrimp 관련만 삭제).*

2. **[의심 버그] 체결이 0건인 날 PLACED 주문이 CANCELLED 처리되지 않음** — `TradingReporter.markFilledOrders()`는 `executions.isEmpty()`면 조기 반환한다. 체결이 1건이라도 있으면 미체결 주문을 CANCELLED로 마킹하지만, 전량 미체결(체결 0건)이면 모든 주문이 PLACED로 영구 잔류한다. 의도된 동작인가? *기본값: 동작 변경하지 않고 Task 4에서 현재 동작을 테스트로 고정만 함. 버그라면 별도 지시 필요.*

3. **`ci.yml`을 PR 전용으로 좁히기** — Task 2에서 fly-deploy.yml verify job이 전체 테스트를 수행하게 되면 push 시 CI가 이중 실행됨. *기본값: ci.yml을 `on: pull_request`로 변경 (Task 2에 포함).*

4. **배포 시간대 가드 방식** — 매매 시간대에 push하면 배포를 (A) 실패시키고 수동 재실행 유도 vs (B) 시간대가 끝날 때까지 대기. *기본값: (A) 실패 + `workflow_dispatch` force 입력으로 강제 배포 우회 (Task 3 기준).*

5. **검토했으나 계획에서 제외한 항목 (동의 확인)** — `static/index.html` 흔적 대시보드(무해·소형), Prometheus/Grafana 로컬 스택(문서 참조 중), 32개 아웃바운드 포트(의도된 헥사고날 설계). 모두 유지. 이견 있으면 지시.

---

## 검토 결과 요약 (실행자 참고용 배경)

| 렌즈 | 발견 | 대응 Task |
|------|------|-----------|
| 취약 | 스케쥴러 인터럽트 시 분산 락 2~3h 잔류 → 관리자 수동 재실행(`runNow`) 불가 | Task 1 |
| 취약 | `SchedulerJobRunner.run(name, Runnable)` 예외 시에도 "완료" 알림 (거짓 성공) | Task 1 |
| 취약 | Fly 배포가 compile+ArchUnit만 통과하면 진행 — 전체 테스트 실패해도 배포됨 | Task 2 |
| 구조충돌 | push마다 롤링 배포 → 매매 시간대(KST 22:30~23:35, 04:30~06:15) sleep 중인 스케쥴러 강제 종료 위험 | Task 3 |
| 누락 | `TradingReporter`(미체결→CANCELLED, 체결 매칭·가중평균가) 단위 테스트 없음 | Task 4 |
| 과잉/드리프트 | architecture.md·workflow.md 스케쥴러 시각이 코드(22:30/04:30)와 불일치(22:00/04:00 기재) | Task 5 |
| 과잉 | `shrimp-rules.md`(구식 — 존재하지 않는 테이블·폐기된 OCI 배포 기술), `.shrimp-data/`, 빈 `package-lock.json`, 빈 `supabase/` | Task 6 |

---

### Task 1: 스케쥴러 인터럽트 시 락 해제 + 거짓 "완료" 알림 수정

**배경:** 배포/재시작으로 매매 스케쥴러가 인터럽트되면 `SchedulerJobRunner`가 `InterruptedException`을 삼키고 정상 반환한다. 그러면 `SchedulerLockService.tryRun()`이 성공으로 간주해 락을 2~3시간 유지하고, 관리자가 `POST /api/admin/scheduler/close`로 재실행해도 락에 막혀 장 마감 전 복구가 불가능하다. `InterruptedException`을 rethrow하면 `tryRun`의 `finally`(completed=false)가 락을 즉시 해제한다.

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/schedule/SchedulerJobRunner.java`
- Modify: `src/main/java/com/kista/adapter/in/schedule/TradingCloseScheduler.java` (runLocked에 throws 추가)
- Modify: `src/main/java/com/kista/adapter/in/schedule/TradingOpenScheduler.java` (runLocked에 throws 추가)
- Create: `src/test/java/com/kista/adapter/in/schedule/SchedulerJobRunnerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/adapter/in/schedule/SchedulerJobRunnerTest.java` 생성:

```java
package com.kista.adapter.in.schedule;

import com.kista.domain.port.out.NotifyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SchedulerJobRunnerTest {

    @Mock NotifyPort notifyPort;
    SchedulerJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SchedulerJobRunner(notifyPort);
    }

    @Test
    void 인터럽트_발생_시_rethrow하여_호출측이_락을_해제할_수_있다() {
        // 인터럽트를 삼키면 SchedulerLockService가 성공으로 간주해 락을 2~3h 유지 → 수동 복구 불가
        assertThatThrownBy(() -> runner.run("마감 매매 스케쥴러", List::of,
                contexts -> { throw new InterruptedException("배포 재시작"); }))
                .isInstanceOf(InterruptedException.class);
        verify(notifyPort).notifyError(any(InterruptedException.class));
        verify(notifyPort, never()).notifyInfo("마감 매매 스케쥴러 완료");
    }

    @Test
    void 일반_예외_시_완료_알림을_보내지_않는다() throws InterruptedException {
        runner.run("마감 매매 스케쥴러", List::of,
                contexts -> { throw new IllegalStateException("boom"); });
        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verify(notifyPort, never()).notifyInfo("마감 매매 스케쥴러 완료");
    }

    @Test
    void Runnable_작업_예외_시_완료_알림을_보내지_않는다() {
        // 현재 코드는 catch 후에도 무조건 "완료" 알림 발송 — 거짓 성공 보고
        runner.run("FearGreed 수집", () -> { throw new IllegalStateException("boom"); });
        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verify(notifyPort, never()).notifyInfo("FearGreed 수집 완료");
    }

    @Test
    void 정상_완료_시_시작과_완료_알림을_모두_보낸다() throws InterruptedException {
        runner.run("장 개시 스케쥴러", List::of, contexts -> {});
        verify(notifyPort).notifyInfo("장 개시 스케쥴러 시작");
        verify(notifyPort).notifyInfo("장 개시 스케쥴러 완료");
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.in.schedule.SchedulerJobRunnerTest'`
Expected: 컴파일 오류(현재 `run` 3-arg는 throws 선언 없음 → assertThatThrownBy 람다에서 InterruptedException 처리 불가) 또는 `인터럽트_발생_시...`, `Runnable_작업_예외...` 테스트 FAIL

- [ ] **Step 3: SchedulerJobRunner 수정**

`src/main/java/com/kista/adapter/in/schedule/SchedulerJobRunner.java` 전체를 아래로 교체:

```java
package com.kista.adapter.in.schedule;

import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

// 스케쥴러 공통 실행 골격 — "알림 시작 → contexts 빌드 → try 실행 → 인터럽트/예외 처리 → 알림 완료"
@Slf4j
@Component
@RequiredArgsConstructor
class SchedulerJobRunner {

    private final NotifyPort notifyPort;

    // BatchContext 없이 단순 Runnable 작업 실행 — FearGreed·MarketCalendar 스케쥴러용
    void run(String name, Runnable job) {
        notifyPort.notifyInfo(name + " 시작");
        log.info("{} 시작", name);
        try {
            job.run();
            log.info("{} 완료", name);
            notifyPort.notifyInfo(name + " 완료");
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            notifyPort.notifyError(e);
        }
    }

    // name: 스케쥴러 표시명 (e.g., "장 개시 스케쥴러", "마감 매매 스케쥴러 수동")
    void run(String name, Supplier<List<BatchContext>> contextSupplier, Action action) throws InterruptedException {
        notifyPort.notifyInfo(name + " 시작");
        List<BatchContext> contexts = contextSupplier.get();
        log.info("{} 시작 — ACTIVE 전략 {}개", name, contexts.size());
        try {
            action.accept(contexts);
            log.info("{} 완료", name);
            notifyPort.notifyInfo(name + " 완료");
        } catch (InterruptedException e) {
            // 배포·재기동 강제 종료 — 알림 후 rethrow해 SchedulerLockService가 락을 즉시 해제하도록 함
            // (삼키면 tryRun이 성공으로 간주 → 락 2~3h 잔류 → 관리자 runNow 재실행 불가)
            log.warn("{} 인터럽트: {}", name, e.getMessage());
            notifyPort.notifyError(e); // rethrow 전에 IO 완료
            throw e;
        } catch (Exception e) {
            log.error("{} 오류: {}", name, e.getMessage(), e);
            notifyPort.notifyError(e);
        }
    }

    @FunctionalInterface
    interface Action {
        void accept(List<BatchContext> contexts) throws Exception;
    }
}
```

핵심 변경 2가지: (1) 3-arg `run`이 `throws InterruptedException` 선언 + catch에서 `throw e` (기존은 `Thread.currentThread().interrupt()` 후 정상 반환), (2) Runnable 오버로드의 "완료" 로그·알림을 try 블록 안으로 이동.

- [ ] **Step 4: 호출측 컴파일 수정**

`TradingCloseScheduler.java`의 `runLocked` 메서드 시그니처에 throws 추가:

```java
    private void runLocked() throws InterruptedException {
```

`TradingOpenScheduler.java`의 `runLocked`도 동일:

```java
    private void runLocked() throws InterruptedException {
```

(각 스케쥴러의 `run()`/`runNow()`는 이미 `throws InterruptedException` 선언되어 있고, `SchedulerLockService.LockedTask.run()`도 `throws InterruptedException`이므로 다른 수정 불필요. `SchedulerLockService.tryRun`의 `finally { if (!completed) release(lockName); }`가 이제 인터럽트 시에도 실행되어 락이 즉시 해제된다.)

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.in.schedule.*'`
Expected: SchedulerJobRunnerTest 4건 + 기존 BatchContextFactoryTest/TradingCloseSchedulerTest/TradingOpenSchedulerTest 모두 PASS.
기존 스케쥴러 테스트가 컴파일 실패하면 해당 테스트의 `run` 호출부에 throws 전파만 추가 (동작 검증 로직 변경 금지).

- [ ] **Step 6: 전체 컴파일 + 전체 테스트**

Run: `bash gradlew compileJava compileTestJava && bash gradlew test`
Expected: BUILD SUCCESSFUL. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 클래스 확인.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/schedule/SchedulerJobRunner.java src/main/java/com/kista/adapter/in/schedule/TradingCloseScheduler.java src/main/java/com/kista/adapter/in/schedule/TradingOpenScheduler.java src/test/java/com/kista/adapter/in/schedule/SchedulerJobRunnerTest.java
git commit -m "$(cat <<'EOF'
fix(schedule): 인터럽트 시 스케쥴러 락 즉시 해제 — 배포 후 수동 재실행 복구 가능

InterruptedException을 삼키면 tryRun이 성공으로 간주해 락을 2~3시간 유지,
관리자 runNow()가 막혀 장 마감 전 복구 불가. rethrow로 변경해 finally에서
락을 즉시 해제한다. Runnable 오버로드의 예외 시 거짓 "완료" 알림도 수정.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Fly 배포를 전체 테스트 게이트에 연결

**배경:** 현재 `fly-deploy.yml`의 verify job은 compile + ArchUnit만 실행 — 전체 테스트가 실패해도 배포된다 (`ci.yml`은 병렬 실행일 뿐 배포를 막지 않음). verify job에 PostgreSQL 서비스를 붙여 전체 테스트를 돌리고, 중복 실행 방지를 위해 ci.yml을 PR 전용으로 좁힌다.

**Files:**
- Modify: `.github/workflows/fly-deploy.yml`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: fly-deploy.yml verify job 교체**

`.github/workflows/fly-deploy.yml`의 `verify` job을 아래로 교체 (deploy job은 그대로):

```yaml
jobs:
  verify:
    name: Full Test Suite
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:17
        env:
          POSTGRES_DB: kistadb
          POSTGRES_USER: kista
          POSTGRES_PASSWORD: kista
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'gradle'
      - name: Create test database
        run: psql -h localhost -U kista -d kistadb -c "CREATE DATABASE kistadb_test OWNER kista;"
        env:
          PGPASSWORD: kista
      - name: Run all tests (ArchUnit 포함)
        run: ./gradlew test --no-daemon
        env:
          SPRING_PROFILES_ACTIVE: test
```

주의: ArchUnit 테스트(`com.kista.architecture.*`)는 `test` 태스크에 포함되므로 별도 스텝 불필요. `deploy` job의 `needs: verify`는 이미 있으므로 유지.

- [ ] **Step 2: ci.yml을 PR 전용으로 변경**

`.github/workflows/ci.yml`의 트리거 블록을 교체:

```yaml
on:
  pull_request:
    branches: [main]
```

(기존 `push: branches: [main]` 제거 — push 시 테스트는 fly-deploy verify가 담당)

- [ ] **Step 3: YAML 문법 검증**

Run: `python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/fly-deploy.yml')); yaml.safe_load(open('.github/workflows/ci.yml')); print('OK')"`
Expected: `OK` (python에 yaml 모듈 없으면 `npx --yes yaml-lint .github/workflows/fly-deploy.yml .github/workflows/ci.yml` 사용)

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/fly-deploy.yml .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
ci: 배포 전 전체 테스트 게이트 — verify job에 PostgreSQL + 전체 테스트 추가

기존 verify는 compile+ArchUnit만 검증해 테스트가 깨져도 배포됐음.
ci.yml은 PR 전용으로 좁혀 push 시 이중 실행 방지.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: 사후 검증 (사용자가 push한 뒤)**

push는 사용자 요청 시에만. push 후 `gh run list --limit 3`으로 verify job이 전체 테스트를 수행하고 deploy가 그 뒤에 실행되는지 확인.

---

### Task 3: 매매 시간대 배포 가드

**배경:** 스케쥴러가 `Thread.sleep`으로 장시간 대기 중(개장 22:30~23:35, 마감 04:30~06:15 KST)에 push하면 롤링 배포가 스케쥴러 스레드를 인터럽트해 주문 접수가 유실될 수 있다. 해당 시간대에는 배포를 실패시키고, 긴급 시 `workflow_dispatch`의 force 입력으로 우회한다.

**Files:**
- Modify: `.github/workflows/fly-deploy.yml`

- [ ] **Step 1: 트리거에 workflow_dispatch 추가**

`.github/workflows/fly-deploy.yml` 상단 `on:` 블록을 교체:

```yaml
on:
  push:
    branches:
      - main
  workflow_dispatch:
    inputs:
      force:
        description: '매매 시간대 가드를 무시하고 강제 배포'
        required: false
        default: 'false'
```

- [ ] **Step 2: deploy job에 가드 스텝 추가**

`deploy` job의 `steps:` 맨 앞 (checkout 이전)에 추가:

```yaml
      - name: 매매 시간대 배포 가드 (KST)
        if: github.event_name != 'workflow_dispatch' || github.event.inputs.force != 'true'
        run: |
          now=$((10#$(TZ=Asia/Seoul date +%H%M)))
          dow=$(TZ=Asia/Seoul date +%u)   # 1=월 ... 7=일
          blocked=no
          # 개장 스케쥴러 실행 구간: 월~금 22:25~23:45 KST (22:30 기동, 비DST 23:30 개장 접수)
          if [ "$dow" -le 5 ] && [ "$now" -ge 2225 ] && [ "$now" -le 2345 ]; then blocked=yes; fi
          # 마감 스케쥴러 실행 구간: 화~토 04:25~06:20 KST (04:30 기동, 비DST 06:10 리포트)
          if [ "$dow" -ge 2 ] && [ "$dow" -le 6 ] && [ "$now" -ge 425 ] && [ "$now" -le 620 ]; then blocked=yes; fi
          if [ "$blocked" = yes ]; then
            echo "::error::현재 KST $(TZ=Asia/Seoul date +%H:%M)는 매매 스케쥴러 실행 시간대 — 롤링 배포가 sleep 중인 매매 스레드를 강제 종료해 주문 접수가 유실될 수 있어 차단합니다. 장 종료 후 Actions에서 Re-run 하거나, 긴급 시 workflow_dispatch force=true로 강제 배포하세요."
            exit 1
          fi
          echo "매매 시간대 아님 (KST $(TZ=Asia/Seoul date +%H:%M)) — 배포 진행"
```

주의: `$((10#...))`는 `0430` 같은 선행 0 값을 8진수로 오해하지 않게 하는 필수 구문 — 제거 금지.

- [ ] **Step 3: 가드 스크립트 로컬 검증**

가드 로직만 떼어 bash로 시뮬레이션 (시각을 하드코딩해 3케이스 확인):

```bash
check() {
  now=$1; dow=$2; blocked=no
  if [ "$dow" -le 5 ] && [ "$now" -ge 2225 ] && [ "$now" -le 2345 ]; then blocked=yes; fi
  if [ "$dow" -ge 2 ] && [ "$dow" -le 6 ] && [ "$now" -ge 425 ] && [ "$now" -le 620 ]; then blocked=yes; fi
  echo "now=$now dow=$dow → $blocked"
}
check $((10#0430)) 3   # 수요일 04:30 → yes 기대
check $((10#2230)) 1   # 월요일 22:30 → yes 기대
check $((10#1500)) 3   # 수요일 15:00 → no 기대
check $((10#0430)) 1   # 월요일 04:30 → no 기대 (마감 스케쥴러는 화~토)
```

Expected 출력: `yes / yes / no / no`

- [ ] **Step 4: YAML 문법 검증 후 커밋**

Run: `python -c "import yaml; yaml.safe_load(open('.github/workflows/fly-deploy.yml')); print('OK')"`

```bash
git add .github/workflows/fly-deploy.yml
git commit -m "$(cat <<'EOF'
ci: 매매 시간대 배포 가드 — KST 개장·마감 스케쥴러 실행 중 배포 차단

스케쥴러는 in-process sleep으로 주문 시각을 대기하므로 롤링 배포가
스레드를 인터럽트하면 주문 접수가 유실될 수 있다. 해당 시간대 push는
배포를 실패시키고 workflow_dispatch force=true로만 우회 가능.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: TradingReporter 단위 테스트 추가

**배경:** `TradingReporter`는 체결 내역을 주문과 매칭해 FILLED/PARTIALLY_FILLED/CANCELLED를 기록하고 가중평균 체결가를 계산하는 돈 경로인데 단위 테스트가 없다. 현재 동작을 테스트로 고정한다 (**구현 코드 수정 없음** — 열린 질문 2 참고).

**Files:**
- Create: `src/test/java/com/kista/application/service/trading/TradingReporterTest.java`

- [ ] **Step 1: 테스트 작성**

```java
package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserSettingsPort;
import com.kista.domain.port.out.broker.ExecutionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingReporterTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock ExecutionPort executionPort;
    @Mock OrderPort orderPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock CyclePositionPersistor cyclePositionPersistor;
    TradingReporter reporter;

    static final LocalDate TODAY = LocalDate.of(2026, 7, 9);
    static final BigDecimal CLOSE = new BigDecimal("22.00");

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Account.Broker.KIS, null
    );
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE
    );
    static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, TODAY, null, null, null
    );
    static final User USER = new User(
            ACCOUNT.userId(), "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null, NotificationChannel.TELEGRAM
    );
    static final BatchContext CTX = new BatchContext(STRATEGY, CYCLE, ACCOUNT, USER);
    static final AccountBalance BALANCE = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));

    @BeforeEach
    void setUp() {
        reporter = new TradingReporter(registry, orderPort, userNotificationPort,
                realtimeNotificationPort, userSettingsPort, cyclePositionPersistor);
        when(registry.require(ACCOUNT, ExecutionPort.class)).thenReturn(executionPort);
        lenient().when(userSettingsPort.findOrDefault(USER.id()))
                .thenReturn(UserSettings.defaultFor(USER.id())); // TRADING_ALERT 기본 활성
    }

    // PLACED 주문 픽스처 — id·externalOrderId 지정
    private static Order placedOrder(UUID id, String externalOrderId, int quantity) {
        return new Order(id, ACCOUNT.id(), CYCLE.id(), TODAY, Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                quantity, new BigDecimal("20.00"), Order.OrderStatus.PLACED,
                externalOrderId, null, null);
    }

    private static Execution buyExecution(String externalOrderId, int quantity, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Execution(TODAY, Ticker.SOXL, Order.OrderDirection.BUY,
                quantity, p, p.multiply(BigDecimal.valueOf(quantity)), externalOrderId);
    }

    @Test
    void 체결_내역이_없는_PLACED_주문은_CANCELLED_처리된다() {
        UUID unfilledId = UUID.randomUUID();
        UUID filledId = UUID.randomUUID();
        List<Order> orders = List.of(placedOrder(unfilledId, "E-UNFILLED", 5),
                placedOrder(filledId, "E-FILLED", 3));
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT))
                .thenReturn(List.of(buyExecution("E-FILLED", 3, "20.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, orders, null);

        verify(orderPort).markCancelled(unfilledId);
        verify(orderPort).markFilled(filledId, 3, new BigDecimal("20.00"), Order.OrderStatus.FILLED);
    }

    @Test
    void 부분_체결은_PARTIALLY_FILLED와_가중평균가를_기록한다() {
        UUID orderId = UUID.randomUUID();
        List<Order> orders = List.of(placedOrder(orderId, "E1", 10));
        // 3주 × $20.00 = $60.00 + 2주 × $21.00 = $42.00 → 5주, 가중평균 $102.00/5 = $20.40
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT))
                .thenReturn(List.of(buyExecution("E1", 3, "20.00"), buyExecution("E1", 2, "21.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, orders, null);

        verify(orderPort).markFilled(orderId, 5, new BigDecimal("20.40"), Order.OrderStatus.PARTIALLY_FILLED);
    }

    @Test
    void 체결이_전혀_없으면_주문_상태를_변경하지_않는다() {
        // 현재 동작: executions가 비면 조기 반환 — PLACED 주문이 CANCELLED되지 않고 잔류 (동작 고정)
        List<Order> orders = List.of(placedOrder(UUID.randomUUID(), "E1", 5));
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, orders, null);

        verify(orderPort, never()).markCancelled(any());
        verify(orderPort, never()).markFilled(any(), anyInt(), any(), any());
    }

    @Test
    void TRADING_ALERT_비활성이면_리포트를_발송하지_않는다() {
        UserSettings muted = mock(UserSettings.class);
        when(muted.isNotificationEnabled(NotificationType.TRADING_ALERT)).thenReturn(false);
        when(userSettingsPort.findOrDefault(USER.id())).thenReturn(muted);
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(), null);

        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void 체결_건별로_SSE_실시간_알림을_발송한다() {
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT))
                .thenReturn(List.of(buyExecution("E1", 3, "20.00"), buyExecution("E2", 2, "21.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(), null);

        verify(realtimeNotificationPort, times(2)).notifyTrade(eq(USER.id()), any());
    }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `bash gradlew test --tests 'com.kista.application.service.trading.TradingReporterTest'`
Expected: 5건 모두 PASS.

컴파일 오류 시 대조할 것 (테스트 코드를 실제 시그니처에 맞출 것 — 구현 수정 금지):
- `UserSettings.defaultFor(UUID)` / `isNotificationEnabled(NotificationType)` 존재 여부 → `domain/model/user/UserSettings.java`
- `AccountBalance(int holdings, BigDecimal avgPrice, BigDecimal usdDeposit)` 생성자 순서 → `domain/model/strategy/AccountBalance.java`
- `BatchContext(Strategy, StrategyCycle, Account, User)` 순서 → `domain/model/strategy/BatchContext.java`
- `Account`/`User`/`StrategyCycle` 생성자는 `TradingServiceTest.java:75-102`의 픽스처와 동일 패턴

- [ ] **Step 3: 전체 테스트 회귀 확인 후 커밋**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL

```bash
git add src/test/java/com/kista/application/service/trading/TradingReporterTest.java
git commit -m "$(cat <<'EOF'
test(trading): TradingReporter 체결 매칭 단위 테스트 추가

미체결→CANCELLED, 부분체결 가중평균가, 체결 0건 조기 반환,
TRADING_ALERT 비활성, SSE 발송 — 미검증이던 돈 경로 동작을 고정.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 스케쥴러 시각 문서 드리프트 수정

**배경:** 코드의 cron은 개장 22:30 / 마감 04:30 KST인데 (`TradingOpenScheduler.java:38`, `TradingCloseScheduler.java:26`), `docs/agents/architecture.md`와 `docs/agents/workflow.md`는 22:00/04:00으로 기재 — 자동 로드되는 AI 컨텍스트라 잘못된 시각이 후속 작업 판단을 오염시킨다.

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/workflow.md`

- [ ] **Step 1: 현재 코드의 cron 시각 확인 (SSOT)**

Run: `grep -n "@Scheduled" src/main/java/com/kista/adapter/in/schedule/TradingOpenScheduler.java src/main/java/com/kista/adapter/in/schedule/TradingCloseScheduler.java`
Expected: open = `0 30 22 * * MON-FRI` (22:30), close = `0 30 4 * * TUE-SAT` (04:30). 이 값이 다르면 아래 수정치를 코드 기준으로 맞출 것.

- [ ] **Step 2: architecture.md 수정**

`docs/agents/architecture.md` 65~66행:
- `TradingOpenScheduler (월~금 22:00 KST` → `TradingOpenScheduler (월~금 22:30 KST`
- `TradingCloseScheduler (화~토 04:00 KST — 장마감 30분 전,` → `TradingCloseScheduler (화~토 04:30 KST — DST 장마감 30분 전,`

주의: 148행의 "스케쥴러는 KST 04:00 실행 → preview() today 오프셋" 문구는 `DstInfo.SCHEDULER_RUN_TIME`(04:00 경계값)을 설명하는 것으로 **수정 금지**. 다만 헷갈리지 않게 해당 행의 `스케쥴러는 KST 04:00 실행`을 `날짜 경계는 KST 04:00 (DstInfo.SCHEDULER_RUN_TIME)`으로 교체.

- [ ] **Step 3: workflow.md 수정**

`docs/agents/workflow.md` 2행:
- `화~토 04:00 KST (미국 장마감 30분 전)` → `화~토 04:30 KST (DST 장마감 30분 전, 비DST는 orderAt 05:30까지 대기)`

- [ ] **Step 4: 잔존 드리프트 grep 확인**

Run: `grep -rn "22:00\|04:00" docs/agents CLAUDE.md`
Expected: 남는 것은 `DstInfo.SCHEDULER_RUN_TIME`(04:00 날짜 경계) 관련 서술과 `RefreshTokenCleanupScheduler (매일 04:00 KST)` 뿐이어야 함. RefreshToken 스케쥴러 cron도 검증: `grep -n "@Scheduled" src/main/java/com/kista/adapter/in/schedule/RefreshTokenCleanupScheduler.java` — 문서와 다르면 코드 기준으로 문서 수정.

- [ ] **Step 5: 커밋**

```bash
git add docs/agents/architecture.md docs/agents/workflow.md
git commit -m "$(cat <<'EOF'
docs(agents): 스케쥴러 시각 드리프트 수정 — 코드 cron(22:30/04:30) 기준으로 정정

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 저장소 청소 — 구식·잔여 파일 제거

**배경:** `shrimp-rules.md`(297줄)는 폐기된 shrimp 태스크 매니저의 규칙 파일로, 존재하지 않는 `trade_histories` 테이블·폐기된 OCI 배포를 기술한다 — AI 에이전트가 읽으면 잘못된 컨텍스트를 얻는 **활성 위험**. `.shrimp-data/`도 동일 잔재. `package-lock.json`은 빈 파일(untracked), `supabase/`는 빈 디렉토리.

**Files:**
- Delete: `shrimp-rules.md`, `.shrimp-data/` (git 추적됨)
- Delete: `package-lock.json` (untracked), `supabase/` (빈 디렉토리)
- Modify: `.gitignore`
- ⚠️ `qodana.yaml`은 열린 질문 1 답변 전까지 **삭제 보류**

- [ ] **Step 1: 삭제 전 참조 확인**

Run: `grep -rn "shrimp" --include="*.md" --include="*.yml" --include="*.json" . --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules | grep -v shrimp-rules.md | grep -v .shrimp-data`
Expected: 출력 없음 (참조 없음). 참조가 나오면 삭제 중단하고 사용자에게 보고.

- [ ] **Step 2: 파일 삭제**

```bash
git rm shrimp-rules.md
git rm -r .shrimp-data
rm -f package-lock.json
rmdir supabase 2>/dev/null || true
```

- [ ] **Step 3: .gitignore에 npm 잔재 방지 추가**

`.gitignore` 끝에 추가:

```
# npm 잔재 (Java 프로젝트 — node 의존성 없음)
package-lock.json
node_modules/
```

- [ ] **Step 4: 빌드 무결성 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL (삭제 파일은 빌드와 무관하므로 형식적 확인)

- [ ] **Step 5: 커밋**

```bash
git add .gitignore
git commit -m "$(cat <<'EOF'
chore: 폐기된 shrimp 잔재 제거 — 구식 규칙 파일이 AI 컨텍스트 오염 유발

shrimp-rules.md는 존재하지 않는 trade_histories 테이블과 폐기된 OCI 배포를
기술해 에이전트가 읽으면 잘못된 전제를 얻는다. .shrimp-data, 빈
package-lock.json도 함께 제거하고 npm 잔재를 gitignore에 추가.

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## 최종 검증 체크리스트 (모든 Task 완료 후)

- [ ] `bash gradlew clean compileJava compileTestJava` → BUILD SUCCESSFUL
- [ ] `bash gradlew test` → 전체 PASS (실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`)
- [ ] `bash gradlew test --tests 'com.kista.architecture.*'` → ArchUnit PASS
- [ ] `git log --oneline -7` → Task별 커밋 6건 확인, author `narafu <narafu@kakao.com>`
- [ ] push는 하지 않았음을 확인 (사용자 요청 시에만)
- [ ] 사용자에게 보고: 완료 내역 + 열린 질문 1~5 중 기본값으로 처리한 항목 명시
