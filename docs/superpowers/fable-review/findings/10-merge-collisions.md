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

## C. JPA·영속

### C-1. 사실관계

| 항목 | 값 | 근거 |
|---|---|---|
| 엔티티 수 | R 21, T 21, S 0(`BaseAuditEntity`·`BaseCreatedAtEntity`는 `@MappedSuperclass`) | `grep '^@Entity'` |
| 엔티티·`*JpaRepository` simple name 중복 | **0** | 전수 비교 |
| 같은 테이블을 가리키는 엔티티 양쪽 존재 | **없음.** `trading.user_notify_profile`은 T `UserNotifyProfileEntity`만 매핑. R의 `users`/`user_settings`는 R만 | `@Table(name,schema)` 전수 |
| `@Table(schema=...)` 명시 | R 21/21, T 21/21 (`finance` 9, `public` 8, `trading` 17, `trading_ref` 3) | grep. `kista_ref` 테이블은 엔티티 없음(JdbcTemplate만) |
| `event_publication` 엔티티 | 없음(Modulith JDBC 저장소가 직접 SQL) | |
| EntityManagerFactory·TransactionManager | 각 앱 1개(Boot 자동구성). `@Transactional(transactionManager=...)`·`@EnableJpaRepositories`·`@EntityScan` 사용 0 | grep |
| 스캔 범위 | `@SpringBootApplication` 패키지 하위가 전부 — R=`com.kista.api`, T=`com.kista.tradingcore`(`ApiApplication.java:10`, `TradingApplication.java:11`) | |
| `ddl-auto: validate` | 양쪽 `application.yml:24`; 엔티티가 스키마를 전부 명시하므로 **한 컨텍스트에서 5개 스키마 42 엔티티 검증 가능** | |

### C-2. 합병 시 부딪히는 지점

| # | 문제 | 근거 | 조치 |
|---|---|---|---|
| C1 | **search_path 단일화.** 엔티티는 스키마를 명시하지만, 다음은 unqualified 이름을 search_path로 해석한다: 모든 `nativeQuery`(R finance 11곳, T trading 약 45곳), `JdbcTemplate`(R `HousingPriceIndexPersistenceAdapter:22`·`HousingBenchmarkPricePersistenceAdapter:20`은 `kista_ref`, T `TradingBatchRunPersistenceAdapter:22`는 `trading`), `SchedulerLockService`(`S/.../scheduling/SchedulerLockService.java:60-107`) | R `application.yml:19`, T `:19` | 풀 하나에 `SET search_path TO finance, kista_ref, trading, trading_ref, public`처럼 합집합. **이름이 겹치는 테이블이 있으면 이 방법이 깨진다 -> C2** |
| C2 | **같은 이름 테이블이 두 스키마에 있다: `scheduler_locks`(public·trading), `event_publication`(public·trading)** | R `V1__init.sql:300`(`public.event_publication`)·`public.scheduler_locks`, T `V1__init.sql:77`(`trading.event_publication`)·`trading.scheduler_locks` | `scheduler_locks`는 `SchedulerLockService`가 unqualified로 쓰므로 search_path 순서가 곧 사용 테이블이다. 한 테이블로 정해야 한다. 판단 재료: 락은 짧은 수명(`lock_until`)이고 이름이 서로 다른 잡이지만, **trading 락은 재기동 시 `TradingBatchResumer`가 잔여 락을 인수하는 의미를 가진다**(`docker-infra.md:13`, `constraints.md` Git 규칙). 전환 시점에 `trading.scheduler_locks` 행을 새 테이블로 옮기거나 진행 중 배치가 없을 때 전환해야 한다. `event_publication`은 E절 |
| C3 | 스캔 루트. 합병 앱 클래스를 `com.kista`에 두면 `com.kista.shared`까지 컴포넌트 스캔되어 `PlatformAutoConfiguration`의 의도("앱은 :shared를 스캔하지 않는다", `PlatformAutoConfiguration.java:25-28` 주석)가 깨진다 | | `@SpringBootApplication(scanBasePackages = {"com.kista.api","com.kista.tradingcore"})` + `@EntityScan`·`@EnableJpaRepositories`에 두 패키지 명시(JPA 스캔은 `scanBasePackages`와 무관하게 `AutoConfigurationPackages`=앱 클래스 패키지를 쓰기 때문). 앱 클래스를 `com.kista.api`에 두고 `com.kista.tradingcore`를 추가 스캔하는 안이 가장 작다. `@ConfigurationPropertiesScan`은 두 패키지 모두로 |
| C4 | Hikari 풀. 지금 prod는 JVM당 max 5(`application-prod.yml:6`), 3 JVM = 15. 합병 후 2 JVM = 10이고 web이 매매 조회를 겸하므로 web 쪽 부족 가능 | | 값 재산정 필요. DB 쪽 `max_connections`(kista-infra) 확인 |
| C5 | `@SQLRestriction`·soft delete는 엔티티 단위라 충돌 없음. `deleted_at` 수동 조건은 native 쿼리 규칙(constraints.md)이 그대로 | | 없음 |
| C6 | `JpaAuditingConfig`(`@EnableJpaAuditing`)는 `:shared`에서 1회만 등록되므로 중복 없음. 단 테스트 베이스 `DataJpaTestBase.TestJpaAuditingConfig`가 별도로 `@EnableJpaAuditing` 선언 | `DataJpaTestBase.java` | 유지 |

## D. Flyway

### D-1. 현재 구성

| 항목 | R | T |
|---|---|---|
| 디렉토리 | `src/main/resources/db/migration`(V1~V6, 6개) | `trading-core/src/main/resources/db/migration-trading`(V1~V20, 20개) |
| 이력 테이블 | `flyway_schema_history_api`(`public`) | `flyway_schema_history_trading`(`trading`) |
| `default-schema` | `public` | `trading` |
| baseline | `baseline-on-migrate: false`(운영 첫 기동만 env로 true) | 동일 |
| 구성 코드 | **없음** — `spring.flyway.*` 프로퍼티뿐, Flyway `@Bean` 0개 | 동일 |
| 문서 | `docs/agents/flyway.md`, 마이그레이션 디렉토리마다 `CLAUDE.md` 존재 | |
| 의존성 | `libs.spring.boot.starter.flyway`, `libs.flyway.postgresql`(`build.gradle.kts:45-46`) | `trading-core/build.gradle.kts:41-42` |

CI 가드 `.github/scripts/check-migrations.sh`가 양 디렉토리의 기존 파일 변경 금지·root의 expand/contract를 검사한다(경로 하드코딩 여부는 K절에서 재확인).

### D-2. 한 프로세스가 이력 2벌을 실행하게 하는 구성

Boot 4.1.1 `FlywayAutoConfiguration`(`spring-boot-flyway-4.1.1-sources.jar`, `:125-128`)의 `FlywayConfiguration`은 `@ConditionalOnMissingBean(Flyway.class)`이고 `FlywayMigrationInitializer`도 이 안(`:338-342`)에 있다. 따라서 `Flyway` 빈을 하나라도 직접 정의하면 **자동구성이 통째로 꺼지므로** 두 벌 모두 직접 정의해야 한다. 필요한 것:

1. `Flyway` 빈 2개(`rootFlyway`, `tradingFlyway`): `Flyway.configure().dataSource(ds).locations(...).table(...).defaultSchema(...).baselineOnMigrate(...).validateOnMigrate(true)`. 값은 기존 `spring.flyway.*`를 대체할 새 프로퍼티(예: `kista.flyway.api.*`, `kista.flyway.trading.*`)로 받는다(기존 `spring.flyway.baseline-on-migrate`를 쓰는 `SPRING_FLYWAY_BASELINE_ON_MIGRATE` env 계약이 바뀐다 -> RUNBOOK `deploy/server/schema-reorg/RUNBOOK.md` 갱신).
2. 각 Flyway에 `@Bean(initMethod = "migrate")` 또는 `FlywayMigrationInitializer` 2개. 순서: root 먼저·trading 나중(두 이력은 서로 독립이라 순서는 무관하나 로그 일관성용).
3. EMF·JdbcTemplate 등이 마이그레이션 뒤에 뜨는 보장은 Boot의 `DatabaseInitializerDetector`(`FlywayDatabaseInitializerDetector`, `AbstractBeansOfTypeDatabaseInitializerDetector` 상속, `META-INF/spring.factories`)가 `Flyway` **타입 빈 전부**를 감지해 의존을 걸므로 자동이다.
4. `FlywaySchemaManagementProvider`(`:119`)는 `ObjectProvider<Flyway>`를 받으므로 2개여도 동작(Hibernate ddl 모드 판정용).
5. **`spring.flyway.enabled=false`로는 못 끈다**: 직접 만든 빈은 이 플래그와 무관하다 — 필요하면 `@ConditionalOnProperty`를 직접 붙인다.
6. 대안(권고하지 않음): `locations`를 두 디렉토리로 합치면 V1 버전이 충돌하고 이력 2개를 1개로 합치는 운영 마이그레이션이 필요하다.

현재 구성 코드 위치: 없음(순수 프로퍼티). 새 `@Configuration`은 `com.kista.api` 최상위(`ClockConfig`와 같은 자리) 또는 `com.kista.shared.platform.persistence`에 둔다. 후자는 `:shared`에 `Flyway` 의존을 추가해야 하고 `shared/build.gradle.kts` 확인 필요.

### D-3. 테스트 DB 영향

- 현재: 테스트 DB는 `kistadb_test`(integration은 `kistadb_it`)로 양 서브프로젝트가 **같은 DB**를 쓰고, 각자 `application.yml`의 `spring.flyway.*`로 자기 이력을 만든다(`shared/src/testFixtures/resources/application-test.yml`에는 flyway 키가 없다). `@DataJpaTest`는 `FlywayAutoConfiguration`만 포함하므로 직접 만든 `Flyway` 빈은 **슬라이스에 로드되지 않는다** -> `DataJpaTestBase`(`shared/src/testFixtures/.../DataJpaTestBase.java`)에 Flyway 구성을 `@Import`해야 슬라이스 테스트가 새 DB에서 스키마를 만든다(이미 migrate된 DB에서만 돌면 우연히 통과해서 놓치기 쉽다).
- `RootDataJpaTestBase`(`src/test/java/com/kista/api/web/RootDataJpaTestBase.java`)는 "엔티티 스캔이 root 범위로 좁혀진다"는 주석대로 존재 이유가 스캔 범위 분리뿐이다. 합병 후 스캔 범위가 하나가 되면 이 클래스는 빈 껍데기라 삭제하고 사용처(root persistence 테스트)를 `DataJpaTestBase`로 바꾼다(grep: 상속처 수 확인 필요). 슬라이스가 42 엔티티를 전부 검증하게 되고 테스트 시작 비용이 늘어난다.
- search_path: 테스트 JVM의 Hikari 설정은 모듈 `application.yml`에서 오므로 합병 후 한 yml의 합집합 search_path가 테스트에도 적용된다. `scheduler_locks` 해석(C2)이 테스트에서도 같게 동작하는지 `SchedulerLockServiceTest`·`SchedulerLockServiceDbTest`(`docs/agents/testing.md`)로 확인.
- 기존 테스트 DB에 이미 두 이력 테이블이 있으므로 합병 후에도 DB 재생성은 불필요하다(같은 이름의 이력 테이블을 그대로 사용).

## E. Modulith · EPR

### E-1. 모듈 전수 (package-info의 `@ApplicationModule`, 전부 `@Modulithic(additionalPackages="com.kista.shared")`)

| 프로세스 | 모듈 ID (type) | 비고 |
|---|---|---|
| R | `admin`, `benchmark`, `finance`, `market`, `notify`, `user` (type 미지정 = CLOSED 기본), `web` (`Type.CLOSED` 명시) | `R/<id>/package-info.java` |
| T | `account`, `broker`, `marketcalendar`, `matching`, `notify`, `privacy`, `stats`, `trading` (미지정), `web` (명시) | `T/<id>/package-info.java` |
| S | `kernel`, `contract`, `platform` (전부 `Type.OPEN`) | `S/<id>/package-info.java` |

- **ID 충돌 2건: `notify`, `web`.** 패키지가 다르므로(`com.kista.api.notify` 대 `com.kista.tradingcore.notify`) 코드 충돌은 없고 `id = "..."` 속성만 바꾸면 된다. 두 ID는 테스트 단언에 문자열로 들어 있다: `src/test/.../ModulithArchitectureTest.java`(R 모듈 10개 `containsExactlyInAnyOrder`), `trading-core/src/test/.../ModulithArchitectureTest.java`(T 12개). 합병 후 두 테스트는 1개로 합치고 ID 집합은 19개(7+9+3)가 된다. 그 외 ID 문자열 참조: 테스트 코드에서 `"notify"`/`"web"` 리터럴 0건(grep), 문서·`flows.yml`·CLAUDE.md의 "api.notify/tradingcore.notify" 표기는 합병 시 갱신. 지침서 05 4절 목표 구성(`notification`, `ops`로 통합)을 따르면 ID 개명 대신 모듈 통합이 되지만 단계 1에서는 개명만 한다(권고 `api-notify`→`notify`, `tc-notify`→`trading-notify` 형식은 실행자가 선택; 중요한 것은 유일성).
- `spring.modulith.detection-strategy: explicitly-annotated`는 양쪽 동일(`application.yml:40`/`:40`). 합병하면 한 줄.
- `@NamedInterface` 선언은 `package-info.java`에 합계 62줄(문자열 포함 라인 수). 합병 후에도 모듈 간 접근은 NamedInterface로만 허용되므로, R이 T 모듈을 부르는 새 코드(내부 HTTP 어댑터 -> 직접 호출)는 **T 모듈이 해당 타입을 NamedInterface로 노출해야 한다**. 노출되지 않은 것을 단계 2에서 부르면 `ApplicationModules.verify()` 실패. 지침서 05 4절 규칙 1(`api`·`events`만 공개)은 단계 3의 일이므로 단계 2에서는 기존 NamedInterface만 쓴다.
- 허용 모듈 의존(지침서 05 1절, 2026-11-11 main import 집계): `web -> admin/benchmark/finance/market/notify/user`, `notify -> user/benchmark/finance/market`, `admin -> user/benchmark + shared.contract`, `finance -> user`; T: `trading -> matching/broker/account/marketcalendar/privacy`, `stats -> trading/broker/matching/account/privacy`, `notify -> trading/account/privacy`, `account -> broker`, `privacy -> matching`, `web -> broker/trading/account/privacy`. `allowedDependencies` 선언 0건. 합병으로 생기는 새 방향: `admin -> trading/account/broker/privacy/stats`(내부 HTTP 어댑터 대체), `benchmark -> stats`(투자 포인트), `market -> marketcalendar/broker`(캔들·휴장일), `notify(api) -> trading`(알림 위임). **순환 위험**: T `notify -> trading` 이미 있고 R `admin -> trading`이 새로 생기면, `trading`이 `admin`을 부르는 경로가 있는지(예: 감사 로그, 오류 보고) 확인해야 한다 — `trading`이 `AppErrorRaisedEvent`를 발행해 admin이 듣는 현재 구조는 이벤트라 순환이 아니지만, 직접 포트 호출로 바꾸면 순환이 생긴다.
- `Documenter`: R `ModulithArchitectureTest`가 `writeModulesAsPlantUml()`/`writeIndividualModulesAsPlantUml()`을 호출(T는 안 함). 합병 후 19개 모듈 그림 1벌.
- 모델에 의존하는 테스트 파일: `ModulithArchitectureTest`(R·T), `ArchitectureMapTest`, `ModuleGraphExporter`, `HexagonalArchitectureTest`(`ApiApplication` 참조), `AdminInternalApiIntegrationTest`(`ApiApplication` 참조), `TradingApplicationContextLoadTest`, `SchedulerLockServiceTest/DbTest`(`TradingApplication`을 `@ContextConfiguration`). 앱 클래스가 하나가 되면 이 참조들이 전부 바뀐다.

### E-2. EPR 테이블 2개

| 항목 | R (`public.event_publication`) | T (`trading.event_publication`) |
|---|---|---|
| 설정 | `spring.modulith.events.jdbc.schema: public`, `schema-initialization.enabled: false`(`application.yml:42-45`) | `schema: trading`, 동일 비활성(`:42-45`) |
| 테이블 DDL | `R V1__init.sql:300` — id uuid, listener_id text, event_type text, serialized_event text, publication_date, completion_date, status text, completion_attempts int, last_resubmission_date | `T V1__init.sql:77` — **컬럼 동일** |
| completion mode | 미설정 = 기본 UPDATE(완료 시 `completion_date` 채움, archive 테이블 없음 — `archive` 키 grep 0건) | 동일 |
| republish | 기본 true -> prod false -> scheduler role만 env true | 키 없음 -> compose가 env로 true |
| 추적 리스너 수(`@TransactionalEventListener`) | 16 | 21 |
| 재발행 소유 | `kista-scheduler` | `kista-trading` |
| 배포 전 고아 검사 | `deploy/hooks/pre-apply.sh`: role `kista-scheduler` -> `public`, `kista-trading` -> `trading`(`:11-14`), `epr-orphans.sh`가 `listener_id` 파싱 | |

- **JDBC 저장소 설정은 앱당 1개다**(`spring.modulith.events.jdbc.schema`는 단일 값). 한 컨텍스트가 두 테이블에 쓰게 하려면 `EventPublicationRepository` 자체를 커스터마이즈해야 해서 비현실적이다. 합병은 **EPR 테이블을 하나로** 만든다는 뜻이다.
- listener_id 형식: `com.kista.x.Listener.method(com.kista.y.SomeEvent)`(Spring `ApplicationListenerMethodAdapter` 기본 id, `docs/agents/docker-infra.md:134`, `deploy/hooks/epr-orphans.sh:6`). 합병은 패키지 루트(`com.kista.api`, `com.kista.tradingcore`)와 리스너·이벤트 클래스명을 **바꾸지 않으면** 양쪽 테이블의 기존 행이 새 앱에서 그대로 resolve된다. 모듈 ID 개명은 listener_id에 영향이 없다(FQCN 기반). 단 단계 3에서 `shared.kernel` 이벤트(`UserDeletedEvent` 등)나 패키지를 옮기면 모든 미완료 행이 고아가 된다(`flyway.md` "이벤트 클래스·리스너 패키지 이동").
- **합병 전환 절차 재료**(결정은 Opus):
  1. 대상 테이블 선택: `public`(root, 변경 없는 DDL) 권고. `spring.modulith.events.jdbc.schema: public`만 유지.
  2. 전환 전: `SELECT count(*), min(publication_date) FROM trading.event_publication WHERE completion_date IS NULL` 및 `public`을 읽기 전용으로 조회해 미완료 행 수를 확인(운영 DB 직접 조회는 이 조사에서 하지 않았다).
  3. 미완료 행이 0이면 `trading.event_publication`은 그대로 두고 contract 단계에서 drop. 남아 있으면 `INSERT INTO public.event_publication SELECT * FROM trading.event_publication WHERE completion_date IS NULL`(컬럼 동일하므로 가능, id uuid 충돌 가능성은 uuid 난수라 사실상 0) 후 새 앱을 기동하면 재발행이 이어진다. 이때 `republish-outstanding-events-on-restart=true`가 worker에서만 켜져 있어야 이중 claim이 없다.
  4. 전환 중 두 옛 프로세스가 모두 죽은 상태에서 복사한다(`kista-trading` stop-first). 
- 합병 후 규칙: 재발행 소유 = worker 1곳(`SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART`). `pre-apply.sh`의 role->schema 매핑(`:11-14`)과 `epr-orphans.sh` 호출은 role이 `kista-web`/`kista-worker`로 바뀌므로 수정 대상(K절).

### E-3. 같은 이벤트를 양쪽이 듣는 곳 = 합병 시 중복 실행

R 리스너 19(`@TransactionalEventListener` 16 + `@EventListener` 3), T 리스너 23(21 + 2). 양쪽이 같은 이벤트 타입을 듣는 것은 3종:

| 이벤트(타입 위치) | R 리스너 | T 리스너 | 합병 시 문제 |
|---|---|---|---|
| `AppErrorRaisedEvent` (`shared.kernel`) | `R/admin/adapter/in/event/AppErrorRaisedListener.on` (`@EventListener`) — `app_error_logs` 직접 저장 | `T/trading/adapter/out/redis/AppErrorStreamPublisher.onAppErrorRaised` (`@EventListener`) — Redis `app.error` 스트림 발행 -> R `AppErrorStreamConsumer`가 같은 저장 수행 | **한 컨텍스트에서 이벤트 1건이 오류 로그 2행을 만든다**(직접 + 스트림 왕복). `AppErrorStreamPublisher`·스트림 컨슈머를 함께 삭제해야 한다. 삭제 순서가 어긋나면 중복 |
| `UserDeletedEvent` (`shared.kernel`) | `UserEventStreamPublisher.onUserDeleted`(스트림 발행) + finance cascade·FCM 정리·탈퇴 알림 3개 | `TradingUserCascadeListener.onUserDeleted` — 지금은 **스트림 컨슈머 -> `UserEventRepublisher`가 로컬 재발행**해야 실행 | 합병하면 R의 발행이 T 리스너를 **직접** 깨운다. 스트림 발행자·컨슈머·`UserEventRepublisher`·`UserEventStreamBridge`를 남겨 두면 T cascade가 2번 돈다(멱등이라 데이터는 안전하나 중복 알림·로그) |
| `UserNotifyProfileChangedEvent` (`shared.kernel`) | `UserEventStreamPublisher.onProfileChanged` | `UserNotifyProfileSyncListener.onProfileChanged` — `user_notify_profile` 복제본 갱신 | 같은 사정. 복제본 제거 시(지침서 05 단계 2-2) 이 리스너와 발행자를 함께 삭제 |

- 역으로 **한쪽만 듣던 이벤트가 합병 후 새로 양쪽에서 실행되는 경우**: `SchedulerLifecycleEvent`(`shared.platform.scheduling`)는 R `SchedulerNotifier.onSchedulerLifecycle`만 듣는다. `SchedulerJobRunner`(shared)가 이를 발행하는데 T에는 리스너가 없어 **현재 매매 잡의 라이프사이클 알림은 가지 않는다**. 합병하면 매매 잡 시작·실패 알림이 관리자 텔레그램으로 새로 나간다(동작 변경 — 의도인지 확인, 지침서 05 "동작 변경 금지" 원칙과 충돌하므로 알림 필터 또는 의도적 허용을 결정).
- 또 하나: R `CompositeUserNotificationAdapter`(`@Primary`) 등이 `@TransactionalEventListener`로 듣는 사용자 알림 이벤트와 T `*Notifier`가 듣는 매매 이벤트(`TradingReportReadyEvent` 등)는 타입이 달라 중복 없음. T `TradingStatsService`의 `@EventListener` 2개(캐시 무효화)는 합병해도 같은 JVM에서만 유효 — web과 worker가 갈리면 web 캐시가 worker의 이벤트를 못 받는다(지침서 05 2-4절, 이 조사의 범위 밖이나 확인 필요).
- 이중 재발행 스케줄러 `UserEventResubmitScheduler` 2벌: A5 참고. 단일 EPR가 되면 하나가 상대 이벤트를 포함해야 하나, 복제본 삭제 후에는 `AccountDeletedEvent`용 1개만 남을 가능성이 높다.

## F. Security

### F-1. 구성 (이미 대부분 공용)

`SecurityConfig`·`JwtAuthFilter`·`InternalTokenAuthFilter`는 `:shared`에 **1벌**이다(`S/platform/security/`). 프로세스별 차이는 `SecurityRoutePolicy` 구현 2개뿐이다.

| 순서 | 규칙(첫 매치 우선, `SecurityConfig.java:44-53`) | 출처 |
|---|---|---|
| 1 | `/error`, `/actuator/**`, `/swagger-ui/**`, `/api-docs/**`, `/swagger-ui.html` permitAll | 공용 |
| 2 | `/api/internal/**` `hasRole("INTERNAL")` | 공용 |
| 3 | `/api/admin/**` `hasRole("ADMIN")` | 공용 |
| 4 | `policies.orderedStream()` 기여 규칙 | R `RootSecurityPolicy`, T `TradingSecurityPolicy` |
| 5 | `anyRequest().authenticated()` | 공용 |

| 정책 | 규칙 |
|---|---|
| `R/web/RootSecurityPolicy.java` | permitAll: `/telegram/webhook`, `POST /api/client-errors`, `GET /api/market/**`, `GET /api/meta`, `GET /api/runtime-config`, `/api/auth/**`(단 아래 예외 먼저). authenticated: `/api/auth/status-stream`, `/api/trades/stream`, `DELETE /api/auth/me`, `GET /api/auth/me`, `POST /api/auth/approval-requests`. 익명 폴백(무효 토큰 통과): client-errors·market·meta·runtime-config |
| `T/web/TradingSecurityPolicy.java` | permitAll: `GET /api/crypto-market-indicators`. 익명 폴백 동일 경로 |

- **경로 충돌 없음**: 두 정책의 경로 집합이 겹치지 않고, 서로 다른 정책이 같은 URL에 다른 인가를 주는 곳이 없다. 합병하면 `ObjectProvider<SecurityRoutePolicy>`가 2개를 모두 받는다(`@Order` 없음, 서로 독립이라 순서 무관). `JwtAuthFilter`도 `policies.orderedStream()`으로 두 정책의 폴백을 합친다(`JwtAuthFilter.java` `anonymousFallback`) — 변경 불필요.
- T는 `/api/admin/**` 컨트롤러가 **0개**다(위 경로 추출). `/api/internal/**`는 R에 컨트롤러가 0개, T에만 있다.
- CORS: 공용 `SecurityConfig.corsConfigurationSource()` 1벌(`:57-73`). 양쪽 값 차이는 B절의 `cors.allowed-origins` 기본값뿐.
- `JwtAuthFilter`는 `TokenBlacklistPort` 단일 빈을 요구한다 — A4(T `RedisBlacklistAdapter` 삭제 필수).
- `InternalTokenAuthFilter`는 `internal.api.token`이 빈값이면 모든 internal 요청을 401로 거부한다(`:36`). 단계 1(자기 호출 유지)에서는 token env 하나를 root가 호출·수신에 겸용하므로 동작은 같다. 단계 2에서 internal 컨트롤러가 전부 사라지면 이 필터와 `/api/internal/**` 규칙(2행)은 **`/api/internal/fida-orders`**(외부 FIDA가 호출, T `FidaOrderController`)가 남는 한 지울 수 없다 — **Caddy `@trading` 정규식에도 이 경로가 있다**(`kista-api.caddy` `@trading`). FIDA 호출자는 외부이므로 이 엔드포인트의 URL·토큰 헤더는 유지해야 한다.
- 시큐리티 테스트: `SecurityConfigOrderingTest`(`src/test/.../web/`, 규칙 순서 잠금), `TradingSecurityPolicy` 대응 테스트 존재 여부는 `trading-core/src/test` 확인 필요.

### F-2. `/api/internal/**` 엔드포인트 전수 (T 컨트롤러 31개 핸들러) ↔ 호출 어댑터

(경로·메서드는 `routes.py` 추출 결과. 호출자는 `src/main`의 `.uri(` grep. 호출되지 않는 엔드포인트는 "호출자 없음".)

| 엔드포인트 | T 컨트롤러 | R 호출자(어댑터 파일) | 합병 후 |
|---|---|---|---|
| `GET /api/internal/accounts` | `AccountInternalController` | `admin/.../AccountQueryHttpAdapter:22-23` | 직접 호출 |
| `GET /api/internal/accounts/count` | 〃 | 〃 `:34` | 직접 호출 |
| `GET /api/internal/broker/candles/latest` | `CandleInternalController` | `market/.../CandleQueryHttpAdapter:34` | 직접 호출 |
| `GET /api/internal/broker/candles/range` | 〃 | 〃 `:48` | 직접 호출 |
| `POST /api/internal/fida-orders` | `FidaOrderController` | `admin/.../PrivacyQueryHttpAdapter:46`(관리자 경유) **+ 외부 FIDA 직접 호출** | **URL 유지 필수**(외부 호출자) |
| `GET /api/internal/marketcalendar/holidays` | `MarketCalendarInternalController` | `market/.../MarketCalendarQueryHttpAdapter:22` | 직접 호출 |
| `GET /api/internal/marketcalendar/session` | 〃 | 〃 `:33` | 직접 호출 |
| `GET /api/internal/marketcalendar/is-open` | 〃 | **호출자 없음**(grep 0건) | 삭제 후보 |
| `GET /api/internal/privacy/trade-bases` | `PrivacyInternalQueryController` | `admin/.../PrivacyQueryHttpAdapter:37` | 직접 호출 |
| `GET/PATCH /api/internal/privacy/trade-bases/{baseId}` | `PrivacyBaseInternalController` | 〃 `:63`, `:75` | 직접 호출 |
| `POST /api/internal/privacy/trade-bases/{baseId}/orders` | 〃 | 〃 `:97` | 직접 호출 |
| `DELETE/PATCH .../orders/{orderId}` | 〃 | 〃 `:86`, `:108` | 직접 호출 |
| `GET /api/internal/trading/accounts/{accountId}/strategies` | `TradingInternalQueryController` | `admin/.../TradingQueryHttpAdapter:42` | 직접 호출 |
| `GET .../strategies/{strategyId}/trade-dates` | 〃 | 〃 `:65` | 직접 호출 |
| `GET /api/internal/trading/orders` | 〃 | 〃 `:28` | 직접 호출 |
| `GET /api/internal/trading/orders/distinct-account-ids` | 〃 | 〃 `:35` | 직접 호출 |
| `POST /api/internal/trading/strategies/by-account-ids` | 〃 | 〃 `:49` | 직접 호출 |
| `POST /api/internal/trading/strategy-summaries` | 〃 | 〃 `:57` | 직접 호출 |
| `PATCH .../strategies/{strategyId}/status` | `StrategyStatusInternalController` | `admin/.../TradingCommandHttpAdapter:41` | 직접 호출 |
| `POST /api/internal/trading/trade-corrections` | `TradingInternalCommandController` | 〃 `:28`(`internalApiWriteRestClient`, 응답 타임아웃 20s) | 직접 호출. **20s 쓰기 타임아웃의 존재 이유(브로커 동기 호출 중 HTTP 타임아웃 -> 감사 로그 누락)는 프로세스 내 호출이 되면 사라지나, 감사 로그 기록이 트랜잭션 밖(`auditLogPort.log`)이라는 구조는 남는다** |
| `GET/PUT /api/internal/trading/policy-settings` | `TradingPolicyInternalController` | `admin/.../TradingPolicyHttpAdapter:32`, `:53` | 직접 호출 |
| `POST /api/internal/trading/scheduler/open`, `/close` | `TradingSchedulerInternalController`(`scheduler.enabled` 게이트) | `admin/.../TradingSchedulerCommandHttpAdapter:22`, `:30` | 직접 호출. **합병 후 web role에서 수동 트리거가 실행되면 배치가 web JVM에서 돈다**(role 게이트 설계 필요) |
| `GET /api/internal/trading/stats/exchange-rate` | `TradingStatsInternalController` | `benchmark/.../CurrentExchangeRateHttpAdapter:26` | 직접 호출 |
| `GET /api/internal/trading/stats/investment-points` | 〃 | `benchmark/.../InvestmentPointsHttpAdapter:29` | 직접 호출 |
| `GET /api/internal/trading/stats/portfolio/current`, `/history` | `PortfolioQueryInternalController` | `admin/.../PortfolioQueryHttpAdapter:24`, `:32` | 직접 호출 |

R 호출 어댑터 11개: admin 7(`AccountQuery`, `PortfolioQuery`, `PrivacyQuery`, `TradingCommand`, `TradingPolicy`, `TradingQuery`, `TradingSchedulerCommand`), benchmark 2(`CurrentExchangeRate`, `InvestmentPoints`), market 2(`CandleQuery`, `MarketCalendarQuery`). 단계 2 순서(지침서 05): market -> benchmark -> admin.
