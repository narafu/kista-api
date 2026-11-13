# 지침서 05. 코드베이스 통합과 모듈 재구성 — 실행 지침

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
| X8 | `shared.contract` 삭제(FIDA 타입 제외), `InternalApiClientConfig`·`InternalApiProperties`·`INTERNAL_API_BASE_URL` 삭제, `InternalApiContractTest` 삭제, own-type 원장(`own-type-ledger.md`) 재판정 | |
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
