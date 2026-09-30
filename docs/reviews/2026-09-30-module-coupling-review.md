# kista-api 모듈 결합도 전면 재검토 (2026-09-30)

기준 커밋 `68ca7cd` (`refactor(broker,trading): BrokerAdapterRegistry를 포트 단위 라우팅으로 교체`). 소스 921파일(`:api` 432 / `:trading-core` 439 / `:shared` 50)의 `import com.kista.*` 전수 집계 + 내부 API 엔드포인트 36개 + 배포 토폴로지(`deploy/server/docker-compose.yml`)를 실측한 결과다. 문서(`docs/agents/**`)가 아니라 코드를 근거로 삼았고, 문서와 코드가 다른 곳은 별도 절(§8)에 모았다.

> **진행 상태**: 1~5단계 완료·검수·커밋, 6·7단계 진행 중. 인계 메모와 재개 절차는 `docs/reviews/2026-09-30-module-coupling-handoff.md`. 아래 본문의 "현재" 수치는 검토 시점 기준 그대로 둔다.

---

## 1. 총평

현재 상태는 이미 "잘 된 모듈러 모놀리스"다. 15개 Spring Modulith 모듈이 전부 CLOSED/OPEN으로 선언돼 `ApplicationModules.verify()`가 GREEN이고, `sharedkernel`·`platform`·`matching`·`web`은 ArchUnit으로 outbound를 못 박았으며, `:shared ← :trading-core ← :api` Gradle 경계까지 컴파일 산출물 기준으로 강제한다. 여기까지는 다시 손댈 이유가 없다.

그런데 결합도 관점에서 **"순환을 피하려고 우회 장치를 얹은 흔적"**이 6군데 누적돼 있고, 그 우회 장치들이 서로를 정당화하는 구조가 됐다. 대표적인 것이 own-type 20쌍(`OwnTypeContractTest`)이다. 이 프로젝트 스스로 2026-09-07 `strategyconfig` 병합 때 "own-type 부채는 잘못된 모듈 구획의 증상"이라고 결론냈는데, 같은 기준을 나머지에 적용하면 아직 5개 구조 결함이 남아 있다.

가장 큰 것부터:

| # | 결함 | 한 줄 요약 | 심각도 |
|---|------|-----------|--------|
| F1 | 프로세스 간 **양방향** 런타임 의존 | `kista-trading → kista-api` 역방향 HTTP 3개(런타임 설정 2, 오류로그 1). 어느 프로세스도 leaf가 아니다 | 높음 |
| F2 | Published Language 부재 | 내부 API 계약을 own-type 20쌍 + 리플렉션 테스트로 손동기화. 내부 컨트롤러 5곳이 도메인 record(`Order`/`Strategy`)를 그대로 wire에 태움 | 높음 |
| F3 | `trading` 신(god) 모듈 | 261파일. `trading.stats`(61)·`trading.notify`(18)는 이미 포트/이벤트로만 core에 의존하는데 같은 모듈 안에 있어 경계 검증이 안 된다. Toss 벤더 타입이 stats까지 침투 | 중상 |
| F4 | `matching` 커널이 `privacy`(영속 애그리게이트)에 의존 | 순수 계산 커널의 입력 타입이 다른 모듈 소유 | 중 |
| F5 | `/api/meta`가 trading-core HTTP 3회 호출 | 상수(capability)를 얻으려고 컨트롤러가 직접 RestClient를 쓴다. trading-core가 죽으면 UI 레이아웃 로드 실패 | 중 |
| F6 | `notify`가 알림 게이트웨이가 아니다 | 텔레그램 봇 명령(승인/거절/포트폴리오 조회)이 notify 안에 있어 `notify → user.usecase`, `notify → trading-core HTTP` 의존 발생. Telegram/Alpaca/Blacklist 인프라 코드 2~3중복 | 중 |
| F7 | 앱셸 비대칭 + 숨은 결합 | trading-core엔 `web` 같은 앱셸이 없어 `TradingExceptionHandler`가 7개 모듈의 advice 역할. `SecurityConfig` 라우트 정책이 platform(인프라 leaf)에 있음. admin의 AOP가 notify 포트를 문자열 포인트컷으로 가로챔(Modulith가 못 보는 결합) | 중 |

이 7개를 풀면 own-type 20쌍·`OwnTypeContractTest`·`sharedkernel.port`·`matching.adapter.in.web`·`ErrorLogAspect`·`web.trading.ActiveStrategyCountAdapter`가 전부 소멸한다. 비용은 크지만(§7 로드맵 기준 7단계) 각 단계는 독립 배포 가능하고, 단계마다 ArchUnit 규칙 1~2개를 추가해 되돌아가지 못하게 잠글 수 있다.

---

## 2. 실측 결과

### 2.1 모듈 간 import 매트릭스 (행 → 열, 값 = import 문 수)

```
              sk   plat  match priv  brok  acct  mcal  trad  user  noti  admin stats mkt   fin   web
sharedkernel  .    .     .     .     .     .     .     .     .     .     .     .     .     .     .
platform      .    .     .     .     .     .     .     .     .     .     .     .     .     .     .
matching      42   .     .     6     .     .     .     .     .     .     .     .     .     .     .
privacy       19   2     .     .     .     .     .     .     .     .     .     .     .     .     .
broker        51   7     .     .     .     .     .     .     .     .     .     .     .     .     .
account       16   3     .     .     2     .     .     .     .     .     .     .     .     .     .
marketcalendar 2   5     .     .     .     .     .     .     .     .     .     .     .     .     .
trading       221  22    79    24    76    52    4     .     .     .     .     .     .     .     .
user          35   7     .     .     .     .     .     .     .     .     .     .     .     .     .
notify        12   5     .     .     .     .     .     .     15    .     .     1     1     .     .
admin         70   7     .     .     .     .     .     .     20    .     .     1     .     .     .
stats         12   13    .     .     .     .     .     .     .     .     .     .     .     .     .
market        1    4     .     .     .     .     .     .     .     .     .     .     .     .     .
finance       4    12    .     .     .     .     .     .     5     1     .     .     .     .     .
web           7    .     .     .     .     .     .     .     3     .     4     2     .     9     .
```

읽는 법:
- 컴파일 타임 순환은 0이다(매트릭스가 상삼각). Modulith verify GREEN과 일치.
- `trading`이 유일한 허브다 — 7개 모듈에 outbound(478 import). 그중 `account` 52(그 중 `Account` record 34), `broker` 76(그 중 Toss/KIS 벤더 타입 14), `privacy` 24.
- root 쪽은 `user`가 허브(notify 15·admin 20·finance 5·web 3이 소비).
- `:api → :trading-core` 컴파일 의존 0 = 매트릭스에 없는 의존은 전부 **HTTP·Redis로 숨어 있다**(§2.2).

### 2.2 프로세스 간 런타임 의존 (컴파일 매트릭스에 안 보이는 것)

배포 토폴로지: `kista-api`(root, 웹) / `kista-scheduler`(root, 배치) / `kista-trading`(trading-core) 3프로세스. `deploy/server/docker-compose.yml:28`은 `kista-api → http://kista-trading:8080`, `:89`는 `kista-trading → http://kista-api:8080`을 가리킨다. **양방향이다.**

| 방향 | 채널 | 건수 | 소비자(root) / 제공자 |
|------|------|------|----------------------|
| root → trading-core | HTTP 동기 | 33 endpoint | admin 5어댑터(`TradingCommand/Query/SchedulerCommand/AccountQuery/PrivacyQuery`), stats 2(`InvestmentPoints/CurrentExchangeRate`), market 2(`MarketCalendarQuery/CandleQuery`), notify 1(`PortfolioQuery`), web 2(`ActiveStrategyCountAdapter`, `MetaController` 직접 호출) |
| **trading-core → root** | **HTTP 동기** | **3 endpoint** | `account.BrokerEnabledHttpAdapter:23` → `GET /api/internal/runtime-settings/broker-enabled/{broker}`, `trading.StrategyCreationPolicyHttpAdapter:28` → `GET .../strategy-creation-policy/{type}`, `trading.TradingExceptionHandler:213` → `POST /api/internal/errors` |
| root → trading-core | Redis Stream (내구) | 2 stream | `user.UserEventStreamPublisher` → `trading.adapter.in.redis.UserEventStreamBridge` (user 삭제·알림프로필 변경) |
| trading-core → root | Redis Pub/Sub (유실 허용) | 2 channel | `RedisTradeEventPublisher`(SSE), `RedisPushNotificationPublisher`(FCM 푸시) → root `RedisTradeEventSubscriber`/`PushNotificationRelayListener` |

세 가지 IPC 메커니즘이 공존하고, 그 계약 타입은 §2.3의 own-type으로 양쪽에 각각 선언돼 있다.

### 2.3 own-type 계약 쌍

`OwnTypeContractTest`가 20쌍을 리플렉션으로 검증한다. 그 밖에 테스트에 안 잡히는 사설 복제가 더 있다: `TradingExceptionHandler:98`의 `private record ErrorLogRequest` ↔ `admin.dto.ErrorLogRequest`, `MarketCalendarQueryHttpAdapter$SessionResponse` ↔ `MarketCalendarInternalController$SessionResponse`(이건 테스트에 있음), `StrategyCapability` ↔ `StrategyCapabilityResponse`. **그리고 내부 컨트롤러 11개 메서드는 도메인 record를 그대로 반환한다** — `TradingInternalQueryController:33,49,55,61,67`이 `List<Order>`·`List<Strategy>`·`Map<UUID,StrategySummary>`를 직렬화하고, `PrivacyInternalQueryController:25`·`PrivacyBaseInternalController:29~54`가 `PrivacyTradeBaseView`를 반환한다. `CLAUDE.md`의 "도메인 객체 직접 반환 금지 → 전용 Response DTO" 규칙이 내부 API에서는 지켜지지 않고 있고, 그 결과 `Order`에 필드 하나를 추가하면 내부 API wire 계약이 조용히 바뀐다(리더 쪽 `AdminOrderView`는 Jackson이 미지 필드를 버리므로 무증상).

### 2.4 `trading` 내부 서브패키지 결합 (실측)

```
trading.stats  → trading.core : 10 타입 — port.output 4(Order/CyclePosition/StrategyCycle/StrategyPort) + domain.model 6
trading.notify → trading.core : 14 타입 — application.event 11 + port.output 1(TradingUserProfilePort) + domain.model 2
trading.core   → trading.stats  : 0
trading.core   → trading.notify : 0
```

즉 stats·notify는 **지금 당장** 별도 Modulith 모듈로 떼어낼 수 있다. 필요한 건 `trading.application.event`에 `"event"` NamedInterface 하나뿐이다.

---

## 3. "이상적 설계"의 판정 기준

이 문서에서 결함을 판정한 기준 6가지. 전부 이 프로젝트가 이미 문서에 채택한 원칙의 연장이다.

1. **불변식을 지키는 모듈이 그 정책을 소유한다.** "증권사 신규 등록 허용 여부"는 계좌 등록의 불변식이므로 account/trading-core 소유가 맞다. admin은 편집 UI이지 소유자가 아니다.
2. **프로세스 사이는 단방향.** 한쪽은 leaf여야 한다. 양방향 동기 호출은 기동 순서·장애 전파·배포 가드를 전부 얽히게 한다.
3. **Published Language.** 프로세스 경계를 넘는 계약(HTTP body, Redis payload)은 한 곳에 선언하고 양쪽이 컴파일 타임에 공유한다. own-type 복제와 리플렉션 검증은 컴파일러가 할 일을 테스트가 대신하는 것이다.
4. **커널은 자기 입력 타입만 안다.** `matching`은 sharedkernel 외 어떤 모듈도 몰라야 백테스트·시뮬레이션·다른 프로세스에서 그대로 재사용된다.
5. **앱셸은 앱셸에.** 부트 클래스·전역 advice·라우트 인가 정책은 도메인 모듈이 아니라 프로세스별 셸 모듈에 둔다(root의 `web`이 이미 이 형태).
6. **보이는 결합만 허용.** AOP 문자열 포인트컷, `@RestControllerAdvice(basePackages=7개)`, 컨트롤러 안의 RestClient처럼 Modulith/ArchUnit이 볼 수 없는 결합은 없앤다.

---

## 4. 발견 사항 (심각도 순)

### F1. 프로세스 간 양방향 런타임 의존 — `kista-trading → kista-api` 역방향 3개

**근거**
- `account/adapter/out/internal/BrokerEnabledHttpAdapter.java:23` — `AccountService.register()/test()`가 계좌를 등록하려면 root가 살아 있어야 한다.
- `trading/adapter/out/internal/StrategyCreationPolicyHttpAdapter.java:28` — `StrategyCreationService.register()`가 전략을 등록하려면 root가 살아 있어야 한다.
- `trading/adapter/in/web/TradingExceptionHandler.java:213` — 예외 처리기 안에서 동기 HTTP로 root에 오류 로그를 보낸다(실패 중인 요청을 로그 저장 때문에 최대 10초 더 붙잡는다).
- `deploy/server/docker-compose.yml:89` — `kista-trading`에 `INTERNAL_API_BASE_URL: http://kista-api:8080`.
- 이 세 어댑터 주석이 스스로 "포트 역전의 제3의 형태 — 정의자(sharedkernel)도 데이터 소유자(admin)도 아닌 trading-core가 HTTP로 구현"이라고 적고 있다. `constraints.md`가 "폐기된 전례"로 명시한 형태(제3자 구현)가 HTTP 어댑터로 이름만 바꿔 살아 있는 것이다.

**왜 결함인가**
- `admin_runtime_settings`(root `public` 스키마) 한 행(jsonb)에 서로 소유자가 다른 정책 3종이 묶여 있다: `approvalRequired`(user 불변식), `brokers.*.enabled`(account 불변식), `strategies.*`(trading 불변식), `benchmarks`(stats). 소유자는 다른데 저장소가 하나라서, 정작 불변식을 검사하는 trading-core가 남의 프로세스에 물어보게 됐다.
- `sharedkernel.port.{BrokerEnabledPort,StrategyCreationPolicyPort}`는 "root가 trading-core 정의 인터페이스를 implements하려면 타입 identity가 필요"해서 만든 것인데(`sharedkernel.md`), 지금 root의 `RuntimeSettingsService`가 구현하는 그 빈은 **trading-core 프로세스에서 쓰이지 않는다**(같은 JVM이 아니다). 존재 이유가 사라진 채 순수 어휘 모듈에 포트가 남아 있다.
- 결과: `kista-api`가 재배포/장애 중이면 `kista-trading`의 계좌 등록·전략 등록·오류 로그가 실패하고, `kista-trading`이 죽으면 `kista-api`의 admin·stats·market·`/api/meta`가 실패한다. leaf가 없다.

**이상적 설계**
1. **런타임 설정을 소유자별로 분리한다.** `approvalRequired`는 root `user`(또는 admin이 대신 저장하되 `ApprovalPolicyPort`는 그대로), `brokers`·`strategies`는 trading-core로 이동해 `trading.runtime_settings`(jsonb 1행, Flyway `db/migration-trading`)에 저장하고 `account`/`trading`이 각자 로컬 포트(`BrokerEnabledPort`는 account 소유, `StrategyCreationPolicyPort`는 trading 소유)로 읽는다. admin의 `PUT /api/admin/settings`는 brokers/strategies 부분을 `PATCH /api/internal/trading/runtime-settings`(root → trading-core, 기존 방향)로 위임하고, 공개 `GET /api/runtime-config`도 같은 방향으로 조합한다. `benchmarks`는 root `stats` 소유로 남긴다.
2. **오류 로그는 push로 바꾼다.** trading-core가 `stream:app.error`(Redis Stream, 기존 `RedisStreamConfig`·`UserEventStreamBridge` 인프라 재사용)에 XADD하고 root `admin`이 컨슈머로 받아 `app_error_logs`에 저장한다. 동기 HTTP가 사라지고, 내구성은 오히려 올라간다(현재는 root 장애 시 오류 로그 유실). `TradingExceptionHandler`의 `ObjectProvider<RestClient>` 우회도 사라진다.
3. 위 둘이 끝나면 `kista-trading`의 `INTERNAL_API_BASE_URL`·`InternalApiClientConfig` 빈 자체를 trading-core에서 제거하고, `GradleModuleBoundaryTest`에 "trading-core 산출물은 `com.kista.platform.internalapi`를 참조하지 않는다" 규칙을 추가해 잠근다. `sharedkernel/port/` 패키지는 삭제한다.

**비용/리스크**: Flyway 마이그레이션(trading 스키마 신규 테이블 + root `admin_runtime_settings` jsonb에서 brokers/strategies 키를 백필), admin 설정 UI 응답 조립 변경(kista-ui 영향 없음 — 응답 스키마 유지 가능), `RuntimeSettingsService` 분해. 2-role backward-compat: `kista-trading`을 먼저 배포하고 root를 나중에 배포하는 순서 제약이 한 번 생긴다.

### F2. Published Language 부재 — own-type 20쌍과 도메인 record 직렬화

**근거**
- `src/test/java/com/kista/architecture/OwnTypeContractTest.java` 20쌍. `own-type-ledger.md`의 (a) 항목 전부가 "Gradle 컴파일 경계 때문에 복제"라고 적혀 있다.
- `TradingInternalQueryController:33,49,55,61,67` — `Order`(15필드)·`Strategy`·`StrategySummary` 도메인 record가 그대로 HTTP body. `PrivacyBaseInternalController`도 `PrivacyTradeBaseView` 직접 반환.
- `TradingExceptionHandler:98` `private record ErrorLogRequest` — 테스트에도 안 잡히는 사설 복제.

**왜 결함인가**
- (a) "순환 불가피" 논리는 `:shared`가 없을 때만 성립한다. `:shared`는 이미 있고 양쪽 다 의존한다. 내부 API의 요청/응답 record는 JDK + sharedkernel enum만 쓰는 outbound-zero 값 타입이라 `:shared`에 둘 수 있는 조건을 완벽히 만족한다. 못 둔 이유는 "sharedkernel은 순수 어휘"라는 자기 규칙뿐인데, **통합 계약(Published Language)은 도메인 어휘가 아니라 별개의 것**이다 — 같은 `:shared` 안에 다른 패키지로 두면 두 규칙이 충돌하지 않는다. 실제로 `sharedkernel.UserDeletedEvent`/`UserNotifyProfileChangedEvent`/`UserPushNotificationRequestedEvent`는 이미 Redis 페이로드로 쓰이는 통합 메시지라, 암묵적으로는 이 분리가 벌써 시작돼 있다.
- 도메인 record 직접 직렬화는 도메인 변경 = 계약 변경이라는 최악의 결합이다. `AdminOrderView ← Order` 쌍은 "리더가 라이터의 부분집합이면 통과"라 `Order`에 필드가 늘어도 테스트가 못 잡고, `Order`의 필드 이름을 바꾸면 그제야 잡힌다.

**이상적 설계**
- `:shared`에 `com.kista.contract`(OPEN 모듈, outbound는 sharedkernel만 허용, ArchUnit으로 강제) 신설. 하위 패키지는 채널별: `contract.trading`(Order/Strategy/StrategySummary/Reorder*/TradeCorrection*/StrategyStatus 요청·응답), `contract.privacy`, `contract.account`, `contract.market`(SessionView/Candle), `contract.stats`(InvestmentPoints/ExchangeRate), `contract.matching`(StrategyCapability — F5로 소멸 예정), `contract.observability`(ErrorLog), `contract.events`(UserDeleted/UserNotifyProfileChanged/PushNotificationRequested/TradeEvent — 현재 sharedkernel·양쪽 notify에 흩어진 것들).
- 각 내부 컨트롤러는 도메인 → contract DTO 매핑(`from()` 팩토리)을 명시하고, 각 HTTP/Redis 어댑터는 contract DTO → 소비 모듈 own-type이 아니라 **contract DTO를 그대로 포트 시그니처에** 쓴다(admin의 `AdminOrderView` 등 read model은 contract 타입으로 대체). `OwnTypeContractTest`는 삭제한다 — 컴파일러가 대신한다.
- 예외 복원(`AdminBrokerCredentialException` 등 own-type 표지 예외 3종)은 contract에 `InternalApiErrorCode` enum + `ProblemDetail.properties.code`로 통일하고 `InternalApiStatusHandlers`(이미 platform에 있음) 한 곳에서 복원한다.

**비용/리스크**: 기계적이지만 넓다(admin 21 DTO + 어댑터 5 + 내부 컨트롤러 10). kista-ui 영향 없음(외부 API 스키마 불변). 한 채널씩 나눠 진행 가능.

### F3. `trading` 신 모듈 — stats·notify 분리 + 벤더 타입 침투 차단

**근거**
- 261파일 = 전체의 28%. `application/service` 43클래스.
- §2.4 실측: `trading.stats`·`trading.notify`는 core의 공개 계약(port/domain/event)만 쓴다. core는 둘을 모른다.
- `trading/stats/application/service/TossStatisticsService.java:6,9` — `broker.domain.model.toss.*`와 Toss 전용 포트 5개를 와일드카드 import. `KisApiException`/`TossApiException`이 `OrderCancelService`·`TradingReporter`·`TradingBuyCompetitionSimulator`·`TradingSellSufficiencySimulator`·`TradingExceptionHandler` 5곳에 등장.

**왜 결함인가**
- 같은 모듈 안이라 `trading.stats → trading.application.service`(internal) 참조가 생겨도 Modulith가 잡지 않는다. NamedInterface `"stats"`를 core `"port"`와 병합 공개한 것도 이 때문에 생긴 우회다.
- broker 모듈은 `BrokerRouter`로 벤더를 추상화해 놓고, 정작 소비자인 trading이 `TossApiException`·`TossCandle`·`TossMarketSession` 같은 벤더 타입을 직접 안다. 신규 브로커 추가 시 "구현체 1개만 추가"라는 broker.md의 약속이 stats에서는 깨진다.
- `TradingExceptionHandler`가 `KisApiException`/`TossApiException`을 각각 매핑하는 것도 같은 문제 — broker가 `BrokerApiException`(벤더 중립 상위 타입, vendor 코드 필드)을 공개하면 소비자는 하나만 알면 된다.

**이상적 설계**
- `com.kista.tradingstats`(CLOSED, `"usecase"`·`"domain"` 공개)와 `com.kista.tradingnotify`(CLOSED, NamedInterface 0 — 순수 리스너 sink) 신설. `trading.application.event`에 `"event"` NamedInterface 부여. `MonthlyReturnCalculator`·`BacktestEngine`은 tradingstats로. EPR 재발행 소유(`kista-trading`)는 프로세스 단위라 변화 없음.
- broker에 `BrokerApiException`(추상) + `PortfolioStatisticsPort`(벤더 중립: 보유·체결·환율·캔들 조회 shape) 신설, `TossBrokerAdapter`만 구현하고 비지원 브로커는 `UnsupportedOperationException` 대신 `Optional`/capability 조회로 응답. `TossStatisticsService`는 `TossStatistics*` 이름을 버리고 `BrokerStatisticsService`가 된다.
- 잠금: `HexagonalArchitectureTest`에 "`com.kista.broker.domain.model.(kis|toss)..`는 `com.kista.broker..` 밖에서 참조 금지" 규칙 추가.

### F4. `matching` 커널의 `privacy` 의존

**근거**: `matching/domain/strategy/PrivacyStrategy.java:6-7,28`(`PrivacyTradeBase`/`PrivacyTrade` 파라미터), `PrivacyCycleOrderStrategy.java:4,50`, `HexagonalArchitectureTest.matching_must_not_depend_on_other_modules`가 privacy를 예외로 허용.

**왜 결함인가**: 커널이 영속 애그리게이트 모듈의 도메인 record를 입력 타입으로 쓴다. `PrivacyTradeBase`는 `id(UUID)`·생성자 검증(`currentCycleStart` 양수) 같은 애그리게이트 성격을 갖고, `PrivacyTrade`는 `tradeDate`·`ticker`까지 담는데 커널이 실제로 읽는 건 `avgPrice/holdings/currentCycleStart` + trade의 `orderType/direction/quantity/price`뿐이다. `PlanContext.PrivacyInputs`라는 커널 소유 입력 껍데기를 이미 만들어 놓고 그 안에 남의 타입을 넣은 반쪽 상태다.

**이상적 설계**: `matching.domain.model.PrivacyPlan(avgPrice, holdings, currentCycleStart, List<PrivacyPlannedTrade>)`을 커널이 소유하고, 변환은 `privacy.PrivacyTradeBase.toPlan()`(privacy → matching 의존은 허용, 역방향 0이 되므로 순환 없음) 또는 trading의 `CycleOrderComputer`가 담당. `matching_must_not_depend_on_other_modules`에서 privacy 예외를 제거하고 sharedkernel만 남긴다. `BacktestEngine`이 `new`로 커널을 쓰는 지금 구조와도 더 잘 맞는다.

**비용**: 소. 파일 4개 + 테스트.

### F5. `/api/meta`의 trading-core 동기 의존 — 상수를 HTTP로 가져온다

**근거**: `web/MetaController.java` — `RestClient internalApiRestClient`를 컨트롤러가 직접 주입받아 `StrategyType` 3개마다 `GET /api/internal/matching/strategy-capabilities/{type}` 호출. 응답 `StrategyCapabilityResponse`는 `requiresPrivacyBase/supportsReverseMode/divisionCounts` 3필드이고, 구현체는 전부 상수(`InfiniteCycleOrderStrategy:46,53` `return true`/`List.of(20,30,40)`, `VrCycleOrderStrategy:29,35`, `PrivacyCycleOrderStrategy:34`). `StrategyType.availableTickers()`는 이미 sharedkernel에 switch로 있다.

**왜 결함인가**: (1) 인바운드 어댑터가 포트 없이 아웃바운드 I/O를 한다 — `application_must_not_depend_on_adapter` 류 규칙이 `web`에는 안 걸려 미검사 표면이다. (2) `/api/meta`는 permitAll이고 UI가 레이아웃 로드마다 부르는데 trading-core 장애가 root의 공개 엔드포인트 장애로 전파된다. (3) 이 상수 4개를 위해 `matching.adapter.in.web`(커널 모듈의 웹 어댑터)·내부 endpoint·own-type 2개·`StrategyCapabilityInternalController`가 존재한다.

**이상적 설계**: `sharedkernel.StrategyCapability(Set<StrategyTicker> tickers, boolean tickerFixed, boolean requiresPrivacyBase, boolean supportsReverseMode, List<Integer> divisionCounts)` record + `StrategyType.capability()`가 SSOT. `CycleOrderStrategy`의 해당 default 메서드는 `type().capability()`로 위임(런타임 동작 불변, SSOT 한 곳). `MetaController`는 HTTP 0회. `matching.adapter.in.web` 패키지 삭제. 잠금: "`com.kista..adapter.in..`은 `org.springframework.web.client..`에 의존하지 않는다" ArchUnit 규칙(현재 위반: `MetaController`, `TradingExceptionHandler`).

**비용**: 소.

### F6. `notify` 모듈의 정체성 — 게이트웨이에 인바운드 명령 채널이 섞임

**근거**
- `notify/adapter/in/telegram/TelegramBotService.java:31` — `UserUseCase`(승인/거절 실행), `:30` `PortfolioQueryPort`(trading-core HTTP `PortfolioQueryHttpAdapter`). 이 두 의존이 `notify → user.usecase 1`·`notify → trading-core HTTP 1`의 전부다.
- `notify/application/port/output/UserNotificationPort.java` — 포트 시그니처가 `User` 애그리게이트 전체. `CompositeUserNotificationAdapter:27`이 `UserPort`로 재조회. 반면 trading-core 쪽 `tradingnotify`는 같은 문제를 `TradingUserProfile`(5필드 투영)로 이미 해결해 두 프로세스의 해법이 다르다.
- 인프라 중복: `TelegramHttpClient`/`TelegramConfig`/`TelegramProperties`가 root notify와 `trading.notify`에 각각(diff 결과 인라인 키보드 메서드 유무만 다름), `AlpacaConfig` 3벌(stats/marketcalendar/trading.stats — 빈 이름만 다름), `RedisBlacklistAdapter` 2벌(user/trading — 같은 Redis 키를 읽음).

**왜 결함인가**: 텔레그램 봇 명령 처리는 "관리자가 텔레그램으로 승인/거절/조회하는" **인바운드 채널**이지 알림 발송이 아니다. notify에 두는 바람에 얇은 게이트웨이여야 할 모듈이 user 유스케이스와 trading-core 조회를 안다. 포트 시그니처의 `User`는 notify가 사용자 도메인 구조 변경에 흔들리게 만든다.

**이상적 설계**
- `TelegramWebhookController`+`TelegramBotService`+`TelegramUpdate`를 `admin.adapter.in.telegram`(승인/거절 = 관리자 명령)으로 이동, `/portfolio` 조회는 `admin`이 이미 가진 trading 조회 어댑터 계열로 통합(`notify.PortfolioQueryPort`·`PortfolioQueryHttpAdapter` 삭제). notify는 `NotifyPort`/`UserNotificationPort`/`FcmDeviceTokenPort` 3포트만 남는 순수 outbound 게이트웨이가 된다.
- `UserNotificationPort`는 notify 소유 `NotificationRecipient(userId, nickname, channel, telegramBotToken, chatId, prefs)`를 받는다. user 이벤트 리스너(`CompositeUserNotificationAdapter`)가 `UserPort`로 조회해 변환하는 건 유지.
- `platform.telegram.TelegramHttpClient`(sendMessage/sendWithInlineKeyboard/answerCallbackQuery) + `platform.http.RestClientFactory`(타임아웃·baseUrl 공통)로 인프라 1벌. `TokenBlacklistPort` 읽기 구현도 platform으로(쓰기 확장 `BlacklistPort`는 user 유지).

**비용**: 중. 텔레그램 콜백 라우팅 테스트 이동 필요.

### F7. 앱셸 비대칭과 숨은 결합

**근거**
- trading-core: `TradingApplication`·`JpaRepositoryConfig`가 `com.kista.trading`(도메인 모듈 패키지) 안. `TradingExceptionHandler`가 `@RestControllerAdvice(basePackages=7개)`로 account/privacy/broker/marketcalendar/matching 컨트롤러까지 처리하고 root `GlobalExceptionHandler`의 `GENERIC_MAPPINGS`를 통째로 복제(주석에 그렇게 적혀 있음).
- `shared/.../platform/security/SecurityConfig.java:41-55` — `/telegram/webhook`·`/api/auth/**`·`/api/meta`·`/api/market/**` 같은 root 전용 라우트 정책이 인프라 leaf에 있고, trading-core 프로세스도 같은 정책으로 뜬다(존재하지 않는 경로를 permitAll).
- `admin/adapter/out/aop/ErrorLogAspect.java:21` — `execution(* com.kista.notify.application.port.output.NotifyPort+.notifyError(..))` 문자열 포인트컷. admin → notify 의존이 컴파일·Modulith 어디에도 안 보인다. 게다가 F1의 오류 로그 경로와 합쳐 "오류가 DB에 남는 경로"가 3갈래(root advice 직접 저장, AOP 가로채기, trading-core HTTP)다.
- `web/trading/ActiveStrategyCountAdapter.java` — `web.md` 스스로 "단일 모듈만 소비하면 web에 두지 말 것"이라 했는데 이건 `user` 포트 구현 1개뿐이다.

**이상적 설계**
- `com.kista.tradingweb`(root `web`과 대칭, NamedInterface 0 sink) 신설: `TradingApplication`, `TradingExceptionHandler`(basePackages 제거 — 프로세스 전역 advice), `JpaRepositoryConfig`, trading-core 라우트 정책. `platform.security`는 필터 2종·`JwtDecoderConfig`·`SecurityFilterChain` **빌더**(`SecurityPolicy` 인터페이스: 각 셸이 `HttpSecurity` 라우트 규칙을 기여)만 남긴다.
- `platform.web.ProblemDetailMappings`(JDK/Spring 범용 예외 → status/title 테이블)를 두고 두 advice가 상속·합성 — `GENERIC_MAPPINGS` 복제 소멸.
- 오류 기록 경로 단일화: `sharedkernel`(또는 F2의 `contract.events`)에 `AppErrorRaisedEvent(errorType, message, stackTrace, context)`를 두고, root에서는 `NotifyPort.notifyError` 구현체(`TelegramAdapter`)와 `GlobalExceptionHandler`가 이벤트를 발행, admin 리스너가 저장. trading-core에서는 같은 이벤트를 Redis Stream으로 내보낸다(F1-2). `ErrorLogAspect` 삭제.
- `ActiveStrategyCountAdapter` → `user.adapter.out.internal`로 이동.

### F8. (중하) `trading` 도메인이 `Account` 애그리게이트 전체(복호화 자격증명 포함)를 들고 다닌다

`trading/domain/model/BatchContext.java`가 `Account`를 필드로 갖고, trading 34개 파일이 `Account`를 import한다. trading이 실제로 쓰는 건 `id/userId/broker`와 `toBrokerRef()`·`verifyOwnedBy()`다. `Account.toBrokerRef()`가 이미 투영을 만들므로 `BatchContext`는 `(accountId, userId, BrokerAccountRef)`만 들면 된다 — 평문 appKey/secretKey가 배치 컨텍스트·프리뷰 캐시·리포트 경로까지 흘러다니는 범위가 줄어든다. 한편 `account.domain.model.Account`가 `broker.domain.model.BrokerAccountRef`를 import하는 도메인→도메인 의존은 허용 범위지만, F2의 contract 도입 시 `BrokerAccountRef`를 sharedkernel 값 타입으로 올리면 account→broker 의존 2건도 0이 된다.

### F9. (중하) `finance → user` 직접 조회 + notify 포트에 finance 전용 메서드

`FinanceRegistrationReminderNotifier`가 `UserPort.findAll`·`UserSettingsPort`·`UserNotificationPort.notifyFinanceRegistrationReminder(User, month)`를 쓴다. 가계부 모듈이 사용자 목록·알림 설정을 직접 훑고, 알림 포트에 자기 전용 메서드를 갖는다. 이상: finance는 `FinanceRegistrationReminderDueEvent(userId, month)`만 발행하고 notify가 구독해 수신자 조회·채널 라우팅을 담당 → `finance → user` 5건·`finance → notify` 1건이 0이 된다(finance는 진짜 독립 모듈이 된다).

### F10. (하) 명명이 소유권을 오도하는 것들

- `user.domain.model.AdminUserView` + `AdminUserViewPort` — user 소유가 맞지만 이름이 admin 것처럼 보인다. `UserSummary`/`UserSummaryPort`로.
- root `stats` vs trading-core `trading.stats` — 같은 이름 두 모듈. root는 벤치마크 전용이므로 `benchmark`로.
- `AdminService`가 `TokenConstants`·`BlacklistPort`로 역할 변경 시 토큰 무효화를 직접 수행 — 인증 메커니즘이 admin에 샌다. `UserUseCase.changeRole()`이 내부에서 처리해야 한다.
- `PushNotificationRelayListener`(Redis Pub/Sub, 유실 허용)로 매매 체결 푸시를 보낸다. SSE는 유실 허용이 맞지만 체결 푸시 1건 누락은 사용자 관점에서 사고다 → user 이벤트와 같은 Redis Stream 경로로.

---

## 5. 유지해야 할 것 (되돌리지 말 것)

이번 검토에서 "이상적 설계"에 이미 부합해 손대면 안 되는 결정들. 향후 리팩토링이 이걸 건드리면 퇴행이다.

- `strategyconfig → trading` 병합(2026-09-07). own-type 부채 판정 논리가 옳았다. F3의 분리 대상은 stats/notify이지 strategy 설정 애그리게이트가 아니다.
- `BrokerRouter` + `BrokerCapabilitiesPort` 다형성, `Account.toBrokerRef()` 1곳 변환, broker의 Account 무참조.
- `user_notify_profile` 복제본 + Redis Stream 동기화 + XAUTOCLAIM 복구. 이것이 F1의 정답 패턴(소유자가 push, 소비자가 복제본)이다.
- `AccountDeletedEvent`/`UserDeletedEvent` cascade를 이벤트로 처리하는 구조, `UserCascadeDeleter`의 직접 포트 호출 0.
- `MockSimulationDataPort` 포트 역전(broker 정의·trading 구현). 데이터 소유자가 구현하는 정상 DIP다. 얇은 뷰 3종(`PlacedOrderView`/`PositionView`/`StrategyRefLite`)도 ISP로 옳다.
- `TradingUserProfile`(trading 소유 5필드 투영). F6에서 root notify가 이 방식을 따라야 한다.
- `GradleModuleBoundaryTest`·`HexagonalArchitectureTest`의 outbound-zero 규칙군.

---

## 6. 결합도 목표 수치

| 지표 | 현재 | 목표 |
|------|------|------|
| 프로세스 간 동기 HTTP 방향 | 양방향(33 + 3) | 단방향 root → trading-core만 |
| own-type 복제 쌍 | 20(+사설 3) | 0 — `com.kista.contract` 단일 선언 |
| 내부 컨트롤러의 도메인 record 직접 반환 | 11 메서드 | 0 |
| Modulith 모듈 수 | 15 | 18(`contract`·`tradingstats`·`tradingnotify`·`tradingweb` 추가, `sharedkernel.port` 삭제) |
| `trading` 파일 수 | 261 | ≈180 |
| `matching` outbound 모듈 | sharedkernel + privacy | sharedkernel만 |
| `notify` → `user.usecase` / trading-core HTTP | 1 / 1 | 0 / 0 |
| 컨트롤러·advice 안의 `RestClient` | 2(`MetaController`/`TradingExceptionHandler`) | 0 |
| AOP 기반 모듈 간 결합 | 1(`ErrorLogAspect`) | 0 |
| 인프라 코드 중복 | Telegram 3파일×2, Alpaca 3, Blacklist 2 | 각 1 |

---

## 7. 로드맵 (독립 배포 가능한 7단계)

각 단계는 이전 단계에 의존하지 않도록 순서를 짰다. 다만 1→2→3 순서가 가장 효율적이다(1이 2의 own-type 수를 줄이고, 2가 3의 모듈 분리 시 계약 이동을 없앤다).

| 단계 | 내용 | 해결 | 규모(파일) | 잠금 규칙 |
|------|------|------|-----------|-----------|
| 1 | 런타임 설정 소유권 이동(trading 스키마 + 내부 PATCH) · 오류 로그 Redis Stream · `sharedkernel.port` 삭제 · `kista-trading`의 `INTERNAL_API_BASE_URL` 제거 | F1, F7 일부 | ~35 + Flyway 1 | trading-core는 `platform.internalapi` 참조 금지 |
| 2 | `com.kista.contract` 신설, own-type 20쌍 → contract 치환, 내부 컨트롤러 DTO 매핑, `OwnTypeContractTest` 삭제 | F2 | ~60 | contract는 sharedkernel 외 참조 금지 / `adapter.in.web`은 `domain.model` 타입을 `@ResponseBody`로 반환 금지 |
| 3 | `tradingstats`·`tradingnotify` 분리, `BrokerApiException`·벤더 중립 통계 포트 | F3 | ~90 (대부분 이동) | broker `domain.model.(kis\|toss)`는 broker 밖 참조 금지 |
| 4 | `StrategyCapability`를 sharedkernel로, `MetaController` HTTP 제거, `matching.adapter.in.web` 삭제, `PrivacyPlan` 커널 입력 타입 | F4, F5 | ~15 | `adapter.in..`은 `org.springframework.web.client..` 참조 금지 / matching outbound = sharedkernel |
| 5 | 텔레그램 봇 명령 → admin, `NotificationRecipient`, platform.telegram/http 통합, blacklist 읽기 구현 통합 | F6 | ~30 | notify는 `..application.usecase..` 참조 금지 |
| 6 | `com.kista.tradingweb` 앱셸, `SecurityPolicy` 기여 방식, 공용 `ProblemDetailMappings`, `AppErrorRaisedEvent`로 `ErrorLogAspect` 대체 | F7 | ~20 | `@Aspect` 포인트컷이 다른 모듈 패키지를 참조 금지 |
| 7 | `BatchContext` 축소, finance 리마인더 이벤트화, 명명 정리, 푸시 알림 Stream 전환, 문서 드리프트 정리 | F8~F10 | ~40 | — |

전체 규모는 대략 300파일 접촉(이동 포함), Flyway 마이그레이션 1~2건, kista-ui 영향 0(외부 API 계약 불변). 각 단계 종료 조건은 `./gradlew test`(ArchUnit·Modulith·전체 테스트) GREEN + 해당 단계 잠금 규칙 추가.

---

## 8. 문서 드리프트 (코드가 SSOT인데 문서가 뒤처진 곳)

검토 중 발견한 것만 기록. 로드맵 7단계에서 함께 정리.

- `user.md`·`architecture.md`: `JwtAuthFilter`/`InternalTokenAuthFilter`/`SecurityConfig`/`JwtDecoderConfig`가 `user.adapter.in.web.security`에 있다고 적혀 있으나 실제는 `platform.security`(`:shared`). `user/adapter/in/web/security/`에는 `JwtIssuerService`·`OpenApiConfig`·`RefreshTokenCookieHelper`만 남아 있다.
- `platform.md`: `http/`(`HttpClientTimeouts`), `internalapi/`(`InternalApiClientConfig` 등 4파일), `security/` 하위 패키지가 누락.
- `admin.md`: `ErrorLogInternalController`·`RuntimeSettingsInternalController`(역방향 내부 API) 미기재. "`accountPort`(trading-core AccountPort 직접 의존)가 `getStats()`에 잔존"이라 적혀 있으나 실측 `admin → account` import 0 — 이미 해소됨.
- `notify.md`: `adapter/out/internal/PortfolioQueryHttpAdapter`·`PortfolioQueryPort`·`PushNotificationRelayListener` 미기재.
- `stats.md`: `CurrentExchangeRateHttpAdapter`·내부 `GET /api/internal/trading/stats/exchange-rate` 미기재.
- `account.md`: `adapter/out/internal/BrokerEnabledHttpAdapter` 미기재(문서는 "admin `RuntimeSettingsService`가 구현"이라고만 적음 — 같은 프로세스가 아니라 실제 구현체는 HTTP 어댑터).
- `trading.md`: `adapter/in/redis/`(Stream 컨슈머 3파일), `adapter/out/security/RedisBlacklistAdapter`, `adapter/out/internal/StrategyCreationPolicyHttpAdapter` 미기재.
- `sharedkernel.md`: `port/`의 존재 이유("root가 trading-core 인터페이스를 implements하려면 타입 identity 필요")가 현재 구조(HTTP 어댑터가 trading-core 안에서 구현)와 맞지 않는다.
- `own-type-ledger.md`: `AdminStrategySummary ← StrategySummary`, `AdminReorderTimingAvailability ← DstInfo.ReorderTimingAvailability`, `StrategyCapability ← StrategyCapabilityResponse` 3쌍이 테스트에는 있으나 원장에 없다.
