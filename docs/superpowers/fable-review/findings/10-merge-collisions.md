# 10. 두 애플리케이션 합병 충돌 전수 (api + trading-core -> 1 앱)

조사 2026-11-12, 읽기 전용. 목적: 지침서 05 단계 1~2 실행 재료. 근거는 전부 file:line. 경로 약칭: `R`=`src/main/java/com/kista/api`, `T`=`trading-core/src/main/java/com/kista/tradingcore`, `S`=`shared/src/main/java/com/kista/shared`.

## A. 빈 충돌

### A-1. 클래스(기본 빈 이름) 충돌 — stereotype 클래스 R 146 / T 218 / S 14 전수 비교

simple name이 겹치는 것은 5종. 이 중 둘은 이미 `@Configuration("...")`/`@Component("...")` 명시 이름으로 같은 프로젝트 내 충돌을 피한 것이라 합병 후에도 그 이름은 유효하고, 새로 생기는 충돌은 R과 T 사이다.

| # | simple name | R 쪽 | T 쪽 | 합병 시 증상 | 조치 |
|---|---|---|---|---|---|
| A1 | `AlpacaConfig` | `R/benchmark/adapter/out/alpaca/AlpacaConfig.java:9` 이름 미지정(빈 `alpacaConfig`) | `T/stats/.../alpaca/AlpacaConfig.java:12` `"tradingStatsAlpacaConfig"`, `T/marketcalendar/.../alpaca/AlpacaConfig.java:10` `"marketAlpacaConfig"` | R의 `alpacaConfig`는 T 두 개와 이름이 달라 충돌 없음. 단 클래스 simple name 3개가 같아 IDE·ArchUnit 혼동 | 이름 충돌은 없음. 개명(`BenchmarkAlpacaConfig`)은 선택 |
| A2 | `BinanceConfig` | R에 없음 | T `broker/.../binance/BinanceConfig.java:8` 미지정(`binanceConfig`), `T/stats/.../binance/BinanceConfig.java:9` `"statsBinanceConfig"` | 충돌 없음(R에 없음). 이미 T 내부 문제는 명시 이름으로 해결됨 | 없음 |
| A3 | `ClockConfig` | `R/ClockConfig.java:13` `@Bean Clock clock()` | `T/ClockConfig.java:13` 동일 본문(패키지 줄만 다름, diff 확인) | **빈 이름 `clockConfig` 충돌 + `clock` @Bean 이름 충돌** — `ConflictingBeanDefinitionException` | 하나 삭제(`Clock.system(TimeZones.KST)` 동일). `ArchitectureMapTest`/가드가 `ClockConfig` 위치를 참조하는지 grep 필요 |
| A4 | `RedisBlacklistAdapter` | `R/user/adapter/out/redis/RedisBlacklistAdapter.java` `@Component`(`BlacklistPort` 구현, 쓰기 3종 포함) | `T/trading/adapter/out/security/RedisBlacklistAdapter.java` `@Component("tradingRedisBlacklistAdapter")`(읽기 전용) | 이름은 달라 통과하지만 **타입 충돌**: `JwtAuthFilter`가 `TokenBlacklistPort`를 생성자 주입(`S/platform/security/JwtAuthFilter.java:28`) -> 후보 2개 `NoUniqueBeanDefinitionException`. `S/platform/security/RedisTokenBlacklistReader.java:9` 주석이 "TokenBlacklistPort 빈은 프로세스당 정확히 1개" 라고 명시 | T 쪽 클래스 삭제. R 구현이 읽기 3종을 이미 상속 |
| A5 | `UserEventResubmitScheduler` | `R/web/UserEventResubmitScheduler.java:27` `@Component`(`UserDeletedEvent`·`UserNotifyProfileChangedEvent` 재발행, `NotifyPort` 알림) | `T/trading/adapter/in/schedule/UserEventResubmitScheduler.java:26` `@Component`(위 두 이벤트 + `AccountDeletedEvent`, `TradingErrorReportPort`) | 빈 이름 `userEventResubmitScheduler` 충돌. 더 중요: 합병 후 EPR 테이블이 하나면 두 잡이 같은 FAILED 행을 10분마다 중복 재발행 | 사용자 복제본 삭제(지침서 05 단계 2-2)와 함께 둘 다 삭제하거나 1개로 통합. 아래 E절 참고 |

### A-2. `@Bean` 메서드 이름 충돌 (같은 이름 -> 합병 시 Boot 기본 `allow-bean-definition-overriding=false`로 기동 실패)

| # | 빈 이름 | R | T | 조치 |
|---|---|---|---|---|
| B1 | `clock` | `R/ClockConfig.java:14` | `T/ClockConfig.java:14` | A3과 동일(1개 삭제) |
| B2 | `fearGreedRestClient` | `R/market/adapter/out/feargreed/FearGreedConfig.java:12` `CnnRestClients.withTimeouts(3_000, 7_000)` | `T/trading/adapter/out/feargreed/TradingFearGreedConfig.java:13` `withTimeouts(3_000, 5_000)` | **타임아웃이 다르다**(읽기 7s vs 5s, T 주석: "배치 지연 상한"). 이름 중복이 기동을 막으므로 둘 중 하나를 개명(`tradingFearGreedRestClient`)하고 소비자 필드명도 맞춘다(RestClient는 필드명 매칭 주입). 지침서 06/04에서 공포탐욕 로직이 한쪽으로 합쳐지면 1개로 |

그 외 RestClient 빈 이름은 전부 유일하다. R: `kbLandRestClient`, `alpacaRestClient`, `kakaoRestClient`, `fearGreedRestClient`. T: `tossRestClient`, `upbitRestClient`, `meritzRestClient`, `kisRestClient`, `binanceRestClient`, `marketAlpacaRestClient`, `fearGreedRestClient`(B2), `heartbeatRestClient`, `statsUpbitRestClient`, `statsCoinbaseRestClient`, `statsCoinMarketCapRestClient`, `statsMempoolRestClient`, `tradingStatsAlpacaRestClient`, `statsFrankfurterRestClient`, `statsBinanceRestClient`. S: `telegramRestClient`, `internalApiRestClient`, `internalApiWriteRestClient`.

- 합병 후 RestClient 빈이 약 24개가 된다. RestClient를 **타입만**으로 받는 곳이 있으면 이미 현재도 깨지므로(R 5개 이상, T 15개 이상) 모든 소비자가 이름 매칭에 의존한다. 개명 시 소비자 필드/파라미터명을 같이 바꿔야 한다(`R/admin/adapter/out/internal/TradingCommandHttpAdapter.java:20` 주석이 이 방식을 명시).
- `@Primary`는 2곳뿐: `R/notify/adapter/out/gateway/CompositeUserNotificationAdapter.java:19`(알림 포트 합성), `T/broker/application/service/BrokerRouter.java:22`(공통 Port 라우터). 서로 다른 포트라 충돌 없음. `@Qualifier` 사용 0건.
- `@Bean`이 없는 항목(없는 것 확인): `ObjectMapper`/`JsonMapper`, `TaskScheduler`, `DataSource`, `Flyway`, `EntityManagerFactory`, `RedisTemplate`, `CorsConfigurationSource` 직접 정의 없음 — 전부 Boot 자동구성. 유일한 `SecurityFilterChain`은 `S/platform/security/SecurityConfig.java:40`(공용 1개). `OpenAPI` 빈은 R만(`R/user/adapter/in/web/security/OpenApiConfig.java:18`), T에 없음 -> 합병하면 spec 1개가 T 엔드포인트까지 담는다(G절 참고).
- 기타 `@Bean` 단독: R `firebaseMessaging`(`FcmConfig.java:21`), S `processResidentMemoryGauge`(`MetricsConfig.java:23`), `redisMessageListenerContainer`(`RedisPubSubConfig.java:18`), `telegramHttpClient`, `jwtFilterRegistration`, `internalFilterRegistration`. T `CycleStrategyBeanConfig`의 전략 빈 6종(`:45-84`). 충돌 없음.

### A-3. `:shared`의 `PlatformAutoConfiguration`과 앱 설정

- `S/platform/PlatformAutoConfiguration.java:29-38`: `@AutoConfiguration @AutoConfigureOrder(HIGHEST_PRECEDENCE)`, `@Import`로 `SecurityConfig, JwtDecoderConfig, JwtAuthFilter, InternalTokenAuthFilter, RedisPubSubConfig, TelegramConfig, InternalApiClientConfig, MetricsConfig, AesCryptoService, AccountNoHasher, SchedulerJobRunner, SchedulerLockService, JpaAuditingConfig`. 등록은 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 한 줄. 두 앱이 같은 자동구성을 쓰므로 합병해도 중복 등록은 없다(앱이 하나가 되면 오히려 자연스럽다).
- 합병 후 바뀌는 점: ① `InternalApiClientConfig`는 `internal.api.base-url` 조건부(`@ConditionalOnProperty` `S/.../InternalApiClientConfig.java:22`)라 T에서는 빠져 있었다 — 단계 2에서 내부 HTTP를 지우면 이 클래스와 `InternalApiProperties`, `InternalTokenAuthFilter`, `GradleModuleBoundaryTest`의 해당 가드가 함께 소멸한다. ② `SecurityConfig`는 `ObjectProvider<SecurityRoutePolicy>`를 `orderedStream()`으로 모두 적용한다(`:44-50`) — 합병하면 `RootSecurityPolicy`와 `TradingSecurityPolicy` 둘이 같이 들어오므로 F절 순서 확인 필수. ③ `JwtAuthFilter`가 `TokenBlacklistPort` 단일 빈을 요구(A4).
- `PlatformAutoConfiguration`은 `@EnableConfigurationProperties({TelegramProperties, InternalApiProperties})`도 등록한다. `TelegramConfig`도 `TelegramProperties`를 다시 등록하나 같은 이름이라 registrar가 건너뛴다(`:21-23` 주석).
- 앱 클래스 2개: `R/ApiApplication.java:10-12`(`@SpringBootApplication @Modulithic(additionalPackages="com.kista.shared") @EnableScheduling`), `T/TradingApplication.java:11-14`(같은 어노테이션 + `@ConfigurationPropertiesScan`). 합병 시 **한 클래스만** 남겨야 하고(`@SpringBootApplication` 2개면 `@SpringBootTest`가 혼동), 스캔 루트가 `com.kista.api`와 `com.kista.tradingcore` 둘을 덮어야 한다(C절 참고). `@ConfigurationPropertiesScan`은 T 소유 properties 4종(`HeartbeatProperties`, `CoinMarketCapProperties`, `marketcalendar.AlpacaProperties`, `stats.AlpacaProperties`)을 위해 필요하다.

### A-4. 같은 prefix의 `@ConfigurationProperties` (prefix 충돌 = 같은 키를 여러 레코드가 바인딩)

| prefix | 클래스 | 필드 | 비고 |
|---|---|---|---|
| `alpaca` | `R/benchmark/.../AlpacaProperties` | apiKey, apiSecret, dataBaseUrl | R `application.yml:119-122`에 base-url 없음 |
| `alpaca` | `T/marketcalendar/.../AlpacaProperties` | baseUrl, apiKey, apiSecret, dataBaseUrl | T `application.yml:111-115` `base-url: https://paper-api.alpaca.markets` |
| `alpaca` | `T/stats/.../AlpacaProperties` | apiKey, apiSecret, dataBaseUrl | |
| `telegram`, `internal.api` | S 공용 | | 단일 |
| `kakao`, `admin`, `kbland` | R 전용 | | 충돌 없음 |
| `heartbeat`, `cmc` | T 전용 | | 충돌 없음 |

`alpaca` 3종은 FQCN이 달라 빈 이름(`alpaca-<FQCN>`)이 겹치지 않는다. 합병하면 `application.yml`의 `alpaca:` 블록이 T 값(base-url 포함)을 겸해야 한다(B절 키 병합). 3종을 1개 레코드로 합치면 `AlpacaConfig` 3개와 `AlpacaProperties` 3개가 줄어든다(지침서 02의 중복 후보이기도 함).

## B. 설정 파일 대조

파일: R=`src/main/resources/application{,-local,-prod}.yml`, T=`trading-core/src/main/resources/application{,-local,-prod}.yml`, 테스트=`shared/src/testFixtures/resources/application-test.yml`(양쪽 공용 1개, `bin/test/application-test.yml`은 IDE 산출물). 비밀값은 기재하지 않는다(로컬 yml에 Firebase 서비스계정·Toss 관리자 secret 등이 평문으로 있다 — 둘 다 .gitignore 대상인지 확인 요망, 이 조사의 범위 밖).

### B-1. 같은 키인데 값이 다른 것 (합병 시 결정 필요)

| 키 | R | T | 합병 판단 |
|---|---|---|---|
| `spring.application.name` | `kista-api` (`application.yml:3`) | `kista-trading` (`:3`) | `management.metrics.tags.application=${spring.application.name}`(R `:71`)로 Grafana 태그가 된다. 합병 후 role 2개가 같은 이름이면 대시보드가 합쳐진다 -> `spring.application.name`은 `kista`로 두고 role 태그를 별도로 추가하는 것을 권고(대시보드·알림 규칙 `deploy/grafana` 확인 필요) |
| `spring.datasource.hikari.pool-name` | `HikariPool-KistaApi` | `HikariPool-KistaTrading` | 1개 풀로 통합. 이름 1개 |
| **`spring.datasource.hikari.connection-init-sql`** | `SET search_path TO finance, kista_ref, public` (`:19`) | `SET search_path TO trading, trading_ref` (`:19`) | **최대 충돌.** 풀 하나는 search_path 하나다. 합치려면 `finance, kista_ref, public, trading, trading_ref` 같은 합집합이 필요한데 같은 이름 테이블이 있으면 순서가 의미를 바꾼다(C절 `event_publication`·`scheduler_locks`). 해법은 C절 |
| `spring.flyway.locations` | `classpath:db/migration` | `classpath:db/migration-trading` | D절 |
| `spring.flyway.table` | `flyway_schema_history_api` | `flyway_schema_history_trading` | D절 |
| `spring.flyway.default-schema` | `public` | `trading` | D절 |
| `spring.modulith.events.jdbc.schema` | `public` | `trading` (`:43`) | E절 |
| `spring.modulith.events.republish-outstanding-events-on-restart` | 기본 `true`(`:46`), prod에서 `false`로 덮음(`application-prod.yml:21`), scheduler role만 env로 `true` | **키 없음**(Modulith 기본값 `false`), compose가 trading에 env로 `true` | 합병 후 worker만 true(지침서 05 S-31). 로컬 기본값은 R이 `true`라 합병 앱 로컬이 항상 재발행한다 |
| `spring.data.redis.url` | `${REDIS_URL:redis://localhost:6379}` | `${REDIS_URL}` 기본값 없음 | 합병 시 R 형태(기본값 있음) 채택 |
| `server.port` | `8080` 고정 | `${SERVER_PORT:8081}`(로컬 2-프로세스 공존용), compose가 `SERVER_PORT=8080`으로 override | 합병 후 로컬은 8080 하나, 포트 변수 불필요. **8081 하드코딩 잔재**: `S/platform/security/SecurityConfig.java:48`의 주석 "management port(8081)", `INTERNAL_API_BASE_URL` 기본값 `http://localhost:8081`(R `:117`) |
| `server.shutdown` | `graceful` | 키 없음(Boot 3.4+ 기본이 graceful이므로 동작 동일, 명시는 R만) | R 채택 |
| `cors.allowed-origins` | `${CORS_ALLOWED_ORIGINS}` 필수 | local·기본 `${...:http://localhost:3000}`, prod는 필수 | R(필수) 채택. 로컬 yml에 값이 이미 있음 |
| `springdoc.paths-to-exclude` | 없음 | `/api/internal/**` | 단계 2에서 내부 컨트롤러가 사라지면 불필요. 그 전 단계 1에서는 합쳐진 spec에서 internal을 제외하도록 R에도 필요(G절) |
| `management.endpoints.web.exposure.include` | `health, info, metrics, prometheus` | **키 없음**(기본 health만) | 합병 시 R 채택. T는 현재 prometheus 비노출이다 — 메트릭 수집 설정(`deploy/grafana`)에 trading 타깃이 없는지 확인 필요 |
| `management.endpoint.health.show-details` | `when_authorized`(공통) / prod `never` | prod `never` | 동일 효과 |
| `management.endpoint.health.group.readiness.include` | `readinessState, db, redis` | 동일 | 동일 |
| `management.metrics.tags.application` | 있음 | 없음 | R 채택 |
| `logging.file.name`(local) | `logs/kista-api.log` | `logs/kista-trading.log` | 합병 후 로컬 로그 파일 1개. `docs/agents/commands.md`의 `tail -f logs/kista-trading.log` 문구 갱신 대상 |
| `spring.lifecycle.timeout-per-shutdown-phase`(prod) | `30s`(주석: docker `stop_grace_period 35s`) | `30s`(주석: 200s) | 합병 후 web 35s, worker 200s로 갈리므로 값은 같고 compose의 grace만 다르다. 단 `TradingBatchShutdownCoordinator`의 `trading.shutdown.critical-wait`(기본 `150s`, `T/trading/application/service/support/TradingBatchShutdownCoordinator.java:25`)가 30s phase 타임아웃과 충돌하는지 확인 필요(합병과 무관하게 이미 존재하는 관계이나 worker role에서 재검증) |
| `scheduler.enabled` | `${SCHEDULER_ENABLED:true}`, local/prod에서 `false` | **키 없음** — 코드는 `matchIfMissing=true`로 읽는다(`@ConditionalOnProperty(prefix="scheduler", name="enabled", matchIfMissing=true)`), compose가 `SCHEDULER_ENABLED=true` 주입. T local은 키가 없어 로컬 T는 스케줄러가 **켜진 채** 기동 | 합병 시 role 플래그로 교체. R local이 `false`이고 T local은 `true`(암묵)라 로컬 동작이 서로 다르다. 합병 후 로컬은 둘 다 켜되 텔레그램 봇 분리 원칙(`commands.md`)을 확인 |
| `telegram.*`, `crypto.aes-key`, `jwt.signing-key`, `internal.api.token`, `alpaca.api-key/api-secret/data-base-url` | 동일 env 이름(`TELEGRAM_BOT_TOKEN` 등) | 동일 | 충돌 없음. 합쳐서 1회만 선언 |
| `alpaca.base-url` | 없음 | `https://paper-api.alpaca.markets` | T 값 추가(A-4) |
| `internal.api.base-url` | `${INTERNAL_API_BASE_URL:http://localhost:8081}` | 없음 | 단계 1에서는 자기 자신(`http://localhost:8080`)을 가리킨다. T local yml `internal.api.base-url: http://localhost:8080`(`application-local.yml:19`)는 trading이 root를 가리키던 옛 흔적이고 읽는 코드 없음(T는 `InternalApiClientConfig` 조건 미충족) — 확인: `InternalApiClientConfig`는 base-url이 있으면 켜진다. **T local에는 base-url이 있어 로컬에서는 T도 이 빈을 만든다**(GradleModuleBoundaryTest가 참조만 금지) |

### B-2. 한쪽에만 있는 키

| 키 | 위치 | 합병 후 |
|---|---|---|
| `spring.task.scheduling.thread-name-prefix: kista-api-scheduler-` | R `:48-50` | 유지. `TaskScheduler` 빈은 Boot 자동구성 1개(pool size 기본 1)라 R·T 스케줄러 전부가 **단일 스레드 풀**을 공유하게 된다 — 지금은 각 JVM이 따로 갖는다. `@Scheduled` 메서드가 길게 도는 곳이 있으면 서로 막는다. Virtual thread 모드(`spring.threads.virtual.enabled=true`)에서는 Boot가 `SimpleAsyncTaskScheduler`를 쓰므로 큰 문제는 아닐 수 있으나 **확인 필요**(Boot 4.1 `TaskSchedulingAutoConfiguration` 소스) |
| `app.cookie.secure/same-site` | R | R 유지 |
| `kakao.*`, `admin.kakao-ids`, `firebase.*`, `kbland.base-url` | R | 유지 |
| `kis.*`(base-url, admin-app-key/secret), `toss.*`(base-url, admin-client-id/secret), `meritz.base-url`, `upbit.base-url`, `heartbeat.*`, `cmc.api-key` | T | 추가 |
| `toss.base-url` | T `:92` + 테스트 yml(`application-test.yml`)에도 있음 | 추가 |
| `app.trading.parallel-per-account`(기본 2), `trading.shutdown.critical-wait`(기본 150s), `binance.base-url`(기본 `https://api.binance.com`) | T `@Value` 기본값만(yml 없음) | 추가 불필요 |
| `management.otlp.metrics.export.*` | 양쪽 prod 동일 + R local `enabled:false` | 합병 시 R local의 `enabled: false` 유지 |

### B-3. 환경변수

- `.env`는 **role별로 갈리지 않는다**: compose 3 서비스 모두 `env_file: .env` 1개를 읽는다(`deploy/server/docker-compose.yml:29,64,100` 부근). role별 차이는 `environment:`로만 준다.
- `.env.example`에 **없는데 T yml이 읽는 변수**: `CMC_API_KEY`(`trading-core/.../application.yml:118`), `KIS_ADMIN_APP_KEY`, `KIS_ADMIN_APP_SECRET`, `SERVER_PORT`. (`TOSS_ADMIN_*`, `HEARTBEAT_*`는 있음.) 합병 후 R도 이 값을 읽게 되므로 `.env.example` 갱신 대상.
- 반대로 `.env.example`에 있고 R만 읽는 변수: `KAKAO_*`, `ADMIN_KAKAO_IDS`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `INTERNAL_API_BASE_URL`(compose 주입).

| compose 환경변수 | kista-api | kista-scheduler | kista-trading | 합병 후(web/worker) |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | prod | prod | prod | 동일 |
| `SCHEDULER_ENABLED` | `"false"` | `"true"` | `"true"` | role 플래그로 교체(web=false, worker=true) |
| `SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART` | 없음(prod yml false) | `"true"` | `"true"` | worker만 true |
| `REDIS_URL` | `redis://redis:6379` | 동일 | 동일 | 동일 |
| `INTERNAL_API_BASE_URL` | `http://kista-trading:8080` | 동일 | 없음 | 삭제(단계 2 이후) |
| `APP_JAR` | 없음(기본 `app.jar`) | 없음 | `trading-core.jar` | 삭제(jar 1개) |
| `SERVER_PORT` | 없음 | 없음 | `"8080"` | 삭제 |
| `JAVA_OPTS` | `-Xmx1280m`(`mem_limit` 2048m) | `-Xmx896m`(1536m) | `-Xmx2048m`(2816m) | 새로 정해야 함. 3 role 합 6400m. web은 trading 조회가 들어오므로 1280m로는 부족할 수 있음, worker는 T 값(2048m) 기준 |
| `stop_grace_period` | 35s | 200s | 200s | web 35s, worker 200s |
