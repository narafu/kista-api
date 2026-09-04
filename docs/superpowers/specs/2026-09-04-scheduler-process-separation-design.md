# 스케쥴러 프로세스 분리 — 같은 이미지 2-role (API / Scheduler) 설계

## 배경/목적

`kista-api`는 현재 단일 Spring Boot 프로세스로 OCI 단일 컨테이너에 배포된다. `@EnableScheduling`이 `KistaApplication`에 붙어 있고, `adapter/in/schedule`의 `@Scheduled` 클래스 9개가 전부 `@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)`로 게이팅된다.

이 구조가 만드는 두 가지 문제:

1. **배포가 매매를 중단시킨다.** `TradingOpenScheduler`/`TradingCloseScheduler`는 개장까지 최대 60분 `Thread.sleep()` 후 주문을 접수하며 락 TTL이 2~3시간이다. 컨테이너를 재시작하면 이 스레드가 `InterruptedException`으로 강제 종료돼 주문 접수가 유실될 수 있다. 이를 막기 위해 `server-deploy.yml`에 **매매 시간대 배포 가드**(월~금 22:20~23:40, 화~토 04:20~06:20 KST에 배포 `exit 1`)가 존재하지만, 이는 HTTP API 변경 배포까지 함께 막는다.
2. **API 장애가 스케쥴러를 죽인다.** 요청 경로의 버그(컨트롤러 NPE, 무거운 통계 쿼리, SSE 커넥션 누수)나 OOM이 같은 프로세스의 매매 배치까지 함께 중단시킨다.

목적: 매매 배치 실행(sleep·주문 접수)을 HTTP API와 **별도 프로세스**로 분리해 (a) API 배포·크래시·OOM·요청경로 버그가 스케쥴러에 닿지 않게 하고 (b) 배포 가드를 스케쥴러 배포에만 국한한다.

## 채택 방향과 기각안

### 채택 — 같은 아티팩트, config로 역할 결정

같은 GHCR 이미지 하나를 컨테이너 2개로 띄우고 환경변수 `SCHEDULER_ENABLED` 하나로 역할을 가른다.

| | `kista-api` | `kista-scheduler` |
|---|---|---|
| `SCHEDULER_ENABLED` | `false` | `true` |
| `@Scheduled` 빈 | 미등록 (`@ConditionalOnProperty`) | 등록 |
| HTTP | 풀 기동, Caddy 공개 라우팅 | 풀 기동, `/api/admin/scheduler/*` + actuator만 |
| 배포 빈도 | 잦음, 매매 가드 없음 | 드묾, 매매 가드 유지 |
| 매매 배치 실행 위치 | — | 이 프로세스 |

### 기각 — 스케쥴러를 물리적으로 분리 ([[2026-08-31-legacy-module-catalog-design]] 이후 11모듈 구조 전제)

- **별도 Gradle 모듈(`:core` + `:scheduler`)**: 스케쥴러는 `adapter/in/schedule`의 얇은 진입점일 뿐, HTTP 컨트롤러가 호출하는 것과 동일한 유스케이스(`TradingExecutionUseCase`·`StrategyLookupPort`·`PrivacyTradePort`·`BatchContextFactory` + KIS/Toss 어댑터 + notify + heartbeat)를 오케스트레이션한다. `:core`가 코드베이스의 ~90%가 되고, Modulith `ApplicationModules.verify()`·ArchUnit 규칙을 재작성해야 하며, 빌드 시간이 ~2배가 된다. **버그 격리는 config 역할 분리와 동일**(소스 공유) — 값이 없다.
- **별도 레포 + 공유 라이브러리(버전 핀)**: 도메인 변경마다 publish·버전 범프·PR 2개. 격리는 "지연 + 명시적 opt-in"이지 면역이 아니다(스키마 변경이 결국 강제 업그레이드). 무엇보다 이 프로젝트는 **preview(API 미리보기) == execution(스케쥴러 접수)** 불변식에 매매 정확성이 걸려 있는데, 버전 스큐 시 API가 core 1.5로 미리보기하고 스케쥴러가 1.4로 접수해 체결이 미리보기와 달라진다. 실재 위험.
- **별도 레포 + 코드 중복**: 매매 로직을 두 번 구현·테스트. 드리프트 확정 — 솔로 개발에서 서로 미묘하게 다른 주문 생성 구현 2개가 생긴다.
- **얇은 크론이 API를 HTTP로 호출**: 매매 배치의 실제 작업(sleep·주문 접수, 락 TTL 2h)이 여전히 `kista-api` 프로세스 안에서 돈다. 배포 가드가 그대로 필요하고 아무것도 격리되지 않는다 — 가벼운 부분(타이머)만 빼고 무거운 부분(배치)을 남긴다. 그럴 거면 호스트 `crontab` + `curl`이 같은 일을 코드 0으로 한다.

**핵심**: 원하는 격리(배포 디커플링 + 런타임 장애 격리)는 "별도 프로세스"에서 나온다. config 역할 분리가 이미 그것을 준다. 모듈/레포 분리는 빌드·버전 복잡도만 추가하고 격리를 더 늘리지 못한다(공유 코드 버그는 어느 쪽이든 양쪽 타격 — 그건 명시적으로 스코프 밖).

### 스코프

- 스케쥴러 **9개 전부** `kista-scheduler`로, `kista-api`는 `scheduler.enabled=false`로 완전히 끈다. 플래그 1개로 처리 — 일부만 나누면 config가 2개가 되고 두 곳을 추론해야 한다.
- 공유 도메인 코드 버그의 격리는 **스코프 밖**(별도 레포/라이브러리가 필요하고 preview==execution 위험 때문에 채택 안 함).

## 설계

### 1. 역할 게이트 (코드 변경 최소)

`src/main/resources/application.yml`:

```yaml
scheduler:
  enabled: ${SCHEDULER_ENABLED:true}   # 명시 바인딩. env 미설정 시 true — local 기존 동작 유지
```

`src/main/resources/application-prod.yml`:

```yaml
scheduler:
  enabled: false   # prod 기본은 API 역할. kista-scheduler 서비스가 SCHEDULER_ENABLED=true로 override
```

- 9개 스케쥴러의 `@ConditionalOnProperty(... matchIfMissing = true)`는 무변경 — 프로퍼티가 항상 존재하게 되므로 `matchIfMissing`은 사실상 no-op이 되지만, local yml에서 프로퍼티를 생략하는 개발자를 위해 유지한다.
- `@EnableScheduling`은 `KistaApplication`에 그대로 둔다 — `scheduler.enabled=false`면 스케쥴 대상 빈이 없어 무해하다.
- `AdminSchedulerController`는 무변경 — `ObjectProvider.getIfAvailable()`가 `kista-api`(스케쥴러 빈 없음)에서 `null`을 반환해 `"스케쥴러가 비활성화 상태입니다"`를 throw한다(Caddy 오라우팅 시 조용한 no-op이 아니라 시끄럽게 실패 — 의도된 동작).
- `application-local.yml`(.gitignored)에서 스케쥴러를 끄던 개발자는 계속 `scheduler.enabled: false`로 하드코딩하면 된다.

### 2. EPR 소유권 — **blocker**

`application.yml`의 `spring.modulith.events.republish-outstanding-events-on-restart: true`(현재 `spring.modulith.events` 하위, `.jdbc` 아님)와 공유 `event_publication` 테이블(`public` 스키마, V21) 조합이 2-role에서 이중 발화를 만든다:

- 두 role 모두 모든 `@TransactionalEventListener`를 탑재한다(코드 공유). 스케쥴러가 `TradingReportReadyEvent`를 발행 → notify 리스너 실패 → 미완료 행 잔존 → `kista-api`가 배포(잦음)로 재기동 → `kista-api`가 같은 행을 재발행 → `kista-api`의 `TradingAlertNotifier`가 텔레그램 발송. 동시 재기동 시 같은 행을 두 번 재발행.

**결정**: 재발행 소유자를 **스케쥴러로 고정**한다.

`application-prod.yml`:

```yaml
spring:
  modulith:
    events:
      republish-outstanding-events-on-restart: false   # API role: 재발행 안 함
```

`kista-scheduler` 서비스는 환경변수로 다시 켠다:

```
SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART=true
```

- 정정(구현 후 확인): `event_publication`은 발행 origin을 구분하지 않는 공유 테이블 — API가 발행한 이벤트(`NewUserRegisteredEvent` 등)의 리스너가 실패해도 그 미완료 행은 `kista-scheduler`의 다음 재기동 때 republish로 그대로 회수된다(양쪽이 동일 리스너 코드를 갖고 있으므로). 즉 재기동 복구 자체를 잃는 게 아니라 "복구 시점이 API 자체 재기동 대신 스케쥴러 재기동으로 이동"할 뿐 — API가 자주 재기동되고 스케쥴러는 드물게 재기동되므로 in-flight 창은 이전보다 넓어지지만 영구 유실은 아니다. 비용은 중복 재발행 방지(양쪽 true 시 발생)와의 트레이드오프뿐.
- `spring.modulith.events.jdbc.schema-initialization.enabled: false`는 무변경(Flyway 소유).

### 3. 스키마/코드 스큐 규율 — **blocker**

독립 배포는 `kista-scheduler`가 **이전 이미지**로 **새 스키마**를 물 수 있게 한다. `ddl-auto: validate`는 기동 시에만 검사하므로 이미 돌던 프로세스는 드롭/리네임된 컬럼을 런타임에 만날 때까지 — 최악의 경우 매매 시간대 도중 — 계속 돈다.

**규칙**: Flyway 마이그레이션은 직전 배포 이미지와 **backward-compatible**해야 한다 (expand/contract — 컬럼 추가는 nullable/DEFAULT, 드롭·리네임은 두 배포에 나눠 코드가 먼저 참조를 끊은 뒤 다음 배포에서 삭제). 이 조건을 만족하지 못하는 마이그레이션을 실은 배포는 **두 role을 함께 배포**해야 한다(스펙·PR 설명에 명시).

- Flyway history 테이블 락이 동시 기동 레이스를 안전하게 만들므로 그 부분은 추가 작업 불필요 — 문제는 레이스가 아니라 스큐다.
- 이 규율은 [[constraints.md]]의 "Flyway" 섹션에 반영한다.

### 4. 수동 트리거 라우팅 + Caddy (크로스 레포)

- `kista-scheduler`는 풀 웹으로 기동하고 `expose: 8080`, `shared_net`(Caddy 접근) + `data_net`(DB/Redis)에 합류한다.
- **Caddy 설정은 `kista-infra` 레포**(private, `/opt/kista-infra/`)에 있다 — 크로스 레포 변경. 라우팅: `/api/admin/scheduler/*` → `kista-scheduler:8080`, 나머지 `/api/*` → `kista-api:8080`.
- Caddyfile이 `handle`(최장 경로 자동 우선)을 쓰면 규칙 순서 무관, `route`(순서 의존)를 쓰면 `/api/*` catch-all보다 앞에 놔야 한다 — 구현 시 `kista-infra/Caddyfile` 확인.
- 배포 순서 제약: `kista-scheduler` 컨테이너가 존재한 **후** Caddy 규칙을 추가한다(그 전엔 502).
- **Fallback**(`kista-infra` 미변경): `kista-api`가 `/api/admin/scheduler/*`를 받아 `INTERNAL_API_TOKEN` + `InternalTokenAuthFilter` 경유로 `kista-scheduler:8080`에 프록시. 지금 채택 안 함, 이름만 남긴다.

### 5. 배포 워크플로 분리

`.github/workflows/server-deploy.yml` 하나 유지. `verify` + `build`(이미지 1개, 두 role 공유)는 그대로. `deploy` 잡을 2개 **독립 잡**으로 분리:

| 잡 | 매매 가드 | `concurrency` | 대상 컨테이너 | prev-image 경로 |
|---|---|---|---|---|
| `deploy-api` | ❌ 없음 | `server-api` | `kista-api` | `/tmp/kista-api-prev-image` |
| `deploy-scheduler` | ✅ 현행 가드 로직 (`exit 1`) | `server-scheduler` | `kista-scheduler` | `/tmp/kista-scheduler-prev-image` |

- 두 잡 독립(`needs: build`만) → 매매 시간대 push 시 `deploy-api`는 성공, `deploy-scheduler`는 실패(빨강). 장 마감 후 그 잡만 GitHub Actions에서 re-run.
- 현재 하드코딩된 `docker inspect kista-api` / `/tmp/kista-prev-image` / `docker compose ps kista-api`를 role별로 파라미터화 — 공유 경로면 롤백이 엉뚱한 이미지를 복원할 수 있다.
- health gate + 자동 롤백도 각 잡이 자기 컨테이너 대상으로.
- `concurrency`를 role별로 분리 — 둘 다 `server-production`에 두면 가드에 막힌 스케쥴러 배포가, 가드가 필요 없는 API 배포 앞에 큐잉된다.
- `workflow_dispatch`의 `force` 입력은 `deploy-scheduler`에만 의미 있게 유지.

### 6. 컨테이너 정의 (`deploy/server/docker-compose.yml`)

`kista-scheduler` 서비스 추가:

```yaml
kista-scheduler:
  image: ${KISTA_API_IMAGE:?KISTA_API_IMAGE is required}   # kista-api와 동일 이미지
  container_name: kista-scheduler
  env_file:
    - .env
  environment:
    SPRING_PROFILES_ACTIVE: prod
    SCHEDULER_ENABLED: "true"
    SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: "true"
    REDIS_URL: redis://redis:6379
    JAVA_OPTS: "-Xmx1536m -Xms128m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:+UseG1GC -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"
  expose:
    - "8080"
  networks:
    - shared_net
    - data_net
  stop_grace_period: 200s   # 매매 배치 인터럽트 → 락 즉시 해제까지 여유 (아래 주 참고)
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

- `kista-api` 서비스는 `SPRING_PROFILES_ACTIVE: prod`만으로 `scheduler.enabled=false`가 적용되므로 명시적 env 불필요(원하면 문서용으로 `SCHEDULER_ENABLED: "false"` 추가 가능).
- **메모리**(실측 근거): 현재 박스 컨테이너 RSS 합 ~750MiB(`kista-api` 572, `kista-ui` 94, `kista-postgres` 66, redis 4, caddy 18), `free -m` `available` 10.4GB. 2번째 JVM 실사용 ~700MiB 예상 → 여유 충분. `mem_limit`을 빠듯하게 잡지 않는 이유: OOM-kill은 `SchedulerLockService`의 `finally { release() }`를 건너뛰어 `trading-open` 락이 2h 홀드되고, 그날 개장 배치가 스킵되며, healthchecks.io dead-man's switch로만 사후 감지된다.
- `stop_grace_period`: `kista-api`(35s)보다 길게 잡는다 — 배포·재기동 시 매매 배치 스레드가 `InterruptedException`을 받고 `SchedulerJobRunner`가 `FAILED` 이벤트 발행 후 rethrow → `SchedulerLockService`가 락을 즉시 해제하는 경로가 완료될 시간. Spring `spring.lifecycle.timeout-per-shutdown-phase`(prod 30s)도 함께 검토 — 스케쥴러 role은 더 길게 필요할 수 있다(구현 시 실측).

### 7. 문서 전용 변경 (코드 변경 없음)

- **캘린더 부트스트랩 staleness**: `MarketCalendarRefreshScheduler`의 초기 적재(`ApplicationReadyEvent`)가 이제 `kista-scheduler` 재기동 시에만 실행된다 — `kista-api` 재기동마다(잦음)가 아니다. self-heal 창이 수시간 → 수주로 늘어난다. 월간/연간 갱신 크론이 있어 무해하나 [[architecture.md]]에 명시.
- `docs/agents/scheduler-time-table.md`의 `src/main/java/com/kista/adapter/in/schedule` 경로가 이미 스탈(모듈별로 이동됨) — 이번 기회에 정정 + "모든 스케쥴러는 `kista-scheduler` 컨테이너에서 실행" 한 줄 추가.
- `docs/agents/docker-infra.md`: "서버 배포 방식" 섹션에 2-role 토폴로지(컨테이너 2개, 같은 이미지, `SCHEDULER_ENABLED`, 배포 잡 2개) 반영.
- `docs/agents/constraints.md`: "Flyway" 섹션에 expand/contract 규율(§3) 추가. "Git 규칙" 근처에 "매매 가드는 이제 `deploy-scheduler` 잡에만 적용" 반영.
- `README.md`: 배포 파이프라인·아키텍처 다이어그램에 스케쥴러 role 분리 반영(드리프트 감지 지침).
- `CLAUDE.md`(루트): "Java 21 + Spring Boot 4 기반 ..." 문단 뒤 배포 토폴로지 한 줄.

## 테스트

- **역할 게이트**: `@SpringBootTest`로 `prod` 프로파일 로드 시 9개 스케쥴러 빈이 컨텍스트에 **없음**을 검증. `SCHEDULER_ENABLED=true` override(`@SpringBootTest(properties = "scheduler.enabled=true")`) 시 **있음**을 검증. (기존 `@SpringBootTest`가 test 프로파일에서 `matchIfMissing=true`로 스케쥴러를 로드하던 것이 프로퍼티 명시 후에도 유지되는지 회귀 확인 — `application-test.yml`에 `scheduler.enabled` 미설정이면 기본 `${SCHEDULER_ENABLED:true}` → `true`.)
- **EPR 프로파일 값**: `prod` 프로파일에서 `spring.modulith.events.republish-outstanding-events-on-restart`가 `false`로 바인딩되는지 검증(`@SpringBootTest` + `Environment` 조회 or `@EnableConfigurationProperties` 바인딩 확인).
- **기존 스위트 전체**: ArchUnit·Modulith `verify()`는 무영향(코드 이동 0, 파일 신규 0 — yml·compose·workflow만 변경). `./gradlew test` 그린 유지.
- 배포 워크플로는 CI에서 직접 테스트 불가 — PR 리뷰 + 최초 배포 시 수동 관측(§게이트).

## 게이트 (구현 완료 판정)

1. `./gradlew test` 전체 그린 (역할 게이트·EPR 테스트 포함).
2. 로컬에서 `SCHEDULER_ENABLED=false`로 기동 → `/actuator/health` 200, 스케쥴러 로그 없음 확인. `SCHEDULER_ENABLED=true`로 기동 → 스케쥴러 등록 로그 확인.
3. `kista-infra` Caddyfile 변경 PR 준비 (`handle`/`route` 확인 결과 포함).
4. 스테이징/최초 배포: `deploy-scheduler` 잡 성공 → `kista-scheduler` 컨테이너 healthy → `docker stats`로 2번째 JVM 실사용 메모리 관측 → `mem_limit` 재조정 여부 판단.
5. 최초 배포 후 첫 매매 사이클(개장/마감) 1회를 `kista-scheduler` 로그 + heartbeat(healthchecks.io) + 텔레그램 리포트로 정상 관측. 중복 알림 없음 확인(EPR 소유권).
6. 매매 시간대에 `deploy-api` 단독 배포가 가드 없이 통과하는지 1회 확인.

## 미해결 / 후속

- `kista-scheduler`의 Spring `spring.lifecycle.timeout-per-shutdown-phase`를 role별로 다르게 줄지(현재 prod 공통 30s) — §6 참고, 구현 시 실측.
- `AdminSchedulerController`가 `kista-scheduler`에만 유효해지므로, 장기적으로 이 컨트롤러를 `com.kista.web`에서 스케쥴러 role 전용 위치로 옮길지 검토(지금은 무변경 — `ObjectProvider` null 처리로 API에서도 안전하게 404/500).
- Fallback(API 프록시) 경로는 `kista-infra` 접근이 불가능한 상황에서만 — 스펙에 이름만.
