# 스케쥴러 프로세스 분리 (같은 이미지 2-role) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `kista-api` Docker 이미지 하나를 컨테이너 2개(API role / Scheduler role)로 띄워 매매 배치 실행을 HTTP API와 별도 프로세스로 분리한다.

**Architecture:** 코드는 이동하지 않는다. `SCHEDULER_ENABLED` 환경변수 하나로 9개 `@Scheduled` 빈의 등록 여부를 가른다(`@ConditionalOnProperty`는 이미 전부 붙어 있음). `application-prod.yml`이 API role 기본값(스케쥴러 off + Modulith 이벤트 재발행 off), `deploy/server/docker-compose.yml`의 `kista-scheduler` 서비스가 환경변수로 override한다. 배포 워크플로는 role별 독립 잡으로 분리하고 매매 시간대 가드는 스케쥴러 잡에만 적용한다.

**Tech Stack:** Spring Boot 4, Spring Modulith, Docker Compose, GitHub Actions, Caddy(kista-infra 레포), PostgreSQL(`scheduler_locks` 분산 락 — 기존).

**Spec:** `docs/superpowers/specs/2026-09-04-scheduler-process-separation-design.md`

## Global Constraints

- 커밋 author: `narafu <narafu@kakao.com>`. 커밋 메시지 한글 + Conventional Commit 접두사(`feat(scope):`/`fix:`/`docs:`/`chore:`). `git push`는 사용자 명시 요청 시에만.
- **코드 이동 0, 신규 Java 파일 0** (테스트 제외) — ArchUnit·Modulith `verify()` 무영향이 이 작업의 전제. `src/main/java` 아래 프로덕션 파일을 만들거나 옮기지 않는다.
- `@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)` 9개는 **무변경** — `matchIfMissing`은 프로퍼티 명시 후 사실상 no-op이 되지만 local yml에서 생략하는 개발자를 위해 유지.
- Modulith 이벤트 재발행 프로퍼티 정확한 키: `spring.modulith.events.republish-outstanding-events-on-restart` (현재 `application.yml`에서 `spring.modulith.events` 하위, `.jdbc` 아님).
- `application-local.yml`·`application-test.yml`은 이 작업에서 **변경 없음** — 기본값 `${SCHEDULER_ENABLED:true}`가 두 프로파일의 기존 동작(스케쥴러 로드)을 유지한다.
- Flyway 마이그레이션 expand/contract 규율: 2-role 배포 후 마이그레이션은 직전 배포 이미지와 backward-compatible해야 함. 이 규율 위반 마이그레이션을 실은 배포는 두 role 함께 배포. (이 플랜에서 마이그레이션을 추가하지는 않음 — 문서화만.)

---

## File Structure

| 파일 | 역할 | 변경 |
|---|---|---|
| `src/main/resources/application.yml` | 공통 설정 | `scheduler.enabled: ${SCHEDULER_ENABLED:true}` 명시 바인딩 추가 |
| `src/main/resources/application-prod.yml` | prod = API role 기본값 | `scheduler.enabled: false` + `spring.modulith.events.republish-outstanding-events-on-restart: false` 추가 |
| `src/test/java/com/kista/SchedulerRoleConfigTest.java` | `application-prod.yml` 값 회귀 방지 (yaml 파싱, 컨텍스트 없음) | 신규 (테스트) |
| `src/test/java/com/kista/SchedulerDisabledContextTest.java` | `scheduler.enabled=false` 시 9개 스케쥴러 빈 미등록 검증 (`@SpringBootTest`) | 신규 (테스트) |
| `deploy/server/docker-compose.yml` | 운영 컨테이너 정의 | `kista-scheduler` 서비스 추가 |
| `.github/workflows/_deploy-role.yml` | role별 배포 재사용 워크플로 | 신규 |
| `.github/workflows/server-deploy.yml` | 오케스트레이션 | `deploy` 잡 → `deploy-api` + `deploy-scheduler` 두 호출로 분리 |
| `docs/agents/scheduler-time-table.md` | 스케쥴러 시간표 | 스탈 경로 정정 + 2-role 한 줄 |
| `docs/agents/docker-infra.md` | 인프라 런북 | "서버 배포 방식"에 2-role 토폴로지 |
| `docs/agents/constraints.md` | 제약 SSOT | Flyway expand/contract 규율 + 매매 가드 범위 |
| `docs/agents/architecture.md` | 아키텍처 맵 | 캘린더 부트스트랩 staleness 명시 |
| `README.md` | 최상위 문서 | 배포 다이어그램·설명에 스케쥴러 role |
| `/Users/phs/workspace/kista/CLAUDE.md` | 루트 멀티레포 문서 | 스탈 "Spring Boot 3 / Fly.io" 정정 + 2-role 한 줄 |

---

## Task 1: 역할 게이트 config + EPR 소유권 + 테스트

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-prod.yml`
- Test: `src/test/java/com/kista/SchedulerRoleConfigTest.java` (create)
- Test: `src/test/java/com/kista/SchedulerDisabledContextTest.java` (create)

**Interfaces:**
- Consumes: 기존 9개 스케쥴러 클래스의 `@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)`.
- Produces:
  - 프로퍼티 `scheduler.enabled` — env `SCHEDULER_ENABLED` 바인딩, 기본 `true`. `false`면 9개 스케쥴러 빈 미등록.
  - `application-prod.yml`에서 `scheduler.enabled=false`, `spring.modulith.events.republish-outstanding-events-on-restart=false`.
  - Task 2가 `deploy/server/docker-compose.yml`의 `kista-scheduler` 서비스에서 `SCHEDULER_ENABLED=true`, `SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART=true`로 되켬.

- [ ] **Step 1: 실패하는 테스트 작성 — `application-prod.yml` 값 회귀 방지**

`src/test/java/com/kista/SchedulerRoleConfigTest.java`:

```java
package com.kista;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

// application-prod.yml = API role 기본값.
// 스케쥴러 off + Modulith 이벤트 재발행 off 두 값이 실수로 지워지면
// (1) API 컨테이너가 스케쥴러를 돌려 운영 DB·텔레그램 중복 실행
// (2) API·스케쥴러 양쪽이 event_publication 미완료 행을 재발행해 새벽 알림 2번
// 이 두 사고를 막는 회귀 테스트. 컨텍스트 로드 없이 yaml만 파싱한다.
class SchedulerRoleConfigTest {

    private Properties prodYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();
        return props;
    }

    @Test
    void prod_disablesScheduler() {
        assertThat(prodYaml().getProperty("scheduler.enabled")).isEqualTo("false");
    }

    @Test
    void prod_disablesModulithEventRepublish() {
        assertThat(prodYaml().getProperty("spring.modulith.events.republish-outstanding-events-on-restart"))
                .isEqualTo("false");
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests 'com.kista.SchedulerRoleConfigTest'`
Expected: FAIL — 두 프로퍼티 모두 `null` (아직 `application-prod.yml`에 없음), `isEqualTo("false")` 불일치.

- [ ] **Step 3: `application.yml`에 명시 바인딩 추가**

`src/main/resources/application.yml` — 최상위 키 `springdoc:` 위(또는 파일 내 논리적 위치)에 추가:

```yaml
# 역할 게이트 — kista-api(HTTP) / kista-scheduler(배치) 같은 이미지 2-role 분리
# env 미설정 시 true (local·test 기존 동작 유지). prod는 application-prod.yml에서 false로 덮음
scheduler:
  enabled: ${SCHEDULER_ENABLED:true}
```

- [ ] **Step 4: `application-prod.yml`에 API role 기본값 추가**

`src/main/resources/application-prod.yml` — 기존 `spring:` 블록 안에 `modulith` 추가, 최상위에 `scheduler` 추가:

```yaml
spring:
  # ... 기존 datasource/jpa/lifecycle 유지 ...
  modulith:
    events:
      # EPR 재발행 소유자 = kista-scheduler 단독. API role은 재발행 안 함
      # (양쪽 true면 미완료 event_publication 행을 둘 다 claim → 중복 알림)
      # kista-scheduler 서비스가 SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART=true로 되켬
      republish-outstanding-events-on-restart: false

# prod 기본은 API role — 스케쥴러 미등록. kista-scheduler 서비스가 SCHEDULER_ENABLED=true로 override
scheduler:
  enabled: false
```

주의: `application-prod.yml`의 기존 `spring:` 블록에 이미 `datasource`/`jpa`/`lifecycle` 자식이 있으므로 `modulith:`를 같은 들여쓰기 레벨로 추가한다. 새 `spring:` 블록을 또 만들지 않는다.

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew test --tests 'com.kista.SchedulerRoleConfigTest'`
Expected: PASS.

- [ ] **Step 6: 실패하는 테스트 작성 — `scheduler.enabled=false` 시 빈 미등록**

`src/test/java/com/kista/SchedulerDisabledContextTest.java`:

```java
package com.kista;

import com.kista.finance.adapter.in.schedule.FinanceRegistrationReminderScheduler;
import com.kista.market.adapter.in.schedule.FearGreedScheduler;
import com.kista.market.adapter.in.schedule.MarketCalendarRefreshScheduler;
import com.kista.stats.adapter.in.schedule.KbLandHousingBenchmarkScheduler;
import com.kista.stats.adapter.in.schedule.KbLandPriceIndexScheduler;
import com.kista.stats.adapter.in.schedule.MarketIndexPriceSyncScheduler;
import com.kista.trading.adapter.in.schedule.TradingCloseScheduler;
import com.kista.trading.adapter.in.schedule.TradingOpenScheduler;
import com.kista.user.adapter.in.schedule.RefreshTokenCleanupScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// API role(scheduler.enabled=false) 시 9개 @Scheduled 빈이 컨텍스트에 전혀 없어야 한다.
// @ConditionalOnProperty가 실제 컨텍스트 로드에서 작동하는지 end-to-end 검증
// (AdminSchedulerControllerDisabledTest는 @WebMvcTest라 빈을 안 mock할 뿐, 조건 자체는 안 탄다).
@SpringBootTest(properties = "scheduler.enabled=false")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class SchedulerDisabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void 스케쥴러_비활성_시_모든_스케쥴러_빈이_미등록된다() {
        Class<?>[] schedulers = {
                TradingOpenScheduler.class, TradingCloseScheduler.class,
                FearGreedScheduler.class, MarketCalendarRefreshScheduler.class,
                KbLandHousingBenchmarkScheduler.class, KbLandPriceIndexScheduler.class,
                MarketIndexPriceSyncScheduler.class, RefreshTokenCleanupScheduler.class,
                FinanceRegistrationReminderScheduler.class,
        };
        for (Class<?> type : schedulers) {
            assertThat(context.getBeanNamesForType(type))
                    .as("%s 는 scheduler.enabled=false 에서 미등록이어야 한다", type.getSimpleName())
                    .isEmpty();
        }
    }
}
```

- [ ] **Step 7: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests 'com.kista.SchedulerDisabledContextTest'`
Expected: FAIL 이 아니라 PASS 가능성 있음 — Step 3~4로 이미 `scheduler.enabled` 프로퍼티가 도입됐고 `properties = "scheduler.enabled=false"`가 먹으므로. 만약 Step 6을 Step 3 전에 작성했다면 `matchIfMissing=true` + 프로퍼티 부재로 빈이 등록돼 FAIL. **현재 순서(Step 3~4 이후)에서는 이 테스트가 바로 PASS 해도 정상** — 회귀 안전망 목적. FAIL을 강제로 보려면 임시로 `application.yml`의 `${SCHEDULER_ENABLED:true}`를 지우고 확인 후 되돌린다(선택).

- [ ] **Step 8: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: PASS. 특히 확인:
- `ApplicationContextLoadTest` — test 프로파일, `scheduler.enabled` 미설정 → `${SCHEDULER_ENABLED:true}` → `true` → 스케쥴러 로드, 기존과 동일.
- `AdminSchedulerControllerDisabledTest` / `AdminSchedulerControllerTest` — 무영향.
- `EventPublicationRegistryTest` — test 프로파일, `application-prod.yml` 미적용 → 재발행 값 무영향.
- ArchUnit(`com.kista.architecture.*`) / Modulith `ModulithArchitectureTest` — 코드 이동 0이라 GREEN.

실패 진단 시: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`

- [ ] **Step 9: 커밋**

```bash
git add src/main/resources/application.yml src/main/resources/application-prod.yml \
        src/test/java/com/kista/SchedulerRoleConfigTest.java \
        src/test/java/com/kista/SchedulerDisabledContextTest.java
git -c user.name=narafu -c user.email=narafu@kakao.com commit -m "feat(scheduler): SCHEDULER_ENABLED 역할 게이트 + prod EPR 재발행 소유자 고정

- application.yml: scheduler.enabled: \${SCHEDULER_ENABLED:true} 명시 바인딩
- application-prod.yml: API role 기본값 — scheduler.enabled=false,
  spring.modulith.events.republish-outstanding-events-on-restart=false
- kista-scheduler 컨테이너가 env로 둘 다 되켬 (Task 2)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019S4k94fsBinHuSfDMSgXXt"
```

---

## Task 2: `kista-scheduler` 컨테이너 정의

**Files:**
- Modify: `deploy/server/docker-compose.yml`

**Interfaces:**
- Consumes: Task 1의 `scheduler.enabled` / `spring.modulith.events.republish-outstanding-events-on-restart` 프로퍼티. `${KISTA_API_IMAGE}` 환경변수(배포 워크플로가 주입, kista-api와 동일 이미지).
- Produces: 운영 서비스 `kista-scheduler` (Task 3의 `deploy-scheduler` 잡이 `docker compose up -d --no-deps kista-scheduler`로 배포, healthcheck 대상).

- [ ] **Step 1: `kista-scheduler` 서비스 추가**

`deploy/server/docker-compose.yml` — `services:` 아래 `kista-api` 다음에 추가:

```yaml
  # 스케쥴러 role — kista-api와 동일 이미지, SCHEDULER_ENABLED=true 로 @Scheduled 빈 등록
  # 매매 배치(sleep·주문 접수)가 이 프로세스에서 실행됨. HTTP는 /api/admin/scheduler/* + actuator 용도로만 기동
  kista-scheduler:
    image: ${KISTA_API_IMAGE:?KISTA_API_IMAGE is required}
    container_name: kista-scheduler
    env_file:
      - .env
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SCHEDULER_ENABLED: "true"
      # EPR 미완료 이벤트 재발행 소유자 = 이 컨테이너 단독 (application-prod.yml 은 false)
      SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: "true"
      REDIS_URL: redis://redis:6379
      # 요청 부하 없는 배치 전용 — 힙 작게. OOM 회피가 중요(OOM-kill 시 scheduler_locks 락 2h 홀드)
      JAVA_OPTS: "-Xmx1536m -Xms128m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:+UseG1GC -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"
    expose:
      - "8080"
    networks:
      - shared_net   # Caddy 가 /api/admin/scheduler/* 라우팅 (kista-infra)
      - data_net     # postgres/redis
    stop_grace_period: 200s   # 매매 배치 인터럽트 → SchedulerJobRunner FAILED 이벤트 발행 + rethrow → 락 즉시 해제까지 여유
    mem_limit: 2048m
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health/liveness"]
      interval: 30s
      timeout: 10s
      start_period: 180s
      retries: 3
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "5"
```

- [ ] **Step 2: `kista-api` 서비스에 문서용 env 추가 (선택, 권장)**

`kista-api` 서비스의 `environment:` 블록에 명시성 위해 추가 (동작상 `application-prod.yml`이 이미 처리하므로 필수 아님):

```yaml
      SCHEDULER_ENABLED: "false"   # 문서용 — application-prod.yml 이 이미 false
```

- [ ] **Step 3: compose 문법 검증**

Run:
```bash
cd deploy/server && \
KISTA_API_IMAGE=ghcr.io/narafu/kista-api:test \
docker compose --env-file /dev/null config 2>&1 | head -40
```
Expected: 두 서비스(`kista-api`, `kista-scheduler`)가 렌더링됨. `external: true` 네트워크 경고는 무시(런타임에 kista-infra가 생성). `.env` 부재 경고도 무시.
검증 불가 환경이면(도커 없음) `python3 -c "import yaml,sys; yaml.safe_load(open('deploy/server/docker-compose.yml'))"` 로 YAML 유효성만 확인.

- [ ] **Step 4: 커밋**

```bash
git add deploy/server/docker-compose.yml
git -c user.name=narafu -c user.email=narafu@kakao.com commit -m "feat(deploy): kista-scheduler 컨테이너 추가 — 같은 이미지 2-role

- SCHEDULER_ENABLED=true, EPR 재발행 true, -Xmx1536m/mem_limit 2048m
- shared_net(Caddy)+data_net(DB/Redis), stop_grace_period 200s
- 메모리 근거: 실측 컨테이너 RSS 합 ~750MiB, available 10.4GB

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019S4k94fsBinHuSfDMSgXXt"
```

---

## Task 3: 배포 워크플로 role별 분리

**Files:**
- Create: `.github/workflows/_deploy-role.yml`
- Modify: `.github/workflows/server-deploy.yml`

**Interfaces:**
- Consumes: `build` 잡의 출력 `image`(GHCR SHA 태그). GitHub Secrets `SERVER_SSH_KEY`/`SERVER_HOST`/`SERVER_USER`/`SERVER_SSH_PORT`.
- Produces: 두 독립 배포 잡 `deploy-api`(가드 없음, `concurrency: server-api`), `deploy-scheduler`(매매 시간대 가드, `concurrency: server-scheduler`). role별 prev-image 경로(`/tmp/kista-<role>-prev-image`), role별 `docker compose` 서비스 타겟.

- [ ] **Step 1: 재사용 워크플로 `_deploy-role.yml` 작성**

`.github/workflows/_deploy-role.yml` — 현재 `server-deploy.yml`의 `deploy` 잡 스텝(가드·SSH·업로드·재시작·헬스게이트)을 role 파라미터화해 이관:

```yaml
name: _deploy-role

on:
  workflow_call:
    inputs:
      image:
        description: '배포할 GHCR 이미지 (SHA 태그)'
        required: true
        type: string
      service:
        description: 'docker compose 서비스명 (kista-api | kista-scheduler)'
        required: true
        type: string
      apply_trading_guard:
        description: '매매 시간대 배포 가드 적용 여부'
        required: true
        type: boolean
      force:
        description: 'workflow_dispatch force 입력 (가드 무시)'
        required: false
        type: string
        default: 'false'

env:
  DEPLOY_PATH: /opt/kista-api

jobs:
  deploy:
    name: Deploy ${{ inputs.service }}
    runs-on: ubuntu-latest
    concurrency: server-${{ inputs.service }}
    environment:
      name: production
    steps:
      - name: 매매 시간대 배포 가드 (KST)
        if: inputs.apply_trading_guard && inputs.force != 'true'
        run: |
          now=$((10#$(TZ=Asia/Seoul date +%H%M)))
          dow=$(TZ=Asia/Seoul date +%u)   # 1=월 ... 7=일
          blocked=no
          # 개장 스케쥴러 실행 구간: 월~금 22:20~23:40 KST
          if [ "$dow" -le 5 ] && [ "$now" -ge 2220 ] && [ "$now" -le 2340 ]; then blocked=yes; fi
          # 마감 스케쥴러 실행 구간: 화~토 04:20~06:20 KST
          if [ "$dow" -ge 2 ] && [ "$dow" -le 6 ] && [ "$now" -ge 420 ] && [ "$now" -le 620 ]; then blocked=yes; fi
          if [ "$blocked" = yes ]; then
            echo "::error::현재 KST $(TZ=Asia/Seoul date +%H:%M)는 매매 스케쥴러 실행 시간대 — kista-scheduler 배포가 sleep 중인 매매 스레드를 강제 종료해 주문 접수가 유실될 수 있어 차단합니다. 장 종료 후 이 잡만 Re-run 하거나, 긴급 시 workflow_dispatch force=true 로 강제 배포하세요."
            exit 1
          fi
          echo "매매 시간대 아님 (KST $(TZ=Asia/Seoul date +%H:%M)) — 배포 진행"

      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4.4.0

      - name: Configure SSH
        env:
          SERVER_SSH_KEY: ${{ secrets.SERVER_SSH_KEY }}
          SERVER_HOST: ${{ secrets.SERVER_HOST }}
          SERVER_SSH_PORT: ${{ secrets.SERVER_SSH_PORT || '22' }}
        run: |
          install -m 700 -d ~/.ssh
          printf '%s\n' "$SERVER_SSH_KEY" > ~/.ssh/deploy_key
          chmod 600 ~/.ssh/deploy_key
          ssh-keyscan -p "$SERVER_SSH_PORT" "$SERVER_HOST" >> ~/.ssh/known_hosts

      - name: Upload deployment files
        env:
          SERVER_HOST: ${{ secrets.SERVER_HOST }}
          SERVER_USER: ${{ secrets.SERVER_USER }}
          SERVER_SSH_PORT: ${{ secrets.SERVER_SSH_PORT || '22' }}
        run: |
          ssh -i ~/.ssh/deploy_key -p "$SERVER_SSH_PORT" "$SERVER_USER@$SERVER_HOST" "mkdir -p '${DEPLOY_PATH}'"
          scp -i ~/.ssh/deploy_key -P "$SERVER_SSH_PORT" \
            deploy/server/docker-compose.yml \
            "$SERVER_USER@$SERVER_HOST:${DEPLOY_PATH}/"

      - name: Verify .env and restart ${{ inputs.service }}
        env:
          SERVER_HOST: ${{ secrets.SERVER_HOST }}
          SERVER_USER: ${{ secrets.SERVER_USER }}
          SERVER_SSH_PORT: ${{ secrets.SERVER_SSH_PORT || '22' }}
          KISTA_API_IMAGE: ${{ inputs.image }}
          SERVICE: ${{ inputs.service }}
          GHCR_USERNAME: ${{ github.actor }}
          GHCR_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          ssh -i ~/.ssh/deploy_key -p "$SERVER_SSH_PORT" "$SERVER_USER@$SERVER_HOST" \
            "DEPLOY_PATH='${DEPLOY_PATH}' KISTA_API_IMAGE='${KISTA_API_IMAGE}' SERVICE='${SERVICE}' GHCR_USERNAME='${GHCR_USERNAME}' GHCR_TOKEN='${GHCR_TOKEN}' bash -s" << 'ENDSSH'
            set -e
            cd "${DEPLOY_PATH}"

            for key in DB_URL DB_USERNAME DB_PASSWORD JWT_SIGNING_KEY AES_ENCRYPTION_KEY KAKAO_CLIENT_ID CORS_ALLOWED_ORIGINS TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID API_DOMAIN; do
              grep -q "^${key}=" .env || { echo "::error::필수 환경변수 누락: ${key}"; exit 1; }
            done

            # role별 롤백용 이전 이미지 기록
            PREV_IMAGE=$(docker compose ps "${SERVICE}" --format '{{.Image}}' 2>/dev/null || echo "")
            echo "$PREV_IMAGE" > "/tmp/kista-${SERVICE}-prev-image"

            echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin
            export KISTA_API_IMAGE
            docker compose pull "${SERVICE}"

            for net in shared_net data_net; do docker network create "$net" 2>/dev/null || true; done

            docker compose up -d --no-deps "${SERVICE}"
          ENDSSH

      - name: Health gate & auto-rollback (${{ inputs.service }})
        env:
          SERVER_HOST: ${{ secrets.SERVER_HOST }}
          SERVER_USER: ${{ secrets.SERVER_USER }}
          SERVER_SSH_PORT: ${{ secrets.SERVER_SSH_PORT || '22' }}
          SERVICE: ${{ inputs.service }}
        run: |
          ssh -i ~/.ssh/deploy_key -p "$SERVER_SSH_PORT" "$SERVER_USER@$SERVER_HOST" \
            "DEPLOY_PATH='${DEPLOY_PATH}' SERVICE='${SERVICE}' bash -s" << 'ENDSSH'
            set -e
            cd "${DEPLOY_PATH}"

            echo "헬스 게이트 시작 — ${SERVICE} (최대 5분)..."
            for i in $(seq 1 30); do
              STATUS=$(docker inspect --format '{{.State.Health.Status}}' "${SERVICE}" 2>/dev/null || echo "unknown")
              if [ "$STATUS" = "healthy" ]; then
                echo "✓ ${SERVICE} 헬스체크 통과 (${i}회 시도)"
                docker exec "${SERVICE}" wget -qO- http://localhost:8080/actuator/health || echo "  (전체 헬스 관측 실패 — 배포는 계속, DB/Redis 확인)"
                docker image prune -f || true
                exit 0
              fi
              if [ "$STATUS" = "unhealthy" ]; then
                echo "✗ ${SERVICE} 헬스체크 실패 (unhealthy, ${i}회)"
                break
              fi
              echo "  대기 중... (${i}/30, 상태: ${STATUS})"
              sleep 10
            done

            PREV_IMAGE=$(cat "/tmp/kista-${SERVICE}-prev-image" 2>/dev/null || echo "")
            if [ -n "$PREV_IMAGE" ]; then
              echo "✗ ${SERVICE} 헬스체크 실패 — 이전 이미지로 롤백: $PREV_IMAGE"
              export KISTA_API_IMAGE="$PREV_IMAGE"
              docker compose up -d --no-deps "${SERVICE}"
            else
              echo "✗ ${SERVICE} 헬스체크 실패 — 이전 이미지 정보 없음, 수동 복구 필요"
            fi
            exit 1
          ENDSSH
```

주의: `Health gate` 스텝은 `~/.ssh/deploy_key`를 재사용하므로 `Configure SSH` 스텝과 같은 잡에 있어야 한다(현재 구조 유지 — 이관만).

- [ ] **Step 2: `server-deploy.yml`에서 `deploy` 잡을 두 호출로 교체**

`.github/workflows/server-deploy.yml` — 기존 `deploy:` 잡 전체를 삭제하고 아래로 교체:

```yaml
  deploy-api:
    name: Deploy API
    needs: build
    uses: ./.github/workflows/_deploy-role.yml
    with:
      image: ${{ needs.build.outputs.image }}
      service: kista-api
      apply_trading_guard: false
      force: ${{ github.event.inputs.force || 'false' }}
    secrets: inherit

  deploy-scheduler:
    name: Deploy Scheduler
    needs: build
    uses: ./.github/workflows/_deploy-role.yml
    with:
      image: ${{ needs.build.outputs.image }}
      service: kista-scheduler
      apply_trading_guard: true
      force: ${{ github.event.inputs.force || 'false' }}
    secrets: inherit
```

`verify`·`build` 잡은 무변경. `permissions:`(`contents: read`, `packages: write`)도 무변경 — 재사용 워크플로는 `secrets: inherit`로 호출자 권한 상속.

- [ ] **Step 3: 워크플로 문법 검증**

Run: `actionlint .github/workflows/server-deploy.yml .github/workflows/_deploy-role.yml`
(actionlint 없으면 `python3 -c "import yaml; [yaml.safe_load(open(f)) for f in ['.github/workflows/server-deploy.yml','.github/workflows/_deploy-role.yml']]"` 로 YAML 유효성만.)
Expected: 오류 없음. `workflow_call` inputs 참조·`secrets: inherit`·`concurrency` 표현식 확인.

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/_deploy-role.yml .github/workflows/server-deploy.yml
git -c user.name=narafu -c user.email=narafu@kakao.com commit -m "feat(deploy): 배포 잡을 role별로 분리 — API는 가드 없음, Scheduler만 매매 가드

- _deploy-role.yml 재사용 워크플로 신설 (service/guard/prev-image 파라미터화)
- deploy-api (concurrency server-kista-api, 가드 없음)
- deploy-scheduler (concurrency server-kista-scheduler, 매매 시간대 exit 1)
- 매매 시간대 push 시 deploy-api 성공 / deploy-scheduler 실패 → 장 마감 후 해당 잡 re-run

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019S4k94fsBinHuSfDMSgXXt"
```

---

## Task 4: 문서 갱신

**Files:**
- Modify: `docs/agents/scheduler-time-table.md`
- Modify: `docs/agents/docker-infra.md`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/agents/architecture.md`
- Modify: `README.md`
- Modify: `/Users/phs/workspace/kista/CLAUDE.md`

**Interfaces:** 없음 (문서 전용).

- [ ] **Step 1: `scheduler-time-table.md` — 스탈 경로 정정 + 2-role**

문서 상단 `이 문서는 \`src/main/java/com/kista/adapter/in/schedule\` 기준의 배치 실행 시점을 정리한다.` 를 아래로 교체:

```markdown
이 문서는 각 모듈 `adapter/in/schedule` 패키지의 배치 실행 시점을 정리한다.
모든 스케줄러는 `kista-scheduler` 컨테이너(같은 이미지, `SCHEDULER_ENABLED=true`)에서만 실행되며,
`kista-api` 컨테이너는 `scheduler.enabled=false`로 스케줄러를 등록하지 않는다.
```

"참고" 섹션의 `모든 스케줄러는 \`scheduler.enabled=true\`일 때만 동작한다.` 는 그대로 두되 뒤에 `— 운영에서는 kista-scheduler 컨테이너만 이 값이 true다.` 추가.

- [ ] **Step 2: `docker-infra.md` — "서버 배포 방식 (현재 OCI)"에 2-role 추가**

`.github/workflows/server-deploy.yml` 를 설명하는 불릿 다음에 새 불릿 추가:

```markdown
- **2-role 배포 (2026-09-04~)**: 같은 GHCR 이미지를 컨테이너 2개로 띄운다 — `kista-api`(HTTP, `scheduler.enabled=false`, 매매 가드 없이 잦은 배포)와 `kista-scheduler`(`SCHEDULER_ENABLED=true`, 매매 배치 실행, 매매 시간대 배포 가드 유지). `server-deploy.yml`은 `verify`·`build` 후 `deploy-api`·`deploy-scheduler` 두 독립 잡(`_deploy-role.yml` 재사용 워크플로)을 호출한다. 매매 시간대에 push하면 `deploy-api`는 통과, `deploy-scheduler`만 `exit 1` — 장 마감 후 Actions에서 해당 잡만 Re-run. EPR 미완료 이벤트 재발행 소유자는 `kista-scheduler` 단독(`application-prod.yml`이 API role은 `false`, 스케쥴러 컨테이너가 env로 `true`) — 양쪽 재발행 시 중복 알림 방지. 수동 트리거(`/api/admin/scheduler/*`)는 Caddy가 `kista-scheduler`로 라우팅(kista-infra 레포)
- **Flyway 마이그레이션 backward-compat 필수**: 2-role은 독립 배포라 `kista-scheduler`가 이전 이미지로 새 스키마를 물 수 있다. 컬럼 추가는 nullable/DEFAULT, 드롭·리네임은 두 배포로 나눠 코드가 참조를 먼저 끊는다(expand/contract). 이 조건을 못 지키는 마이그레이션은 두 role을 같은 커밋에서 함께 배포
```

- [ ] **Step 3: `constraints.md` — Flyway 섹션에 규율 추가**

`### Flyway` 섹션의 `ddl-auto: validate` 불릿 근처에 추가:

```markdown
- **2-role 배포 backward-compat (expand/contract)**: `kista-api`·`kista-scheduler`가 독립 배포되므로, 새 마이그레이션은 직전 배포 이미지와 호환돼야 한다 — 컬럼 추가는 nullable 또는 DEFAULT, 컬럼/테이블 드롭·리네임은 두 배포에 분리(먼저 코드 참조 제거 → 다음 배포에서 스키마 변경). 이를 못 지키는 마이그레이션을 실은 커밋은 두 role을 함께 배포한다. `ddl-auto: validate`는 기동 시에만 검사하므로, 스큐 상태의 스케쥴러는 드롭된 컬럼을 매매 도중 런타임에 만날 때까지 계속 돈다
```

`### Git 규칙` 섹션에 추가:

```markdown
- 매매 시간대 배포 가드는 이제 `deploy-scheduler` 잡에만 적용된다 — `deploy-api`는 시간대 무관하게 배포 가능
```

- [ ] **Step 4: `architecture.md` — 캘린더 부트스트랩 staleness 명시**

`com.kista.market/` 절의 `adapter/in/schedule` 항목(`FearGreedScheduler/MarketCalendarRefreshScheduler`) 뒤에 추가:

```markdown
    - **2-role 이후 캘린더 부트스트랩 staleness**: `MarketCalendarRefreshScheduler`의 초기 적재(`ApplicationReadyEvent`, "기동 시 캘린더 초기 적재")는 `@ConditionalOnProperty(scheduler.enabled)`로 게이팅되므로 이제 `kista-scheduler` 재기동 시에만 실행된다 — `kista-api` 재기동마다(잦음)가 아니다. 캘린더 데이터 self-heal 창이 수시간 → 수주로 늘어난다. 월간(매월 1일)·연간(1월 1일) 갱신 크론이 있어 무해하나, 캘린더 이상 시 `kista-scheduler`를 재기동하면 즉시 재적재된다는 점을 기억
```

- [ ] **Step 5: `README.md` — 배포 다이어그램·설명**

`## 배포` mermaid 다이어그램의 `OciInfra` subgraph에서 `APIApp["kista-api (Spring Boot)"]` 다음 줄에 추가:

```
        SchedApp["kista-scheduler (같은 이미지, 배치)"]
```

`RepoAPI -->|...| APIApp` 아래에 추가:

```
    RepoAPI -->|"deploy-scheduler 잡<br/>(매매 시간대 가드)"| SchedApp
```

`Caddy --> APIApp` 아래에 `Caddy -->|"/api/admin/scheduler/*"| SchedApp` 추가. `APIApp --> HC` 를 `SchedApp --> HC` 로 변경(스케쥴러 생존 확인은 이제 스케쥴러 컨테이너가 핑).

다이어그램 뒤 불릿에 추가:

```markdown
- `kista-api`와 `kista-scheduler`는 **같은 GHCR 이미지**를 `SCHEDULER_ENABLED` 환경변수로 역할만 갈라 띄운다. API 배포는 매매 시간대 제약 없이 잦게, 스케쥴러 배포는 매매 시간대 가드 유지. API 크래시·OOM·요청경로 버그가 매매 배치를 건드리지 않는다 (상세 → `docs/agents/docker-infra.md`).
```

- [ ] **Step 6: 루트 `/Users/phs/workspace/kista/CLAUDE.md` — 스탈 정정 + 2-role**

`├── kista-api/   # Java 21 + Spring Boot 3 (독립 git, Fly.io 배포)` 를:

```
├── kista-api/   # Java 21 + Spring Boot 4 (독립 git, OCI 배포 — kista-api·kista-scheduler 2-role)
```

`└── kista-ui/   # Next.js 16 App Router (독립 git, Vercel 배포)` 를:

```
└── kista-ui/    # Next.js 16 App Router (독립 git, OCI 배포)
```

(kista-ui도 이미 OCI 호스팅 — README 9번째 줄 근거. Vercel은 폐지됨.)

- [ ] **Step 7: 커밋**

```bash
git add docs/agents/scheduler-time-table.md docs/agents/docker-infra.md \
        docs/agents/constraints.md docs/agents/architecture.md README.md
git -c user.name=narafu -c user.email=narafu@kakao.com commit -m "docs(scheduler): 2-role 배포 토폴로지 반영 — 시간표·인프라·제약·아키텍처·README

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019S4k94fsBinHuSfDMSgXXt"

git -C /Users/phs/workspace/kista add CLAUDE.md 2>/dev/null || true
# 루트 /Users/phs/workspace/kista 에는 git 없음 (kista-api/kista-ui 각각 독립 레포).
# 루트 CLAUDE.md 변경은 커밋 대상 아님 — 편집만 하고 사용자에게 알린다.
```

주의: `/Users/phs/workspace/kista/CLAUDE.md`는 루트에 git이 없어(멀티레포) 이 레포 커밋에 포함되지 않는다. 편집만 하고 완료 보고에 "루트 CLAUDE.md도 수정함(git 미추적)"을 명시한다.

---

## Task 5: kista-infra Caddy 라우팅 (이 레포 밖 — 산출물 문서)

**Files:** 없음 (이 레포에서 실행 불가 — `kista-infra` private 레포, `/opt/kista-infra/`).

**Interfaces:**
- Consumes: Task 2의 `kista-scheduler` 컨테이너 (`shared_net`의 `kista-scheduler:8080`).
- Produces: `/api/admin/scheduler/*` → `kista-scheduler`, 나머지 `/api/*` → `kista-api` 라우팅.

- [ ] **Step 1: kista-infra Caddyfile 확인 항목 문서화**

`kista-infra` 레포에서 별도 PR로 처리해야 하는 변경을 완료 보고에 포함:

1. `kista-infra/Caddyfile`(또는 상당 파일)에서 API 도메인 블록이 `handle` vs `route` 중 무엇을 쓰는지 확인.
   - `handle` (경로 최장일치 자동 우선): `handle /api/admin/scheduler/* { reverse_proxy kista-scheduler:8080 }` 를 아무 위치에 추가.
   - `route` (선언 순서 = 매칭 순서): `/api/admin/scheduler/*` 규칙을 기존 `/api/*` catch-all **앞**에 놓아야 함.
2. `reverse_proxy` 대상은 `kista-scheduler:8080` (Task 2 `container_name` + `expose`).
3. `kista-scheduler`는 `shared_net`에 가입돼 있어야 Caddy가 접근 가능 (Task 2에서 이미 포함).
4. **배포 순서**: `kista-scheduler` 컨테이너가 최초 기동된 **후** Caddy 규칙을 반영한다 (그 전엔 502).

- [ ] **Step 2: Fallback 경로 (kista-infra 접근 불가 시에만)**

`kista-infra`를 건드릴 수 없는 경우에만 — `kista-api`에 `/api/admin/scheduler/*` 수신 → `INTERNAL_API_TOKEN` + `X-Internal-Token` 헤더로 `http://kista-scheduler:8080` 프록시하는 얇은 컨트롤러 추가. **이 플랜에서는 구현하지 않음** — Task 5는 문서화까지만.

---

## Self-Review

**1. Spec coverage:**

| Spec 섹션 | 구현 Task |
|---|---|
| §1 역할 게이트 config | Task 1 (Step 3~4) |
| §2 EPR 소유권 (blocker) | Task 1 (Step 4) + Task 2 (Step 1 env) |
| §3 스키마/코드 스큐 규율 (blocker) | Task 4 Step 3 (constraints.md), Task 4 Step 2 (docker-infra.md) — 문서화(스펙도 "문서화만"으로 명시) |
| §4 수동 트리거 라우팅 + Caddy | Task 5 (레포 밖 산출물) + Task 2 (shared_net 가입) |
| §5 배포 워크플로 분리 | Task 3 |
| §6 컨테이너 정의 | Task 2 |
| §7 문서 전용 변경 | Task 4 |
| 테스트 | Task 1 (Step 1, 6) |
| 게이트 1 (전체 테스트 그린) | Task 1 Step 8 |
| 게이트 2~6 (배포 관측) | 실행 후 수동 — 완료 보고에 체크리스트로 |

갭 없음. 게이트 4~6(스테이징 메모리 관측, 첫 매매 사이클 관측, 매매 시간대 API 단독 배포 확인)은 실배포 후에만 가능하므로 완료 보고에 "미검증 — 최초 배포 시 관측 필요" 항목으로 넘긴다.

**2. Placeholder scan:** 없음. 모든 스텝에 실제 파일 경로·코드·명령어 포함. Task 5만 "레포 밖"으로 명시적으로 실행 제외(스펙 §4가 이미 크로스 레포로 규정).

**3. Type consistency:**
- `scheduler.enabled` 프로퍼티명 — Task 1 전체·Task 2 env(`SCHEDULER_ENABLED`)·테스트에서 일관.
- `spring.modulith.events.republish-outstanding-events-on-restart` — Task 1 Step 4(yaml)·Step 1(테스트 assert)·Task 2 env(`SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART`) 일관. `.jdbc` 하위 아님을 Global Constraints에 명시.
- compose 서비스명 `kista-scheduler` — Task 2·Task 3(`service: kista-scheduler`)·Task 5(`reverse_proxy kista-scheduler:8080`) 일관.
- `_deploy-role.yml` inputs (`image`/`service`/`apply_trading_guard`/`force`) — Task 3 Step 1 정의·Step 2 호출 일관.
- prev-image 경로 `/tmp/kista-${SERVICE}-prev-image` — Task 3 Step 1 내 기록·롤백에서 동일 표현식.

---

## Execution Handoff

플랜 완료. 저장 위치: `docs/superpowers/plans/2026-09-04-scheduler-process-separation.md`
