# 모듈 결합도 리팩토링 — 세션 인계 메모 (2026-09-30)

다른 세션이 이어서 진행하기 위한 상태 기록. 설계 근거와 결함 목록(F1~F10)은 `docs/reviews/2026-09-30-module-coupling-review.md`, 단계별 변경 경위는 `docs/agents/modulith-migration-history.md` 끝의 "결합도 재검토 N단계" 절들이 SSOT다.

## 1. 현재 상태

| 단계 | 상태 | 위치 |
|------|------|------|
| 리뷰 문서 | 완료 | `main` `8559cc9` |
| 1단계 — 런타임 정책 소유권 trading-core 이동, 오류 보고 Redis Stream화, `sharedkernel.port` 폐지 (F1) | 완료·검수·커밋 | `main` `522654e` |
| 2단계 — `com.kista.contract` Published Language, own-type 20쌍 제거 (F2) | 완료·검수·커밋 | `main` `d35db20` |
| 1·2단계 리뷰 지적 수정 (app.error 구독 자가복구, 공개 runtime-config 장애 강등) | 완료·커밋 | `main` `9351b33` |
| 3단계 — `tradingstats`·`tradingnotify` 분리, 브로커 벤더 타입 차단 (F3) | 완료·검수·커밋 | `main` `89fdfff` |
| 4단계 — matching 커널 `PrivacyPlan`, `StrategyCapability` sharedkernel 승격, `/api/meta` HTTP 제거 (F4·F5) | 완료·검수·커밋 | `main` `a64aa3b` |
| 5단계 — notify 순수 게이트웨이화 + 인프라 공용화 (F6) | 완료·검수·커밋 (리뷰 지적 6건 반영: 봇 토큰 로그 마스킹, getMe 방어 파싱, chat-id 이중 바인딩 제거, 타임아웃 단언 복원, 테스트 헬퍼 통합, 원장 갱신) | `main` |
| 6단계 — 앱셸 대칭화 (F7) | 완료·검수(high)·커밋 | `main` |
| 7단계 — 잔여 정리 (F8~F10 + 문서 드리프트) | 미착수 | — |

5단계는 검수 후 `main`에 반영됐다(`wip/coupling-phase5`는 역할을 다해 삭제 — 원격에 남아 있으면 지워도 된다). 주의: 인계 메모 커밋 `e137b9b`에 5단계 파일 이동·삭제가 실수로 섞여 들어가 그 커밋 단독으로는 컴파일되지 않는다(직후 5단계 커밋이 복구). 이력 재작성 없이 그대로 둔다.

## 2. 재개 절차

1. 5단계까지 `main`에 반영 완료.
2. 6단계 → 7단계 순으로 진행(아래 §4). 단계마다 같은 절차: 구현 → 전체 컴파일 → 전체 단위 테스트 → 리뷰어 검수 → 커밋.

## 3. 진행 방식 (지금까지 쓴 방법)

- 오케스트레이터(고수준 모델)가 단계 브리프 작성·검증·검수·커밋을 맡고, 구현은 단계당 Sonnet 서브에이전트 1개에 위임했다.
- 단계들이 같은 파일(admin 어댑터, trading-core 부트 클래스, `HexagonalArchitectureTest` 등)을 건드리므로 **순차 실행**. Gradle도 동시에 두 개 돌리지 않는다(결과 XML이 섞인다).
- 커밋: author `narafu <narafu@kakao.com>`(CLAUDE.md), committer는 환경 요구로 `Claude <noreply@anthropic.com>` — `git -c user.name=narafu -c user.email=narafu@kakao.com commit ...` 후 `git -c user.name=Claude -c user.email=noreply@anthropic.com commit --amend --no-edit`.
- 커밋 메시지: 한글, Conventional Commit 접두사, 본문 끝에 `Co-Authored-By`/`Claude-Session` 줄.
- BOM 확인: `grep -rl $'\xef\xbb\xbf' src trading-core/src shared/src --include='*.java'` 결과가 비어야 한다.

### 검증 명령

```bash
./gradlew compileJava compileTestJava :shared:compileTestJava :trading-core:compileTestJava -q
./gradlew test -q --continue
```

이 클라우드 환경에는 Postgres·Redis가 없어 DB·Redis가 필요한 테스트(`*PersistenceAdapterTest`, `@SpringBootTest` 컨텍스트, `EventPublicationRegistryTest`, `ApplicationContextLoadTest`, `SchedulerDisabledContextTest` 등)는 "Connection refused"로 실패한다. 판정은 "Connection refused가 아닌 실패가 0건"으로 했다:

```bash
python3 - <<'EOF'
import glob,re
for proj in ['build','shared/build','trading-core/build']:
    tot=0; nondb=[]
    for f in glob.glob(proj+'/test-results/test/TEST-*.xml'):
        s=open(f).read(); m=re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"',s[:3000])
        t,sk,fa,er=map(int,m.groups()); tot+=t
        if (fa+er) and 'Connection refused' not in s: nondb.append(f.split('TEST-')[1][:-4])
    print(proj,'tests=',tot,'NON-DB failures=',nondb)
EOF
```

4단계 기준 수치: root 858, shared 44, trading-core 1237 — 비DB 실패 0. 5단계 작업 트리 기준: root 856, shared 53, trading-core 1236 — 비DB 실패 0.

**로컬에서 반드시 한 번 확인할 것**: `docker compose up -d postgres redis` 후 `./gradlew test integration`. 1~5단계 모두 DB·Redis 경로(Flyway `V2`, 정책 영속 어댑터, app.error 스트림, 부트 컨텍스트 빈 배선)를 실제로 띄워 본 적이 없다.

## 4. 남은 단계 브리프

### 5단계 (WIP 브랜치) — 검수 시 볼 곳
- 텔레그램 봇 명령 채널(`TelegramWebhookController`/`TelegramBotService`/`TelegramUpdate`)과 `PortfolioQueryPort`/`PortfolioQueryHttpAdapter`를 notify → admin으로 이동. `/telegram/webhook` 경로 불변.
- `UserNotificationPort`가 `User` 대신 notify 소유 `NotificationRecipient(userId, nickname, notificationChannel, telegramBotToken, telegramChatId, rejectReason)`를 받음. 매핑은 `CompositeUserNotificationAdapter`·`FinanceRegistrationReminderNotifier`의 private static 메서드.
- `NotificationChannel`을 `com.kista.sharedkernel`로 승격(상수명 불변, `@Enumerated(STRING)` 영향 없음).
- 인프라 공용화: `platform.telegram.{TelegramProperties,TelegramConfig,TelegramHttpClient}`(trading-core 빈 이름이 `tradingTelegramRestClient`/`tradingTelegramHttpClient` → `telegramRestClient`/`telegramHttpClient`로 바뀜 — 부트 컨텍스트 배선을 로컬에서 확인할 것), `platform.http.RestClients.withTimeouts(...)`(Alpaca 설정 3개가 사용, 빈 이름 불변), `platform.security.RedisTokenBlacklistReader`(비빈 기반 클래스, root·trading-core 블랙리스트 어댑터가 상속).
- 잠금: `HexagonalArchitectureTest.notify_must_stay_pure_outbound_gateway`(notify는 `..application.usecase..`·`org.springframework.web.client..` 참조 금지).

### 6단계 — 앱셸 대칭화 (리뷰 F7)
- `com.kista.tradingweb`(root `com.kista.web`과 대칭, NamedInterface 0 sink) 신설: `TradingApplication`, `JpaRepositoryConfig`, `TradingExceptionHandler`(현재 `@RestControllerAdvice(basePackages=6개)` → basePackages 제거, 프로세스 전역 advice)를 이동. 부트 클래스 이동 시 `@WebMvcTest`가 찾는 `@SpringBootConfiguration`, tradingstats 테스트들의 `@ContextConfiguration(classes = TradingApplication.class)`, Dockerfile/`APP_JAR` 메인 클래스 참조, `bootJar` mainClass 설정을 함께 확인.
- `platform.security.SecurityConfig`의 라우트 정책(`/telegram/webhook`·`/api/auth/**`·`/api/meta`·`/api/market/**` permitAll 등 root 전용 경로)을 각 셸이 기여하는 방식으로: platform에는 필터 2종·`JwtDecoderConfig`·체인 빌더만 두고, 셸(`web`/`tradingweb`)이 `SecurityPolicy`(예: `Customizer<AuthorizeHttpRequestsConfigurer<...>.AuthorizationManagerRequestMatcherRegistry>`) 빈을 제공. 주의: `/api/internal/**` INTERNAL·`/api/admin/**` ADMIN·`anyRequest().authenticated()` 순서가 깨지면 인가 우회가 생긴다 — 보안 민감 변경이라 검수는 `/code-review high` 권장(`kista-ui` constraints.md 기준).
- `platform.web.ProblemDetailMappings`(JDK/Spring 범용 예외 → status/title 테이블 + `isClientDisconnect` + `problem()` 헬퍼)로 root `GlobalExceptionHandler`와 `TradingExceptionHandler`의 `GENERIC_MAPPINGS` 복제를 제거. 응답 status/title은 바이트 단위로 불변이어야 한다.
- 오류 기록 경로 단일화: root에서 `TelegramAdapter.notifyError`(notify `NotifyPort` 구현)와 `GlobalExceptionHandler.saveErrorLog`가 `AppErrorRaisedEvent`를 발행하고 admin 리스너가 `AppErrorLogPort.save`로 저장 → `admin.adapter.out.aop.ErrorLogAspect`(notify 포트를 문자열 포인트컷으로 가로채는 숨은 결합) 삭제. trading-core는 1단계에서 이미 같은 이벤트를 쓴다. `EventPublicationRegistryTest`가 `NotifyPort.notifyError` 호출을 전제로 하니 함께 확인.
- 잠금: `@Aspect` 포인트컷이 다른 모듈 패키지를 가리키지 않게 하는 ArchUnit 규칙(또는 `@Aspect` 자체 금지).

### 7단계 — 잔여 정리 (리뷰 F8~F10, §8)
- `trading.domain.model.BatchContext(Strategy, StrategyCycle, Account, TradingUserProfile)` → `Account` 대신 `accountId`/`userId`/`BrokerAccountRef`만 보유. trading의 `Account` 직접 참조(34파일)를 가능한 범위에서 줄인다 — 매매 핵심 경로라 `docs/agents/workflow.md` 필수 Read, 테스트 대량 변경 주의.
- finance 가계부 리마인더: `FinanceRegistrationReminderNotifier`가 `UserPort`·`UserSettingsPort`·`UserNotificationPort`를 직접 쓰는 구조를 `FinanceRegistrationReminderDueEvent(userId, month)` 발행 + notify 구독으로 바꿔 `finance → user`·`finance → notify` 의존을 0으로. `UserNotificationPort.notifyFinanceRegistrationReminder` 제거.
- 명명: `user.domain.model.AdminUserView`/`AdminUserViewPort` → `UserSummary`/`UserSummaryPort`, root `stats` 모듈 → `benchmark`(패키지 이동이라 빈 이름·`@Scheduled`·`AdminSchedulerController` 참조·`detect-deploy-scope.sh`의 `adapter/in/schedule` 경로 게이팅 확인). `AdminService`가 역할 변경 시 `TokenConstants`·`BlacklistPort`로 토큰 무효화를 직접 하는 부분을 `UserUseCase.changeRole()` 내부로.
- 매매 체결 FCM 푸시(`UserPushNotificationRequestedEvent`, 현재 Redis Pub/Sub 유실 허용)를 Redis Stream(`platform.redis.RedisStreams` 재사용, 컨슈머 그룹 `root`)으로 전환. 실시간 SSE(`TradeEventMessage`)는 유실 허용 그대로 둔다.
- 리뷰 문서 §8의 문서 드리프트 중 남은 것 정리, 리뷰 문서 머리의 "진행 상태" 블록을 최종 상태로 갱신.

## 5. 배포 시 주의 (누적)

- **1단계 `V2__trading_runtime_settings.sql`**: 매매 시간대 밖에서 적용. kista-trading 먼저(또는 동시) 배포가 정석. root가 먼저 올라가면 공개 `/api/runtime-config`는 기본 정책으로 강등되고 관리자 설정 저장만 거절된다. `V2`가 옛 `public.admin_runtime_settings`의 brokers/strategies를 1회 백필한다.
- **1단계**: `kista-trading`의 `INTERNAL_API_BASE_URL` 제거(compose 반영됨). kista-infra `.env`에는 해당 키가 없음을 확인했다.
- **3단계 EPR**: 매매 알림 리스너가 `com.kista.trading.notify` → `com.kista.tradingnotify`로 이동해 `trading.event_publication.listener_id`가 바뀌었다. deploy-trading 직전(매매 시간대 밖) `SELECT count(*) FROM trading.event_publication WHERE completion_date IS NULL AND listener_id LIKE 'com.kista.trading.notify.%'`로 확인하고 0이 아니면 같은 조건으로 DELETE(`docs/agents/constraints.md`에 기재).
- **5단계**: trading-core Telegram 빈 이름 변경 — 배포 전 로컬 2-프로세스 기동(`docs/agents/commands.md`)으로 부팅 확인 권장.

## 6. 알려진 후속 과제 (범위 밖으로 남긴 것)

- trading-core `UserEventStreamConsumerConfig`는 기동 실패 시 재시도가 없다(root `AppErrorStreamConsumer`에 넣은 `cancelOnError=false` + 5분 주기 재기동 방식을 동일하게 적용 필요). 별도 작업 카드로 제안해 두었다.
- `HexagonalArchitectureTest.vendor_models_must_not_leak_outside_broker`는 root 테스트 클래스패스에서 돌아 trading-core **테스트** 클래스의 Toss 타입 사용은 검사하지 않는다.
- `TradingStatsInternalController`가 `ExchangeRatePort`를 직접 주입 — Toss 외 구현체가 생기면 주입이 모호해진다.
- `StrategyCapability` 생성자의 `EnumSet.copyOf`는 빈 non-EnumSet 입력에 예외 — 현재 호출부 없음.
- Legacy Pub/Sub 푸시 구독자 제거 — trading-core 배포가 Stream 발행 버전으로 확인된 다음 릴리스에서 `notify.adapter.in.redis.LegacyPushNotificationRelayListener`(+ 테스트)와 `RedisPubSubConfig.LEGACY_PUSH_NOTIFICATION_CHANNEL`(@Deprecated)을 삭제한다.
