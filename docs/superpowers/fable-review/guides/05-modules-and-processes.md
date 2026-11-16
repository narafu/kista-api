# 지침서 05. 코드베이스 통합과 모듈 재구성 — 실행 지침

> **실행 세션은 먼저 맨 끝의 14절 "2차 실행 전 보정"(2026-11-15), 13절 "실행 전 보정", `00-roadmap.md` 9절(공통 규칙)을 읽는다.** 서로 다르면 14절 → 13절 → 본문 순으로 우선한다. 4-3절(전환 배포)은 14-3절이 대체한다.

작성 2026-11-11, **전면 개정 2026-11-12** (Fable). 이 문서는 Fable 없이 Opus/Sonnet이 실행하는 것을 전제로 쓴다.

> **2026-11-14 갱신**: X6의 호출처 목록에서 `CryptoDcaRunner`가 빠진다(`15-crypto-removal.md`). 단계 R에서 `MarketDataPort`와 Alpaca 어댑터를 `market` 모듈로 옮긴다(`14-market-data-provider.md` 4-1절). 단계 P의 선행인 04 단계 A는 KIS만 다룬다.

근거(구현 전에 해당 절을 직접 읽는다):
- `findings/09-process-local-state.md` — trading 코드가 여러 프로세스에서 돌 때 깨지는 것 전수
- `findings/10-merge-collisions.md` — 두 애플리케이션을 한 컨텍스트에 올릴 때의 충돌 전수(빈, 설정, JPA, Flyway, Modulith·EPR, 보안, 예외 처리기, 경계 코드 3자 매핑표, `shared.kernel` 전수, 빌드, 배포 파일)
- `findings/01`(경계 집계), `findings/02` E절, `docs/history/modulith-migration-history.md:132-146`

용어: R = root(`src/`, `com.kista.api`), T = trading-core(`trading-core/`, `com.kista.tradingcore`), S = shared.

## 0. 사용자 결정 (2026-11-12)

D1 한 코드베이스로 합치고 실행 역할로 프로세스를 나눈다 / D2 DB 스키마 5개 유지, 물리 DB 분리(S-04) 폐기 / D3 trading 스키마에도 expand/contract / D4 `admin`·`web` 셸 해체. 사용자는 "합칠 때 새로 생기는 문제의 더 나은 대안"을 Fable이 다시 검토하라고 했고, 그 결과가 1절이다.

## 1. 설계 원칙 (가장 중요 — 이 절을 어기는 구현은 중단한다)

### 1-1. 매매 엔진은 한 프로세스에서만 실행한다

조사 결과 trading 코드가 두 프로세스에서 동시에 돌면 다음이 깨진다(`findings/09` I-1).

| | 내용 | 근거 |
|---|---|---|
| M1 | 계좌 예산 락이 JVM 락뿐이다. `orders`에 (사이클, 거래일, 슬롯) 유일 제약도 없다. 수동 매매와 배치가 다른 프로세스에서 겹치면 주문 이중 저장·예산 이중 차감 | `AccountBudgetLock.java` 주석 "다중 인스턴스가 되면 DB 락으로 승격" |
| M2 | 브로커 호출 간격 게이트가 프로세스별이다(메리츠 110ms). 합산되면 벤더 한도 초과, 주문 POST는 재시도가 없어 배치 접수 실패 | `CallIntervalGate` |
| M3 | 토큰 발급 직렬화가 JVM 락이다 | `DbTokenCoordinator` |

그래서 **"web이 모든 HTTP를 받고 worker는 배치만"이라는 첫 권고를 버린다.** 대신:

> **원장을 바꾸거나 브로커를 호출하는 코드는 worker 프로세스에서만 실행한다. worker는 항상 1개다.**

이 원칙이면 M1~M3은 발생하지 않는다(지금의 kista-trading과 같은 조건). 합치는 이득(내부 HTTP·contract·사용자 복제본·이중 구현 제거, `shared.kernel` 해체, 모델 1개)은 전부 얻는다.

### 1-2. 역할 배치

| role | 인스턴스 | 받는 것 |
|---|---|---|
| **worker** | 1개, stop-first, `stop_grace_period` ≥ 200s | 모든 `@Scheduled`, 배치 재개, Redis 스트림 컨슈머, EPR 재발행. **엔진 모듈의 HTTP 전부**: trading, account, stats, broker, calendar, privacy, market, benchmark와 그 관리자 엔드포인트 |
| **web** | blue/green(교체 중 2개) | 엔진과 무관한 HTTP: user(인증·승인·프로필), finance(가계부), notification 웹훅, ops(오류·감사 로그), `/api/meta`, SSE |

- 지금의 3 role 대비: kista-trading + kista-scheduler → worker, kista-api → web. **런타임 구조는 거의 그대로이고 코드베이스만 하나가 된다.** 가계부·로그인은 worker 배포 중에도 응답하고, 가계부 배포는 매매 프로세스를 재기동하지 않는다(role별 SHA 선언은 kista-infra가 이미 지원).
- market·benchmark를 worker에 두는 이유: 캔들·환율 조회가 브로커(시세 공급자)를 호출한다. 원칙 1-1에 따라 worker다.
- **포기하는 것**: worker 배포 중(약 3~4분) 전략·계좌·통계 화면이 응답하지 않는다(지금과 같다). 되찾는 길은 8절의 선택 단계다.

### 1-3. role을 구현하는 방식 (빈을 role로 쪼개지 않는다)

두 role 모두 **전체 컨텍스트를 올린다.** 모듈별로 빈을 조건부 로딩하지 않는다(조건이 수백 곳에 퍼지고 빠뜨리면 조용히 깨진다). role이 가르는 것은 넷뿐이다.

1. 프로퍼티 하나: `kista.role = web | worker | all`. **기본값 없음 — 누락이면 기동 실패**(`@Validated`). 로컬·테스트는 `all`. 운영 compose가 role별로 준다. 기존 `scheduler.enabled`(누락 시 켜짐)는 없앤다.
2. worker 전용 빈(`@ConditionalOnWorkerRole` = role이 worker 또는 all): 모든 `@Scheduled` 클래스, `TradingBatchResumer`, Redis 스트림 컨슈머, 스케줄러 수동 트리거. 메타 애너테이션 하나로 표시하고 ArchUnit으로 "`@Scheduled` 메서드를 가진 클래스는 이 애너테이션을 가진다"를 강제한다.
3. EPR 재발행: worker만 true(compose env).
4. **요청 경로 가드**: 서블릿 필터 `RoleRoutingGuard`가 role=web일 때 엔진 경로 요청을 503(`code=WRONG_ROLE`)으로 거절하고, role=worker일 때 web 경로를 같은 방식으로 거절한다. 잘못된 라우팅이 조용히 동작하지 않게 한다. `all`이면 통과.

경로→role 표는 **한 곳**에서 만든다: 컨트롤러가 속한 Modulith 모듈로 판정한다(엔진 모듈 목록은 1-2 표). `RoleRoutes`(platform)가 기동 시 `RequestMappingHandlerMapping`에서 모듈별 경로를 모아 가드에 주고, 테스트가 같은 표로 Caddy 스니펫의 matcher를 검증한다(기존 `CaddyRoutingTest` 확장). UI도 이 표에서 생성한 목록을 쓴다(7절).

### 1-4. role 경계를 넘는 통신은 2가지만 남긴다

| 방향 | 수단 | 내용 |
|---|---|---|
| web → worker | Redis Stream `stream:user.deleted`(기존) | 사용자 탈퇴 시 매매 데이터 정리. 미체결 주문 취소가 브로커 호출이라 worker에서 실행돼야 한다 |
| worker → web | Redis Pub/Sub 매매 이벤트(기존) | web이 쥔 SSE 연결로 매매 알림 푸시 |

그 밖의 스트림 3종(`user.notify-profile.changed`, `app.error`, `user.push-notification.requested`)과 내부 HTTP 31개 핸들러는 삭제한다. web이 엔진 모듈을 프로세스 안에서 호출하는 것은 **DB 읽기 전용 조회만** 허용한다(예: `/api/runtime-config`가 매매 정책 설정을 읽는 것). 쓰기와 브로커 호출은 금지다. 허용 목록을 `RoleBoundaryTest`(ArchUnit)에 명시한다: web 모듈(user, finance, notification, ops, app)이 참조할 수 있는 엔진 타입의 화이트리스트.

**이벤트 주의**: Spring 이벤트 리스너는 발행한 프로세스에서 돈다. web에서 발행되는 이벤트를 엔진 모듈이 `@EventListener`로 직접 들으면 엔진 코드가 web에서 실행된다. 현재 해당하는 것은 `UserDeletedEvent`(→ `TradingUserCascadeListener`)와 `UserNotifyProfileChangedEvent`다. 처리 방법은 3절 P5.

### 1-5. 기각한 대안

- **web이 모든 HTTP를 받는다(첫 권고)**: M1~M3 해결이 선행이다. 분산 락 3종을 새로 만들고 주문 안전 경로를 바꿔야 한다. 이득(worker 배포 중 화면 응답)에 비해 위험이 크다. 8절 단계 C로 미룬다.
- **브로커를 부르는 요청만 worker로 넘긴다**: 수동 매매·잔고·시세·전략 등록(전일 종가 조회)이 전부 브로커를 불러 경계를 그을 수 없다(`findings/09` I-4).
- **현 2-코드베이스 유지**: 사용자가 통합으로 결정.
- **완전 단일 프로세스**: `kista.role=all`로 언제든 가능하다. 운영은 2 role로 간다.
- **모듈별 조건부 빈 로딩**: 1-3 참고.

## 2. 전체 순서

| 단계 | 내용 | 배포 | 선행 |
|---|---|---|---|
| P | 사전 정리(현 2-앱 구조에서, 각각 독립 배포) | 기존 3 role | 지침서 04 단계 A(KIS·업비트 삭제) |
| M | 한 애플리케이션으로 합치고 2 role로 전환 | **구조 전환 배포**(kista-infra 협업) | P 전부 |
| X | 경계 코드 제거(내부 HTTP, contract, 복제본, 스트림 3종) | 2 role | M |
| R | 모듈 재구성(kernel 해체, calendar, admin·web 해체, 공개 범위 축소) | 2 role | X, 지침서 01 단계 B |
| G | Gradle 서브프로젝트 단일화, UI 진입점 정리 | 2 role | R |

**단계마다 운영에 배포하고 최소 2거래일(개장·마감 배치 각 2회) 관찰한 뒤 다음 단계로 간다.** 단계 M은 매매 시간대 밖(KST 07:00~16:00, 미국장 휴장일이면 더 좋다)에 한다.

공통 검증 명령(이하 "전체 검증"):

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|warning: \[|BUILD"
./gradlew test 2>&1 | grep -E "FAILED|BUILD"          # 단계 끝에 1회
grep -l 'failures="[1-9]' build/test-results/test/TEST-*.xml trading-core/build/test-results/test/TEST-*.xml
```

## 3. 단계 P — 사전 정리 (현 구조에서 충돌 원인을 먼저 없앤다)

각 항목은 동작을 바꾸지 않는 독립 커밋이다. 전부 `findings/10`의 해당 절에 file:line이 있다.

**P1. 예외 처리기 통합** (Opus) — `findings/10` G-1. **가장 위험한 충돌이라 먼저 한다.**
T `TradingExceptionHandler`가 `@Order(HIGHEST_PRECEDENCE)`에 `@ExceptionHandler(Exception.class)`를 갖고 있다. 한 컨텍스트에 올리면 모든 예외를 먼저 잡아 R의 매핑(가계부 409 7종, `MONTH_CLOSED`, Cooldown 429 + `Retry-After`, `InvalidRefreshToken` 401, SSE 종료 처리)에 도달하지 못한다. 컴파일도 대부분의 테스트도 이를 못 잡는다.
- 목표: advice 클래스는 S(platform)에 **하나**. 예외→응답 매핑은 모듈이 `ProblemDetailMappingContributor` 빈으로 기여하고 advice가 전부 모아 한 표로 만든다. 개별 핸들러가 필요한 것(Cooldown의 헤더, `BrokerApiException`의 오류 보고, SSE 종료, `MethodArgumentNotValidException`)도 이 advice로 옮긴다.
- 선행 테스트: R·T 각 `MAPPINGS`의 예외마다 "이 예외가 던져지면 이 상태·code"를 단언하는 표 기반 테스트를 먼저 만든다(현재 값 고정). 통합 뒤에도 같은 테스트가 통과해야 한다.
- 같은 뜻의 쌍(`AdminBrokerCredentialException`↔`BrokerCredentialException` 등 3쌍)은 단계 X에서 정리한다. 여기서는 건드리지 않는다.

**P2. 중복 빈 정리** (Sonnet) — `findings/10` A절.
`RedisBlacklistAdapter`를 S로 옮겨 한 벌로(`JwtAuthFilter`가 `TokenBlacklistPort` 단일 빈을 요구한다). `fearGreedRestClient` 빈 이름 충돌(읽기 타임아웃 7s 대 5s)은 이름을 다르게. `UserEventResubmitScheduler` 2벌은 P5에서.
- `ClockConfig`는 여기서 옮기지 않는다(Q-07). S에서 `Clock` 빈을 둘 수 있는 곳은 platform뿐인데 `platform_must_not_depend_on_other_modules`가 `shared.kernel.TimeZones` 참조를, `zoneId_of_must_only_be_called_in_TimeZones`가 `ZoneId.of` 직접 호출을 막는다. 가드를 풀거나 `TimeZones`를 옮길 만큼의 이득이 없다 — 두 프로세스인 동안은 충돌이 없고 본문이 같으므로 단계 M의 M9에서 T 쪽 한 벌을 지운다.
- Alpaca 3벌 → 1벌도 여기서 하지 않는다. 빈 이름·properties FQCN이 모두 달라 충돌이 아니고(`findings/10` A1·A4), 합치려면 R benchmark와 T marketcalendar·stats가 함께 쓸 새 platform 타입이 필요하다. 단계 X·R에서 alpaca 소유 모듈이 정해질 때 한다.

**P3. SQL을 search_path에 의존하지 않게** (Sonnet, 기계적·검수 Opus) — `findings/10` C절.
합친 뒤에는 커넥션 풀이 하나라 search_path도 하나다. 그런데 `scheduler_locks`와 `event_publication`이 public·trading 두 스키마에 같은 이름으로 있다.
- 모든 `nativeQuery`(R 11곳, T 약 45곳)와 `JdbcTemplate` SQL의 테이블 이름에 스키마를 붙인다(`trading.orders`). 엔티티는 이미 전부 `@Table(schema=...)`다.
- `SchedulerLockService`(S)는 테이블 이름을 프로퍼티로 받는다(`kista.scheduler-lock.table`, R=`public.scheduler_locks`, T=`trading.scheduler_locks`).
- 가드 테스트: main의 native SQL 문자열에서 `from|join|into|update` 뒤 식별자가 스키마 한정인지 정규식으로 검사.
- 검증: 기존 persistence 테스트(`*DbTest`) 전부 통과. **`deleted_at IS NULL` 조건을 건드리지 않는다.**

**P4. role 프로퍼티 도입** (Sonnet)
`kista.role`을 두 앱에 추가한다(R: kista-api=web, kista-scheduler=worker / T: worker). `@ConditionalOnProperty(prefix="scheduler", ...)`를 전부 `@ConditionalOnWorkerRole`로 바꾸고 `scheduler.enabled` 키와 `SCHEDULER_ENABLED` env를 지운다. T의 `@Scheduled` 클래스에도 붙인다(지금은 게이트가 없는 것이 있다 — Redis 스트림 복구 `@Scheduled` 등, `findings/09` F절). ArchUnit 가드 추가. compose·`.env.example`·bats 갱신(`cross-repo.md`에 env 변경 기록).
- 함정: 운영 compose에 `KISTA_ROLE`을 넣는 배포와 코드 배포의 순서. **env를 먼저 배포**(구 코드는 무시)하고 코드를 배포한다. 반대면 기동 실패다.
- `docs/agents/commands.md`의 "SCHEDULER_ENABLED 일회성 override" 주의 문구를 `kista.role`로 고친다.

**P5. 사용자 이벤트 경로 단순화** (Opus) — `findings/09` M4, `findings/10` E-3·H-2.
지금: R `UserEventStreamPublisher` → Redis Stream → T `UserEventStreamConsumer` → `UserEventRepublisher`가 **같은 이벤트 클래스를 로컬 재발행** → `TradingUserCascadeListener`. 한 컨텍스트에 올리면 재발행된 이벤트를 다시 스트림 발행자가 받아 **무한 순환**한다.
- **단계 P에서는 손대지 않는다(사용자 결정 2026-11-13, Q-06).** 컨슈머가 유스케이스를 직접 부르는 안은 EPR과 같은 보장을 주지 않는다 — 스트림은 24회(약 2시간) 뒤 포기하고, IAE·NPE를 poison으로 보고 알림 없이 ack하며, 내구성이 Postgres 행이 아니라 Redis pending 목록에 기댄다. 두 프로세스인 동안은 순환이 생기지 않으므로 지금 경로를 그대로 둔다.
- 단계 M의 합병 커밋(M9)에서 스트림 경로를 통째로 지운다: R `UserEventStreamPublisher`, T `UserEventStreamConsumer`·`UserEventRepublisher`·`UserEventStreamBridge`. R이 발행한 `UserDeletedEvent`·`UserNotifyProfileChangedEvent`를 T `TradingUserCascadeListener`·`UserNotifyProfileSyncListener`가 직접 받는다. 두 리스너는 지우지 않는다 — `@TransactionalEventListener`라 EPR 재시도·재기동 재발행 보장이 그대로 남는다.
- 리스너 FQCN·메서드 시그니처를 바꾸지 않는다(EPR listener_id 유지). 4-3의 EPR 이관이 미완료 행을 그대로 옮긴다.
- `UserEventResubmitScheduler` 2벌은 M9에서 한 벌로 합친다(빈 이름 충돌, `findings/10` A5). 남는 한 벌의 필터 대상에 위 두 이벤트가 들어 있는지 확인한다.
- 스트림 발행자를 지우기 전 `stream:user.deleted`·`stream:user.notify-profile.changed`의 pending 0건을 확인한다(4-3 사전 확인에 추가). 남아 있으면 옛 `kista-trading`이 소진할 때까지 기다린 뒤 정지한다.
- `user_notify_profile` 복제본과 `UserNotifyProfileSyncListener`는 예정대로 단계 X에서 지운다.

**P6. 오류 로그 이중 기록 방지 준비** (Sonnet)
`AppErrorRaisedEvent`를 R은 직접 저장하고 T는 스트림으로 R에 보낸다. 합치면 한 이벤트가 2행이 된다. 단계 M의 같은 커밋에서 T `AppErrorStreamPublisher`와 R `AppErrorStreamConsumer`를 지운다(여기서는 삭제 목록만 확정).

**P7. 결정 사항 1건: 스케줄러 라이프사이클 알림** — `findings/10` E-3.
`SchedulerLifecycleEvent`는 R `SchedulerNotifier`만 듣는다. 지금 매매 잡의 시작·실패 알림은 가지 않는다. 합치면 관리자 텔레그램으로 새로 나간다. **기본값: 매매 잡은 알림에서 제외해 현재 동작을 유지한다**(잡 이름 필터). 받고 싶으면 사용자가 켠다.

단계 P 완료 판정: 전체 검증 통과, 3 role 운영 배포 후 2거래일 관찰(개장·마감 배치 정상). 탈퇴 cascade 확인(로컬 dev-e2e 사용자)은 경로가 바뀌는 단계 M 완료 판정으로 옮긴다.

## 4. 단계 M — 한 애플리케이션으로 합치고 2 role로 전환

### 4-1. 코드 (Opus 설계·검수, Sonnet 적용) — 한 브랜치에서 끝낸다

| # | 작업 | 근거·주의 |
|---|---|---|
| M1 | root `build.gradle.kts`에 `implementation(project(":trading-core"))`. T의 `bootJar` 비활성, 일반 `jar` 활성. `Dockerfile`에서 `trading-core.jar`·`APP_JAR` 분기 삭제 | `findings/10` J·K절 |
| M2 | 앱 클래스 하나: `ApiApplication`을 유지하고 `@SpringBootApplication(scanBasePackages={"com.kista.api","com.kista.tradingcore"})`, `@EntityScan`·`@EnableJpaRepositories`·`@ConfigurationPropertiesScan`에 두 패키지 명시. `TradingApplication` 삭제. **`com.kista`를 통째로 스캔하지 않는다**(S는 `PlatformAutoConfiguration`으로만 올린다는 기존 의도). `@EnableScheduling`이 남아 있는지 확인(과거 누락 사고) | C3 |
| M3 | yml 병합: T의 `application{,-local,-prod}.yml`을 R 쪽에 키 단위로 합치고 T 파일을 지운다. 같은 이름의 클래스패스 리소스라 둘 다 두면 한쪽이 조용히 사라진다. 값이 다른 키의 결정은 `findings/10` B-1 표의 "합병 판단" 열을 따른다. `spring.application.name`은 **`kista-api` 유지**(Grafana 쿼리 17곳이 이 값으로 필터), `management.metrics.tags.role=${kista.role}` 추가 | B절 |
| M4 | search_path: P3 덕에 SQL은 의존하지 않는다. `connection-init-sql`은 `SET search_path TO public`으로 단순화하고, Modulith EPR 스키마는 `public` 명시 | C1 |
| M5 | Flyway 2벌: `Flyway` 빈을 직접 2개 정의(`rootFlyway`: `db/migration`, `flyway_schema_history_api`, `public` / `tradingFlyway`: `db/migration-trading`, `flyway_schema_history_trading`, `trading`), 각 `initMethod="migrate"`. **빈을 하나라도 직접 정의하면 Boot 자동구성이 통째로 꺼진다**(Boot 4.1.1 소스 확인). 기존 env 계약 `SPRING_FLYWAY_BASELINE_ON_MIGRATE`는 새 프로퍼티(`kista.flyway.*.baseline-on-migrate`)로 대체하고 RUNBOOK 갱신. `DataJpaTestBase`에 이 구성을 `@Import`(슬라이스는 직접 만든 Flyway 빈을 로드하지 않는다 — 이미 migrate된 로컬 DB에서는 우연히 통과하므로 **빈 DB로 한 번 검증**) | D절 |
| M6 | `scheduler_locks`: 한 테이블로. **`trading.scheduler_locks`를 쓴다**(매매 배치 재개가 잔여 락을 인수하므로 이쪽 행을 보존). R 잡 이름과 T 잡 이름이 겹치지 않는지 확인. `public.scheduler_locks`는 contract 단계에서 drop | C2 |
| M7 | EPR 한 테이블: `public.event_publication`. 전환 절차는 4-3 | E-2 |
| M8 | Modulith: 모델 1개. ID 충돌 `notify`·`web`은 T 쪽을 `trading-notify`·`trading-web`으로 개명(임시. 단계 R에서 최종 이름). `ModulithArchitectureTest` 2개를 1개로(모듈 19개). listener_id는 FQCN 기반이라 ID 개명과 무관 — **이 단계에서 패키지와 클래스 이름은 바꾸지 않는다** | E-1 |
| M9 | 삭제: T `AppErrorStreamPublisher` + R `AppErrorStreamConsumer`(P6), 사용자 이벤트 스트림 경로 4종과 `UserEventResubmitScheduler` 한 벌(P5 — 남기면 이벤트 무한 순환), `PlainDecimalSerializer` 중복 1벌, T `com.kista.tradingcore.ClockConfig`(P2에서 넘어옴 — R 것과 본문 동일. `main_code_must_not_read_system_clock_directly` 예외 목록에서 T FQCN도 지운다) | E-3, A3 |
| M10 | `RoleRoutingGuard`·`RoleRoutes`(1-3) 추가. 이 단계의 엔진 경로 = 현재 Caddy `@trading`·`@scheduler` matcher와 `/api/internal/**` | K-2 |
| M11 | 내부 HTTP는 **그대로 둔다.** web의 `INTERNAL_API_BASE_URL`은 `http://kista-worker:8080`. worker 자신이 내부 API를 부르는 경로가 있으면(구 kista-scheduler 잡) `http://localhost:8080` | H-1 |
| M12 | 테스트 정리: `TradingApplicationContextLoadTest`·`SchedulerLockService*Test`의 `TradingApplication` 참조 교체, `RootDataJpaTestBase` 삭제 후 `DataJpaTestBase`로, `GradleModuleBoundaryTest`는 "T → R 의존 금지"만 남기고 "R → T 금지" 삭제. `InternalApiContractTest`는 단계 X까지 유지 | D-3, J절 |
| M13 | springdoc: `paths-to-exclude: /api/internal/**`를 합친 yml에. 스키마 단순명 충돌(`Market` 2개, `MarketSessionResponse` 2개, `InvestmentPoint` 2개)은 `@Schema(name=...)`으로 분리. UI의 스펙 병합 스크립트는 단일 스펙으로 바뀐다(`cross-repo.md`) | G-3 |
| M14 | 리소스: Hikari 풀(`findings/10` C4 — 지금 JVM당 5, DB `max_connections` 확인 후 web 5·worker 10 정도로), `TaskScheduler` 풀 크기(R·T 스케줄러가 풀 하나를 공유하게 된다. 기본 1이면 긴 잡이 다른 잡을 막는다 → `spring.task.scheduling.pool.size`를 명시), JVM 힙(web 1280m, worker 2048m에서 시작) | B-2, B-3 |

### 4-2. 예상되는 오류와 대처

| 증상 | 원인 | 대처 |
|---|---|---|
| `ConflictingBeanDefinitionException` | 같은 simple name의 `@Component` | P2에서 빠진 것. S로 합치거나 명시 이름 |
| `NoUniqueBeanDefinitionException` | 타입 후보 2개(`TokenBlacklistPort` 등) | 중복 구현 삭제. `@Primary`로 덮지 않는다 |
| `Ambiguous mapping` | 같은 메서드+경로(조사상 0건이지만 정규식 추출의 한계) | 실제 중복이면 한쪽 삭제 판단을 보고 |
| `Schema-validation: missing table` | `@Table(schema)` 누락 또는 스캔 범위 | 엔티티에 스키마 명시(조사상 42/42 명시) |
| 테이블 없음(빈 DB에서만) | 슬라이스 테스트에 Flyway 구성 미포함 | M5의 `@Import` |
| 순환 빈 참조 | R 빈과 T 빈이 서로 주입 | `@Lazy`로 덮지 않는다. 어느 쪽이 내부 HTTP 어댑터를 통해야 하는지 확인(단계 M에서는 R → T 직접 주입이 생기면 안 된다) |
| `ApplicationModules.verify()` 실패 | R 모듈이 T 모듈 내부 참조 | 단계 M에서는 새 참조가 생기면 안 된다. 생겼다면 범위를 넘은 것 |
| 응답 상태가 바뀜(409 → 500 등) | P1 누락 | P1의 표 테스트로 확인 |
| 오류 로그 2행 | M9 누락 | |
| 기동 직후 CPU 100%, Redis 스트림 폭증 | M9의 P5 삭제 누락(이벤트 순환) | 즉시 중단, 스트림 길이 확인 |
| 스케줄 잡이 돌지 않음 | `@EnableScheduling` 누락 또는 `kista.role` 조건 | `/actuator/scheduledtasks` 또는 로그의 스케줄 등록 확인 |
| 잡이 두 번 돔 | worker가 2개 떴다 | 4-3의 전환 순서 위반 |

### 4-3. 전환 배포 (kista-infra 세션과 함께, 매매 시간대 밖 계획 작업)

**main push 자동 배포로 내보내지 않는다.** kista-infra의 reconcile은 `roles`에 있는 서비스만 올리고, 새 번들에서 사라진 서비스의 컨테이너는 지우지 않는다. role 이름이 바뀌는 첫 배포를 자동으로 내보내면 옛 `kista-scheduler`·`kista-trading`이 고아로 계속 도는 옆에 `kista-worker`가 떠서 **스케줄러와 매매 배치가 동시에 두 벌 돈다**(kista-infra 지침서 03 5-1절). 전환 커밋은 배포 범위 판정에서 제외되게 하거나 자동 배포를 잠시 끄고, 아래 순서를 사람이 확인하며 진행한다.

사전(읽기 전용): `SELECT count(*), min(publication_date) FROM trading.event_publication WHERE completion_date IS NULL;` 와 `public` 쪽. 진행 중 배치가 없는지 `trading.scheduler_locks`와 배치 실행 테이블 확인.

1. 옛 `kista-scheduler` → `kista-trading` 순으로 **정지하고 컨테이너를 삭제**한다(trading은 `stop_grace_period`를 다 기다린다). `docker ps`에 두 이름이 없는 것을 확인한다. 옛 `kista-api`는 아직 둔다(가계부·로그인 유지. 매매 화면은 이 동안 응답하지 않는다).
2. EPR 이관: 미완료 행만 복사 `INSERT INTO public.event_publication SELECT * FROM trading.event_publication WHERE completion_date IS NULL;`(컬럼 동일). 복사한 행 수를 기록.
3. kista-infra `state/kista-api.yml`의 role 키를 `kista-web`·`kista-worker`로 바꾼다(`build-bundle.sh:17`이 roles 파일과 state 키 집합이 다르면 실패시킨다).
4. reconcile. `roles` 적용 순서는 **worker → web**이다. 근거: web의 관리자·벤치마크 화면이 단계 X 전까지 worker의 내부 API를 부르고, Flyway 2벌을 worker가 먼저 적용하면 web은 검증만 한다. EPR 재발행 소유는 worker 하나다.
5. `kista-worker` readiness 확인 → 로그에서 EPR 재발행 처리와 `TradingBatchResumer` 확인.
6. `kista-web` 기동 확인 → Caddy가 기본 upstream으로 `kista-web`을, `@trading`·`@scheduler`로 `kista-worker`를 가리키는지 확인.
7. 옛 `kista-api` 컨테이너를 정지·삭제한다.
8. 확인: 로그인, 가계부 조회(web), 전략 목록·주문 미리보기(worker), 관리자 스케줄러 상태, `/actuator/prometheus`의 role 태그, 오류 로그 1행, `docker ps`에 옛 role 이름 없음.

롤백: 새 2 role을 정지·삭제하고 state 키를 되돌린 뒤 구 이미지 SHA로 3 role을 올린다. **2번에서 복사한 EPR 행은 `public`에 남으므로**, 롤백 전에 그 행들의 `completion_date`를 확인하고 미완료면 `public`의 복사본을 지운다(`trading` 쪽 원본이 재발행된다. 이중 처리 방지). 리스너가 멱등인지에 기대지 않는다.

바뀌는 배포 파일 전수는 `findings/10` K-3 표를 따른다(compose, roles, readiness, bluegreen, Caddy 스니펫, `pre-apply.sh`의 role→schema 매핑, `detect-deploy-scope.sh`, `plan-deploy.sh`, bats, Grafana). `check-migrations.sh`의 expand/contract 검사를 **trading 디렉토리에도 적용**한다(D3).

단계 M 완료 판정: 전체 검증 통과, 빈 DB에서 기동 성공, 2거래일 관찰.

## 5. 단계 X — 경계 코드 제거

원칙: 엔진 모듈을 부르는 컨트롤러는 worker에서 뜨므로, 내부 HTTP 어댑터는 **worker 안의 직접 호출**이 된다. 3자 매핑표(`findings/10` H-1)에 어댑터 메서드 → 내부 엔드포인트 → 실제 서비스가 전부 있다.

| # | 작업 | 주의 |
|---|---|---|
| X1 | market(어댑터 2) → benchmark(2) → admin(7) 순으로 어댑터를 직접 호출로 교체하고 대응 내부 컨트롤러·`*ContractMapper`·contract 타입을 지운다. 해당 컨트롤러 경로(`/api/market/**` 캔들·캘린더, `/api/stats/housing-benchmark/**`, `/api/admin/` 중 매매·계좌·PRIVACY·포트폴리오·스케줄러)를 `RoleRoutes`의 엔진 경로로 옮기고 Caddy matcher를 갱신 | 대상 타입이 NamedInterface로 공개돼 있지 않으면 `verify()`가 실패한다. 이 단계에서는 기존 공개 범위(`usecase`·`port`)를 쓴다. 공개 범위 축소는 단계 R |
| X2 | `Admin*` 복원용 예외 3쌍과 오류 복원 코드(`InternalApiErrorDetails`, `InternalApiStatusHandlers`, T `ErrorBodyDecoder`) 삭제, P1의 매핑 표에서 원본 예외 하나로 | 응답 상태·code가 같아야 한다(P1 테스트) |
| X3 | `POST /api/internal/trading/trade-corrections`의 20초 쓰기 타임아웃은 직접 호출이 되면 사라진다. 감사 로그가 트랜잭션 밖에서 기록되는 구조는 유지 | |
| X4 | 스케줄러 수동 트리거: `TradingSchedulerInternalController`가 스케줄러 빈(인바운드 어댑터)을 직접 부른다. 유스케이스로 감싸고 관리자 컨트롤러가 그것을 부른다. worker 전용 | |
| X5 | **`/api/internal/fida-orders`는 유지**(fida는 별도 레포 유지로 사용자 결정 2026-11-12). 같이 할 것: 응답 `id` 필드명 고정 단언, `orders`에 `@NotNull`(현재 null이면 NPE→500 가능), `tradeDate`↔`releaseDate`(D-1) 의미를 계약 주석에 확정 — `cross-repo.md` D절. : 외부 FIDA 프로젝트가 호출한다. `InternalTokenAuthFilter`와 `/api/internal/**` 보안 규칙도 남는다. 요청·응답 타입은 contract에서 privacy 모듈의 `api`로 옮긴다. FIDA 통합 여부(fable-fida 검토)에 따라 나중에 달라진다 | |
| X6 | 사용자 복제본 제거: `TradingUserProfilePort` 호출처 전수(`findings/10` H-3 — 알림 notifier 7종, `BatchContextFactory`, `MarketEventNotifier`, `StrategyCreationService`, `CryptoDcaRunner`, `UserTradingDataEraseService`)를 user 모듈의 읽기 API로 바꾼다. user 모듈에 `UserNotifyProfile` 조회 유스케이스를 만든다(R `UserNotifyProfilePublisher`가 이미 `UserPort` + `UserSettingsPort`로 같은 스냅샷을 조립한다). native SQL `StrategyJpaRepository.java:44-58`의 `JOIN user_notify_profile`은 활성 사용자 id 목록을 user API로 받아 `IN`으로 거르거나, 예외 대장에 올리고 `public.users` 조인으로 | "다른 모듈 소유 테이블 참조 금지" 규칙의 유일한 긴장 지점. 기본값은 **user API로 id 목록을 받는 쪽**(규칙 유지). 활성 사용자 수가 수천을 넘으면 재검토 |
| X7 | 스트림 삭제: `user.notify-profile.changed`(발행자·컨슈머·`UserNotifyProfileSyncListener`), `user.push-notification.requested`(T 발행자·R 컨슈머 → worker가 `PushNotificationPort` 직접 호출). `user_notify_profile` 테이블 drop은 다음 배포(contract) | FCM 자격증명이 worker에도 필요(`findings/09` S8). 알림 채널 판정 2곳(`TradingUserNotificationAdapter.java:175`, `UserPushNotificationAdapter.java:32`)을 한 곳으로 |
| X8 | `shared.contract` 삭제(FIDA 타입 제외), `InternalApiClientConfig`·`InternalApiProperties`·`INTERNAL_API_BASE_URL` 삭제, `InternalApiContractTest` 삭제, own-type 원장(`decisions.md` 5절) 재판정 | |
| X9 | 통계 캐시: stats가 worker에만 있으므로 프로세스 간 무효화 문제(S1)는 없다. 확인만 | |

완료 판정: 전체 검증, `grep -rn "api/internal" src trading-core --include=*.java`가 FIDA 경로만 남음, 2거래일 관찰(알림 발송·관리자 화면·벤치마크).

## 6. 단계 R — 모듈 재구성

목표 구성(패키지 루트는 최종적으로 `com.kista.<module>`):

```
user  account  broker  calendar  matching  trading(+privacy 중첩 검토)  stats
market  benchmark  finance  notification  ops  platform  app(모듈 아님: 앱 클래스·role 가드·조립)
```

규칙:
1. 모듈이 공개하는 NamedInterface는 `api`(다른 모듈이 부르는 서비스와 입출력 값)와 `events`뿐이다. `domain`·`port`·`usecase`를 통째로 공개하지 않는다. `allowedDependencies`를 package-info에 선언한다.
2. `shared.kernel` 해체: `Strategy*` → trading, `Broker`·`Market` → broker, `User*`·`NotificationChannel` → user. 세 모듈 이상이 쓰는 순수 값(`Currency`, `Quantities`, `TimeZones`, `AccountNumberMasker`)만 platform. 타입별 참조 수와 원 소유는 `findings/10` I절 표.
3. `calendar`: `DstInfo`(trading), `MarketSessionTimes`, `SessionTradeDates`, `UsTradeDates`(platform)를 흡수. `TossPriceApi`의 세션 판정 복제 삭제(지침서 04).
4. `admin` 해체: 관리자 엔드포인트는 데이터 소유 모듈의 `Admin*Controller`. 남는 것(런타임 설정, 감사·오류 로그)은 `ops`.
5. `web` 셸 해체: advice·보안 정책은 platform, 조립 코드는 `app`.
6. `notification`: 채널 선택과 발송만. 무엇을 누구에게 보낼지는 업무 모듈이 정해 `notification :: api`를 호출. 남의 이벤트를 듣는 리스너는 이벤트를 발행한 모듈 쪽으로.
7. 1,500줄 미만 모듈은 3단 패키지를 강제하지 않는다.

**이 단계의 최대 위험: 이벤트·리스너 클래스의 패키지 이동.** EPR의 `listener_id`는 `FQCN.method(이벤트FQCN)`이고 직렬화된 이벤트도 타입 이름으로 복원한다. 옮기면 미완료 행이 고아가 된다.
- 이벤트·리스너를 옮기는 커밋은 **그것만** 담고, 배포 직전 운영에서 미완료 행이 0인지 확인한다(`deploy/hooks/pre-apply.sh`·`epr-orphans.sh`, `docs/agents/docker-infra.md`의 EPR 절). 0이 아니면 소진을 기다리거나 런북대로 `listener_id`를 UPDATE한다.
- `@Enumerated(STRING)` 컬럼의 enum은 상수 이름만 유지하면 패키지 이동은 안전하다. JSON으로 저장된 타입 이름(EPR `serialized_event`, Redis 페이로드)은 별도 확인.

순서: ① kernel 해체(이벤트 제외) ② calendar ③ admin·web 해체 ④ notification ⑤ 이벤트·리스너 이동(EPR 절차) ⑥ 공개 범위 축소와 `allowedDependencies` ⑦ 패키지 루트를 `com.kista.<module>`로(대량 rename, IDE 리팩토링, 이것도 EPR 절차). 각각 별도 커밋 묶음·별도 배포.

지침서 01 단계 B(UseCase 인터페이스 제거)를 ⑥ 전에 끝낸다. ⑥에서 `api`로 공개할 타입이 구체 서비스 클래스가 된다.

문서: 모듈 관계도는 Modulith `Documenter` 생성물로 바꾸고 `architecture.md`의 모듈 표를 지운다(지침서 09).

## 7. 단계 G — 마무리

- Gradle: `:trading-core`·`:shared`를 루트 프로젝트로 합친다(모듈 경계는 Modulith와 ArchUnit이 지킨다). testFixtures·`workingDir` 특례 삭제.
- UI 진입점: API 게이트웨이는 Caddy 하나다. kista-ui의 서버 측 호출도 Caddy 내부 주소 하나로 보내 UI가 role을 모르게 한다. **fable-infra가 로컬에서 실증했다(2026-11-12, 운영과 같은 `caddy:2.9-alpine`)**: api 스니펫(`deploy/server/caddy/kista-api.caddy`)의 사이트 주소에 하나를 더 적는다 — `{$API_DOMAIN}, http://:8080 { … }`. 한 사이트 블록이라 `@trading`·`@scheduler` matcher가 그대로 재사용된다. 8080은 infra compose의 공개 포트가 아니라 `shared_net` 안에서만 닿는다. UI는 `API_BASE_URL=http://caddy:8080` 하나를 쓰고 `TRADING_API_BASE_URL`을 지운다(UI 지침서 02 태스크 8). 스니펫만 바뀌는 배포는 caddy reload뿐이다. **이 변경은 단계 M 직후에 해도 된다**(단계 G까지 기다릴 필요 없다).
  - 확인할 것 (a) 내부 리스너 요청은 `X-Forwarded-Proto: http`, `Host: caddy:8080`으로 온다. 이 값으로 쿠키 Secure·리다이렉트 주소·CORS를 판단하는 코드가 api·ui에 있는지 grep한다(`app.cookie.secure`는 프로퍼티 고정이라 영향이 없어야 한다) (b) `CaddyRoutingTest`가 주소 2개짜리 사이트 줄을 파싱하는지 (c) FIDA의 `/api/internal/fida-orders`는 공인 도메인 경로 그대로다.
  - fable-ui 확인 결과(2026-11-12): UI는 api의 리다이렉트를 브라우저로 넘기지 않고 AT 쿠키를 직접 만든다. api의 RT `Set-Cookie`는 3곳에서 그대로 전달하지만 `RefreshTokenCookieHelper.java:23-38`이 Secure·SameSite를 설정값으로 만들고 Domain을 달지 않아 영향이 없다. **불변 조건: RT 쿠키를 요청의 scheme·Host로 만들지 않는다.** `server.forward-headers-strategy`를 켜거나 Secure를 `request.isSecure()`로 바꾸면 UI 중계가 깨진다. 이 조건을 `RefreshTokenCookieHelper`의 주석과 테스트(요청 scheme이 http여도 Secure 값이 설정대로)로 고정한다.
  - SSE(fable-infra 로컬 실증 2026-11-12): 내부 리스너를 지나는 SSE는 버퍼링되지 않고(`flush_interval` 지정 불필요, `encode` 포함 상태), 130초 무송신에도 끊기지 않았다. upstream 정지 시 스트림은 정상 종료로 닫히고 재연결하면 새 컨테이너로 간다. `caddy reload`에도 유지된다. 미확인: undici Agent로 붙었을 때와 Spring SSE의 graceful 종료 — 첫 실배포에서 본다.
  - worker가 내려가 있을 때의 응답(실증 완료): 엔진 handle 안에서 `vars engine 1`을 심고 사이트 블록 수준의 `handle_errors`에서 그 변수와 상태 502·503·504로 가른다(`handle_errors`는 `handle @trading` 안에 넣을 수 없다. matcher를 다시 적지 않는다). 본문 `{"status":503,"code":"ENGINE_RESTARTING","detail":"매매 서버를 재시작하는 중입니다. 잠시 후 다시 시도해 주세요."}`, `Content-Type: application/problem+json`, `Retry-After: 30`. Caddy가 만든 오류에만 걸리고 worker가 직접 응답한 5xx는 그대로 지나간다. 스니펫 전문은 kista-infra 지침서 03 5-5절. `RoleRoutingGuard`의 `WRONG_ROLE`도 같은 형식이다. 두 code를 `ErrorCode`에 추가한다. detail에 내부 호스트·경로를 넣지 않는다.
  - 엔진 대역의 `lb_try_duration`은 **5초**(지금 `@scheduler`는 120초). worker 재시작이 3~4분이라 길게 기다려도 넘기지 못하고 요청만 붙잡힌다. web 대역은 현재 값 유지.
  - `CaddyRoutingTest`가 `vars`·`handle_errors` 줄과 주소 2개짜리 사이트 줄을 견디는지 확인하고 고친다.
  - 통합 OpenAPI 스펙 주소: `/api-docs`(지금의 root 주소 그대로, 단계 M부터 trading 경로 포함). UI의 스펙 병합 스크립트(`scripts/fetch-spec.mjs`)는 단일 주소 내려받기로 바뀐다.
  - 상세: kista-infra 지침서 03 5-4절.
- 문서 전면 갱신(지침서 09): `architecture.md`, `constraints.md`, `docker-infra.md`, `commands.md`, `local-dev.md`, `flyway.md`, `flows.yml`.

## 8. 선택 단계 (지금은 하지 않는다)

- **B. 조회 화면을 web으로**: 브로커를 부르지 않는 trading 조회(전략 목록, 주문·사이클 이력, 통계)를 web 경로로 옮겨 worker 배포 중에도 뜨게 한다. 선행: 통계 캐시 무효화를 Redis Pub/Sub으로 전파(S1), 조회 경로가 브로커 포트에 닿지 않음을 ArchUnit으로 고정. 시작 조건: worker 배포 중 화면 중단이 실제 불편이 될 때.
- **C. 쓰기·브로커 호출도 web으로**: 선행 M1(계좌 예산 락을 세션 단위 advisory lock 또는 Redis 락으로. 임계 구역 본문이 여러 짧은 트랜잭션이라 트랜잭션 하나로 감싸면 의미가 바뀐다), M2(호출 간격을 Redis 슬롯 예약으로), M3(토큰 lease 일반화), `orders` 슬롯 유일 인덱스. 지침서 04의 토큰 단순화(메모리 캐시)는 이 단계와 양립하지 않는다. 시작 조건: 다중 worker가 필요해질 때. 지금 규모(계좌 8, 활성 전략 9)에서는 필요 없다.

## 9. 판단이 갈릴 때의 기본값

| 상황 | 기본값 |
|---|---|
| 어떤 엔드포인트가 web인지 worker인지 애매 | worker. 호출 그래프가 broker 모듈이나 trading 쓰기에 닿으면 무조건 worker |
| web이 엔진 데이터를 읽어야 함 | DB 읽기 전용 `api` 호출만. 화이트리스트(`RoleBoundaryTest`)에 추가하고 이유를 주석으로 |
| 새 role 간 통신이 필요해 보임 | 만들지 않는다. 해당 엔드포인트를 worker로 옮긴다 |
| 충돌을 `@Primary`·`@Lazy`·`allow-bean-definition-overriding`으로 넘기고 싶음 | 금지. 원인(중복)을 없앤다 |
| 동작이 바뀌는 것을 발견(알림이 새로 나감, 상태 코드 변화) | 현재 동작을 유지하고 보고 |
| 한 단계가 예상보다 커짐 | 쪼개서 배포한다. 단계 M만은 쪼갤 수 없다(코드 합병 + 전환) |

## 10. 중단 조건 (사용자에게 보고하고 멈춘다)

- 단계 M에서 사용자 이벤트 스트림 경로를 지운 뒤 탈퇴 cascade가 EPR 행 없이 실행된다(리스너가 `@TransactionalEventListener`가 아니게 됐거나 발행이 트랜잭션 밖이다).
- 단계 M에서 R ↔ T 순환 빈 참조가 구조적으로 풀리지 않는다.
- 전환 배포 중 옛 `kista-scheduler`·`kista-trading` 컨테이너가 남아 있거나 worker가 2개 뜬 흔적(잡 이중 실행, 락 인수 로그 2회).
- 전환 후 개장·마감 배치의 주문 수·금액이 전환 전 미리보기와 다르다.
- EPR 미완료 행이 재기동 후에도 줄지 않는다.

## 11. 재심한 과거 결정

| 결정 | 재심 결과 |
|---|---|
| S-01 2-프로세스 분리(코드베이스 분리) | 뒤집는다. 런타임 분리(엔진 단일 프로세스)는 유지 |
| S-03 스키마 5분리 | 유지. P3으로 SQL이 스키마를 명시 |
| S-04 물리 DB 분리 보류 | 폐기 |
| S-05 Modulith, `explicitly-annotated` | 유지, 모델 1개 |
| S-07·S-08·S-09 NamedInterface·contract·own-type | 공개 범위를 `api`·`events`로 축소, contract 소멸(FIDA 제외), 원장 재판정 |
| S-31 EPR 재발행 소유 | worker 하나 |
| `AccountBudgetLock`의 JVM 락(주석의 승격 계획) | 유지. 엔진 단일 프로세스가 전제임을 주석과 `constraints.md`에 명시 |
| "kista-trading 단일 인스턴스·stop-first·200s"(`constraints.md` Git 규칙) | worker에 그대로 승계 |
| memory "Pub/Sub @PostConstruct 유지"(2026-10-31) | 유지. worker → web 매매 이벤트 Pub/Sub은 남는다 |
| C-02 세션 계산 복제 | 뒤집는다(calendar) |

## 12. 다른 지침서·레포와의 관계

- 04: 단계 A(KIS·업비트 삭제)를 가장 먼저. 토큰 단순화는 이 문서의 원칙 1-1을 전제로 한다.
- 01: 단계 B는 P와 병행 가능, 단계 C(포트 제거)는 단계 R ⑥과 묶는다.
- 02: View·결과 record의 위치는 모듈 `api`. 02의 trading 저장소 통합(T6)은 단계 X 뒤.
- 10: 내부 API 목록 규약(T4 ④)은 불필요해진다. 단일 OpenAPI 스펙(M13).
- kista-infra: role 2개, 전환 배포(4-3, infra 지침서 03 5-1절과 짝), Caddy 내부 리스너(7절), `KISTA_ROLE` env, EPR 훅, 고아 컨테이너 감지(infra 03 T8). 인스턴스 구성은 "운영 1대(3 OCPU·18GB로 확대, fida 포함) + 최소 대기 1대"로 사용자 결정(2026-11-12) — worker·web·DB가 한 호스트라는 전제는 그대로다.
- kista-ui: 스펙 병합 스크립트(M13), 진입점(7절).
- fida: `/api/internal/fida-orders` 계약(X5).

## 13. 실행 전 보정 (2026-11-14 — 본문과 다르면 이 절이 우선)

공통 규칙은 `00-roadmap.md` 9절. 측정 원본은 `findings/23`. 남은 것: P3·P4·P6, 단계 M·X·R·G.

**P3 실행 결과(2026-11-15)**: 대상 전부 한정, 스키마 판단이 애매한 테이블 0. 별칭을 새로 쓴 곳은 `SchedulerLockService`(`INSERT INTO %s AS l`)뿐. 루트 테스트 classpath에는 두 `application.yml`이 있지만 루트 resources가 앞이라 `public.scheduler_locks`를 받는다(기존 search_path가 갈리던 방식과 같다). M6은 이 프로퍼티를 role별로 두지 않고 `trading.scheduler_locks` 하나로 정하면 된다.

**P4 실행 결과(2026-11-15)**: `shared.platform.role.@ConditionalOnWorkerRole`(`kista.role` = web|worker|all, 누락·오타는 기동 실패), 게이트 16개 교체, base yml `${KISTA_ROLE:all}`·prod `${KISTA_ROLE}`(기본값 없음), 가드 `scheduled_classes_must_be_gated_by_worker_role`(`adapter.in.redis`·shared 제외). 본문과 다르게 한 것: ① 스트림 컨슈머(root·trading-core 모두 `adapter/in/redis`)에는 게이트를 붙이지 않았다 — 복구 `@Scheduled`만이 아니라 구독 전체가 꺼지고, root 컨슈머는 kista-api도 돌리고 있어 동작이 바뀐다. M9에서 지운다 ② "env 먼저 배포"는 따로 하지 않는다 — `KISTA_ROLE`은 `.env`가 아니라 레포의 compose `environment:`에 있고 kista-infra reconcile이 compose·images.env를 한 release로 묶어 role별로 올리고 롤백도 묶음째라, 구 코드+새 compose나 새 코드+구 compose 조합이 생기지 않는다(리뷰가 `reconcile.sh`로 확인) ③ `.env.example`은 바꿀 것이 없다. 로컬 root는 gitignored `application-local.yml`의 `kista.role: web`이 이전 `scheduler.enabled: false`를 대신한다(다른 개발 환경에서는 그 파일을 같이 고쳐야 스케줄러가 꺼진다).

전제 정정:

- P3의 nativeQuery 수는 지금 R 16곳, T 39곳이다(본문 11·약 45). 대상은 수가 아니라 "전부"이고 신설 가드가 빠진 것을 잡는다.
- P4: `scheduler.enabled`를 말하는 문서가 본문보다 많다. `README.md`, `deploy/server/docker-compose.yml`, `docs/agents/`의 `local-dev.md`·`commands.md`·`docker-infra.md`·`scheduler-time-table.md`·`modules/tradingcore/{dca,account,trading}.md`, `docs/architecture-map.md`를 같이 고친다. `kista.role`은 아직 어디에도 없다.
- M9의 "`PlainDecimalSerializer` 중복 1벌"은 대상이 없다(10 T1·T2가 다른 방식으로 처리했다). 건너뛴다.
- M5가 고치라고 한 `deploy/server/schema-reorg/RUNBOOK.md`는 09 T2가 지운다. 09가 먼저 끝났으면 baseline 계약은 `docker-infra.md`에 적는다.
- X6의 `TradingUserProfilePort` 호출처는 착수할 때 다시 grep한다. 본문 목록의 `CryptoTradeNotifier`·`CryptoDcaRunner`는 15가 지우고, `TradingUserProfiles`·`UserNotifyProfilePersistenceAdapter`·`UserNotifyProfileSyncListener`가 목록에 빠져 있다.
- X5의 `FidaOrderRequest.orders` `@NotNull`은 끝났다(`820203d13`).
- G의 `RefreshTokenCookieHelper`는 `api/user/adapter/in/web/security/`에 있다.
- `lb_try_duration`(120초)과 `build-bundle.sh:17`은 kista-infra 레포의 값이다. 이 레포에서 확인되지 않으므로 전환 계획을 쓸 때 kista-infra에서 지금 값을 읽는다.

같은 커밋에서 고칠 가드:

| 태스크 | 가드 |
|---|---|
| P4 | `GradleModuleBoundaryTest:74`(`..adapter.in.schedule..` 참조 규칙), `SchedulerDisabledContextTest`. 둘 다 `scheduler.enabled`를 전제한다 |
| M8(모듈 ID 개명) | `ArchitectureMapTest:40-44`(두 모델, `api.notify`·`tradingcore.notify`·`shared.kernel` 이름 단언), `ModuleGraphExporter`, `docs/agents/modules/` 파일명, `architecture.md`의 모듈 표 |
| M9 | `HexagonalArchitectureTest:179-180`의 `ClockConfig` FQCN 둘 중 T 쪽. 합친 `UserEventResubmitScheduler`가 `api.web`에 남아 서비스를 직접 쓰면 `web_must_stay_pure_inbound_sink`에 걸리므로 소유 모듈의 `adapter/in/schedule`로 옮긴다 |
| M12 | `TradingApplication`을 참조하는 테스트 6개, `RootDataJpaTestBase`를 상속한 테스트 20개 |
| X1~X7 | `InternalApiContractTest`는 X8까지 둔다. 중간에 contract 타입이 전부 사라져 규칙 대상이 비면 그 커밋에서 X8을 앞당겨 테스트를 지운다 |
| R②·⑦ | `HexagonalArchitectureTest:70,99,136`, `:296-311`, `GradleModuleBoundaryTest:55,120,130`, `TradingCoreTestVendorModelTest`. 패키지 문자열만 고치고 규칙의 뜻(의존 방향, 시간 기준, 계좌 접근 제한)은 그대로 둔다 |

기본값(9절 표에 더한다):

- 가드를 통과시키려면 규칙을 지우거나 예외를 늘려야 한다면 그 이동은 하지 않는다. 다음 순번(①~⑦)으로 넘어가고 건너뛴 이유를 이 절 아래에 적는다.
- M3의 값이 다른 yml 키는 `findings/10` B-1 표를 따르되, 표에 없는 키가 나오면 worker(지금의 kista-trading) 값을 쓴다.
- 단계 P·X·R의 배포는 동작이 같으면 승인 대상이 아니다. **단계 M의 전환 배포만은 일정과 롤백 계획을 사용자가 승인한 뒤에 한다**(4-3절). 실행 세션은 단계 M 브랜치를 만들고 4-3절 사전 확인 SQL의 결과와 전환 순서를 정리해 보고하는 데까지 한다.

중단 조건의 대체 경로(10절에 더한다):

- R ↔ T 순환 빈 참조: 순환을 만드는 호출 가운데 web 쪽에서 엔진으로 가는 호출을 읽기 전용 `api` 인터페이스 호출로, 엔진에서 web 쪽으로 가는 호출을 이벤트로 바꾼다. 그래도 안 풀리면 단계 M 브랜치를 합치지 않고 순환 경로(빈 이름 사슬)를 `questions.md`에 "사용자 결정 필요"로 적는다. `@Lazy`로 넘기지 않는다.
- 탈퇴 cascade가 EPR 행 없이 실행된다: M9에서 T 리스너 둘(`TradingUserCascadeListener`·`UserNotifyProfileSyncListener`)이 `@TransactionalEventListener`로 남아 있고 발행 지점이 트랜잭션 안인지 본다. 고쳐도 EPR 행이 남지 않으면 스트림 경로를 그대로 둘 수는 없다(한 컨텍스트에서는 같은 이벤트를 서로 재발행해 순환한다 — 2절의 M4 위험). 대안은 Q-06의 선택지 1이다: 컨슈머가 trading 내부 전용 이벤트로 재발행하고 T 리스너가 그 타입을 듣는다. 리스너 파라미터 타입이 바뀌므로 배포 직전 EPR 미완료 행 0건을 확인한다.
- 전환 뒤 주문 수·금액이 미리보기와 다르다, worker가 둘 떴다, EPR 미완료 행이 줄지 않는다: 4-3절의 롤백을 사용자에게 요청한다. 원인을 찾기 전에 다시 전환하지 않는다.

## 14. 2차 실행 전 보정 (2026-11-15, Fable — 본문·13절과 다르면 이 절이 우선)

Fable이 마지막으로 본 절이다. 측정 원본은 `findings/29-merge-readiness.md`(위험·충돌·M 목록 재측정, kista-infra 배포 구성 원본은 부록 D). 13절의 정정 가운데 이 절이 다시 고친 것: 가드 줄 번호(규칙 이름으로 찾는다), M9 삭제 목록, M12, M14, 4-3 전체.

### 14-1. 순서 결정

```
P3 → P4            (W2. 지금 시작한다. 04 A를 기다리지 않는다)
04 단계 A(KIS 삭제)  (P3·P4와 다른 세션에서 병행 가능. M 브랜치를 만들기 전에 main에 들어가 있어야 한다)
15 단계 A(코인 삭제) (아래 조건이 맞으면 M 앞, 아니면 M 관찰 뒤)
M 브랜치 → 전환 배포(14-3) → 2거래일 관찰
X → R → G
```

| 결정 | 이유 | 반대 순서의 비용 |
|---|---|---|
| **P3·P4는 지금 한다** | M4(search_path)·M10(role 가드)의 선행이다. KIS·코인 전용 파일에는 native SQL이 0개라 04 A·15를 기다려도 줄어드는 작업이 없다(`findings/29` 3-2) | 기다리면 M이 그만큼 늦어질 뿐이다 |
| **04 A를 M 앞에 둔다** | ① 1-1 원칙의 전제 가운데 토큰 발급 경합(M3)이 KIS에만 있다. 지우면 사라진다 ② 운영 데이터를 지우는 SQL과 가드 5곳 수정이 M 관찰 기간과 겹치지 않는다 ③ 사용자 쪽 작업이 끝나 외부 대기가 없다 | M을 먼저 하면 지울 파일 25개를 합병 때 한 번 더 만지고(yml 키 3, 빈 12, `ApplicationContextLoadTest`의 저장소 수), 전환 직후 이상이 생겼을 때 원인 후보에 KIS 삭제가 섞인다 |
| **15는 조건부로 M 앞** | 코인 삭제는 M 항목을 없애지 않는다(구조 작업이라). 줄어드는 것은 빈 41개, 10분 틱 스케줄러, X6 호출처 2개다. 한편 15 단계 A는 kista-ui 코인 화면 제거 배포(`cross-repo.md` G1), S16, 사용자의 정책 비활성(15 단계 P ②)을 기다린다 | 15를 무조건 앞에 두면 ui 일정이 M을 막는다. 15를 무조건 뒤에 두면 전환 당일 `crypto-dca` 틱이 10분마다 돌고, 합병 때 코인 파일 100여 개를 옮긴다 |
| P6은 따로 할 일이 없다 | 삭제 목록이 14-2로 확정됐다 | — |

**15의 판정 규칙(실행 세션이 스스로 정한다):** P3·P4·04 A가 main에 들어간 날, 15 단계 A의 선행 넷(S17 배포 ✅, 사용자 단계 P ①✅·②, ui G1 배포, S16)이 전부 끝나 있으면 15 단계 A를 M 앞에 한다. 하나라도 남았으면 기다리지 않고 M 브랜치를 시작하고, 15는 M의 2거래일 관찰이 끝난 뒤에 한다. M 브랜치를 시작한 뒤에는 15를 끼워 넣지 않는다.

같이 지킬 것:

- M 브랜치가 열려 있는 동안 main에 들어가도 되는 것은 문서와 긴급 수정뿐이다. 합병 브랜치는 파일 대부분을 건드려 rebase 비용이 크다. 다른 묶음(S7·S9·S13·N8 등)은 M 브랜치 시작 전이나 전환 뒤로 미룬다.
- 전환 주에는 N9(06 T3~)·N19(14 T3 시세 전환)를 배포하지 않는다. S11·S15 T2의 관찰(2026-11-16~18)이 끝난 뒤에 전환한다.
- P3·P4·04 A는 동작이 같으므로 배포 승인 대상이 아니다(9-4). 단 P4는 env 먼저(`KISTA_ROLE`을 3 role compose에 넣는 배포 → 코드 배포), 04 A의 데이터 SQL은 사용자 승인 뒤다.

### 14-2. M 커밋 목록 보정 (4-1절 표에 덮어쓴다)

| # | 보정 |
|---|---|
| M1 | 이미지는 원래 1개다. 할 일은 root에 `implementation(project(":trading-core"))`, T의 `bootJar` 끄고 `jar` 켜기, `Dockerfile`의 `:trading-core:bootJar`·`trading-core.jar` 복사·`APP_JAR` 삭제, compose의 `APP_JAR`·`SERVER_PORT` 삭제. `shared/build.gradle.kts`의 `bootJar` 설정도 같이 본다 |
| M2 | `@EntityScan`·`@EnableJpaRepositories`는 지금 없다(기본 스캔). 새로 넣는다. `@ConfigurationPropertiesScan`은 `com.kista.tradingcore`만 주면 된다(R은 쓰지 않는다) |
| M3 | 값이 다른 키는 11개고 전부 `findings/10` B-1에 있다. 표에 없는 한쪽 키 6개는 R 값을 그대로 둔다(prod 값은 양쪽이 같다). `spring.application.name`은 `kista-api`, `server.port` 8080, `spring.data.redis.url`·`cors.allowed-origins`는 R 쪽 정의 |
| M5 | `SPRING_FLYWAY_BASELINE_ON_MIGRATE`는 어디에도 없다. 대체 프로퍼티를 만들지 않는다. **M 브랜치에는 마이그레이션 파일을 하나도 넣지 않는다**(롤백이 스키마와 무관해야 한다 — 14-3). `public.scheduler_locks`·`trading.event_publication` drop은 단계 X 뒤의 별도 배포다 |
| M6 | 락 이름 R 8·T 7, 겹침 0. 운영 `public.scheduler_locks`에 `trading-open`·`trading-close` 등 낡은 행이 있지만 `trading` 쪽 테이블을 쓰므로 영향이 없다. 건드리지 않는다 |
| M9 | 삭제 대상 확정: ① T `AppErrorStreamPublisher` + R `AppErrorStreamConsumer`(테스트 각 1, `flows.yml:1530,1540`) ② R `UserEventStreamPublisher`, T `UserEventStreamConsumer`·`UserDeletedStreamConsumer`·`UserNotifyProfileStreamConsumer`·`UserEventRepublisher`·`UserEventStreamBridge`(테스트 9개, `flows.yml:593,601-602,1193`) ③ **R `api.web.UserEventResubmitScheduler`를 지우고 T 쪽을 남긴다** — T가 `AccountDeletedEvent`까지 거르는 상위집합이고 이미 `adapter/in/schedule`에 있어 13절의 "옮긴다"가 필요 없다. R 쪽 테스트(`UserEventResubmitSchedulerTest`·`UserEventResubmitEndToEndIT`)의 단언 가운데 T 테스트에 없는 것만 옮긴다 ④ T `ClockConfig`(가드의 FQCN 예외 한 줄) ⑤ **`ExceptionReporter` 한 벌 — T `TradingExceptionReporter`를 지우고 R `ApiExceptionReporter`를 남긴다**(13절에 없던 항목. 안 지우면 `NoUniqueBeanDefinitionException`). `app_error_logs` context의 caller 값이 매매 쪽 오류도 `ApiExceptionReporter`로 찍히게 된다 — 알고 넘어간다. `PlainDecimalSerializer`는 없다 |
| M10 | 신설 대상 전부 0건에서 시작한다(P4가 `kista.role`·`@ConditionalOnWorkerRole`을 먼저 만든다). `RoleRoutes`·`RoleRoutingGuard`는 인터페이스를 두지 않는다(`PENDING_REMOVAL_CEILING` 여유 0) |
| M11 | worker의 `INTERNAL_API_BASE_URL`은 `http://localhost:8080`이다. `@Scheduled` 잡이 내부 API를 부르는 일은 없지만, worker로 라우팅되는 `/api/admin/scheduler/**`의 수동 트리거가 내부 HTTP를 거쳐 자기 자신을 부른다. web은 `http://kista-worker:8080` |
| M12 | "R → T 금지" 규칙은 원래 없다. 고칠 것: `ApplicationContextLoadTest`의 "trading-core 빈이 없다" 단언 삭제와 저장소 수 합산, `TradingApplicationContextLoadTest`의 단언을 그쪽으로 합치기, `TradingApplication` 코드 참조 4파일, `RootDataJpaTestBase` 상속 19개, `OpenApiSchemaNameCollisionTest` 두 벌을 한 벌로(두 패키지를 함께 스캔해야 R·T 사이 충돌을 잡는다), `OpenApiNullMarkedRecordContractTest` 두 벌 |
| M13 | 본문의 충돌 3건은 지금 없는 것으로 본다(`findings/29` 3-1: `MarketSessionResponse`는 이미 이름을 갈랐고, `Market`은 enum 인라인, `InvestmentPoint`는 스펙 밖). 할 일은 `paths-to-exclude` 병합과, 합친 `OpenApiSchemaNameCollisionTest`가 통과하는지 보는 것이다. 테스트가 새 충돌을 잡으면 14-4의 기본값(내부·관리자 쪽에 `@Schema(name)`) |
| M14 | 가상 스레드가 켜져 있다(`spring.threads.virtual.enabled: true`). `spring.task.scheduling.pool.size`를 넣지 않는다. 확인만 한다: 컨텍스트의 `TaskScheduler` 빈이 `SimpleAsyncTaskScheduler`인지. `ThreadPoolTaskScheduler`면 크기 4를 준다. Hikari는 `maximum-pool-size: ${DB_POOL_MAX:5}`로 두고 compose에서 worker만 10(DB `max_connections` 100, 지금 사용 7). 힙: web `-Xmx1280m`·`mem_limit 2048m`, worker `-Xmx2048m`·`mem_limit 3072m`, 둘 다 `MaxMetaspaceSize=384m`(합친 컨텍스트라 320m에서 올린다) |
| 배포 파일 | 같은 브랜치에서: `deploy/server/{docker-compose.yml,roles,bluegreen,README.md}`, `caddy/kista-api.caddy`(upstream 이름만. `lb_try_duration`·`handle_errors`는 넣지 않는다 — 14-3 끝), `deploy/hooks/pre-apply.sh`(매핑을 `kista-worker) schema=public`으로, `kista-web`은 건너뜀), `.github/scripts/detect-deploy-scope.sh`(플래그를 `verify`·`config`·`web`·`worker`로. jar가 하나이므로 main 소스가 바뀌면 web·worker 둘 다), `.github/tests/{compose-invariants,deploy-plan,pre-apply}.bats`. compose 이미지 변수 이름은 `KISTA_WORKER_IMAGE`·`KISTA_WEB_IMAGE`여야 한다(kista-infra가 role 이름에서 만든다). `compose-invariants.bats`에 "재발행 플래그는 `kista-worker`만 true, `kista-web`에는 없다"를 넣는다(지금 가드는 trading 한 줄만 본다) |

M 브랜치 완료 판정에 더한다:

1. 빈 DB에서 `kista.role=all`로 기동 성공.
2. **로컬 2 프로세스 리허설**: 같은 jar를 `KISTA_ROLE=worker`(8081)와 `KISTA_ROLE=web`(8080)으로 띄워 ① worker 로그에 스케줄 등록과 `market-calendar-startup` 락 획득 ② web 로그에 스케줄 등록 없음 ③ web에 엔진 경로를 치면 503 `WRONG_ROLE` ④ dev 사용자 탈퇴 → `public.event_publication`에 `TradingUserCascadeListener` 행이 생기고 완료됨 ⑤ `stream:user.deleted`·`stream:app.error`의 `XLEN`이 늘지 않음. 이때 본 worker·web의 기동 로그 줄을 14-3의 5·6단계 기대값으로 적어 둔다(이 문서의 해당 칸을 채운다).
3. `deploy-plan.bats`에 "state의 role 키가 옛 이름(`kista-api`·`kista-scheduler`·`kista-trading`)이고 새 플래그가 켜진 경우 role SHA가 바뀌지 않는다"를 넣는다(14-3의 안전 장치가 여기에 기댄다).

### 14-3. 전환 런북 (4-3절을 대체한다)

kista-infra의 지금 동작(`findings/29` 부록 D)이 4-3절 초안과 다른 점:

- **자동 배포를 끄는 스위치가 없다.** skip 토큰도 경로 필터도 없다. 대신 `build-bundle.sh:17`이 "번들의 `roles`와 state의 role 키가 다르면" 서버를 건드리기 전에 실패한다. 전환은 이 실패를 안전 장치로 쓴다.
- `plan-deploy.sh:46`은 state의 role 이름에서 `kista-`를 뗀 문자열을 `detect-deploy-scope.sh`의 플래그 키로 쓴다. state 키와 플래그 이름이 같은 시점에 바뀌어야 한다.
- reconcile의 자동 롤백은 직전 release의 compose로 되돌리는데 거기에는 새 role이 없다. 이름이 바뀌는 배포가 실패하면 수동 복구다.
- infra의 `Reconcile App` 수동 실행은 state보다 옛 SHA를 무시한다. 롤백은 서버에서 `reconcile.sh`를 직접 부른다.
- `pre-apply.sh`는 모르는 role 이름을 건너뛴다. M 브랜치가 매핑을 고쳐야 전환 배포에서 EPR 검사가 돈다.
- Caddy 스니펫 설치와 reload는 reconcile이 모든 role 통과 뒤 스스로 한다.
- `lb_try_duration`은 지금 전 대역 120초(`kista-infra/Caddyfile:9`), 게이트는 role당 최대 5분, compose healthcheck `start_period` 180초.

**초안과 다르게 정한 것 — 옛 `kista-api`도 내린다(전체 정지).** 초안은 가계부·로그인을 살리려고 옛 `kista-api`를 끝까지 뒀다. 그러면 ① 옛 api가 전환 중에 발행한 사용자 이벤트가 소비자 없는 스트림으로 가서 사라지고(새 코드는 스트림을 읽지 않는다) ② 옛 리스너 id의 EPR 행이 새로 쌓이며 ③ 메모리가 겹치고 ④ 고아 알림이 온다. 활성 사용자가 2명이고 창이 토요일 낮이다. 15~20분 전체 정지가 이 넷을 다 없앤다. 사용자가 무중단을 원하면 14-3 끝의 "옛 api를 살려 두는 변형"을 쓴다.

**일시:** 토요일 KST 09:00~12:00(금요일 마감 리포트 뒤, 월요일 22:00 개장 배치까지 약 60시간). 미국장 조기 폐장·휴장 주여도 무방하다. 평일이면 KST 09:00~15:00. **사용자가 정한다.**

**역할:** 사용자 = 일시 결정, 각 관문의 go/no-go, push·workflow 실행, 브라우저 확인. 실행 세션(kista-api) = 읽기 전용 조회, 사용자가 그 자리에서 승인한 서버 쓰기 명령(`docker stop/start/rm`, EPR SQL). kista-infra 세션 = state 키 커밋(D-0 3단계)과 그 레포의 테스트. 운영 쓰기는 한 단계씩 승인받는다.

#### D-7 ~ D-1 (준비)

| # | 누가 | 할 일 | 확인 |
|---|---|---|---|
| a | 실행 세션 | M 브랜치 완성, 14-2의 완료 판정 3개 통과 | 전체 검증 통과, 리허설 기록 |
| b | 실행 세션 | 배포 전 확인표(로드맵 9-4)를 사용자에게 보고: 이 런북, 리허설에서 얻은 기대 로그, M 브랜치에 마이그레이션 0개(`git diff main --stat -- '*.sql'`가 비어 있음) | 사용자 승인 |
| c | kista-infra 세션 | state 키 변경 커밋을 **브랜치로** 준비(`cross-repo.md` I-1). main에는 아직 넣지 않는다 | infra bats 통과 |
| d | 사용자 | D-1 낮(매매 시간대 밖)에 M 브랜치를 main에 합치고 push | 아래 e |
| e | 실행 세션 | push 뒤 `Server Deploy` 실행을 본다. **기대: infra `Reconcile App`이 "deploy/server/roles와 state의 role 목록이 다름"으로 실패하고 텔레그램 `[배포 실패]`가 온다. 서버는 그대로다.** `ssh kista-server 'docker ps --format "{{.Names}} {{.Image}}"'`의 이미지 SHA가 push 전과 같아야 한다 | 다르면 즉시 중단·보고(전환이 자동으로 시작된 것이다) |
| f | 사용자 | api `Server Deploy`를 수동 실행(`build_only=true`)해 M 커밋의 이미지를 만든다 | `docker manifest inspect ghcr.io/narafu/kista-api:<M SHA>` 성공 |

d 이후 전환이 끝날 때까지 kista-api main에 다른 커밋을 push하지 않는다(push해도 e와 같은 이유로 배포는 실패하지만 알림이 온다).

#### D-0 (전환. 괄호는 예상 소요)

**0. 사전 확인 — 읽기 전용 (5분).** 하나라도 어긋나면 그날은 하지 않는다.

```bash
ssh kista-server 'date; docker ps --format "{{.Names}}\t{{.Status}}"; readlink /opt/kista-api/current; ls /opt/kista-api/releases'
ssh kista-server 'docker exec -i kista-postgres psql -U kista -d kistadb -At' <<'SQL'
select 'epr', 'trading', count(*) from trading.event_publication where completion_date is null
union all select 'epr', 'public', count(*) from public.event_publication where completion_date is null;
select 'lock', name, lock_until > now() as held, finished_at from trading.scheduler_locks where name in ('trading-open','trading-close');
select 'submitting', count(*) from trading.orders where status = 'SUBMITTING';
select 'err', coalesce(max(created_at)::text, '-') from public.app_error_logs;
SQL
ssh kista-server 'docker exec kista-infra-redis-1 sh -c "for s in stream:user.deleted stream:user.notify-profile.changed stream:app.error stream:user.push-notification.requested; do echo \$s; redis-cli XLEN \$s; redis-cli XPENDING \$s \$(redis-cli XINFO GROUPS \$s | sed -n 2p); done"'
```

| 기대값 | no-go |
|---|---|
| 컨테이너 3개(`kista-api-kista-api-N`·`kista-scheduler`·`kista-trading`) healthy | 하나라도 아니면 |
| EPR 미완료 두 스키마 모두 0 | 0이 아니면 10분 뒤 재확인, 그래도 남으면 no-go(2026-11-15 기준 0) |
| `trading-open`·`trading-close` 모두 `held = f` | `t`면 배치가 도는 중이다 |
| `SUBMITTING` 0 | 0이 아니면 |
| 스트림 4종 pending 0 | 0이 아니면 |
| `current`가 가리키는 release id를 적는다(**옛 release id**, 롤백에 쓴다). `XLEN` 네 값과 `app_error_logs` 최신 시각도 적는다 | — |

사용자: 브라우저에서 전략 한 개의 주문 미리보기 화면을 캡처해 둔다(장이 닫혀 있어 전환 뒤에도 같은 값이어야 한다).

**1. 정지 (2~8분).** 사용자 go 뒤. 삭제(`rm`)하지 않는다 — 정지된 컨테이너가 가장 빠른 롤백 수단이다.

```bash
ssh kista-server 'docker stop $(docker ps -q --filter label=com.docker.compose.project=kista-api --filter label=com.docker.compose.service=kista-api)'   # 35초 이내
ssh kista-server 'docker stop kista-scheduler'    # 최대 200초
# 스트림 pending 0을 한 번 더 확인한 뒤(0단계의 redis 명령)
ssh kista-server 'docker stop kista-trading'      # 최대 200초
ssh kista-server 'docker ps --format "{{.Names}}" | grep -c "kista-api-kista-api\|kista-scheduler\|kista-trading"'   # 기대 0
```

이 시점부터 API 전체가 502다. UptimeRobot 경보가 온다(예상된 것).

**2. EPR 이관 (1분).** 0단계의 SELECT를 다시 돌린다. 두 스키마 모두 0이면 이 단계는 없다. `trading` 쪽이 0이 아니면(1단계 정지 중에 생긴 행) 사용자 승인 뒤:

```sql
insert into public.event_publication select * from trading.event_publication where completion_date is null returning id;
```

돌려받은 id를 적는다. `public` 쪽에 `UserEventStreamPublisher` 리스너의 미완료 행이 있으면 no-go(M9가 그 리스너를 지웠다. pre-apply 훅도 여기서 막는다) — 롤백하고 그 행이 소진된 뒤 다시 잡는다.

**3. state 키 전환 (2분).** kista-infra 세션이 c의 브랜치를 main에 합쳐 push한다. `state/kista-api.yml`의 `roles`가 `kista-worker`·`kista-web` 두 키가 되고 값은 **지금의 옛 SHA 그대로**다(요청이 state보다 새것이어야 채택된다).

**4. reconcile 시작 (1분).** 사용자가 kista-infra의 `Reconcile App`을 수동 실행한다.

```bash
gh workflow run reconcile.yml -R narafu/kista-infra -f app=kista-api -f config=<M SHA> -f roles="kista-worker=<M SHA> kista-web=<M SHA>"
```

`force`는 주지 않는다(pre-apply 훅이 `public.event_publication`을 새 소스와 대조한다). 훅이 막으면 no-go → 롤백.

**5. worker (3~5분).** reconcile이 `roles` 순서대로 worker부터 올린다.

```bash
ssh kista-server 'docker ps --filter name=kista-worker --format "{{.Status}}"'      # 기대: Up … (healthy)
ssh kista-server 'docker logs kista-worker 2>&1 | grep -E "Started ApiApplication|ERROR|WARN.*(Flyway|role)" | tail -20'
echo "select name, locked_at, finished_at from trading.scheduler_locks where name = 'market-calendar-startup'" | ssh kista-server 'docker exec -i kista-postgres psql -U kista -d kistadb -At'
```

| 기대값 | no-go |
|---|---|
| 5분 안에 healthy | 게이트 실패(reconcile이 exit 1·2) |
| `Started ApiApplication` 1줄, ERROR 0줄 | ERROR가 있으면 내용을 보고 판단. `BeanDefinition`·`Flyway`·`Schema-validation`이면 no-go |
| `market-calendar-startup`의 `locked_at`이 방금 시각 | 옛 시각이면 worker 전용 빈이 뜨지 않은 것이다 → no-go |
| 리허설에서 적어 둔 스케줄 등록·재개 로그 줄(14-2 완료 판정 2) | 없으면 no-go |

**6. web과 Caddy (3~5분).** reconcile이 이어서 web을 올리고 스니펫을 설치·reload한다.

```bash
gh run list -R narafu/kista-infra --workflow reconcile.yml -L 1      # 기대: completed success, 이어서 state 커밋 deploy(kista-api): …
ssh kista-server 'docker ps --format "{{.Names}}\t{{.Status}}\t{{.Image}}" | grep kista-'
ssh kista-server 'cat /opt/kista-infra/routes/kista-api.caddy | grep reverse_proxy'   # 기대: kista-worker 2줄, kista-web 1줄
curl -s -o /dev/null -w '%{http_code}\n' https://<API 도메인>/api/meta               # 기대 200 (web)
curl -s -o /dev/null -w '%{http_code}\n' https://<API 도메인>/api/trading-cycles     # 기대 401 (worker까지 닿음. 502·503이면 no-go)
ssh kista-server 'docker logs $(docker ps -q --filter label=com.docker.compose.service=kista-web) 2>&1 | grep -ciE "scheduled|TradingBatchResumer"'   # 기대: 리허설의 web 값(스케줄 등록 없음)
```

**7. 기능 확인 (10분).** 사용자가 브라우저로: 로그인, 가계부 조회, 전략 목록, 0단계에서 캡처한 주문 미리보기(값이 같아야 한다), 관리자 스케줄러 상태 화면. 실행 세션은:

```sql
select count(*) from public.app_error_logs where created_at > '<0단계의 최신 시각>';            -- 기대 0
select count(*) from public.event_publication where completion_date is null;                 -- 기대 0
select count(*) from trading.event_publication where completion_date is null;                -- 기대 0(이후 여기에는 행이 생기지 않는다)
```

Redis `XLEN stream:user.deleted`·`stream:user.notify-profile.changed`·`stream:app.error`가 0단계 값과 같아야 한다(발행자가 없어졌다). `stream:user.push-notification.requested`는 단계 X까지 쓰이므로 늘 수 있다.

**8. 확정 또는 롤백.** 7단계까지 전부 맞으면 go: 옛 컨테이너를 지운다.

```bash
ssh kista-server 'docker rm kista-scheduler kista-trading $(docker ps -aq --filter label=com.docker.compose.project=kista-api --filter label=com.docker.compose.service=kista-api)'
ssh kista-server 'docker ps -a --format "{{.Names}}\t{{.Status}}" | grep kista-'    # 기대: kista-worker, kista-api-kista-web-N, kista-ui-…만
```

**시간:** 1단계 시작부터 6단계 끝까지 15~25분이 전체 정지 구간이다. **1단계 시작 뒤 45분이 지나도 6단계가 끝나지 않으면 원인을 찾지 말고 롤백한다.**

#### 롤백 (10~15분)

8단계에서 옛 컨테이너를 지우기 전이면 언제든 가능하다. M 브랜치에 마이그레이션이 없으므로 스키마는 그대로다.

1. 새 role을 내리고 지운다: `docker stop kista-worker $(docker ps -q --filter label=com.docker.compose.service=kista-web)` → 같은 대상 `docker rm`. `docker ps`에 둘 다 없는 것을 확인한다.
2. EPR을 되돌린다(사용자 승인). worker가 `public`에 남긴 매매 쪽 미완료 행은 옛 `kista-trading`이 읽지 못한다.
   ```sql
   select id, listener_id from public.event_publication where completion_date is null and listener_id like 'com.kista.tradingcore.%';
   -- 2단계에서 복사한 id가 여기 있으면 public에서 지운다(trading의 원본이 재발행된다)
   delete from public.event_publication where id in (<2단계에서 적은 id>) and completion_date is null;
   -- 전환 뒤에 새로 생긴 행은 trading으로 옮긴다
   with moved as (delete from public.event_publication where completion_date is null and listener_id like 'com.kista.tradingcore.%' returning *)
   insert into trading.event_publication select * from moved;
   ```
   2단계에서 복사한 행 가운데 worker가 이미 완료한 것은 `trading` 원본에도 완료를 찍지 않으면 한 번 더 실행된다. 그런 행이 있으면 id와 리스너를 사용자에게 보이고 정한다(알림 중복은 감수, 주문 취소·데이터 삭제 리스너면 원본을 지운다).
3. 옛 release로 되돌린다: `ssh kista-server 'nohup bash /opt/kista-infra/bin/reconcile.sh kista-api <옛 release id>'`. 정지해 둔 옛 컨테이너 3개가 다시 뜨고 Caddy 스니펫이 옛것으로 돌아간다. 실패하면 `docker start kista-trading` → healthy → `docker start <옛 api 컨테이너>` → `docker start kista-scheduler` 순으로 직접 올리고 `/opt/kista-api/releases/<옛 id>/caddy/kista-api.caddy`를 `/opt/kista-infra/routes/`에 복사한 뒤 `docker exec kista-infra-caddy-1 caddy reload --config /etc/caddy/Caddyfile`.
4. 0단계의 확인을 다시 돌려 3 role healthy와 EPR을 본다.
5. kista-infra 세션: state 키 커밋(과 성공했다면 자동 state 커밋)을 되돌려 3 role 키·옛 SHA로 맞춘다.
6. 사용자: kista-api main에서 M 합병 커밋을 `git revert`하고 push한다(3 role 플래그가 돌아와 되돌린 코드가 정상 배포된다). 그 전에는 main에 push하지 않는다.
7. 원인을 `questions.md`에 "사용자 결정 필요"로 적는다. 원인을 찾기 전에 다시 전환하지 않는다.

8단계(옛 컨테이너 삭제) 뒤에 되돌려야 하면 1·2·5·6은 같고 3은 `reconcile.sh kista-api <옛 release id>`만 쓴다(이미지를 다시 받는다).

#### 전환 뒤

- 관찰 2거래일(월요일 22:00 개장 배치부터). 볼 것: 개장·마감 배치의 주문 수와 금액(전략별로 직전 거래일과 같은 꼴), `trading.scheduler_locks`의 `trading-open`·`trading-close`가 한 번씩만 잡혔는지(`locked_by`가 하나), 텔레그램 리포트 1통씩, `app_error_logs`, EPR 미완료 0, healthchecks.io 핑. 관찰 결과를 로드맵 N1 칸에 적는다.
- 첫 일반 배포에서 web의 blue/green 교체가 처음 돈다(전환 때는 실행 중 컨테이너가 없어 stop-first로 떴다). 교체 중 web이 2개 뜨는 것이 정상이다.
- 하지 않고 넘긴 것(안정된 뒤 따로): 엔진 대역 `lb_try_duration` 5초와 `handle_errors`의 `ENGINE_RESTARTING` 응답(7절. kista-infra `Caddyfile`의 `(upstream)` 스니펫이 인자를 더 받아야 한다 — `cross-repo.md` I-3), Caddy 내부 리스너와 ui의 `API_BASE_URL` 단일화, Grafana 대시보드의 role 구분.

#### 옛 api를 살려 두는 변형 (사용자가 무중단을 고른 경우에만)

1단계에서 `kista-api`를 내리지 않고 7단계 뒤에 내린다. 더할 것: ① 전환 창 동안 가입·승인·프로필·알림 설정을 바꾸지 않는다(바꾸면 그 이벤트는 사라진다. 전환 뒤 `select count(*) from trading.user_notify_profile p join public.users u on u.id = p.user_id where p.updated_at < u.updated_at`로 어긋남을 본다) ② 7단계에서 `public.event_publication`의 미완료 행 가운데 `listener_id like '%UserEventStreamPublisher%'`를 확인하고 사용자 승인 뒤 지운다 ③ 메모리 합계가 한도를 넘지 않는지 `docker stats`로 본다 ④ `kista-api: 고아 kista-api` 텔레그램이 한 번 온다. 매매 화면은 어느 쪽이든 worker가 뜰 때까지 응답하지 않는다.

### 14-4. 판단 지점의 기본값과 멈출 조건 (9·10절과 13절의 표에 더한다)

처리 순서는 로드맵 9-5 그대로다: 되돌린다 → 이 표의 기본값 → 없으면 그 태스크만 멈추고 사용자. "멈춘다"는 그 커밋을 합치지 않고 `questions.md`에 "사용자 결정 필요"로 적는다는 뜻이다.

**단계 M — 코드**

| 판단 지점 | 기본값(묻지 않는다) | 멈추고 묻는다 |
|---|---|---|
| `ConflictingBeanDefinitionException` | 표에 있는 것(`ClockConfig`, `UserEventResubmitScheduler`)은 14-2대로 한 벌 삭제. 표에 없는 새 이름이면 두 클래스의 본문을 비교해 같으면 T 쪽 삭제, 다르면 T 쪽에 `@Component("trading…")` 명시 이름 | 두 벌의 동작이 다르고 어느 쪽이 맞는지 테스트로 가릴 수 없을 때 |
| `NoUniqueBeanDefinitionException` | `ExceptionReporter`는 14-2. 그 밖에는 중복 구현을 지운다. `@Primary`·`@Qualifier`로 덮지 않는다 | 두 구현이 서로 다른 일을 해서 둘 다 필요할 때(그러면 타입을 나눠야 한다 — 설계 변경) |
| 순환 빈 참조 | 13절의 대체 경로 | 그래도 안 풀리면 |
| `ApplicationModules.verify()` 실패 | M은 새 모듈 간 참조를 만들지 않는다. 실패는 대개 ID 개명(`trading-notify`·`trading-web`) 누락이나 삭제한 클래스를 가리키던 `allowedDependencies`다. 그것만 고친다 | R 모듈이 T 모듈 내부를 새로 참조해야만 통과할 때(범위를 넘었다) |
| yml 값 충돌 | B-1 표 → 표에 없으면 14-2의 M3(R 값) → prod 값이 서로 다르면 worker(옛 trading) 값 | prod 값이 다르고 그 키가 주문·보안·토큰에 닿을 때(`jwt.*`, `crypto.*`, `internal.api.*`, `app.trading.*`) |
| `Schema-validation` 실패 | 엔티티 42개는 전부 스키마를 명시하고 있다(2026-11-15). 그래도 나면 스캔 범위(M2의 `@EntityScan`)부터 본다 | 실제 컬럼이 엔티티와 다를 때(합병과 무관한 기존 드리프트 — 따로 보고) |
| Flyway 2벌이 이력 테이블을 새로 만들려 한다 | 빈 정의의 `table`·`defaultSchema`·`locations`를 지금 yml 값과 글자 그대로 맞춘다. baseline 옵션을 켜지 않는다 | 운영과 같은 이력을 가진 로컬 DB에서 `migrate`가 "적용할 것 없음"이 아닐 때 |
| 가드가 막는다 | 로드맵 9-3. FQCN·패키지 문자열·개수는 고친다. 규칙의 뜻과 부딪히면 그 항목을 하지 않는다 | 가드를 지우지 않으면 합병 자체가 안 될 때 |
| 구현 하나짜리 인터페이스가 필요해 보인다 | 만들지 않는다(구체 클래스) | — |
| 동작 보존 테스트가 기대값을 바꿔야 통과한다 | 되돌린다. 예외: `app_error_logs` caller 값(14-2 M9)과 저장소 수·모듈 수 같은 구조 단언 | 응답 상태·본문·주문 값이 바뀔 때 |
| 합친 스펙에 스키마명 충돌 | `@Schema(name=)`을 **내부·관리자 쪽** 타입에 단다(ui가 읽는 공개 스키마 이름은 그대로) | 공개 스키마 이름을 바꿔야만 할 때 |
| 리허설에서 탈퇴 cascade가 EPR 행 없이 돈다 | 13절의 대체 경로 | 그 뒤에도 |
| M 브랜치에 마이그레이션이 필요해 보인다 | 넣지 않는다. 필요한 변경은 M 앞(P 단계)이나 관찰 뒤로 뺀다 | 마이그레이션 없이는 기동이 안 될 때 |
| M이 너무 크다 | 쪼갤 수 있는 것은 미리 main에 넣는다: M9의 ④⑤는 못 뺀다(두 앱이 각자 필요). M13의 `@Schema` 이름, M12의 테스트 기반 클래스 통합(`RootDataJpaTestBase` → `DataJpaTestBase`)은 M 앞에 따로 넣어도 된다 | — |

**단계 M — 전환 당일.** 14-3의 no-go 칸이 전부다. 실행 세션이 스스로 정하는 것은 "no-go면 롤백을 요청한다"뿐이고, 운영 쓰기(정지·기동·삭제·SQL)는 전부 그 자리에서 사용자 승인을 받는다. 기대값과 다른데 no-go 칸에 없는 경우에는 진행하지 않고 값을 보여 준다.

| 판단 지점 | 기본값 | 멈추고 묻는다 |
|---|---|---|
| 0단계에서 EPR 미완료가 0이 아니다 | 10분 기다렸다 재확인. 리스너 id가 새 소스에도 있으면 진행 가능(2단계 이관) | 지워진 리스너(`UserEventStreamPublisher`)의 행이면 그날은 하지 않는다 |
| D-1의 push에서 배포가 실패하지 않고 진행됐다 | 즉시 `docker ps`로 상태를 보고 사용자에게 알린다. 손대지 않는다 | 항상 |
| worker가 healthy인데 스케줄 등록 로그가 없다 | no-go | — |
| worker가 둘 떴거나 옛 scheduler·trading이 실행 중이다 | 새 worker를 먼저 내린다(옛것이 원래 주인이다) → 롤백 | 항상 보고 |
| reconcile이 exit 2(수동 복구) | 롤백 절차 3번의 직접 기동 경로 | 항상 보고 |
| 월요일 개장 배치의 주문 수·금액이 직전 거래일의 꼴과 다르다 | 원인이 시세·사이클 진행으로 설명되면 기록만 | 설명되지 않으면 그날 마감 배치 전에 롤백 여부를 묻는다 |

**단계 X**

| 판단 지점 | 기본값 | 멈추고 묻는다 |
|---|---|---|
| 직접 호출로 바꾸려는 대상이 NamedInterface로 공개돼 있지 않다 | 대상 서비스에 `@NamedInterface("api")`를 단다(S6이 3개 서비스에 쓴 방식). 패키지를 통째로 공개하지 않는다 | — |
| `InternalApiContractTest`의 핸들러 하한(`> 20`)이 깨진다 | 그 커밋에서 하한을 남은 수에 맞춘다. contract 타입이 FIDA만 남으면 X8을 당겨 테스트를 지운다 | — |
| `CaddyRoutingTest`의 "root 경로는 엔진 대역에 걸리지 않는다"가 깨진다(X1) | 테스트를 `RoleRoutes` 기준으로 다시 쓴다: 엔진 모듈 컨트롤러 경로 ⊆ 엔진 matcher, 그 밖 ∩ 엔진 matcher = ∅ | Caddy 정규식으로 표현되지 않는 경로 조합이 나올 때(경로를 바꿔야 한다 → ui 계약) |
| 엔드포인트를 web → worker 대역으로 옮긴다 | 옮긴다. ui는 Caddy 뒤라 주소가 같다 | ui가 `TRADING_API_BASE_URL`로 직접 치는 경로면 `cross-repo.md`에 적고 같은 날 배포를 요청 |
| X2에서 예외 쌍을 합치니 응답 `code`가 달라진다 | 지금 ui가 받는 `code`를 유지한다(`ExceptionResponseTableTest`가 기준) | `ErrorCode` 상수를 없애야 할 때 |
| X6에서 활성 사용자 id 목록을 `IN`으로 넘긴다 | 그대로 한다(활성 사용자 2명) | — |
| X6의 호출처 수가 13절·`findings/29`와 다르다 | 착수 때 grep한 값으로 한다(15가 끝났으면 `CryptoTradeNotifier`·`CryptoDcaRunner`가 없다) | — |
| X7에서 FCM 자격증명이 worker에 필요하다 | 이미 있다(세 role이 같은 `.env`를 쓴다). compose를 고치지 않는다 | — |
| X7의 `user_notify_profile` drop | 코드 삭제 배포 뒤 2거래일을 보고 다음 배포에서(expand/contract) | drop SQL은 운영 데이터 삭제라 승인 |
| 스트림을 지우는 배포 직전 pending이 0이 아니다 | 그날은 배포하지 않는다(9-4) | 사흘 연속이면 |
| web·worker가 잠깐 다른 SHA로 돈다(reconcile이 worker → web 순서) | Pub/Sub `trade.event` 페이로드와 Redis 키 형식은 한 배포에서 필드를 추가만 한다. 삭제·개명은 두 배포로 나눈다 | — |

**단계 R**

| 판단 지점 | 기본값 | 멈추고 묻는다 |
|---|---|---|
| 이벤트·리스너 패키지를 옮기는 배포(⑤·⑦) | pre-apply 훅이 고아가 될 미완료 행을 막는다(M에서 매핑을 고쳤다). 배포 직전 `public.event_publication` 미완료 0을 확인하고 push를 요청한다. 훅이 막으면 소진을 기다린다 | 하루가 지나도 남으면: `listener_id` UPDATE(리스너만 옮김·멱등)냐 삭제냐를 행 목록과 함께 묻는다 |
| 옮기려는 타입 이름이 저장돼 있을 수 있다 | 옮기기 전에 grep: `@Enumerated`는 상수 이름만이라 안전, EPR `serialized_event`와 Redis 페이로드에 FQCN(`@class`)이 실리는지 한 건 조회로 본다. 실리면 EPR 절차를 탄다 | Redis에 FQCN이 든 장기 보관 키가 있을 때 |
| kernel 타입의 새 집이 애매하다 | `findings/10` I절 표의 "원 소유". 쓰는 모듈이 셋 이상이고 행위 없는 값이면 platform | 옮기면 모듈 순환이 생길 때 — 그 타입은 kernel에 남기고 넘어간다(13절 기본값) |
| ⑥에서 공개 범위를 줄이니 `verify()`가 깨진다 | 깨진 참조를 `api`에 올리지 말고 호출 쪽이 `api` 서비스를 쓰게 고친다. 한 모듈씩 | 도메인 타입을 통째로 공개해야만 풀릴 때 — 그 모듈은 지금 공개 범위를 유지하고 `decisions.md`에 이유를 적는다 |
| ⑦ 패키지 루트 rename | 가장 마지막. 한 커밋·한 배포. 직전에 EPR 미완료 0, web·worker 같은 SHA로 함께 | 15·N8·N9 등 다른 큰 작업이 열려 있으면 미룬다 |
| `HexagonalArchitectureTest` 줄 번호가 지침서와 다르다 | 규칙 이름으로 찾는다(13절 표의 줄 번호는 S6 뒤 밀렸다. `ClockConfig` FQCN은 `:210-211`) | — |
| 14의 `MarketDataPort`·Alpaca 어댑터를 `market`으로 옮긴다 | 인터페이스를 만들지 않는다(Q-12). 클래스만 옮긴다 | — |

### 14-5. 단계 X·R의 전제 확인 (2026-11-15)

- X1: R 내부 HTTP 어댑터 11개(market 2, benchmark 2, admin 7), 메서드 28. T 내부 핸들러 30(컨트롤러 13) — 본문의 31에서 1 줄었다.
- X6: `TradingUserProfilePort` 주입처 14곳(알림 notifier 6, `TradingUserProfiles`, `UserNotifyProfileSyncListener`, `BatchContextFactory`, `UserNotifyProfilePersistenceAdapter`, `CryptoDcaRunner`, `MarketEventNotifier`, `StrategyCreationService`, `UserTradingDataEraseService`). X7의 채널 판정 줄 번호는 낡았다 — `includesFcm`으로 찾는다.
- X8: `shared.contract` 타입 19개. 남는 것은 FIDA 2개(`FidaOrderRequest`·`FidaOrderResponse`)와, 단계 X 뒤에도 role 경계를 넘는 `notify`의 `TradeEventEnvelope`·`TradeEventMessage`(worker → web Pub/Sub). 뒤의 둘은 본문 X8이 빠뜨렸다 — 지우지 말고 notification 모듈의 `events`로 옮긴다.
- R: `shared.kernel` 타입 42개, 그중 이벤트 4개 + `platform.scheduling.SchedulerLifecycleEvent`. EPR 대상 리스너는 R 16·T 20개. 이벤트 클래스 34개 가운데 22개가 `tradingcore.*.application.event`·`api.*.application.event`에 있어 ⑦에서 전부 FQCN이 바뀐다.
- 6절 규칙 7("1,500줄 미만 모듈은 3단 패키지를 강제하지 않는다")과 `PENDING_REMOVAL_CEILING`: 지금 82/82다. R ⑥(01 단계 C와 묶음)에서 포트를 지울 때마다 목록과 상한을 같이 내린다.
- 8절 단계 C의 선행은 넷에서 셋이 됐다(`findings/29` 1-3): 메리츠 호출 간격의 공유, `remaining()`을 사이클 행 락 안에서 구하기, 유일 제약 위반의 409 매핑. 토큰 lease 일반화는 KIS 삭제로 빠진다. 여전히 하지 않는다.
- 11절의 "`AccountBudgetLock` JVM 락 유지"에 덧붙인다: 주석의 "계좌 row FOR UPDATE로 승격"은 틀린 방향이다. 불변식이 사이클 단위라 승격한다면 사이클 행 락이다. 지금은 주석만 "엔진 단일 프로세스 전제(05 1-1)"로 고친다(단계 R의 문서 반영 때).

## 15. 3차 실행 전 보정 (2026-11-16, Fable — 14절과 다르면 이 절이 우선)

`questions.md` F-04의 답이다. 근거는 `findings/30-post-hoc-review.md` 0·5절과 kista-infra 회신(`cross-repo.md` I-5, infra `344da10` 기준).

**선행 상태.** P3·P4(`1dc134c78`·`33b129e1a`), 04 단계 A·B(`a43b5af51`·`c882fbf3e`)가 main에 들어가 배포됐다. M 브랜치는 아직 없다.

**15를 M 앞에 둔다(14-1의 판정 규칙을 고친다).** 14-1은 "P3·P4·04 A가 main에 들어간 날 선행 넷이 끝나 있으면"이라고 날짜를 박았다. 그 규칙의 목적은 ui 일정이 M을 막지 않게 하는 것이었는데, ui G1이 2026-11-16에 배포됐고 M은 어차피 S11·S15 T2 관찰(2026-11-16~18)이 끝나야 전환할 수 있다. 그래서: **15 단계 A(T3~T5) → 1거래일 관찰 → M 브랜치 시작.** 15의 11절이 남은 선행(사용자 단계 P ②)을 뺐다. 15 단계 B(T6)는 M 전환 뒤로 미뤄도 된다(메타 필드와 컬럼 하나라 합병과 겹치는 파일이 적다). M 브랜치를 시작한 뒤에는 끼워 넣지 않는다는 문장은 그대로다.

**M 브랜치 세션의 첫 작업.** 14-2의 수치(빈 수, 락 이름 R 8·T 7, 삭제 테스트 수, `flows.yml` 줄 번호)는 KIS·코인 삭제 전 값이다. 다시 세고 시작한다. 2026-11-16 운영의 락 이름은 `public` 8, `trading` 6(`crypto-dca` 포함 — 15 뒤 5)이고 겹침은 여전히 0이다.

**런북(14-3) 보정.**

| 곳 | 보정 |
|---|---|
| D-1 e | M 합병 push가 멈추는 첫 지점은 infra `resolve-request.sh:70-72`("state에 없는 role", exit 1)다. `build-bundle.sh:19-23`은 두 번째 방어선이다. 어느 쪽이든 번들 업로드 전이라 서버는 그대로고, 텔레그램 `[배포 실패] kista-api reconcile`과 api 커밋 status `deploy/production` failure가 온다(예정된 소음) |
| 0단계 | 명령 전부를 2026-11-16 운영에서 시험 실행했다(읽기 전용). 컬럼·스트림 이름이 맞고 결과는 EPR 0·0, 락 held f·f, SUBMITTING 0, 스트림 pending 0이었다. `XLEN`은 `user.deleted` 0, `notify-profile.changed` 2, `app.error` 10, `push-notification.requested` 329 |
| 0단계 | release id는 config SHA + 접미사 꼴이다(2026-11-16 `current` = `33b129e1af32-58a8bec9`, `previous` = `…-3657bfdb`). role 이미지만 바뀐 배포도 release가 새로 생기고 5개만 보존되므로 **당일 다시 적는다** |
| 2단계 | `insert … select *`는 그대로 쓴다. 두 스키마의 `event_publication` 컬럼 순서가 같다(2026-11-16 확인: id, listener_id, event_type, serialized_event, publication_date, completion_date, status, completion_attempts, last_resubmission_date). S13이 완료 행 삭제를 켜서 평소 두 테이블은 0행이다 |
| 5단계 no-go | worker 게이트가 실패하면 reconcile의 자동 롤백은 직전 compose에 `kista-worker`가 없어 exit 2로 끝나고 **실패한 `kista-worker` 컨테이너가 떠 있는 채로 남는다**(infra 코드 읽기 기준). 롤백 1번의 `docker stop kista-worker`를 옛 role을 올리기 전에 반드시 먼저 한다(이중 실행 방지). `kista-web`도 같다 |
| 4단계 | 실패한 infra run은 Re-run으로 다시 쓸 수 있다(checkout이 `ref: main`이라 재실행 시점의 state를 읽는다) |
| 롤백 3번 | 맞다(infra 확인). 워크플로로는 어느 조합으로도 후퇴할 수 없다. 서버에서 `reconcile.sh kista-api <옛 release id>` 실행 뒤 infra `state/kista-api.yml`을 실제 적용 SHA로 수동 커밋한다 |
| 배포 파일(14-2) | `deploy/server/README.md`의 롤백 절("state를 그 SHA로 수정·커밋 뒤 dispatch")은 동작하지 않는다(`cross-repo.md` I-4 확정). M의 배포 파일 수정에서 위 한 줄로 고친다 |

**kista-infra 준비 상태(2026-11-16).** I-1(state 키 브랜치)은 시작되지 않았다. infra 로드맵에는 있다(infra `guides/03-instance-topology.md` 5-1, T7). bats 픽스처는 실제 state를 읽지 않아 반드시 고칠 것은 없어 보인다(실행 미확인). infra 03 5-1의 순서(옛 scheduler·trading 정지 → state 키 → reconcile → Caddy 확인 뒤 옛 api 정지)는 14-3의 "옛 api를 살려 두는 변형"과 같고, 14-3의 기본안(전체 정지)과는 옛 api를 내리는 시점이 다르다. 사용자가 전체 정지를 고르면 infra 03 5-1을 14-3에 맞춘다(infra 세션 몫, D-7 c와 같은 커밋).

**일정.** 전환 창 후보는 15 단계 A 배포와 1거래일 관찰이 끝난 뒤의 토요일이다. 2026-11-26(목)은 휴장, 11-27(금)은 조기 마감이다. 11-28(토)에 전환하면 0단계의 `trading-close` `finished_at`이 조기 마감 시각대(KST 03시대)로 찍힌다 — 정상이다. 일시와 전체 정지 여부는 여전히 사용자 결정이다.

**M 목록 전체의 재검수는 하지 않았다.** `findings/29`의 M 목록은 2026-11-15 판정 그대로 두고, 위 "첫 작업"의 재측정에서 어긋난 것만 M 브랜치 세션이 14-4의 기본값으로 처리한다.

**P4 후속 — role 기본값(M10에서 함께 고친다).** `kista.role`의 기본값이 `${KISTA_ROLE:all}`이다(root `application.yml:132`, trading-core `:116`). 운영은 prod 프로필이 기본값 없는 `${KISTA_ROLE}`이라 안전하지만, 로컬은 gitignore된 `application-local.yml`에 `kista.role: web`이 없으면 스케줄러(매매 배치 포함)가 켜진 채 뜬다. 옛 `scheduler.enabled: false`는 P4 뒤로 읽히지 않는다. 2026-11-16 이 저장소의 Windows 작업 PC에서 두 로컬 파일이 옛 키만 갖고 있어 `kista.role: web`으로 고쳤다. **다른 PC의 로컬 파일은 사용자가 확인한다**(`grep -n "role\|scheduler" src/main/resources/application-local.yml trading-core/src/main/resources/application-local.yml`). M10에서 기본값을 `web`으로 바꾸고(1-3절 "기본값은 꺼짐"), `all`이 필요한 테스트는 프로퍼티로 지정한다. `docs/agents/local-dev.md`에 "로컬 파일에 `kista.role: web`을 넣는다"를 한 줄 더한다.
