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
| `internal.api.base-url` | `${INTERNAL_API_BASE_URL:http://localhost:8081}` | 없음(단, T local yml `application-local.yml:19`에 `http://localhost:8080`이 있어 로컬 T에서는 `InternalApiClientConfig`가 조건부로 켜진다 — 읽는 코드 없는 옛 잔재) | 단계 1에서는 자기 자신(`http://localhost:8080`)을 가리킨다. 단계 2에서 삭제 |

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

## G. Web

### G-1. `@RestControllerAdvice` — 가장 위험한 충돌

| | R `GlobalExceptionHandler` (`R/web/GlobalExceptionHandler.java`) | T `TradingExceptionHandler` (`T/web/TradingExceptionHandler.java`) |
|---|---|---|
| 순서 | `@Order` 없음(최저) | `@Order(HIGHEST_PRECEDENCE)` (주석: "다른 advice가 같은 컨텍스트에 생겨도 먼저 평가되도록 하는 방어적 명시") |
| catch-all | `@ExceptionHandler(Exception.class)` -> `ProblemDetailMappings.catchAll(ex, MAPPINGS, ...)` | 동일 형태, 자기 `MAPPINGS` |
| 고유 핸들러 | `User.CooldownException`(429 + `Retry-After` 헤더, 개별 유지), `MethodArgumentNotValidException`, `AsyncRequestTimeoutException`/`AsyncRequestNotUsableException`(SSE 종료, 본문 없음) | `BrokerApiException`(503, 벤더 라벨 title, 오류 보고), `MethodArgumentNotValidException` |
| `MAPPINGS` 고유 | R: `InvalidRefreshTokenException` 401, `AdminBrokerCredentialException` 422, `AdminBrokerRateLimitException` 429, `TradingPolicyUnavailableException` 503, `CandleUnavailableException` 503, `AdminPrivacyTradeConflictException`·`User.AlreadyActiveException`·`FinanceBudget.OverlappingPeriodException`·`FinanceAccount.DuplicateAccountNoException`·`FinanceAccount.LinkedAssetSnapshotsException`·`FinanceCategory.DuplicateNameException`·`FinanceGroupInvitation.InvalidInvitationStateException`·`FinanceGroup.MembershipConflictException` 409, `MonthlyClosing.MonthClosedException` 409+`MONTH_CLOSED` | T: `BrokerWithdrawalEnabledException`·`BrokerIpNotAllowedException`·`BrokerPermissionDeniedException`·`BrokerCredentialException` 422, `BrokerRateLimitException` 429, `AlreadyOrderedTodayException`·`DcaManualExecutionUnsupportedException`·`ManualTradingException`·`OrderCancelException`·`PrivacyTradeConflictException`·`DuplicateStrategyTickerException`·`Account.DuplicateAccountException` 409, `ManualTradingFailedException` 500(고정 문구) |
| 공통 | `ProblemDetailMappings.GENERIC`(`S/platform/web/ProblemDetailMappings.java`) 11종 병합 | 동일 |

**같은 예외를 다르게 매핑하는 곳은 없다**(양쪽 고유 목록의 교집합 0, 공통은 GENERIC 하나). 같은 의미의 쌍은 있다: `AdminBrokerCredentialException`(R own-type) <-> `BrokerCredentialException`(T), `AdminBrokerRateLimitException` <-> `BrokerRateLimitException`, `AdminPrivacyTradeConflictException` <-> `PrivacyTradeConflictException`. 합병하면 R의 `Admin*` 복원용 own-type이 필요 없어지고(내부 API 응답에서 예외를 되살리던 것 — `InternalApiErrorDetails`, `ErrorBodyDecoder`), T 원본 예외가 그대로 컨트롤러까지 올라와 T 매핑을 쓴다.

**충돌 증상**: 두 advice가 한 컨텍스트에 있으면 `@Order(HIGHEST)`인 T의 `handleGeneric(Exception)`이 **모든 예외를 먼저 잡는다**. Spring은 advice 순서대로 "그 advice에 맞는 핸들러가 하나라도 있는지"를 보고 첫 advice를 채택하므로, R의 고유 핸들러·매핑(Cooldown 429+Retry-After, Finance 409 7종, `MONTH_CLOSED`, `InvalidRefreshToken` 401, SSE 종료)이 **도달하지 못하고** T의 catch-all이 GENERIC 또는 500+오류 보고로 처리한다. 즉 가계부 중복 이름이 409가 아니라 500이 된다. 합병 필수 조치: **advice 1개로 통합**(`MAPPINGS` 합집합, 핸들러 합집합). 둘의 `reportErrorLog`는 caller 문자열 `X.class.getSimpleName()`만 다르다(`web.md` "합치지 못하는 이유" 문구는 프로세스 분리가 근거였으므로 합병 시 소멸). `ExceptionMessageFormatTest`, `docs/agents/modules/api/web.md`·`tradingcore/web.md`의 `MAPPINGS` SSOT 규칙도 갱신.

- 단위 테스트가 이 advice 2개에 걸려 있다(`GlobalExceptionHandler*Test`, `TradingExceptionHandler*Test` — 파일 목록은 `src/test`·`trading-core/src/test` grep으로 확인 필요).
- `AppErrorRaisedEvent.of(ex, <caller>)`의 caller는 합병 후 단일 이름. 로그 조회(관리자 화면 필터) 호환 확인 필요.
- T advice의 `BrokerApiException` 핸들러는 R `MAPPINGS`에 없다(R에서는 `CandleUnavailableException` 등 복원 예외로 우회). 합병 후 R 컨트롤러가 브로커 예외를 직접 만나면 이 핸들러가 필요하므로 통합 advice에 포함.

### G-2. URL 매핑 충돌

- 컨트롤러 핸들러 추출: R 105, T 71(`routes.py`, `@RequestMapping` 클래스 prefix + 메서드 매핑 단순 조합). 메서드+경로를 변수명 정규화(`{id}`->`{}`)해 비교한 **교집합 0**. 같은 prefix를 나누는 곳은 `/api/stats`(R `housing-benchmark/*` 5개, T `summary|equity-curve|cycles`)뿐이며 하위 경로가 달라 충돌 없음(Caddy 정규식이 이미 이 분기를 하드코딩 — K절).
- T 공개 경로: `/api/accounts`(계좌 CRUD 5), `/api/accounts/{accountId}/{crypto-portfolio|crypto-prices|cycle-history|daily-trades|ledger-totals|portfolio|prices|strategy-seed-preview|trading-cycles...}`, `/api/trading-cycles/**`(23), `/api/backtest`, `/api/crypto-market-indicators`, `/api/daily-trades`, `/api/orders/{orderId}`, `/api/stats/{summary|equity-curve|cycles}`. R 공개: `/api/finance/**`, `/api/auth/**`, `/api/settings/**`, `/api/market/{session|holidays|fear-greed|candles}`, `/api/meta`, `/api/runtime-config`, `/api/client-errors`, `/api/fcm/tokens`, `/api/trades/stream`, `/telegram/webhook`, `/api/admin/**`(users 4, logs 4, accounts 4, finance 4, privacy-trade-bases 6, scheduler 6, trades 2, settings 2, dashboard 1, `_ping`).
- 경계 케이스: R `/api/admin/accounts`·`/api/admin/privacy-trade-bases`와 T `/api/accounts`·`/api/internal/privacy/...`는 접두가 달라 충돌 없음. 단계 2에서 admin 컨트롤러가 T 서비스를 직접 부르면 `/api/admin/*`는 그대로 두고 어댑터만 바꾼다.
- 정규식 추출이라 `@RequestMapping(method=...)`·상수 경로·`produces` 조합은 놓칠 수 있다. 확정은 합병 후 기동 시 `Ambiguous mapping` 오류로 드러난다(컨텍스트 로드 테스트가 잡는다).

### G-3. `/api/meta`·OpenAPI·Jackson

| 항목 | 현황 | 합병 영향 |
|---|---|---|
| `/api/meta` | R `MetaController`(`R/web/MetaController.java`)만. enum 메타 번들 | 충돌 없음. T enum도 `shared.kernel`이라 이미 R이 노출 |
| OpenAPI `OpenAPI` 빈 | R `OpenApiConfig`(`R/user/.../OpenApiConfig.java:18`: title "KISTA API", bearerAuth, 태그 순서 목록) + `ErrorCodeOpenApiCustomizer`(`R/web/`, `ErrorCode` 스키마 주입). T는 빈 없음, springdoc 기본 | 합병 시 한 spec에 R·T가 합쳐진다. 지금은 UI가 `fetch:spec`으로 8080·8081 spec을 **병합**한다(`trading-core application.yml:51` 주석). 합병 후 UI 병합 스크립트 단순화(L절). 태그 목록(`OpenApiConfig`)에 T 컨트롤러 태그가 이미 있는지는 확인 필요(`거래 사이클`, `통계`가 보임 — T 태그가 R 설정에 이미 선언돼 있다) |
| `springdoc.paths-to-exclude` | T만 `/api/internal/**` | R에 이식 필요(안 하면 internal contract 타입이 공개 spec에 들어온다). 단계 2 이후 internal 컨트롤러가 없어지면 불필요(FIDA용 `/api/internal/fida-orders` 1개만 남는다) |
| springdoc 스키마 이름 충돌 | 단순 클래스명이 스키마 ID. 같은 단순명 쌍: `Market`(`S/kernel/Market` vs `R/finance/domain/model/Market`), `MarketSessionResponse`(`S/contract/marketcalendar` vs `R/market/adapter/in/web/dto`), `CycleHistoryResponse`·`CycleHistoryPageResponse`(`T/stats/.../dto` vs `T/trading/.../dto`, **T 내부에서 이미 공존**), `InvestmentPoint`(R/benchmark domain vs T/stats domain, DTO 아님), `PlainDecimalSerializer` | `Market`·`MarketSessionResponse`는 두 쪽이 한 spec에 들어오면 한쪽 스키마가 덮이거나 `Market1` 접미사가 붙는다 -> UI 생성 타입 변경(kista-ui `gen:types`). 공개 경로에서 도달하는지는 `springdoc` 실측 필요(미확인) |
| Jackson | `ObjectMapper`/`JsonMapper` `@Bean`·`@JsonComponent`·`spring.jackson.*` **모두 0** — Boot 기본 `JsonMapper`(Jackson 3) 1개. 어댑터들이 필요 시 자체 `JsonMapper`를 `new`/주입 | 충돌 없음. `PlainDecimalSerializer`는 2벌(`S/contract/notify/PlainDecimalSerializer`, `T/stats/adapter/in/web/dto/PlainDecimalSerializer`)이라 합병 때 1개로 |

## H. 프로세스 경계 코드 삭제 대상

### H-1. HTTP 3자 매핑 (R 어댑터 메서드 -> 내부 엔드포인트 -> T 서비스/포트)

`TradingContractMapper`(`T/trading/adapter/in/web/`), `StatsContractMapper`(`T/stats/adapter/in/web/`), `PrivacyContractMapper`(`T/privacy/adapter/in/web/`)가 T 도메인 <-> `shared.contract` 변환을 한다. R 쪽은 어댑터가 contract 응답을 R own-type(`AdminStats`, `AdminAnomalies` 등 `R/admin/domain/model/`)으로 다시 변환한다.

| R 어댑터 (`R/.../adapter/out/internal/`) | 엔드포인트 | T 컨트롤러가 부르는 실제 대상 | 합병 후 직접 호출 대상 |
|---|---|---|---|
| admin `AccountQueryHttpAdapter` | `GET /accounts`, `/accounts/count` | `AccountPort.findAll()`, `countAll()` | `account` 모듈 `AccountPort`(NamedInterface 노출 여부 확인) |
| admin `PortfolioQueryHttpAdapter` | `GET /trading/stats/portfolio/current`, `/history` | `PortfolioUseCase.getCurrent(userId)`, `getHistory(userId,from,to)` + `StatsContractMapper` | `stats` 모듈 `PortfolioUseCase` |
| admin `PrivacyQueryHttpAdapter` | `GET /privacy/trade-bases`; `GET/PATCH /privacy/trade-bases/{id}`; `POST .../orders`; `PATCH/DELETE .../orders/{orderId}`; `POST /fida-orders` | `PrivacyTradePort.findBasesFromTradeDate/findByIdOrThrow/updateBase/addOrder/updateOrder/deleteOrder`, `PrivacyUseCase`(fida-orders, `PrivacyContractMapper.toCommand/toResponse`) | `privacy` 모듈 포트·유스케이스. **admin이 `PrivacyTradePort`(영속 포트)를 직접 부르게 되므로 지침서 05 4절 규칙 1과 충돌** -> 유스케이스 경유 필요 |
| admin `TradingCommandHttpAdapter` | `POST /trading/trade-corrections`(write 클라이언트), `PATCH .../strategies/{id}/status` | `ManualTradeCorrectionUseCase.correctManualFills(...)`, `AccountPort`·`StrategyPort`·`StrategyUseCase.resume/pause`(account.userId() 위임) | `trading` 모듈 유스케이스 |
| admin `TradingPolicyHttpAdapter` | `GET/PUT /trading/policy-settings` | `TradingPolicyUseCase.getPolicy()/replacePolicy()` | `trading` 모듈 |
| admin `TradingQueryHttpAdapter` | `GET /trading/orders`, `/orders/distinct-account-ids`, `/accounts/{a}/strategies`, `/accounts/{a}/strategies/{s}/trade-dates`; `POST /strategies/by-account-ids`, `/strategy-summaries` | `OrderPort.findAll/findDistinctAccountIdsByTradeDateBetween/findTradeDatesByStrategyId`, `StrategyPort.findByAccountId/findByAccountIds/findSummariesByCycleIds` + `TradingContractMapper` | `trading` 모듈. **영속 포트(`OrderPort`·`StrategyPort`) 직접 호출이라 관리자용 읽기 유스케이스 신설 필요** |
| admin `TradingSchedulerCommandHttpAdapter` | `POST /trading/scheduler/open`, `/close` | `TradingOpenScheduler`·`TradingCloseScheduler` 빈 직접 호출(`scheduler.enabled` 게이트) | `trading` 모듈 스케줄러 트리거. **인바운드 어댑터 클래스를 서비스처럼 부르는 구조라 유스케이스로 감싸야 한다** |
| benchmark `CurrentExchangeRateHttpAdapter` | `GET /trading/stats/exchange-rate` | `BrokerStatisticsUseCase.currentExchangeRate().midRate()` | `stats`/`broker` 모듈 |
| benchmark `InvestmentPointsHttpAdapter` | `GET /trading/stats/investment-points` | `InvestmentPointsQuery.fetch(userId, scope, strategyId, currency, from, to)` + `StatsContractMapper.toResponse` | `stats` 모듈 |
| market `CandleQueryHttpAdapter` | `GET /broker/candles/latest`, `/range` | `CandlePort.getLatestCandles/getCandles` | `broker` 모듈 포트(`CandleUnavailableException` 복원 코드 삭제) |
| market `MarketCalendarQueryHttpAdapter` | `GET /marketcalendar/holidays`, `/session` | `MarketCalendarPort.findHolidaysForMonth/findSession/isMarketOpen` | `marketcalendar` 모듈 |

삭제 대상 합계(합병 단계 2 완료 시): R 어댑터 11 + R `package-info`/포트 일부, T 내부 컨트롤러 12개(`FidaOrderController`는 외부 FIDA 계약이라 유지), T `*ContractMapper` 3, `shared.contract` 21 중 대부분(아래 I절), R `Admin*` own-type·예외 복원 코드(`InternalApiErrorDetails`·`InternalApiStatusHandlers`·T `ErrorBodyDecoder`), `InternalApiClientConfig`·`InternalApiProperties`(token은 FIDA 때문에 `InternalTokenAuthFilter`가 계속 필요하므로 properties 일부는 남음), `AdminInternalApiIntegrationTest`, `InternalApiContractTest`, `CaddyRoutingTest` 중 `/api/internal` 분기.

### H-2. Redis 채널·스트림 4종 + Pub/Sub 1종

| 종류 | 키/채널(`RedisStreamConfig`·`RedisPubSubConfig`) | 방향 | 발행자 | 소비자 | 합병 후 대체 |
|---|---|---|---|---|---|
| Stream | `stream:user.deleted` | R -> T | `R/user/.../UserEventStreamPublisher.onUserDeleted` | `T/trading/adapter/in/redis/UserEventStreamConsumer` -> `UserEventStreamBridge`·`UserEventRepublisher`(로컬 재발행) -> `TradingUserCascadeListener` | **직접 이벤트**(R 발행이 T 리스너에 도달). 발행자·컨슈머·브리지·재발행자·`UserEventResubmitScheduler` 2개 삭제 |
| Stream | `stream:user.notify-profile.changed` | R -> T | `UserEventStreamPublisher.onProfileChanged` | T `UserEventStreamConsumer` -> `UserNotifyProfileSyncListener`(복제본 upsert) | **복제본 자체 삭제**(H-3) |
| Stream | `stream:app.error` | T -> R | `T/trading/adapter/out/redis/AppErrorStreamPublisher`(`@EventListener AppErrorRaisedEvent`) | `R/admin/adapter/in/redis/AppErrorStreamConsumer` | R `AppErrorRaisedListener`가 같은 이벤트를 직접 받는다 — **발행자·컨슈머 삭제 안 하면 이중 저장**(E-3) |
| Stream | `stream:user.push-notification.requested` (maxlen 10,000) | T -> R | `T/notify/adapter/out/gateway/RedisPushNotificationPublisher` | `R/notify/adapter/in/redis/PushNotificationStreamConsumer` -> FCM 발송 | 직접 포트 호출(`PushNotificationPort`). 컨슈머 그룹 `root`(kista-api·kista-scheduler 공용), `trading-core` 그룹 삭제. `UserPushNotificationRequestedEvent`(`shared.kernel`) 소멸 |
| Pub/Sub | 매매 이벤트 채널 | T -> R | `T/notify/adapter/out/gateway/RedisTradeEventPublisher` | R `RedisTradeEventSubscriber`(SSE 푸시, `TradeSseEmitterRegistry`), `TradeEventBenchmarkCacheInvalidator` | 같은 JVM이면 Spring 이벤트로 대체 가능하나 **web/worker 분리 시 worker가 발행하고 web이 SSE를 쥐므로 Redis Pub/Sub이 계속 필요**. 유지 판정 — 삭제 대상 아님. (memory "Pub/Sub @PostConstruct 유지" 확정 항목 재검토 대상, 지침서 05 7절) |

`RedisStreamSubscriber`(`S/platform/redis/`)는 남는 스트림 컨슈머가 0개가 되면 삭제 가능(Pub/Sub 컨테이너 `RedisPubSubConfig`는 유지). `RedisStreams` 정적 유틸·`RedisStreamConfig` 상수도 동시 정리. **role 분리 시 컨슈머는 worker 조건**(지침서 05 단계 1-2).

### H-3. `user_notify_profile` 복제본 읽는 곳 전수

복제본 테이블 `trading.user_notify_profile`(엔티티 `T/trading/adapter/out/persistence/UserNotifyProfileEntity.java:` 컬럼: user_id, notification_prefs(JSON), is_active, telegram_bot_token(AES), chat_id, updated_at, notification_channel)을 `TradingUserProfilePort`(`T/trading/application/port/output/TradingUserProfilePort.java`: `findByUserId`, `findAllByUserIds`, `findAllActive`, `applyChange`, `markDeleted`)로 읽는 호출처:

| 호출처(`T/`) | 용도 |
|---|---|
| `notify/adapter/out/gateway/CycleEndedNotifier`, `CycleLifecycleNotifier`(2), `CryptoTradeNotifier`, `TradingReportNotifier`, `TradingAlertNotifier`(약 8메서드), `ApiKeyExpiryNotifier`, `TradingUserProfiles`(헬퍼 `requireProfile`) | 알림 수신자·채널·봇 토큰 조회 |
| `trading/adapter/in/schedule/BatchContextFactory` | 배치 컨텍스트에 프로필 적재 |
| `trading/application/service/MarketEventNotifier` | 개장·마감 브로드캐스트(`findAllActive`) |
| `trading/application/service/StrategyCreationService` | 등록 시 사용자 활성·프로필 확인 |
| `trading/application/service/CryptoDcaRunner` | 코인 DCA 알림 |
| `trading/application/service/UserTradingDataEraseService` | 탈퇴 정리 `markDeleted` |
| `trading/adapter/in/event/UserNotifyProfileSyncListener` | 쓰기(`applyChange`) |
| **native SQL**: `T/trading/adapter/out/persistence/StrategyJpaRepository.java:44-58` | `JOIN user_notify_profile p ON p.user_id = a.user_id` — 활성 사용자 필터. 복제본 삭제 시 `public.users`(+ status) 조인으로 바꿔야 하며 이것이 "다른 서비스 소유 테이블 참조 금지"(`constraints.md`) 규칙의 변경점이다 |

원본(R `user` 모듈)에서 같은 정보를 얻는 길: `UserPort.findById`(status·`telegramBotToken`·`telegramChatId`·`notificationChannel`)와 `UserSettingsPort.findOrDefault(userId)`(`notificationPrefs`) — R `UserNotifyProfilePublisher`(`R/user/application/service/`)가 정확히 이 두 포트를 합쳐 스냅샷을 만든다. 합병 후에는 `user` 모듈이 `TradingUserProfile`에 해당하는 읽기 유스케이스(예: `UserNotifyProfileQuery.findByUserId/findAllByUserIds/findAllActive`)를 `user :: api`로 공개하고 T 호출처 12곳을 이것으로 바꾼다. `TradingUserProfile`(`T/trading/domain/model/`)은 R 쪽 타입 하나로 통합. **주의**: `telegram_bot_token`은 R `users`에 AES 암호문(`@Convert`)으로 있어 R 포트가 복호화된 평문을 이미 돌려준다 — 복제본의 복호화 어댑터 코드는 불필요.

## I. `shared.kernel` · `shared.contract` 전수

참조 수 = main 소스에서 해당 FQCN(또는 같은 패키지 와일드카드)을 import하고 단순명을 쓰는 **파일 수**(`refs.py`, 테스트 제외). S = 같은 `:shared`의 다른 패키지 파일 수. kernel 42개 타입 + `package-info` = 43파일, contract 20개 타입 + `package-info` = 21파일(지침서의 '43개·21개'는 `package-info` 포함 파일 수). 합병 후 `shared.kernel`은 지침서 05 4절에 따라 해체 대상이다.

### I-1. `shared.kernel`

| 타입 | 종류 | R 파일 | T 파일 | S 파일 | 원래 소유로 보이는 모듈 |
|---|---|---|---|---|---|
| `AccountNumberMasker` | class | 2 | 2 | 0 | platform(공용 유틸) |
| `ApiKeyExpirySource` | enum | 0 | 2 | 0 | account(T) |
| `AppErrorRaisedEvent` | record/event | 4 | 3 | 0 | ops/admin(R 소비) · 합병 후 platform 이벤트 |
| `Broker` | enum | 5 | 40 | 2 | broker/account |
| `BrokerSettings` | record | 1 | 0 | 0 | broker(R만 참조: 관리자 설정) |
| `CryptoTradingRules` | record | 0 | 5 | 0 | trading(T) |
| `Currency` | enum | 10 | 55 | 4 | 공용 값타입(common) |
| `DcaExecutionStatus` | enum | 1 | 2 | 0 | trading(T) |
| `DcaFrequency` | enum | 1 | 4 | 0 | trading |
| `DcaIndicator` | enum | 2 | 8 | 0 | trading |
| `DcaOperator` | enum | 1 | 5 | 0 | trading |
| `DcaShortfallPolicy` | enum | 1 | 5 | 0 | trading |
| `DcaSizingType` | enum | 1 | 6 | 0 | trading |
| `DcaSkipReason` | enum | 1 | 14 | 0 | trading |
| `KstDays` | class | 0 | 4 | 0 | platform(time) |
| `Market` | enum | 0 | 14 | 0 | broker/marketcalendar |
| `MarketSession` | enum | 0 | 1 | 0 | marketcalendar |
| `NotificationChannel` | enum | 8 | 4 | 0 | user |
| `NotificationType` | enum | 7 | 6 | 0 | user |
| `OrderDirection` | enum | 2 | 66 | 5 | matching/trading |
| `OrderStatus` | enum | 1 | 15 | 1 | trading |
| `OrderTiming` | enum | 1 | 18 | 1 | trading |
| `OrderType` | enum | 1 | 24 | 4 | trading |
| `Quantities` | class | 1 | 9 | 0 | 공용 값타입 |
| `RecurringMode` | enum | 1 | 3 | 0 | trading |
| `ReturnMetrics` | class | 1 | 1 | 0 | stats |
| `StrategyCapability` | record | 1 | 1 | 0 | trading |
| `StrategyCreationSettings` | record | 2 | 9 | 0 | trading(+admin 정책) |
| `StrategyCycleSeedType` | enum | 1 | 20 | 1 | trading |
| `StrategyDefaults` | class | 0 | 9 | 0 | trading |
| `StrategyFieldSettings` | record | 2 | 0 | 0 | trading(+admin 정책) |
| `StrategyStatus` | enum | 6 | 11 | 2 | trading |
| `StrategyTicker` | enum | 5 | 124 | 7 | trading |
| `StrategyType` | enum | 6 | 49 | 4 | trading |
| `TimeZones` | class | 8 | 27 | 0 | 공용 |
| `TradeLegSummary` | record | 0 | 3 | 0 | trading |
| `TradingPolicySettings` | record | 6 | 5 | 0 | trading(런타임 정책, R admin이 편집) |
| `UserDeletedEvent` | record/event | 6 | 4 | 0 | user |
| `UserNotifyProfileChangedEvent` | record/event | 3 | 4 | 0 | user(복제본 삭제 시 소멸) |
| `UserPushNotificationRequestedEvent` | record/event | 1 | 2 | 0 | notification(Redis 삭제 시 소멸) |
| `UserRole` | enum | 19 | 0 | 0 | user |
| `UserStatus` | enum | 23 | 0 | 0 | user |

### I-2. `shared.contract` (합병 단계 2에서 대부분 삭제)

| 타입 | 종류 | R 파일 | T 파일 | S 파일 | 비고 |
|---|---|---|---|---|---|
| `AccountSummaryResponse` | record | 10 | 1 | 0 | account |
| `DailyCandleResponse` | record | 5 | 1 | 0 | broker |
| `MarketSessionResponse` | record | 2 | 1 | 0 | marketcalendar |
| `PlainDecimalSerializer` | class | 0 | 0 | 0 | platform |
| `TradeEventEnvelope` | record | 2 | 1 | 0 | notify(Pub/Sub 유지 시 존속) |
| `TradeEventMessage` | record | 1 | 4 | 0 | notify(Pub/Sub 유지 시 존속) |
| `FidaOrderRequest` | record | 5 | 2 | 0 | privacy(외부 FIDA 계약 — 유지) |
| `FidaOrderResponse` | record | 1 | 2 | 0 | privacy(유지) |
| `PrivacyBaseUpdateRequest` | record | 5 | 2 | 0 | privacy |
| `PrivacyOrderAddRequest` | record | 5 | 2 | 0 | privacy |
| `PrivacyOrderUpdateRequest` | record | 5 | 2 | 0 | privacy |
| `PrivacyTradeBaseResponse` | record | 7 | 3 | 0 | privacy |
| `InvestmentPointsResponse` | record | 1 | 2 | 0 | stats |
| `PortfolioCurrentResponse` | record | 3 | 2 | 0 | stats |
| `PortfolioOrderResponse` | record | 3 | 2 | 0 | stats |
| `OrderResponse` | record | 6 | 2 | 0 | trading |
| `StrategyResponse` | record | 7 | 2 | 0 | trading |
| `StrategySummaryResponse` | record | 6 | 2 | 0 | trading |
| `TradeCorrectionRequest` | record | 5 | 2 | 0 | trading |
| `TradeCorrectionResponse` | record | 5 | 2 | 0 | trading |

관찰:
- **R만 쓰는 kernel 타입**: `UserRole`(19)·`UserStatus`(23)·`BrokerSettings`·`StrategyFieldSettings` — T 참조 0. 프로세스를 넘겨야 해서가 아니라 contract/kernel 규칙 때문에 `shared`에 있었다. 합병 후 `user`·`broker`로 복귀 가능(지침서 05 4절 규칙 2).
- **T만 쓰는 kernel 타입**: `ApiKeyExpirySource`, `CryptoTradingRules`, `KstDays`, `Market`, `MarketSession`, `StrategyDefaults`, `TradeLegSummary` — R 참조 0(`Market`은 R에 같은 이름의 `finance.Market`이 따로 있다, G-3).
- **양쪽이 쓰는 핵심**: `StrategyTicker`(T 124), `Currency`(T 55), `OrderDirection`(T 66), `StrategyType`(T 49), `Broker`(T 40). T 내부에서 압도적으로 쓰이므로 해체 시 `trading`/`broker`로 이동하는 것이 대량 import 변경(수백 파일)이다. 단계 3의 별도 커밋 묶음.
- **이벤트 5종**(`AppErrorRaisedEvent`, `UserDeletedEvent`, `UserNotifyProfileChangedEvent`, `UserPushNotificationRequestedEvent` + 이미 platform의 `SchedulerLifecycleEvent`)은 H-2에서 보듯 3종이 Redis 경유 전달용이라 합병으로 소멸하거나 단순 이벤트가 된다.
- contract는 R의 admin 어댑터·T 내부 컨트롤러/mapper가 대부분 유일한 사용처다. 예외: `FidaOrderRequest/Response`(외부 FIDA HTTP 계약), `TradeEventEnvelope/Message`·`PlainDecimalSerializer`(Pub/Sub·SSE 경로, 유지). `InternalApiContractTest`가 이 패키지의 규약을 강제한다(J절).

## J. 빌드 · 테스트

### J-1. Gradle 구성 차이

| 항목 | 루트 `:api` (`build.gradle.kts`) | `:trading-core` (`trading-core/build.gradle.kts`) | `:shared` |
|---|---|---|---|
| 플러그인 | java, java-test-fixtures, spring-boot, dependency-mgmt | 동일 | 동일(`bootJar` `enabled=false`) |
| `settings.gradle.kts` | `include("trading-core")`, `include("shared")` (3줄) | | |
| main 의존 `:shared` | `implementation(project(":shared"))` | 동일 | |
| main 의존 상대 프로젝트 | **없음** | 없음 | 없음 |
| 루트에만 | `firebase.admin`(FCM), `gson`(runtimeOnly), `testImplementation(project(":trading-core"))`, `mockwebserver3:5.5.0`, `httpclient5`(테스트), `spring-boot-starter-jdbc-test`/`resttestclient`/`http-client`/`restclient`, `spring.modulith.docs`(Documenter) | | |
| trading-core에만 | | `micrometer.prometheus`, `micrometer.otlp`(`implementation`, 주석: 이 둘만 있고 actuator가 없으면 부팅 실패) | `:shared`가 `micrometer.*`·`httpclient5`·`starter-data-redis`를 `implementation`으로 가진다(런타임 전이) |
| 버전 | 전부 `gradle/libs.versions.toml` 카탈로그(Boot 4.1.1, Modulith BOM 동일, springdoc 3.1.1 등)라 **버전 차이 없음** | | |
| `bootJar` | `app.jar` | `trading-core.jar` | 비활성 |
| `Dockerfile` | `./gradlew clean bootJar :trading-core:bootJar` -> 이미지에 `app.jar`·`trading-core.jar` 둘 다(`Dockerfile:18-30`), 실행은 `APP_JAR` | | |

합병(단계 1) 시 필요한 의존 변경: 루트 `implementation(project(":trading-core"))` 추가(현재 `testImplementation`만 있음, 주석에 의도 명시). trading-core는 Boot 플러그인을 적용해 `jar`가 plain 산출물이라 의존 해결은 되지만 `:trading-core:bootJar`는 `enabled = false`로 꺼야 이미지·CI 시간이 줄어든다.

**클래스패스 리소스 충돌(합병의 숨은 위험)**: 루트 jar에 두 프로젝트 리소스가 들어가면 `application.yml`·`application-local.yml`·`application-prod.yml`이 **같은 이름으로 2벌** 존재한다(`src/main/resources/`, `trading-core/src/main/resources/`). Spring Boot는 `classpath:/application.yml`을 단일 리소스로 로드하므로 **클래스패스 순서상 먼저인 한쪽만 적용되고 다른 쪽은 조용히 무시된다**(`BOOT-INF/classes`가 `BOOT-INF/lib`보다 먼저라 루트가 이긴다 — 확인 필요). B절 키 병합을 먼저 끝내 단일 yml로 만든 뒤 `trading-core/src/main/resources/application*.yml`을 삭제해야 한다. 같은 이유로 `db/migration`(루트)과 `db/migration-trading`은 디렉토리가 달라 안전하나, `CLAUDE.md` 파일(`db/migration/CLAUDE.md`, `db/migration-trading/CLAUDE.md`)이 jar에 포함되는 것은 현재도 동일.

### J-2. 테스트 태스크

| 항목 | 루트 | trading-core |
|---|---|---|
| `test` | `excludeTags("integration")`, `maxHeapSize=2g`, `-XX:+EnableDynamicAgentLoading`, `user.timezone=Asia/Seoul`, JUnit 병렬(`concurrent`/dynamic) 켬, `inputs.file(deploy/server/caddy/kista-api.caddy)`(`CaddyRoutingTest` 입력) | `workingDir = rootProject.projectDir`, `maxHeapSize=2g`, TZ, **병렬 설정 없음**, `EnableDynamicAgentLoading` 없음 |
| `integration` | `kistadb_it`, `includeTags("integration")` | 동일 + `workingDir=rootProject.projectDir` |
| `testJvmSlot` | 루트 `build.gradle.kts` 하단에서 `allprojects { tasks.withType<Test>().configureEach { usesService(testJvmSlot) } }` — 로컬에서 테스트 JVM 동시 1개 | 위 allprojects 적용 |
| testFixtures | `testFixtures(project(":shared"))`(`DataJpaTestBase`/`WebMvcTestSupport`/`FixedClockTestConfig`/`application-test.yml`), 자체 `DomainFixtures`(User) | `testFixtures(project(":shared"))` + 자체 `TradingFixtures`·`StubBrokerApiException` |
| 서로의 test 의존 | 루트 `testImplementation(project(":trading-core"))`(main만, trading-core testFixtures는 아님) | 루트 test 소스를 보지 않음 |

합병 후 테스트 태스크는 1개로: 병렬 설정(루트 방식)을 T 테스트에도 적용하면 T 테스트의 `@Execution`/상태 공유 가정이 깨질 수 있다(`testing.md` "Mockito 병렬 테스트 주의"는 이미 루트 기준 규칙이라 T 테스트가 병렬 안전한지는 확인 필요). `workingDir`가 루트 `projectDir`로 통일되는지(T `ExceptionMessageFormatTest`류 소스 스캔 경로, `backtest-golden` 리소스 경로) 확인.

### J-3. "두 프로젝트 분리"를 전제로 한 가드 테스트

| 테스트 | 위치 | 지키는 것 | 합병 후 |
|---|---|---|---|
| `GradleModuleBoundaryTest` | `src/test/.../architecture/` | `:trading-core`·`:shared`가 `com.kista.api`를 참조하지 않음, `:shared`가 `tradingcore`/`api`를 참조하지 않음 | `shared`가 api/tradingcore를 참조하지 않는 규칙 1개만 `HexagonalArchitectureTest`로 이관, 나머지 삭제 |
| `InternalApiContractTest` | 〃 | `/api/internal/**` 핸들러 반환·바디 타입은 contract·kernel·JDK만, contract는 허용 패키지만 의존 | 단계 2 후 삭제(FIDA 1개만 남으면 `FidaOrder*` contract 정도로 축소) |
| `TradingCoreTestVendorModelTest` | `trading-core/src/test/.../architecture/` | broker 밖 T **테스트**가 벤더 타입(`domain.model.kis/toss/upbit/meritz/binance`) 미참조 | 유지. 합병 후엔 루트 `HexagonalArchitectureTest.vendor_models_must_not_leak_outside_broker`(주석: "T 테스트 클래스는 못 본다")가 이 역할까지 하도록 스캔 범위 확장 후 2개를 합칠 수 있다 |
| `ModulithArchitectureTest` ×2 | R·T 각각 | 프로세스별 모델 + 모듈 ID 집합 | 1개로 합침(E-1) |
| `ArchitectureMapTest`(+`ArchitectureMapExporter`·`ModuleGraphExporter`) | `src/test/.../architecture/` | `flows.yml`의 `code:` 참조 존재·enum 집합, 맵 HTML 생성(`ApiApplication` 참조) | 합병 후 trading 클래스를 `flows.yml`이 이미 참조하는지(테스트 classpath에 T main 포함이라 현재도 가능) — 앱 클래스 참조만 바꾸면 됨 |
| `OpenApiSchemaNameCollisionTest` ×2 | R·T 각각 | web DTO + contract record의 simple name 충돌 시 구조(컴포넌트)가 같아야 함 | 합쳐서 R+T DTO 전체를 스캔하게 하면 `MarketSessionResponse`(`S/contract` vs `R/market/.../dto`)와 `CycleHistoryResponse`(T 내부 2벌) 구조 동일 여부가 새로 검증됨(현재는 각자 안에서만) — 실패하면 DTO 정리 필요(G-3) |
| `CaddyRoutingTest` | `src/test/.../architecture/` | Caddy `@trading`·`@scheduler` regex가 컨트롤러 경로와 맞는지(2026-09-16·17 사고 재발 방지), `inputs.file(caddy)` | 라우팅이 web 하나(+scheduler 수동 트리거)가 되면 `@trading` 분기가 소멸 — 테스트를 `@scheduler`만 남기거나 삭제. Caddy 파일 갱신과 같은 커밋(K절) |
| `EventPublicationRegistryTest` | 〃 | EPR이 `@TransactionalEventListener`를 전역 추적함을 실증(`public.event_publication` 사용) | 유지(EPR 1개). 주석의 "4a에서 이관" 문구는 합병 시 stale |
| `StreamSubscriberStartupTest` | `trading-core/src/test/.../architecture/` | `RedisStreamSubscriber` 하위가 `@PostConstruct`로 구독 시작 금지 | 컨슈머가 사라지면 삭제(남은 스트림 0개일 때) |
| `HexagonalArchitectureTest`(506줄) | 루트 | 레이어 방향, `kernel`·`contract`·`platform`·`matching` 불변식, `@GeneratedValue` 금지 등 | `kernel_must_not_depend...`·`contract_must_not_depend...` 2개는 contract 삭제·kernel 해체와 함께 수정. 나머지는 스캔 루트(`com.kista.api` 전체)가 `tradingcore`까지 넓어져야 한다 — **현재 루트 ArchUnit이 T까지 검사하는 규칙이 있는지는 `feedback_root_archunit_covers_tradingcore`(memory)가 이미 언급** |
| `ExceptionMessageFormatTest` | 루트 | application·domain의 IAE/ISE 리터럴이 "~니다."로 끝남 | T 소스 스캔이 포함되는지 확인(소스 루트 경로 상수) |
| `RepositoryLimitingQueryTest` | 루트 | 숫자 없는 `Top/First`+`Pageable` 금지 | 현재도 T main 리포지토리를 함께 검사(테스트 classpath) — 변경 없음 |
| `ApplicationContextLoadTest`·`SchedulerDisabledContextTest`(루트), `TradingApplicationContextLoadTest`(T) | | 각 앱 컨텍스트 로드, scheduler.enabled=false 시 `AdminSchedulerController` 부재 | 1개 컨텍스트 테스트로 합치고 role 플래그 2가지(web/worker) 조합을 각각 로드하는 테스트로 대체 |
| `SchedulerLockServiceTest/DbTest` | `trading-core/src/test/java/com/kista/shared/platform/scheduling/` | `@ContextConfiguration(classes = TradingApplication.class)` | 앱 클래스 참조 변경 |
| `AdminInternalApiIntegrationTest` | 루트 | 관리자 -> 내부 API 통합 | 단계 2에서 삭제 |

`HexagonalArchitectureTest.setUp`·각 ArchUnit 테스트의 `ClassFileImporter` 스캔 범위·`archunit.properties`(`failOnEmptyShould=true`, R·T 각 1개 — 합병 후 1개)도 합병 영향권이다.

## K. 배포 · 운영

### K-1. 현재 3 role 정의 (`deploy/server/docker-compose.yml`)

| 항목 | kista-api | kista-scheduler | kista-trading |
|---|---|---|---|
| 이미지 | `${KISTA_API_IMAGE}` | `${KISTA_SCHEDULER_IMAGE}` | `${KISTA_TRADING_IMAGE}` — **전부 같은 GHCR 레포의 SHA 태그**(`server-deploy.yml:147-151` `ghcr.io/<repo>:<sha>`), role별로 다른 SHA를 줄 수 있을 뿐 이미지 빌드는 1개 |
| 실행 jar | `app.jar`(기본) | `app.jar` | `APP_JAR=trading-core.jar` |
| `container_name` | 없음(blue/green이 옆에 새 컨테이너) | `kista-scheduler` | `kista-trading` |
| 핵심 env | `SCHEDULER_ENABLED=false`, `INTERNAL_API_BASE_URL=http://kista-trading:8080` | `SCHEDULER_ENABLED=true`, `...REPUBLISH...=true`, `INTERNAL_API_BASE_URL` 동일 | `SERVER_PORT=8080`, `SCHEDULER_ENABLED=true`, `...REPUBLISH...=true` |
| healthcheck(공통 anchor) | `wget http://localhost:8080/actuator/health/liveness`, interval 30s, timeout 10s, start_period 180s, retries 3 | 동일 | 동일 |
| `stop_grace_period` | 35s | 200s | 200s |
| `mem_limit` / `-Xmx` | 2048m / 1280m | 1536m / 896m | 2816m / 2048m |
| 네트워크 | `shared_net`(Caddy), `data_net`(postgres·redis) | 동일 | 동일 |
| 포트 | `expose: 8080` | 8080 | 8080 |
| 교체 방식 | blue/green(`deploy/server/bluegreen`에 `kista-api`만) | stop-first | stop-first, **단일 인스턴스 불변식** |
| 헬스 게이트 | `deploy/server/readiness`: 3 role 모두 `http://localhost:8080/actuator/health/readiness` | | |
| 적용 순서 | `deploy/server/roles`: `kista-trading` -> `kista-api` -> `kista-scheduler` (이유: kista-api가 trading 내부 API 호출, `user_notify_profile` 발행 순서) | | |

`Dockerfile`은 1개(루트): 빌더 단계에서 `bootJar :trading-core:bootJar`, 런타임에 `app.jar`·`trading-core.jar` 둘 다 복사(`Dockerfile:18-35`), `ENTRYPOINT java $JAVA_OPTS -jar ${APP_JAR:-app.jar}`. 로컬 `docker-compose.yml`(루트)은 postgres·prometheus·grafana·redis만 있고 앱 서비스 없음.

### K-2. Caddy (`deploy/server/caddy/kista-api.caddy`)

| matcher | upstream | 규칙 |
|---|---|---|
| `@scheduler` `^/api/admin/scheduler/.+$` | `kista-scheduler:8080` | 스케줄러 수동 트리거 전부(`AdminSchedulerController`가 scheduler role에만 있음). `lb_try_duration 120s`, health_uri liveness |
| `@trading` `^/api/(trading-cycles(/.*)?|accounts(/.*)?|backtest|crypto-market-indicators|daily-trades|orders(/.*)?|stats/(summary|equity-curve|cycles)|internal/fida-orders)$` | `kista-trading:8080` | T 공개 경로 + 외부 FIDA 경로 |
| 기본 `handle` | `kista-api:8080` | `transport http { keepalive off }`(blue/green 교체 중 502 방지) |

### K-3. 이미지 1개 · role 2개(web/worker)가 될 때 바뀌어야 할 파일

| 파일 | 변경 |
|---|---|
| `Dockerfile` | jar 1개. `COPY app.jar` 1줄, `APP_JAR` 분기 삭제(또는 유지 불필요), `:trading-core:bootJar` 삭제 |
| `deploy/server/docker-compose.yml` | 서비스 3 -> 2(`kista-web`, `kista-worker`), 변수 `KISTA_WEB_IMAGE`/`KISTA_WORKER_IMAGE`. worker: `container_name`, `stop_grace_period >= 200s`, env `...REPUBLISH...=true`, role 플래그(예: `KISTA_ROLE_WORKER=true`), 단일 인스턴스. web: blue/green, 35s. `INTERNAL_API_BASE_URL`·`APP_JAR`·`SERVER_PORT` 삭제. **JAVA_OPTS·mem_limit 재산정**(합 6400m 호스트 11.6GiB 제약, 주석 `mem_limit` 블록) |
| `deploy/server/roles` | 순서 재정의(예: worker -> web, 또는 web -> worker; 사용자 복제본이 사라지면 순서 제약 소멸) |
| `deploy/server/readiness`, `deploy/server/bluegreen` | role 이름 교체. `bluegreen`은 web만 |
| `deploy/server/caddy/kista-api.caddy` | `@trading` 분기 삭제(모두 web). `@scheduler`는 worker 유지(`AdminSchedulerController`가 worker에서만 뜨는 한). **`/api/admin/scheduler/**`는 worker, `internal/fida-orders`는 web**. `CaddyRoutingTest`를 같은 커밋에서 갱신 |
| `deploy/hooks/pre-apply.sh` | role->schema 매핑(`:11-14` `kista-trading=trading`, `kista-scheduler=public`)을 worker->`public` 1개로 |
| `deploy/hooks/epr-orphans.sh` | 변경 없음(FQCN 기반, 스키마 무관) |
| `.github/scripts/detect-deploy-scope.sh` | 플래그 `api/scheduler/trading` 3개 -> `web/worker` 2개. 경로 규칙: `trading-core/src/main/*`·`src/main/*` 구분이 사라지고(한 jar) 스케줄러 전용 경로(`adapter/in/schedule/*`, `AdminSchedulerController`)만 worker 단독 판정. `db/migration*` 둘 다 양 role |
| `.github/scripts/plan-deploy.sh` | `pick "$sha" "${role#kista-}"`(`:46`)가 role 이름 접두 `kista-`를 떼서 플래그 키로 쓴다 — role명 변경 시 키 매핑 수정 |
| `.github/scripts/check-migrations.sh` | `ALL_DIRS`에 두 디렉토리를 그대로 둘 수 있다. **root expand/contract 검사(`ROOT_DIR`만)가 trading 디렉토리에도 적용되어야 한다**(D3: web·worker 버전 중첩) — 지침서 05 D3 |
| `.github/workflows/server-deploy.yml` | build 잡은 이미 이미지 1개라 변경 없음. `client_payload[roles]`가 새 role 이름을 싣는다. `event_type=deploy-kista-api`, `app=kista-api`는 kista-infra와 계약이라 이름 유지 권고 |
| `.github/tests/*.bats` | `compose-invariants.bats`(role 이름·이미지 변수·roles/readiness 계약·`bluegreen` 내용), `deploy-plan.bats`, `pre-apply.bats`, `check-migrations.bats` 갱신 |
| `deploy/server/README.md`, `deploy/server/schema-reorg/RUNBOOK.md` | role 이름·`SPRING_FLYWAY_BASELINE_ON_MIGRATE` env 계약(D-2의 새 프로퍼티) 갱신 |
| `infra/prometheus/prometheus.yml`·`deploy/grafana/kista-api-dashboard.json` | 대시보드 쿼리 17곳이 `application="kista-api"`로 필터한다(`management.metrics.tags.application=${spring.application.name}`). 합병 후 이름을 유지하면 worker·web이 한 시리즈로 합쳐진다 -> role 태그 추가가 필요하면 `management.metrics.tags.role` 신설 |
| `scripts/dev-seed/seed.sh` | `TRADING=${TRADING:-http://localhost:8081}`(`:14`) -> 8080 |
| docs | `docs/agents/{docker-infra,commands,local-dev,architecture,constraints,workflow,workflow-batch,flyway}.md`, `docs/architecture-map.md`, `CLAUDE.md` 상단, `README.md`, 모듈 `CLAUDE.md`, `.github/CLAUDE.md`(8081·3 role·EPR 2테이블 서술). kista-infra 레포의 role 정의·state 스키마(`state/kista-api.yml`의 `kista-*` role 키)·`Reconcile App` 워크플로는 **이 레포 밖** — 지침서 05 단계 4가 협업 항목으로 지정 |

- 단계 1(내부 HTTP 유지) 동안 운영은 기존 3 role 그대로 돌 수 있다: 이 단계의 jar는 여전히 `app.jar`·`trading-core.jar` 2개가 빌드되어야 하므로 `bootJar :trading-core:bootJar` 유지(J-1의 `:trading-core:bootJar` 비활성은 단계 4).
- `INTERNAL_API_BASE_URL`: 단계 1에서 각 role이 자기 자신을 가리키게 하면 kista-trading(구 jar)과 신 jar가 섞이는 기간이 생긴다 — 같은 SHA 쌍으로 교체해야 한다.
- `kista-api-net` 네트워크(로컬 compose `:58`)·로컬 포트 8081 문서는 단계 1 후 제거.

## L. kista-ui 연계

(조사 대상 `/Users/phs/workspace/kista/kista-ui`, 읽기만)

### L-1. `TRADING_API_BASE_URL` · `API_BASE_URL`을 쓰는 곳 (kista-ui, 파일 목록)

- 정의·해석: `shared/lib/env.ts`(`getApiBaseUrl`, `getApiBaseUrlOrNull`, `getTradingApiBaseUrl`).
- 선택 로직 2곳(합병 시 단일화 대상): `shared/lib/proxy/createProxyRoute.ts:35`(`target === 'trading'`이면 trading base), `shared/lib/api-client/index.ts:5-13`(`TRADING_PREFIXES` + `/api/stats` 분기, Server Component 직결 fetch).
- `getApiBaseUrl*` 직접 호출(전부 root 대상, 변경 불필요): `proxy.ts`, `app/auth/callback/route.ts`, `app/api/auth/{status-stream,logout,reapply-done,me}/route.ts`, `app/api/trades/stream/route.ts`, `app/api/fcm/tokens/route.ts`, `app/api/fcm/tokens/[token]/route.ts`, `shared/lib/auth/refresh.ts`, `entities/meta/api/index.ts`, `entities/market/api/index.ts`.
- 설정·배포: `deploy/server/docker-compose.yml:13`(`TRADING_API_BASE_URL: ${TRADING_API_BASE_URL}`), `.env.example:3`, `deploy/server/required-env`(**`API_BASE_URL`만 필수, `TRADING_API_BASE_URL`은 목록에 없음**), `.env.production.public`(`NEXT_PUBLIC_API_BASE_URL`만), `Dockerfile:18,27`(`NEXT_PUBLIC_API_BASE_URL`), `.github/workflows/{ci,server-deploy}.yml`, `playwright.config.ts:7-8,35`(`E2E_TRADING_API_BASE` 기본 `http://localhost:8081`), `e2e/global.setup.ts`, `scripts/visual-diff/run.mjs`, `.github/tests/contract.bats`, 문서 `README.md`·`docs/agents/{app,shared,deployment}.md`.
- 테스트: `proxy.test.ts`, `app/auth/callback/route.test.ts`, `app/api/stats/[[...path]]/route.test.ts`, `shared/lib/proxy/createProxyRoute.test.ts`, `shared/lib/api-client/index.test.ts`, `shared/lib/env.test.ts`, `shared/lib/auth/refresh.test.ts`, `entities/meta/api/index.test.ts`, `entities/market/api/index.test.ts`.

### L-2. 프록시 라우트 -> 호스트 표

| UI 라우트 (`app/api/...`) | target | 실제 호스트(현재) | 경로 prefix 근거 |
|---|---|---|---|
| `accounts/[[...path]]` | `'trading'` | trading-core | T `AccountController` 등 |
| `trading-cycles/[[...path]]` | `'trading'` | trading-core | |
| `backtest` | `'trading'` | trading-core | |
| `daily-trades` | `'trading'` | trading-core | |
| `orders/[[...path]]` | `'trading'` | trading-core | |
| `crypto-market-indicators` | `'trading'`(공개) | trading-core | T `TradingSecurityPolicy` permitAll |
| `stats/[[...path]]` | 함수: `segments[0]==='housing-benchmark' ? 'api' : 'trading'` | `housing-benchmark`=root, 그 외(summary·equity-curve·cycles)=trading-core | R·T가 `/api/stats` prefix 공유(Caddy 정규식도 동일 분기) |
| `admin/[[...path]]` | (기본) | root | `/api/admin/**` |
| `finance/[[...path]]` | root | root | |
| `market/[[...path]]` | root | root | |
| `settings/[[...path]]` | root | root | |
| `runtime-config`, `client-errors` | root | root | |
| `auth/*`, `fcm/*`, `trades/stream`, `auth/status-stream` | (직접 fetch) `getApiBaseUrl` | root | |
| `health` | 로컬 | UI 자체 | |

합병 후: `createProxyRoute`의 `target` 옵션과 `getTradingApiBaseUrl`을 삭제하고 `api-client`의 `TRADING_PREFIXES` 분기를 제거하면 한 호스트가 된다. 이 변경은 **kista-ui 세션의 일**(`feedback_ui_work_to_ui_session`)이며 `docs/superpowers/fable-review/guides/02-data-layer.md`(kista-ui, 3경로 프록시)와 연동된다. 배포 순서: UI가 `TRADING_API_BASE_URL` 미설정 시 단일 호스트로 가도록 먼저 바꾸고(폴백 `TRADING_API_BASE_URL || API_BASE_URL`), 이후 백엔드 전환, 마지막에 env 삭제하는 3단계로 하면 UI·API 배포 순서 의존이 없다.

---

## 요약: 충돌 목록 (우선순위)

| # | 절 | 충돌 | 심각도 |
|---|---|---|---|
| 1 | G-1 | `@RestControllerAdvice` 2개의 catch-all(T는 `@Order(HIGHEST)`)이 R 고유 매핑을 가린다 — 가계부 409·Cooldown 429가 500·GENERIC으로 변질 | 조용한 동작 회귀 |
| 2 | B/J-1 | 클래스패스에 `application.yml`·`-local`·`-prod`가 2벌 — 한쪽만 로드, 다른 쪽 키 소실 | 조용한 설정 소실 |
| 3 | C1/C2 | search_path 1개 + `scheduler_locks`·`event_publication`이 두 스키마에 중복. `SchedulerLockService`가 unqualified SQL | 락 의미 변경·데이터 이행 |
| 4 | D-2 | Flyway 이력 2벌: 자동구성이 꺼지므로 `Flyway` 빈 2개 + 초기화 직접 구성, 슬라이스 테스트에도 import | 구성 신설 |
| 5 | E-3 | 같은 이벤트를 양쪽이 듣는 3종(`AppErrorRaisedEvent` 이중 저장, `UserDeletedEvent`·`UserNotifyProfileChangedEvent` Redis 왕복+직접 이중 실행) + 새로 켜지는 `SchedulerLifecycleEvent` 알림 | 이중 실행 |
| 6 | A3/A4/A5/B2 | 빈 이름·타입 충돌: `ClockConfig`·`clock`, `fearGreedRestClient`(타임아웃 상이), `TokenBlacklistPort` 후보 2개, `UserEventResubmitScheduler` 이름 | 기동 실패(명시적) |
| 7 | E-1 | Modulith ID `notify`·`web` 중복, 테스트 ID 집합 | 기동 실패(명시적) |

건수: 빈·이름 5 + `@Bean` 이름 2 + 이벤트 3 + advice 1 + 설정 키 충돌 다수(B-1 표 약 20행) + 스키마 중복 테이블 2 + Modulith ID 2 + 리소스 파일 3종.
