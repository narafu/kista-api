# 18. 횡단 관심사 측정 (보안·설정·이벤트·오류·미사용 코드)

조사 기준: 2026-11-13, `git rev-parse --short HEAD` = 1db0d8db1 (main). 읽기 전용 측정(소스 수정 없음, 빌드·테스트 미실행). 경로 약어: `trading-core/src/main/java/com/kista/tradingcore/` = `TC/`, `shared/src/main/java/com/kista/shared/` = `SH/`, `src/main/java/com/kista/api/` = `API/`.
05(`guides/05-modules-and-processes.md`)가 이미 처방한 것: 예외 처리기 통합(P1), 중복 빈 정리(P2, `RedisBlacklistAdapter`·`ClockConfig` 한 벌), 사용자 이벤트 경로 단순화(P5), 내부 HTTP 31개 핸들러·`shared.contract`·스트림 3종 삭제(X단계), EPR 한 테이블(M7), `scheduler_locks` 한 테이블(M6). 그 항목은 아래에서 "통합(05)으로 사라짐"으로 표시하고 다시 세지 않는다.

## A. 보안·JWT·인증 흐름

### A-1. 관련 클래스 전수

| 클래스 | file | 줄 | 비고 |
|---|---|---|---|
| `SecurityConfig` | `SH/platform/security/SecurityConfig.java` | 99 | **두 프로세스가 같은 클래스 한 벌을 쓴다**(`PlatformAutoConfiguration` `@Import`, `SH/platform/PlatformAutoConfiguration.java:32-33`). 보안 설정이 프로세스마다 복제돼 있지 않다 |
| `JwtAuthFilter` | `SH/platform/security/JwtAuthFilter.java` | 122 | 한 벌 공유 |
| `InternalTokenAuthFilter` | `SH/platform/security/InternalTokenAuthFilter.java` | 54 | 한 벌 공유 |
| `JwtDecoderConfig` | `SH/platform/security/JwtDecoderConfig.java` | 33 | 한 벌 공유(ES256 공개키 검증) |
| `SecurityRoutePolicy`(확장점) | `SH/platform/security/SecurityRoutePolicy.java` | 23 | 인터페이스 |
| `TokenBlacklistPort` / `RedisTokenBlacklistReader` | `SH/platform/security/` | 12 / 38 | 읽기 3종 공유 베이스 |
| `RootSecurityPolicy` | `API/web/RootSecurityPolicy.java` | 44 | root 전용 라우트 9규칙 |
| `TradingSecurityPolicy` | `TC/web/TradingSecurityPolicy.java` | 31 | trading 전용 1규칙 |
| `RedisBlacklistAdapter`(root) | `API/user/adapter/out/redis/RedisBlacklistAdapter.java` | 34 | 읽기 베이스 + 쓰기 3종 |
| `RedisBlacklistAdapter`(trading) | `TC/trading/adapter/out/security/RedisBlacklistAdapter.java` | 14 | 읽기 베이스만 상속(같은 단순명 클래스) → 통합(05) P2로 한 벌 |
| `AuthController` | `API/user/adapter/in/web/AuthController.java` | 141 | 카카오 로그인·갱신·로그아웃·me·탈퇴·재신청 |
| `DevAuthController` | `API/user/adapter/in/web/DevAuthController.java` | 118 | `@Profile("local")`(:34), dev-token 4종 |
| `JwtIssuerService` | `API/user/adapter/in/web/security/JwtIssuerService.java` | 66 | ES256 발급 |
| `RefreshTokenCookieHelper` | 같은 폴더 | 52 | |
| `KakaoOAuthAdapter` | `API/user/adapter/out/kakao/KakaoOAuthAdapter.java` | 80 | |
| `TokenService` | `API/user/application/service/TokenService.java` | 153 | RTR + 60초 grace |
| `UserService`(login/register/승격) | `API/user/application/service/UserService.java` | 207 | |
| `AdminSeedPromoter` / `AdminBootstrapProperties` | `API/user/application/service/`, `API/user/config/` | (파일 줄 수 미집계) / 18 | ADMIN seed 승격 |
| `RefreshTokenPort`·영속 3종 | `API/user/adapter/out/persistence/auth/` | 20 / 55+52+68 | |
| `RefreshTokenCleanupScheduler` | `API/user/adapter/in/schedule/` | 43 | |
| `InternalApiProperties` | `SH/platform/internalapi/InternalApiProperties.java` | 14 | 내부 토큰 헤더·`@NotBlank` |
| `GlobalExceptionHandler` | `API/web/GlobalExceptionHandler.java` | 128 | (D절 참조) |

보안 전용 클래스 합계는 `SH` 381줄 + root user 인증 클래스 약 1,050줄(위 표 합) + 정책 75줄. 필터·설정은 한 벌이라 "두 프로세스에 중복된 설정·필터 코드"는 **0줄**(줄 단위 비교 대상이 없다). 두 프로세스 사이에 갈라진 것은 (a) `SecurityRoutePolicy` 구현 2개(내용이 다르다), (b) `RedisBlacklistAdapter` 2벌(34줄 대 14줄, 위 표), (c) `JwtDecoder` 빈 소비처(root `AuthController`가 logout용으로 직접 주입 — `AuthController.java:41`)뿐이다.

### A-2. 흐름 4개 추적

**① 카카오 로그인 → JWT 발급** (root 전용)
1. `POST /api/auth/kakao/callback` — `AuthController.java:49-60`. 라우트 규칙 `/api/auth/**` permitAll(`RootSecurityPolicy.java:35`).
2. `userUseCase.login(code, redirectUri)` — `UserService.java:68`. 카카오 토큰 교환·사용자 조회 `KakaoOAuthAdapter.java:21-62`(타임아웃 설정은 `kakaoRestClient` 빈).
3. 신규면 `register`(`UserService.java:102-119`, ADMIN seed면 ADMIN·ACTIVE), 기존이고 seed인데 USER면 `adminSeedPromoter.promote`(`UserService.java:89-91`, `AdminSeedPromoter.java:29`).
4. RT 발급 `tokenUseCase.issueRefreshToken`(`AuthController.java:55` → `TokenService.java:42-46`: 같은 User-Agent의 기존 RT 삭제 후 새 RT, 해시 저장) → HttpOnly 쿠키(`RefreshTokenCookieHelper.java:23-31`).
5. AT 발급 `JwtIssuerService.issue`(`AuthController.java:57` → `JwtIssuerService.java:36-47`: jti·sub·role·iat·exp, `AT_TTL`=1일 `TokenConstants.java:6`). 서명 키는 **발급할 때마다** `ECKey.parse`로 다시 파싱(`:55-65`).

**② 인증된 요청 처리**
- root·trading-core 동일: `SecurityConfig.filterChain`(`SH/.../SecurityConfig.java:40-63`) → `JwtAuthFilter.doFilterInternal`(`JwtAuthFilter.java:32-40`): Bearer 추출(`:115`) → `jwtDecoder.decode`(`:47`) → sub=UUID(`:48`) → jti 블랙리스트·userId 블랙리스트(`:52`, Redis `blacklist:*`) → `roleChangedAt` 이전 발급 AT 차단(`:60-66`) → `role` 클레임을 `ROLE_*`로(`:69-72`) → principal=UUID 설정(`:73`). 무효 토큰이어도 공개 경로(`anonymousFallbackMatchers`)면 익명 통과(`:99-107`).
- 컨트롤러는 `@AuthenticationPrincipal UUID userId`로 받는다.
- 차이점: root는 `RedisBlacklistAdapter`(쓰기 포함)가, trading-core는 읽기 전용 `RedisBlacklistAdapter`가 `TokenBlacklistPort` 빈이다. 두 프로세스가 같은 Redis 키를 읽는다. JWT 검증은 서명 키(공개키)만 있으면 되므로 DB 조회가 없다.
- **사용자 상태(PENDING/REJECTED) 검사가 요청 경로에 없다**: `JwtAuthFilter`는 status를 보지 않고, `UserStatus`를 import한 trading-core 웹 클래스는 0개(`grep UserStatus TC/` → `UserNotifyProfileEntity`뿐, 영속 엔티티), root는 `AdminUserController`·`SseEmitterRegistry` 등 관리·알림 경로뿐. REJECTED는 거절 시 `blacklistPort.add`(`UserService.java:150`)로 AT가 차단되지만, **PENDING(가입 직후 승인 전) 사용자는 로그인 시 AT를 받고(`AuthController.java:57`) 그 AT로 `/api/accounts`·`/api/trading-cycles/**` 등 인증 경로 전부를 호출할 수 있다**. 막는 것은 UI뿐으로 보인다. 미확인: kista-ui 프록시가 서버 측에서 PENDING을 막는지(UI는 이 조사에서 열지 않음). 통합(05)과 무관하게 남는다.

**③ 토큰 갱신·로그아웃** (root 전용)
- 갱신: `POST /api/auth/refresh`(`AuthController.java:68-80`) → 쿠키 추출(`RefreshTokenCookieHelper.java:45-51`) → `TokenService.refresh`(`TokenService.java:59-101`): 해시 조회 → 만료 확인(`:71`) → 이미 회전됐으면 grace 60초(`:77-85`) → 미회전이면 `markRotated` 원자 UPDATE(`:89`), 경쟁 패자는 grace 판정(`:90-98`) → 새 RT·새 AT. grace 초과 재제시는 전체 세션 폐기(`:142-146`), `noRollbackFor`로 폐기 커밋 보장(`:60`). RT 수명 `Duration.ofDays(5)`(`TokenService.java:31`)와 쿠키 `432_000L`(`RefreshTokenCookieHelper.java:14`)가 **같은 값의 이중 선언**이다.
- 로그아웃: `POST /api/auth/logout`(`AuthController.java:85-107`): 컨트롤러가 `JwtDecoder`로 AT를 직접 디코딩해 jti·exp 추출(`:96-98`) → `TokenService.logout`(`:103-116`)이 RT 삭제 + `blacklistPort.addJti`(`:112`). RT가 없으면(`rawRt == null`) AT jti 차단도 하지 않는다(`AuthController.java:103`). RT 쿠키 삭제(`:106`). 만료 RT·회전 RT 정리 스케줄러 `RefreshTokenCleanupScheduler.java:25,35`.
- 역할 변경: `UserService.java:198` `markRoleChanged`가 `blacklist:rolechange:{userId}`를 쓰고 `JwtAuthFilter.java:60-66`이 읽는다.

**④ 내부 API 호출 (root → trading-core)** (통합(05)으로 사라짐, 단 `/api/internal/fida-orders`는 유지)
1. 발신: `InternalApiClientConfig`가 `X-Internal-Token` 헤더를 기본 헤더로 심은 RestClient 생성(`SH/platform/internalapi/InternalApiClientConfig.java:55`). 호출처는 root 어댑터 약 30개 URI(`API/admin/adapter/out/internal/*`, `API/market/adapter/out/internal/*`, `API/benchmark/adapter/out/internal/*`).
2. 수신: `SecurityConfig.java:49` `/api/internal/**` → `hasRole("INTERNAL")`. `InternalTokenAuthFilter.java:33-36`이 `/api/internal/` 접두에만 적용, `:43` 토큰이 비었거나 다르면 401, 일치하면 ROLE_INTERNAL 인증(`:48-51`). 비교는 `String.equals`(상수 시간 비교 아님, `:43`). 토큰 `@NotBlank` 기동 검증 `InternalApiProperties.java:11`.
3. 필터 순서 주석과 실제가 다르다: 주석은 "InternalTokenAuthFilter는 JWT 필터보다 먼저"(`SecurityConfig.java:55`)이지만 `addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter)` 뒤에 `addFilterBefore(jwtFilter, InternalTokenAuthFilter)`(`:56,58`)라 **JWT 필터가 먼저** 돈다. `/api/internal/**` 요청에 Bearer가 있으면 JWT 검증이 먼저 일어난다(해로운 동작은 확인 못 함, 주석 오기).
4. root 쪽 `/api/internal/**` 컨트롤러는 0개(`grep '/api/internal' API/` → 어댑터 호출 문자열뿐). 즉 내부 HTTP는 **단방향**(root → trading-core). trading-core → root는 Redis 스트림 3종.

### A-3. 라우트 규칙 목록 (매처 순서 그대로)

`SecurityConfig.java:46-53` 공통 → 셸 정책(순서: `policies.orderedStream()`, 빈 순서 미지정이라 프로세스당 정책이 1개뿐이어서 무관) → 마지막.

| 순 | 매처 | 규칙 | 실제 경로 컨트롤러 | file:line |
|---|---|---|---|---|
| 1 | `/error` | permitAll | Boot 기본 | `SecurityConfig.java:46` |
| 2 | `/actuator/**` | permitAll | actuator(management 별도 포트, 두 프로세스 모두 yml에 `management` 있음) | `:47` (주석에 "Render에서 외부 미노출" — 현재 배포는 OCI, 주석 낡음) |
| 3 | `/swagger-ui/**`, `/api-docs/**`, `/swagger-ui.html` | permitAll | springdoc | `:48` |
| 4 | `/api/internal/**` | hasRole INTERNAL | trading-core 컨트롤러 13개(`@RequestMapping("/api/internal…")`) | `:49` |
| 5 | `/api/admin/**` | hasRole ADMIN | root admin·finance·web 컨트롤러 12개 | `:50` |
| root 정책 6 | `/telegram/webhook` | permitAll | `TelegramWebhookController` `API/admin/adapter/in/telegram/TelegramWebhookController.java:23` | `RootSecurityPolicy.java:28` |
| 7 | POST `/api/client-errors` | permitAll(+익명 폴백) | `ClientErrorLogController` | `:29` |
| 8 | `/api/auth/status-stream` | authenticated | `StatusStreamController.java:29` | `:30` |
| 9 | `/api/trades/stream` | authenticated | `TradeStreamController.java:26` | `:31` |
| 10 | DELETE `/api/auth/me` | authenticated | `AuthController.java:135` | `:32` |
| 11 | GET `/api/auth/me` | authenticated | `AuthController.java:112` | `:33` |
| 12 | POST `/api/auth/approval-requests` | authenticated | `AuthController.java:126` | `:34` |
| 13 | `/api/auth/**` | permitAll | login·refresh·logout + dev-* 4개(local만) | `:35` |
| 14 | GET `/api/market/**`, `/api/meta`, `/api/runtime-config` | permitAll(+익명 폴백) | `FearGreedController`·`MarketHolidayController`·`MetaController`·`RuntimeConfigController` | `:36` |
| trading 정책 | GET `/api/crypto-market-indicators` | permitAll(+익명 폴백) | `TC/stats/adapter/in/web/CryptoMarketIndicatorController.java:30` | `TradingSecurityPolicy.java:24` |
| 끝 | anyRequest | authenticated | | `SecurityConfig.java:53` |

**컨트롤러 경로가 없는 매처: 0개**(위 모두 컨트롤러 확인). 단 6·8·10·11·12는 `/api/auth/**` permitAll(13)의 예외로 앞에 둔 것이라 순서 의존이 강하다(순서를 바꾸면 `/api/auth/me`가 permitAll이 돼 주석대로 `getById(null)` 500). 이 순서 의존은 통합(05) 후 root 정책이 worker/web 역할 라우트까지 합쳐도 그대로 남는다.

### A-4. 권한 검사 이중 선언

- 메서드 수준 보안(`@PreAuthorize`·`@Secured`·`@EnableMethodSecurity`) **0건**(전체 grep). 역할 검사는 URL 규칙 2개(`hasRole INTERNAL`, `hasRole ADMIN`)뿐이다. 이중 선언 없음.
- 단 역할 검사가 **URL 접두사에만 의존**한다: `/api/admin/` 밖에 둔 관리자 기능이 생기면 `anyRequest().authenticated()`만 걸려 일반 사용자에게 열린다. 현재는 `API/admin/adapter/in/web`의 컨트롤러 중 `ClientErrorLogController`(`/api/client-errors`)·`RuntimeConfigController`(`/api/runtime-config`)가 admin 패키지에 있지만 의도된 공개 경로다. `AdminSchedulerController`는 `API/web/`에 있다(`@RequestMapping("/api/admin/scheduler")`).
- `AdminPingController.java:19-20`는 `hasRole("ADMIN")` 가드 동작 확인용 엔드포인트(테스트 전용 성격).

### A-5. 소유권 검증을 거치지 않는 계좌·전략 id 수신 엔드포인트

방법: 계좌·전략·주문·사이클 id를 `@PathVariable`/`@RequestParam`으로 받는 컨트롤러 핸들러(admin·internal 제외) 전수 → 호출하는 서비스의 `OwnedStrategy.load`/`requireOwnedAccount`/`verifyOwnedBy` 경유 여부를 서비스 파일에서 확인. 검증 파일: `StrategyService.java:72,91,113,156,218,225,236`, `StrategyUpdateWriter.java:57`, `VrReconfigureService.java:65`, `CapitalLedgerService.java:72,113`, `CapitalWithdrawalService.java:29`, `DcaConfigService.java:36`, `DcaExecutionQueryService.java:39`, `ManualTradingService.java:53`, `TradingPreviewService.java:74,99`, `StrategyCreationService.java:74`, `OrderCancelService.java:52,166`, `StrategyHistoryQueryService.java:57,100,116`, `AccountLedgerTotalsService.java:37`, `AccountStatisticsService.java:46,54,94`, `CryptoPortfolioService.java:75`, `AccountService.java:120,134,176`.

| 컨트롤러 핸들러 | 소유권 검증 | 결과 |
|---|---|---|
| `TradingCycleController` 18개(`/api/trading-cycles/{id}/**`, `/api/accounts/{accountId}/trading-cycles*`) | 모두 `userId`를 유스케이스에 전달, 서비스가 `OwnedStrategy.load` 또는 `requireOwnedAccount` | 통과 |
| `OrderCancelController` DELETE `/api/orders/{orderId}` | `OrderCancelService.java:166` `requireOwnedAccount(order.accountId(), …)` | 통과 |
| `AccountController` PUT `/{id}`·`/{id}/credentials`·DELETE `/{id}` | `AccountService.java:120,134,176` | 통과 |
| `AccountLedgerTotalsController`, `StatisticsController` 3개, `DashboardController` cycle-history, `CryptoPortfolioController` 2개 | 위 서비스들 | 통과 |
| `TradingStatsController` `/cycles?accountId=` | 본인 계좌로 한정해 필터(`TradingStatsService.java:201-204`), 타 사용자 id는 빈 결과 | 통과(다른 방식: 검증 대신 필터) |
| `BenchmarkController` `?strategyId=`(root) | root는 검증 없이 trading-core 내부 API로 위임(`InvestmentPointsHttpAdapter.java:27-33`), 수신 측 `InvestmentPointsQueryService`가 `requireOwnedAccount` | 통과 |
| admin `AdminAccountController`·`AdminTradeController` 등 `{accountId}`/`{strategyId}` | 의도적으로 검증 없음(ADMIN 전용 URL 규칙으로 보호) | 설계 |
| internal 13개 컨트롤러 | 내부 토큰만(호출자 신뢰) | 설계 |

**소유권 검증을 빠뜨린 사용자용 엔드포인트: 0개**(확인한 범위). 일관성 문제: 소유권 판정이 세 가지 방식(`OwnedStrategy.load` / `requireOwnedAccount` / 결과 필터)이고, `InvestmentPointsQueryService`는 모듈 경계 때문에 `OwnedStrategy`를 못 써서 별도 구현이다(`architecture.md` 명시). 미확인: finance 컨트롤러 `{id}` 수정·삭제의 소유 검증은 `FinanceAccountController.java:66,80`이 `userId`를 서비스에 넘기는 것까지만 확인(서비스 내부는 이번 범위 밖).

### 숫자 요약
- 보안 필터·설정은 `:shared` 한 벌(381줄)이라 프로세스 간 중복 설정은 0줄. 갈라진 것은 정책 구현 2개와 `RedisBlacklistAdapter` 2벌(34·14줄).
- 라우트 매처 14개(공통 5 + root 9 + trading 1 중 일부 묶음), 컨트롤러 없는 매처 0, 메서드 수준 권한 0건.
- 사용자용 id 수신 핸들러 약 30개 중 소유권 미검증 0개.

### 통합(05) 뒤에도 남는 문제 목록
1. **PENDING 사용자가 AT로 인증 경로 전부 호출 가능**(상태 검사가 필터·서비스 어디에도 없음) — `SH/platform/security/JwtAuthFilter.java:45-84`, 발급 `API/user/adapter/in/web/AuthController.java:55-57`. 미확인: UI 프록시 차단 여부.
2. `/api/auth/**` permitAll 뒤에 개별 authenticated를 앞세운 순서 의존 규칙 5개 — `API/web/RootSecurityPolicy.java:28-36`.
3. 필터 순서 주석과 실제가 반대 — `SH/platform/security/SecurityConfig.java:55-58`.
4. 내부 토큰 비교가 상수 시간이 아님(통합 후 fida 호출용 `/api/internal/fida-orders`만 남으므로 범위는 좁아짐) — `SH/platform/security/InternalTokenAuthFilter.java:43`.
5. RT 수명 5일이 두 곳에 숫자로 중복 — `API/user/application/service/TokenService.java:31`, `API/user/adapter/in/web/security/RefreshTokenCookieHelper.java:14`.
6. AT 발급마다 EC 키 재파싱 — `API/user/adapter/in/web/security/JwtIssuerService.java:55-65`(성능 미측정, 로그인·갱신 빈도 한정).
7. 로그아웃이 RT가 없으면 AT jti를 차단하지 않음 — `API/user/adapter/in/web/AuthController.java:103-105`.

## B. 설정 파일의 키

### B-1. yml 파일 전수

| 파일 | 줄 | 최상위 키 | git 추적 |
|---|---|---|---|
| `src/main/resources/application.yml` (root) | 133 | spring, management, scheduler, springdoc, server, telegram, crypto, app, jwt, kakao, admin, cors, internal, alpaca, kbland, firebase, logging | 추적 |
| `src/main/resources/application-prod.yml` | 43 | spring, scheduler, management, logging | 추적 |
| `src/main/resources/application-local.yml` | 58 | spring, telegram, scheduler, management, crypto, jwt, kakao, cors, admin, internal, firebase, alpaca, logging | **gitignored**(`.gitignore:21`) |
| `trading-core/src/main/resources/application.yml` | 118 | spring, springdoc, server, management, internal, cors, jwt, crypto, kis, toss, meritz, upbit, telegram, heartbeat, alpaca, cmc | 추적 |
| `trading-core/src/main/resources/application-prod.yml` | 37 | spring, cors, management, logging | 추적 |
| `trading-core/src/main/resources/application-local.yml` | 44 | spring, server, internal, jwt, crypto, telegram, alpaca, logging, toss, cmc | gitignored |
| `shared/src/testFixtures/resources/application-test.yml` | 42 | spring, telegram, crypto, jwt, kakao, cors, internal, toss | 추적(테스트 공용) |
| `shared/src/main/resources/` | — | `META-INF`만 있고 yml 없음 | — |
| `src/test/resources/`·`trading-core/src/test/resources/` | — | yml 없음(`architecture/`, `archunit.properties`, `backtest-golden/`) | — |

`bin/`(IDE 산출물, `.gitignore:49`)에도 옛 사본이 있으나 추적되지 않아 대상 아님. 합계 추적 yml 5개 373줄 + 로컬 2개 102줄.

### B-2. 커스텀 키 바인딩 (Spring·라이브러리 표준이 아닌 것)

바인딩 방식: `@ConfigurationProperties` 10개 record(아래 B-4), `@Value` 20곳, `@ConditionalOnProperty` 17곳(`scheduler.enabled` 16 + `internal.api.base-url` 1). yml 커스텀 키 전수를 위 목록과 대조한 결과:

| 키 | 바인딩 | 비고 |
|---|---|---|
| `scheduler.enabled` | `@ConditionalOnProperty` 16곳(root 8, trading 8) | trading-core yml에는 이 키가 **없다**(`matchIfMissing=true`, compose env `SCHEDULER_ENABLED`가 relaxed binding으로 채움). root yml은 base `${SCHEDULER_ENABLED:true}`, prod `false`, local `false` 3곳 선언. 통합(05) P4가 `kista.role`로 대체 → 사라짐 |
| `telegram.bot-token/chat-id` | `TelegramProperties` | root·trading-core·test 3곳 선언 |
| `crypto.aes-key` | `@Value` 2곳(`AesCryptoService.java:21`, `AccountNoHasher.java:18`) | |
| `jwt.signing-key` | `@Value` 2곳(`JwtDecoderConfig.java:24`, `JwtIssuerService.java:32`) | |
| `cors.allowed-origins` | `@Value` 1곳(`SecurityConfig.java:29`) | root는 기본값 없음, trading-core는 `:http://localhost:3000` 기본값 있음 — 같은 키의 기본값 불일치 |
| `internal.api.token/base-url` | `InternalApiProperties` + `@ConditionalOnProperty`(`InternalApiClientConfig.java:22`) | 통합(05) X8로 사라짐(token은 fida용으로 남을 수 있음) |
| `app.cookie.secure/same-site` | `@Value`(`RefreshTokenCookieHelper.java:16,19`) | 코드 기본값 `true`/`None`이 yml 값과 동일 |
| `kakao.client-id/secret` | `KakaoProperties` | |
| `admin.kakao-ids` | `AdminBootstrapProperties` | |
| `alpaca.api-key/api-secret/data-base-url/base-url` | `AlpacaProperties` **3개**(root `benchmark`, trading `marketcalendar`, trading `stats`) | 같은 prefix를 3개 record가 각자 바인딩. `base-url`은 marketcalendar 것만 읽음(`AlpacaConfig.java:18`). 통합(05) P2로 한 벌 |
| `kbland.base-url` | `KbLandProperties` | |
| `firebase.service-account-json` | `@Value`(`FcmConfig.java:22`) | |
| `kis.base-url/admin-app-key/admin-app-secret` | `@Value`(`KisConfig.java:17`, `KisAdminPriceSource.java:30-31`) | |
| `toss.base-url/admin-client-id/admin-client-secret` | `@Value`(`TossConfig.java:18`, `TossAuthApi.java:32,34`) | `admin-*`는 코드 기본값 없음, yml이 `${…:}`로 빈 기본값 제공 |
| `meritz.base-url`, `upbit.base-url` | `@Value` + **코드 기본값**(`MeritzConfig.java:14`, `UpbitConfig.java:14`) | yml에도 같은 URL 선언 → 이중 선언 2개 |
| `heartbeat.open-url/close-url/crypto-url` | `HeartbeatProperties` | |
| `cmc.api-key` | `CoinMarketCapProperties` | |
| `springdoc.paths-to-exclude` 등 | 라이브러리 표준 | |

**바인딩 0인 커스텀 키: 0개.** (환경변수 `${…}` 31개 전부 yml 키에 매핑됨, `X`는 yml 주석 안의 예시 문자열)

**코드가 읽는데 yml에 없는 키**
| 키 | 위치 | 기본값 |
|---|---|---|
| `binance.base-url` | `BinanceHttpClient.java:43` | 코드 기본값 `https://api.binance.com` — kis·toss·meritz·upbit는 yml에 있는데 바이낸스만 yml에 없다(비일관) |
| `app.trading.parallel-per-account` | `TradingParallelRunner.java:25` | 코드 기본값 2, yml 없음 |
| `trading.shutdown.critical-wait` | `TradingBatchShutdownCoordinator.java:25` | 코드 기본값 150s, yml 없음 |
| `kista.test-db-name` | `application-test.yml:2`(`${kista.test-db-name:kistadb_test}`) | build.gradle이 IT에 주입 |

기본값도 yml도 없는 필수 키(미설정 시 기동 실패 의도): `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`, `AES_ENCRYPTION_KEY`, `JWT_SIGNING_KEY`, root `CORS_ALLOWED_ORIGINS`, trading-core `REDIS_URL`. 모두 compose/.env가 공급(B-5).

### B-3. 중복 선언

**root `application.yml` 대 trading-core `application.yml`**(각 58개 리프 키): 값까지 **같은 키 30개**: `spring.threads.virtual.enabled`, `datasource.*`(url·username·password·driver·hikari 3), `jpa.*`(5), `flyway.enabled/baseline-on-migrate/validate-on-migrate`, `modulith.detection-strategy`, `modulith…schema-initialization.enabled`, `management…readiness.include`, `springdoc.api-docs.path`·`swagger-ui.path`·`operations-sorter`, `telegram.*`, `crypto.aes-key`, `jwt.signing-key`, `internal.api.token`, `alpaca.api-key/api-secret/data-base-url`. 서비스별로 다른 값 11개(앱 이름, pool-name, init-sql, flyway 위치·이력 표·스키마, modulith 스키마, redis url, port, cors 기본값). 통합(05) 후에도 2 role이 같은 jar·같은 yml 하나를 쓰면 이 30줄 중복은 사라진다. 단 스키마 5분리(D2) 때문에 datasource 관련(init-sql·flyway 위치·modulith 스키마) 값은 한 yml에서 role별로 달라져야 하는 항목이 남는다.

**`application-prod.yml` root 대 trading-core**: 15개 키가 값까지 동일(hikari 5, `jpa.show-sql`, `lifecycle.timeout-per-shutdown-phase`, `management.health.probes`·`show-details`, otlp 4(`enabled·url·headers·step`), `logging.level` 2). root에만 있는 것은 `republish-outstanding-events-on-restart: false`·`scheduler.enabled: false`, trading-core에만 `cors.allowed-origins`.

**프로파일 값이 base와 동일해 불필요한 선언**: `hikari.connection-timeout: 20000`·`jpa.show-sql: false`가 root·trading-core 각각 base와 prod에 반복(4줄). 라이브러리 기본값과 같은 선언: `spring.flyway.enabled: true`·`validate-on-migrate: true`·`baseline-on-migrate: false`(Flyway 기본값과 동일), `spring.jpa.show-sql: false`(3곳), root `server.shutdown: graceful`(Boot 4.1.1 `ServerProperties.java:107` 기본값이 `GRACEFUL`임을 sources jar로 확인).

**trading-core에 없는 root 설정**: `server.shutdown`은 기본값이 같아 문제 없음. `spring.task.scheduling.thread-name-prefix`, `management.endpoints.web.exposure.include`, `management.metrics.tags.application`이 trading-core yml에 없다(추측: 메트릭 노출이 root만 의도됐는지 불명. 미확인: `deploy/grafana`가 trading-core 메트릭을 쓰는지).

**로컬 yml 두 파일 중복**(gitignored): `telegram`, `crypto.aes-key`, `jwt.signing-key`, `alpaca`, `internal.api.token`, `datasource` 6개 블록이 root·trading-core local에 같은 값으로 반복. trading-core local이 `internal.api.base-url: http://localhost:8080`을 두는데(`trading-core/.../application-local.yml`), main yml은 "이 프로세스는 내부 API를 호출하지 않으므로 base-url을 두지 않는다"라 했고 `InternalApiClientConfig`가 `internal.api.base-url` 존재로 활성화되므로(`:22`) **로컬에서만 trading-core에 내부 API 클라이언트 빈이 생긴다**(운영과 다른 구성).

### B-4. `@ConfigurationProperties` 클래스 전수와 미사용 필드

| 클래스 | file | 필드 | 코드에서 읽지 않는 필드 |
|---|---|---|---|
| `TelegramProperties` | `SH/platform/telegram/` | botToken, chatId | 0 |
| `InternalApiProperties` | `SH/platform/internalapi/` | baseUrl, token | 0 |
| `KakaoProperties` | `API/user/adapter/out/kakao/` | clientId, clientSecret | 0(`KakaoOAuthAdapter.java:24,27`) |
| `AdminBootstrapProperties` | `API/user/config/` | kakaoIds | 0 |
| `AlpacaProperties`(root) | `API/benchmark/adapter/out/alpaca/` | apiKey, apiSecret, dataBaseUrl | 0(`AlpacaIndexPriceAdapter.java:36,47,48`) |
| `KbLandProperties` | `API/benchmark/adapter/out/kbland/` | baseUrl | 0(`KbLandConfig.java:24`) |
| `AlpacaProperties`(marketcalendar) | `TC/marketcalendar/adapter/out/alpaca/` | baseUrl, apiKey, apiSecret, dataBaseUrl | **`dataBaseUrl`**(marketcalendar 안 `.dataBaseUrl()` 호출 0) |
| `AlpacaProperties`(stats) | `TC/stats/adapter/out/alpaca/` | apiKey, apiSecret, dataBaseUrl | 0 |
| `HeartbeatProperties` | `TC/trading/adapter/out/heartbeat/` | openUrl, closeUrl, cryptoUrl | 0(`HeartbeatAdapter.java:19,24,29`) |
| `CoinMarketCapProperties` | `TC/stats/adapter/out/coinmarketcap/` | apiKey | 0 |

합계 10개 record(같은 prefix `alpaca`를 3개 record가 중복 정의), 읽지 않는 필드 1개(marketcalendar `AlpacaProperties.dataBaseUrl`). `TelegramProperties`는 `@Validated @NotBlank`로 기동 검증.

### B-5. 환경변수 대조

yml `${ENV}` 참조 30개(+`X`는 주석 속 예시): `ADMIN_KAKAO_IDS, AES_ENCRYPTION_KEY, ALPACA_API_KEY, ALPACA_API_SECRET, CMC_API_KEY, CORS_ALLOWED_ORIGINS, DB_PASSWORD, DB_URL, DB_USERNAME, FIREBASE_SERVICE_ACCOUNT_JSON, GRAFANA_CLOUD_OTLP_{AUTH_HEADER,ENABLED,ENDPOINT}, HEARTBEAT_{CLOSE,CRYPTO,OPEN}_URL, INTERNAL_API_BASE_URL, INTERNAL_API_TOKEN, JWT_SIGNING_KEY, KAKAO_CLIENT_ID, KAKAO_CLIENT_SECRET, KIS_ADMIN_APP_{KEY,SECRET}, REDIS_URL, SCHEDULER_ENABLED, SERVER_PORT, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, TOSS_ADMIN_CLIENT_{ID,SECRET}`.

- `deploy/server/docker-compose.yml`(118줄, 서비스 3개 `kista-api`·`kista-scheduler`·`kista-trading`)가 직접 주입: `SPRING_PROFILES_ACTIVE`, `SCHEDULER_ENABLED`, `REDIS_URL`, `INTERNAL_API_BASE_URL`(api·scheduler만), `JAVA_OPTS`, trading만 `APP_JAR`·`SERVER_PORT`, scheduler·trading만 `SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART`. 나머지는 `.env`(infra 레포·서버 파일)에서 온다. 이미지 변수 `KISTA_API_IMAGE`·`KISTA_SCHEDULER_IMAGE`·`KISTA_TRADING_IMAGE`.
- `.github/workflows/*.yml`은 애플리케이션 env를 주입하지 않는다(`INFRA_APP_*`·`GITHUB_TOKEN`만).
- `.env.example`(65줄, 24개 변수) 대 yml: **yml에 있고 `.env.example`에 없는 것** `CMC_API_KEY`, `KIS_ADMIN_APP_KEY`, `KIS_ADMIN_APP_SECRET`(나머지 `INTERNAL_API_BASE_URL`·`REDIS_URL`·`SCHEDULER_ENABLED`·`SERVER_PORT`는 로컬 기본값 있음). `.env.example`에만 있는 것은 `SPRING_PROFILES_ACTIVE`(Spring 표준).
- `deploy/server/README.md`의 .env 목록(28개) 대 yml: README에만 있는 것 `API_DOMAIN`(Caddy용), `JAVA_OPTS`(compose용). yml에만 있고 README에 없는 것은 compose가 직접 주입하는 `INTERNAL_API_BASE_URL`·`REDIS_URL`·`SCHEDULER_ENABLED`·`SERVER_PORT`뿐이라 **한쪽에만 있는 env 이름 불일치는 없다**. `ADMIN_KAKAO_IDS` 등 선택값은 둘 다 있다.
- 양쪽 어디에도 없는데 코드가 env로 읽는 것: `BINANCE`는 env 참조 자체가 없다(yml 키 없음, B-2). 없음.

### 숫자 요약
- 추적 yml 5개 373줄, 커스텀 키 중 바인딩 0은 0개, 읽지 않는 `@ConfigurationProperties` 필드 1개(`dataBaseUrl`), `alpaca` prefix record 3중 정의.
- root 대 trading-core 값까지 동일한 base 키 30개, prod 키 15개. 프로파일·라이브러리 기본값과 같은 불필요한 선언 약 9줄.
- yml에 없이 코드 기본값만 있는 키 3개(`binance.base-url`, `app.trading.parallel-per-account`, `trading.shutdown.critical-wait`), 코드·yml 이중 기본값 4개(`meritz`·`upbit`·`app.cookie.secure`·`same-site`).

### 통합(05) 뒤에도 남는 문제 목록
1. `alpaca` prefix를 `AlpacaProperties` 3벌이 각자 바인딩(P2에서 정리 예정이나 `dataBaseUrl` 미사용 필드가 이미 있음) — `TC/marketcalendar/adapter/out/alpaca/AlpacaProperties.java`, `TC/stats/adapter/out/alpaca/AlpacaProperties.java`, `API/benchmark/adapter/out/alpaca/AlpacaProperties.java`.
2. `binance.base-url`만 yml에 없고 코드 기본값 — `TC/broker/adapter/out/binance/BinanceHttpClient.java:43`.
3. `meritz.base-url`·`upbit.base-url` 코드·yml 이중 기본값 — `TC/broker/adapter/out/meritz/MeritzConfig.java:14`, `TC/broker/adapter/out/upbit/UpbitConfig.java:14`, `trading-core/src/main/resources/application.yml`.
4. 같은 키 `cors.allowed-origins`의 기본값이 root는 없음, trading-core는 localhost — `shared/.../SecurityConfig.java:29`의 `@Value`는 하나인데 yml이 둘(통합 시 한 yml로 정리 대상).
5. 문서화되지 않은 운영 튜닝 키 2개가 yml에 없음 — `TC/trading/application/service/support/TradingParallelRunner.java:25`, `TC/trading/application/service/support/TradingBatchShutdownCoordinator.java:25`.
6. 로컬 yml이 trading-core에 `internal.api.base-url`을 두어 운영과 다른 빈 구성 — `trading-core/src/main/resources/application-local.yml`(gitignored라 저장소에는 흔적 없음, 통합(05) X8이 키를 지우면 해소).
7. `.env.example`에 `CMC_API_KEY`·`KIS_ADMIN_APP_*` 누락 — `/.env.example`.

## C. 이벤트·EPR 사용 방식

09 `findings/09-process-local-state.md` C·G절이 "web N + worker 1" 관점에서 리스너·스트림을 이미 전수했다. 이 절은 **이벤트 타입 단위**(발행·리스너 수, 일관성, 멱등)로 다시 묶고, 09와 다른 사실 1건을 바로잡는다.

### C-0. EPR 기록 범위 정정 (Modulith 2.1.1 소스로 확인)

09 C절 서두는 "EPR은 트랜잭션 안에서 발행된 `@TransactionalEventListener`(AFTER_COMMIT)만 기록한다. `fallbackExecution=true`는 트랜잭션 밖 발행이면 즉시 동기 실행(EPR 기록 없음)"이라 했다. gradle 캐시의 `spring-modulith-events-core-2.1.1-sources.jar` `PersistentApplicationEventMulticaster.multicastEvent`(:116-145)는 **트랜잭션 활성 여부를 검사하지 않고** 매칭된 모든 `TransactionalApplicationListener`에 대해 `storePublications`를 호출한다(`isActualTransactionActive` 검색 결과 0건). 따라서 EPR 행이 남는 것은 **`@TransactionalEventListener` 36개 전부**(fallbackExecution 18+5 포함)이고, 남지 않는 것은 일반 `@EventListener`뿐이다. 영향: ① `TradingErrorEvent`는 발행 지점 38곳이라 오류 1건마다 `event_publication` 행이 생기고 리스너가 끝나야 완료 처리된다(쓰기 증폭, 건수 미측정 — 운영 `SELECT count(*) FROM trading.event_publication`은 쓰기가 아니라 확인 가능하나 이번 조사 범위 밖). ② 알림 리스너가 예외로 끝나면 재기동 시 같은 알림이 재발행된다(C-4). 통합(05) M7 "EPR 한 테이블" 전환 절차를 쓸 때 이 사실이 미완료 행 수를 키운다.

### C-1. 이벤트 타입 전수 (32개 + Spring 이벤트가 아닌 1개)

`event` 패키지·`shared.kernel`·`platform.scheduling`에서 `publishEvent` 인자 또는 리스너 파라미터로 쓰이는 타입 전수. 정의 위치는 모두 발행 모듈(`application/event`) 또는 `shared`. 발행 수는 `publishEvent(` 호출 지점 수(팩토리 `of()`·`failed()` 포함). `TL`=`@TransactionalEventListener`, `EL`=`@EventListener`. 종류가 `TL(FB)`이면 `fallbackExecution=true`.

| 이벤트 | 발행 지점 수·모듈 | 리스너(file 약칭:line)·종류 | 프로세스 경계 |
|---|---|---|---|
| `AccountDeletedEvent` | 1 · tc.account `AccountDeletionWriter.java:29` | tc.trading `AccountCascadeListener:29` TL(AFTER_COMMIT) | 없음 |
| `ApiKeyExpiringEvent` | 1 · tc.account `ApiKeyExpiryScheduler:43` | tc.notify `ApiKeyExpiryNotifier:18` TL(FB) | 없음 |
| `AppErrorRaisedEvent` | 4 · root `GlobalExceptionHandler:123`, root `TelegramAdapter:26`, tc `TradingExceptionHandler:115`, tc `TradingNotifyAdapter:33` | root `AppErrorRaisedListener:20` EL, tc `AppErrorStreamPublisher:26` EL(→Redis `stream:app.error`) | **tc→root 스트림**(root 컨슈머가 다시 저장) |
| `ApprovalRequirementDisabledEvent` | 1 · root.admin `RuntimeSettingsService:45` | root.user `ApprovalRequirementDisabledListener:24` TL(AFTER_COMMIT) | 없음 |
| `BatchInterruptedEvent` | 1 · tc `TradingBatchGuard:59` | tc.notify `TradingAlertNotifier:77` TL(FB) | 없음 |
| `BenchmarkAlertRaisedEvent` | 2 · root.benchmark `HousingBenchmarkService:39,52` | root.notify `AlertNotifier:25` TL(FB) | 없음 |
| `CryptoOrderFilledEvent` | 1 · tc `CryptoOrderSynchronizer:128` | tc.notify `CryptoTradeNotifier:27` TL(FB) | 없음 |
| `CycleCompletedEvent` | 1 · tc `CycleRotationService:53` | tc.notify `CycleLifecycleNotifier:23` TL(FB) | 없음 |
| `CycleEndedEvent` | 1 · tc `ManualTradeCorrectionService:112` | tc.notify `CycleEndedNotifier:23` TL(AFTER_COMMIT) | 없음 |
| `CycleRotationFailedEvent` | 1 · tc `CycleRotationService:113` | tc.notify `TradingAlertNotifier:89` TL(FB) | 없음 |
| `DcaRoundSkippedEvent` | 1 · tc `DcaExecutionRecorder:87` | tc.notify `TradingAlertNotifier:72` TL(FB) | 없음 |
| `FearGreedFetchFailedEvent` | 1 · root.market `FearGreedService:37` | root.notify `AlertNotifier:20` TL(FB) | 없음 |
| `FinanceRegistrationReminderDueEvent` | 1 · root.finance `FinanceRegistrationReminderNotifier:68` | root.notify `FinanceReminderChannelNotifier:19` **EL** | 없음 |
| `InsufficientBalanceEvent` | 3 · tc `CycleRotationService:124`, `TradingOrderExecutor:193`, `TradingCandidatePlanner:265` | tc.notify `TradingAlertNotifier:46` TL(FB) | 없음 |
| `MarketCloseEvent` / `MarketOpenEvent` | 1 / 1 · tc `MarketEventNotifier:35 / :31` | `TradingAlertNotifier:67 / :62` TL(FB) | 없음 |
| `MarketClosedEvent` | 1 · tc `TradingService:464` | `TradingAlertNotifier:57` TL(FB) | 없음 |
| `NewCycleStartedEvent` | 3 · tc `CycleRotationService:126`, `VrCycleRolloverService:167`, `VrReconfigureService:140` | tc.notify `CycleLifecycleNotifier:30` TL(FB) | 없음 |
| `NewUserRegisteredEvent` | 1 · root.user `UserService:121` | root.notify `CompositeUserNotificationAdapter:29` TL(AFTER_COMMIT) | 없음 |
| `OrderCancelFailedEvent` | 1 · tc `OrderCancelService:155` | tc.notify `OrderCancelFailureNotifier:19` TL(AFTER_COMMIT, FB) | 없음 |
| `PrivacyAlertRaisedEvent` | 2 · tc.privacy `PrivacyService:31,36` | tc.notify `PrivacyAlertNotifier:18` TL(FB) | 없음 |
| `SchedulerLifecycleEvent` | 7 · shared `SchedulerJobRunner:23-54` | root.notify `SchedulerNotifier:18` TL(FB) | **trading-core 스케줄러가 발행해도 리스너는 root에만** — 아래 C-3 |
| `StrategyAutoPausedEvent` | 2 · tc `CycleRotationService:77,78` | `TradingAlertNotifier:83` TL(FB) | 없음 |
| `StrategyCapitalChangedEvent` | 10 · tc.trading(서비스 7 + 리스너 1 + `CryptoOrderSynchronizer:126`·`ManualTradeCorrectionService:116`) | tc.stats `TradingStatsService:114` **EL** | 없음(단 A12: 발행 프로세스 캐시만 비움) |
| `TradingErrorEvent` | **38** · tc.trading(`TradingService`·`OrderSubmitter` 6·`VrCycleRolloverService` 8·…) | tc.notify `TradingAlertNotifier:36` TL(FB) | 없음 |
| `TradingReportReadyEvent` | 1 · tc `TradingReporter:85` | tc.notify `TradingReportNotifier:34` TL(FB), tc.stats `TradingStatsService:105` **EL** | 없음 |
| `UserApprovedEvent` | 2 · root `UserService:134,180` | root.notify `SseEmitterRegistry:43`, `CompositeUserNotificationAdapter:42` TL(AFTER_COMMIT) | 없음 |
| `UserRejectedEvent` | 1 · root `UserService:148` | `SseEmitterRegistry:48`, `CompositeUserNotificationAdapter:47` TL(AFTER_COMMIT) | 없음 |
| `UserReappliedEvent` | 1 · root `UserService:178` | `CompositeUserNotificationAdapter:52` TL(AFTER_COMMIT) | 없음 |
| `UserSeedPromotedEvent` | 1 · root.user `AdminSeedPromoter:31` | root.admin `UserSeedPromotedAuditListener:24` TL(default phase) | 없음 |
| `UserDeletedEvent` | 1 · root `UserCascadeDeleter:40` | **5개**: root.notify `UserFcmCleanupListener:21`·`UserDeletedNotifier:17`, root.user `UserEventStreamPublisher:29`, root.finance `FinanceUserCascadeListener:21`, tc.trading `TradingUserCascadeListener:25` — 전부 TL(AFTER_COMMIT) | **root→tc 스트림**(`stream:user.deleted` → tc `UserEventRepublisher` 로컬 재발행) |
| `UserNotifyProfileChangedEvent` | 1 · root `UserNotifyProfilePublisher:48` | root.user `UserEventStreamPublisher:35`, tc.trading `UserNotifyProfileSyncListener:27` TL(AFTER_COMMIT, FB) | **root→tc 스트림**(`stream:user.notify-profile.changed`) |
| `UserPushNotificationRequestedEvent`(shared.kernel) | **Spring 이벤트가 아니다** — tc `RedisPushNotificationPublisher.publish`가 `RedisStreams.add`(`TradingUserNotificationAdapter.java:167`), root `PushNotificationStreamConsumer:48`이 읽어 처리 | — | tc→root 스트림. 이름이 `*Event`지만 `publishEvent`를 거치지 않는 DTO(명명 비일관) |

리스너 합계: `@TransactionalEventListener` 36개(FB 포함 23, phase 명시 17, 둘 다 없음 1), `@EventListener` 비-기동 5개(`AppErrorRaisedListener`, `AppErrorStreamPublisher`, `FinanceReminderChannelNotifier`, `TradingStatsService` 2) + 기동 훅 5개(`ApplicationReadyEvent`: 스트림 컨슈머 3, `TradingBatchResumer`, `MarketCalendarRefreshScheduler`).

### C-2. 집계

- **리스너가 0개인 이벤트: 0개.** **발행처가 0인 이벤트: 0개**(`UserPushNotificationRequestedEvent`는 Spring 이벤트가 아니므로 제외).
- **발행처 1·리스너 1이고 같은 모듈 안인 이벤트(직접 호출 후보): 0개.** 발행·리스너가 같은 모듈인 쌍은 `UserDeletedEvent`·`UserNotifyProfileChangedEvent` → root.user `UserEventStreamPublisher`(스트림 발행기, 05 P5·X7로 삭제)뿐이다. 나머지는 전부 모듈 경계를 넘는다 — 알림은 `notify` 모듈 26건(이벤트 26개가 notify 리스너를 가짐), 정리는 `trading`·`stats`·`finance`. 이벤트를 직접 호출로 바꾸면 `ModulithArchitectureTest`의 의존 방향(발행 모듈이 notify를 알게 됨)을 깨므로 후보가 아니다.
- **비일관성 묶음**
  1. 같은 목적(운영 알림 리스너)을 세 가지 방식으로 선언: `TL(fallbackExecution=true)` 18개 (대부분 trading-core), `TL(phase=AFTER_COMMIT)` 단독 12개(root user·notify), `EL` 1개(`FinanceReminderChannelNotifier`, EPR 없음, `API/notify/adapter/out/gateway/FinanceReminderChannelNotifier.java:19`). 발행 지점이 트랜잭션 안인지에 따라 달라진 것인데 기준 문서는 `constraints.md` "발행 지점이 트랜잭션 밖이면 동기 `@EventListener`" 한 줄이고, 실제 코드는 트랜잭션 밖 발행에 `TL(FB)`를 쓴다(`SchedulerNotifier.java:11-18`, `AlertNotifier.java:12-25` 주석). `constraints.md` 규칙과 코드가 다르다.
  2. 같은 목적(캐시 무효화)을 두 방식으로: trading-core `TradingStatsService` `@EventListener`(프로세스 로컬) 대 root `TradeEventBenchmarkCacheInvalidator`(Redis Pub/Sub `trade.event`, `API/benchmark/adapter/in/redis/TradeEventBenchmarkCacheInvalidator.java:29`).
  3. 같은 목적(체결·승인 → 화면 실시간 통지)을 두 방식으로: 승인/거절 SSE는 `SseEmitterRegistry` TL 리스너(프로세스 로컬 emitter), 체결 SSE는 `RedisTradeEventSubscriber`(Pub/Sub, `API/notify/adapter/out/sse/RedisTradeEventSubscriber.java:30`). 09 A-3이 다룬 "인스턴스 경계에서 유실"의 원인이 이 비일관성이다.
  4. 이름이 거의 같은 이벤트 쌍: `MarketCloseEvent`(`MarketEventNotifier:35`)와 `MarketClosedEvent`(`TradingService:464`)가 둘 다 `TradingAlertNotifier`로 가고(`:67`,`:57`), `CycleCompletedEvent`와 `CycleEndedEvent`가 둘 다 사이클 종료 알림이지만 리스너 클래스가 다르고(`CycleLifecycleNotifier:23`, `CycleEndedNotifier:23`) 발행 경로(배치 회전 / 수동 체결 보정)와 phase 선언 방식이 다르다. 두 쌍의 의미 차이는 이벤트 클래스 주석 기준으로만 확인했고 합칠 수 있는지는 판단하지 않는다.
  5. 트랜잭션 보장이 서로 다른 같은 이벤트: `UserApprovedEvent`는 `SseEmitterRegistry`(메모리 emitter)와 `CompositeUserNotificationAdapter`(Telegram/FCM 외부 호출)를 한 이벤트에 TL(AFTER_COMMIT)로 둘 다 건다 — EPR 재시도 시 SSE 쪽은 무의미한 재실행, 외부 호출 쪽은 중복 발송 위험(C-4).
- 발행 수 편중: `TradingErrorEvent` 38곳 — 이 중 `TradingErrorReporter.reportError`(`TradingErrorReporter.java:18`, context=null)를 거치는 호출은 11곳, 서비스가 `new TradingErrorEvent(...)`를 직접 발행하는 곳이 37곳(`VrCycleRolloverService` 9, `OrderSubmitter` 6 등)이다. `StrategyCapitalChangedEvent` 10곳.

### C-3. 프로세스 경계를 넘는 이벤트·메시지 전수

| 대상 | 방향 | 매체 | 통합(05) |
|---|---|---|---|
| `UserDeletedEvent` | root → tc | Redis Stream `stream:user.deleted` → tc `UserDeletedStreamConsumer` → `UserEventStreamBridge:28` → `UserEventRepublisher:23,28`이 같은 클래스를 **로컬 재발행** → `TradingUserCascadeListener` | 사라짐(05 P5·X7) |
| `UserNotifyProfileChangedEvent` | root → tc | `stream:user.notify-profile.changed` → `UserNotifyProfileStreamConsumer` → `UserEventStreamBridge:33` → 재발행 → `UserNotifyProfileSyncListener` | 사라짐 |
| `AppErrorRaisedEvent`(JSON) | tc → root | `stream:app.error` → root `AppErrorStreamConsumer:33,49` | 사라짐(M-표 X단계) |
| `UserPushNotificationRequestedEvent`(JSON) | tc → root | `stream:user.push-notification.requested` → root `PushNotificationStreamConsumer:32,48` | 사라짐 |
| 체결 이벤트(JSON 문자열) | tc → root(web N) | Pub/Sub `trade.event`(`RedisPubSubConfig.java:15`) 발행 `RedisTradeEventPublisher:25`, 구독 `RedisTradeEventSubscriber:30`·`TradeEventBenchmarkCacheInvalidator:29` | **남음**(worker → web 팬아웃 필수) |
| `SchedulerLifecycleEvent` | tc 스케줄러 → (리스너 root만) | 리스너 `SchedulerNotifier`는 root.notify에만 있다(`API/notify/adapter/out/gateway/SchedulerNotifier.java:18`). tc에서도 `SchedulerJobRunner.run`을 `MarketCalendarRefreshScheduler:48,60,71`·`TradingOpenScheduler:59,75`·`TradingCloseScheduler:187,203`이 호출해 이벤트를 발행하지만 trading-core 프로세스 안에는 리스너가 없어 매칭 리스너 0으로 **조용히 버려진다**(`PersistentApplicationEventMulticaster:132-134`가 매칭이 없으면 즉시 return — EPR 행도 없음) | 합치면 한 컨텍스트에 리스너가 있어 사라질 비일관(남는 것은 알림 중복 여부) |

`RedisPubSubConfig.java:9-11` 주석은 "두 앱 모두 `com.kista.shared.platform`을 스캔"이라고 하지만 실제로는 `PlatformAutoConfiguration`이 `@Import`한다(`PlatformAutoConfiguration.java:32-37`, `architecture.md` "스캔 목록 없음") — 주석 드리프트.

Redis 스트림·Pub/Sub 전수(상세는 09 G절): 스트림 4(`stream:user.deleted`, `stream:user.notify-profile.changed`, `stream:app.error`, `stream:user.push-notification.requested`, 상수 `SH/platform/redis/RedisStreamConfig.java:9-12`), Pub/Sub 1(`trade.event`). 컨슈머 5개(tc 3: `UserDeletedStreamConsumer`·`UserNotifyProfileStreamConsumer`·`UserEventStreamConsumer`(기동·복구 `@Scheduled`), root 2: `AppErrorStreamConsumer`·`PushNotificationStreamConsumer`)가 공통 골격 `RedisStreamSubscriber`(`SH/platform/redis/RedisStreamSubscriber.java:73,157`, `@Scheduled` 4개)를 쓴다. 생산자: root `UserEventStreamPublisher` 1, tc `AppErrorStreamPublisher`·`RedisPushNotificationPublisher`·`RedisTradeEventPublisher` 3.

### C-4. 리스너 멱등성 판단 (EPR 재발행 시 중복 부작용)

EPR 재발행은 미완료 행(리스너가 예외로 끝났거나 기동 중 중단)을 다시 부른다. 판단 근거는 리스너 본문.

| 리스너 | 판단 | 근거 |
|---|---|---|
| 알림 리스너 전부(`TradingAlertNotifier` 9메서드, `TradingReportNotifier`, `CycleLifecycleNotifier`, `CycleEndedNotifier`, `CryptoTradeNotifier`, `OrderCancelFailureNotifier`, `ApiKeyExpiryNotifier`, `PrivacyAlertNotifier`, root `AlertNotifier`·`SchedulerNotifier`·`CompositeUserNotificationAdapter`·`UserDeletedNotifier`) | **비멱등** — 재실행하면 Telegram/FCM이 중복 발송된다. 채널 2개(Telegram→FCM) 중 첫 발송 뒤 두 번째가 실패하면 첫 채널이 다시 나간다(발송 중복 방지 키는 확인하지 못함, `grep` 대상 클래스에서 멱등 키 없음) | 알림이라 피해는 중복 메시지. 금액 영향 없음 |
| `TradingUserCascadeListener:25` | 대체로 멱등 | `cancelOpenOrders`(이미 취소된 주문은 재취소가 실패할 수 있음, 미확인) 후 `erase` |
| `UserNotifyProfileSyncListener:27` | **멱등** | `occurredAt` 가드(`UserNotifyProfilePersistenceAdapter:92`) |
| `AccountCascadeListener:29` | 멱등(이미 삭제된 행) | 삭제 후 `StrategyCapitalChangedEvent` 재발행 |
| `FinanceUserCascadeListener:21`, `UserFcmCleanupListener:21` | 멱등(삭제) | |
| `ApprovalRequirementDisabledListener:24` | 멱등 | PENDING만 일괄 승인 |
| `UserSeedPromotedAuditListener:24` | **비멱등** | `auditLogPort.log` — 재실행하면 감사 로그 행이 하나 더 생긴다(`REQUIRES_NEW`, `:24-25`) |
| `UserEventStreamPublisher:29,35` | 비멱등(중복 XADD) | 수신 쪽(`UserNotifyProfileSyncListener`)이 멱등이라 영향 흡수 |
| `AppErrorRaisedListener:20`(EL, EPR 아님) | 재발행 대상 아님 | 단 스트림 컨슈머가 XCLAIM으로 재전달하면 `app_error_logs`에 중복 행 가능(저장 전 중복 검사 없음, `:20-22`) |
| `TradingStatsService` EL 2개 | 멱등(캐시 무효화) | |

### 숫자 요약
- 이벤트 타입 32(+Spring 아닌 1), 리스너 `TL` 36·`EL` 비-기동 5, 리스너 0인 이벤트 0, 발행 0인 이벤트 0, 직접 호출 후보(같은 모듈 1:1) 0.
- 프로세스 경계를 넘는 것: Spring 이벤트 3종 + 푸시 DTO 1 = 스트림 4, Pub/Sub 1. 통합(05)이 스트림 4를 지우고 Pub/Sub 1은 남긴다.
- 비일관성 묶음 5(알림 리스너 선언 3방식, 캐시 무효화 2방식, SSE 2방식, 유사명 이벤트 2쌍, 한 이벤트에 메모리·외부 리스너 혼재).

### 통합(05) 뒤에도 남는 문제 목록
1. EPR이 `fallbackExecution` 리스너까지 기록하므로 `TradingErrorEvent` 38곳이 오류마다 EPR 행을 만든다 — `TC/notify/adapter/out/gateway/TradingAlertNotifier.java:36`, 근거 Modulith 2.1.1 `PersistentApplicationEventMulticaster:116-145`. 09 C-서두의 "EPR 기록 없음" 서술과 다르다.
2. 알림 리스너 13개 이상이 비멱등(EPR 재발행 시 중복 발송) — 대표 `TC/notify/adapter/out/gateway/TradingAlertNotifier.java:36-89`.
3. 알림 리스너 선언 방식 3종 혼재, `constraints.md` 규칙과 실제가 다름 — `API/notify/adapter/out/gateway/FinanceReminderChannelNotifier.java:19`(EL), `TC/notify/adapter/out/gateway/CycleEndedNotifier.java:23`(TL AFTER_COMMIT).
4. 같은 이벤트에 메모리 emitter 리스너와 외부 발송 리스너 혼재 — `API/notify/adapter/out/sse/SseEmitterRegistry.java:43,48` 대 `API/notify/adapter/out/gateway/CompositeUserNotificationAdapter.java:42,47`(web N 인스턴스에서 SSE 유실, 09 A-3).
5. 유사 이벤트 쌍 — `TC/trading/application/event/MarketCloseEvent.java`·`MarketClosedEvent.java`, `CycleCompletedEvent.java`·`CycleEndedEvent.java`.
6. `TradingErrorReporter` 래퍼 호출 11곳 대 직접 `new TradingErrorEvent` 37곳 — `TC/trading/application/service/support/TradingErrorReporter.java:18`.
7. `SchedulerLifecycleEvent`를 tc 스케줄러가 발행해도 리스너가 root에만 있던 경로(통합 후엔 해소되나 발행 지점 감사 필요) — `SH/platform/scheduling/SchedulerJobRunner.java:23-54`.

## D. 오류 처리

### D-1. 커스텀 예외 클래스 전수 (36개, 중첩 포함)

throw=`new X(`/`X::new` 지점 수(main 전체), catch=`catch (… X …)` 수. 줄 수는 별도 파일인 것만(중첩 클래스는 도메인 모델 파일 안, 3~7줄). 매핑: G=`API/web/GlobalExceptionHandler.java:49-69` MAPPINGS, T=`TC/web/TradingExceptionHandler.java:73-86` MAPPINGS, B=`BrokerApiException` 전용 핸들러(`:81-88`), 상속=상위 클래스 계층 탐색(`ProblemDetailMappings.resolve`)으로 상위 매핑.

| 예외 | 위치 | 줄 | throw | catch | HTTP 매핑 |
|---|---|---|---|---|---|
| `AdminBrokerCredentialException` | `API/admin/domain/model/` | 9 | **0** | 0 | G 422 |
| `AdminBrokerRateLimitException` | 같은 곳 | 8 | **0** | 0 | G 429 |
| `AdminPrivacyTradeConflictException` | 같은 곳 | 10 | 1 | 0 | G 409 |
| `TradingPolicyUnavailableException` | 같은 곳 | 10 | 2 | 1 | G 503 |
| `CandleUnavailableException` | `API/market/domain/model/` | 9 | 2 | 0 | G 503 |
| `InvalidRefreshTokenException` | `API/user/domain/auth/` | 8 | 4 | 0 | G 401 |
| `User.CooldownException` | `User.java:28` | 중첩 | 1 | 0 | 전용 `@ExceptionHandler`(`GlobalExceptionHandler.java:72`) 429+Retry-After |
| `User.AlreadyActiveException` | `User.java:40` | 중첩 | 1 | 1 | G 409 |
| `FinanceAccount.LinkedAssetSnapshotsException` / `DuplicateAccountNoException` | `FinanceAccount.java:51,58` | 중첩 | 1 / 2 | 0 | G 409 |
| `FinanceBudget.OverlappingPeriodException` | `:33` | 중첩 | 3 | 0 | G 409 |
| `FinanceCategory.DuplicateNameException` | `:60` | 중첩 | 1 | 0 | G 409 |
| `FinanceGroup.MembershipConflictException` | `:18` | 중첩 | 3 | 0 | G 409 |
| `FinanceGroupInvitation.InvalidInvitationStateException` | `:21` | 중첩 | 1 | 0 | G 409 |
| `MonthlyClosing.MonthClosedException` | `:28` | 중첩 | 1 | 0 | G 409+MONTH_CLOSED |
| `Account.DuplicateAccountException` | `TC/account/domain/model/Account.java:79` | 중첩 | 4 | 0 | T 409 |
| `BrokerApiException`(추상 상위) | `TC/broker/domain/model/` | 49 | 0(직접) | 0 | B 503 |
| `Binance/Kis/Meritz/Toss/UpbitApiException` | `broker/domain/model/<벤더>/` | 24/24/32/33/24 | 6/11/12/15/3 | 2/2/2/6/1 | 상속→B 503 |
| `BrokerCredentialException` | `broker/domain/model/` | 14 | 18 | 2 | T 422 |
| `BrokerIpNotAllowed/PermissionDenied/WithdrawalEnabled` | 같은 곳 | 8씩 | 2 / 4 / 1 | 0 | T 422(각 code) |
| `BrokerRateLimitException` | 같은 곳 | 9 | 7 | 0 | T 429 |
| `PrivacyTradeConflictException` | `TC/privacy/domain/model/` | 7 | 1 | 0 | T 409 |
| `AlreadyOrderedTodayException` | `TC/trading/domain/model/` | 8 | 2 | 0 | T 409+code |
| `DcaManualExecutionUnsupportedException` | 같은 곳 | 8 | 2 | 0 | T 409+code |
| `DuplicateStrategyTickerException` | 같은 곳 | 8 | 2 | 0 | T 409(ISE 상속이지만 명시) |
| `ManualTradingException` / `ManualTradingFailedException` | 같은 곳 | 12 / 9 | 4 / 1 | 0 | T 409 / T 500(고정 문구) |
| `OrderCancelException` | 같은 곳 | 8 | 6 | 0 | T 409+ORDER_NOT_CANCELLABLE |
| `OrderStateConflictException` | 같은 곳 | 8 | 2 | **7** | **매핑 없음**(ISE 상속 → GENERIC 400 "Invalid State") |
| `PrivacyValuationException` | `TC/matching/domain/strategy/` | 8 | 4 | 2 | **매핑 없음**(ISE 상속 → 400) |

집계
- **throw 0곳**: `AdminBrokerCredentialException`·`AdminBrokerRateLimitException`(main 어디에서도 생성되지 않음. `grep`상 `GlobalExceptionHandler.java:53-54` 매핑과 테스트 2개만 참조) — 이미 죽은 클래스. 05 X2가 삭제 예정.
- **throw 1곳뿐**: `AdminPrivacyTradeConflictException`, `User.CooldownException`, `User.AlreadyActiveException`, `FinanceAccount.LinkedAssetSnapshotsException`, `FinanceCategory.DuplicateNameException`, `FinanceGroupInvitation.InvalidInvitationStateException`, `MonthlyClosing.MonthClosedException`, `PrivacyTradeConflictException`, `BrokerWithdrawalEnabledException`, `ManualTradingFailedException` (10개).
- **매핑 테이블에 없는 것**: `OrderStateConflictException`, `PrivacyValuationException` 2개(둘 다 내부에서 catch되는 경로가 주이고 HTTP까지 새면 400). 나머지는 직접 또는 상속으로 매핑됨. 매핑 테이블 엔트리 root 17(범용 GENERIC 10 별도) / trading 13.
- **같은 상황에 다른 예외**: "이미 X 상태" 충돌이 (a) 전용 예외+409(`Account.DuplicateAccountException`, `DuplicateStrategyTickerException`, finance 7종), (b) `IllegalStateException`+400로 갈린다 — `StrategyService.java:97,117,160`("이미 일시중지/보관/활성화된 전략")은 ISE 400, `ManualTradeCorrectionService.java:79`도 ISE. 같은 "이미 ~" 문구 ISE 5곳 + IAE 1곳. 범용 예외 throw 수: IAE 202, ISE 94, SecurityException 18, NoSuchElementException 17.
- 계층 중복: root `Admin*` 복원 예외 3쌍이 trading 원본과 같은 의미를 중복(05 X2로 사라짐).

### D-2. `ErrorCode` 14개

| 상수 | main 사용 | UI 분기(생성 타입·테스트 제외) |
|---|---|---|
| BROKER_UNAVAILABLE | 2 | `shared/lib/proxy/routeHelpers.ts` |
| BROKER_CREDENTIAL_INVALID | 3 | `entities/account/model/brokerCredentials.ts` |
| BROKER_IP_NOT_ALLOWED | 1 | `brokerCredentials.ts`, `ApiStepParts.tsx` |
| BROKER_PERMISSION_DENIED | 1 | `brokerCredentials.ts` |
| BROKER_RATE_LIMITED | 2 | `brokerCredentials.ts` |
| BROKER_WITHDRAWAL_ENABLED | 1 | `brokerCredentials.ts` |
| DUPLICATE_ACCOUNT, ALREADY_ORDERED_TODAY, DCA_MANUAL_EXECUTION_UNSUPPORTED, ORDER_NOT_CANCELLABLE, MONTH_CLOSED, ACCESS_DENIED, COOLDOWN_ACTIVE, TRADING_CORE_UNAVAILABLE | 각 1 | **없음**(생성된 `shared/lib/api-types.ts`와 테스트에만 등장) |

사용처 0인 상수 0개. UI가 코드로 분기하는 것은 6개, 나머지 8개는 서버가 내보내지만 UI 소스에서 분기하지 않는다(detail 문구 표시로 충분한 것으로 보이나 미확인: UI가 `code`를 일반 경로로 표시하는지). 상수 삭제 금지 계약(`constraints.md`)은 유지.

### D-3. `BrokerCallGuard`

정의 `TC/broker/application/service/BrokerCallGuard.java`(48줄). 사용처(trading-core main): `wrap` 8곳, `tryGet` 28곳(`CryptoMarketIndicatorService` 17곳 포함), `isBrokerTyped` 외부 1곳(`ManualTradingService.java:165`). 실패 정책이 `tryGet`=fail-open인 곳 중 `wrap` 결과로 5xx로 새는 경로: `wrap`은 비증권사 예외를 ISE(400)로 바꾼다(`BrokerCallGuard.java:32-33`) — 예: 네트워크 타임아웃이 `BrokerApiException`이 아니면 사용자에게 400 "Invalid State"로 나간다(확인: ISE→GENERIC 400, 어댑터가 모든 실패를 `*ApiException`으로 바꾸는지는 미집계).

### D-4. 서비스·usecase 층의 raw `catch (RuntimeException|Exception)` 51곳 전수 분류

(전 프로젝트 124곳: service 51, adapter 52, 기타 21.) 서비스 51곳 분류 — "이벤트·분기 필요" 예외에 해당(E)·분기·집계 목적(B)·로그 후 삼킴(S)·`BrokerCallGuard` 정의 자체(G)·tryGet 대체 후보(T):

| 분류 | 수 | 위치(file:line, `API/…`·`TC/…application/service/` 약칭) |
|---|---|---|
| E 이벤트·관리자 보고 | 18 | `HousingBenchmarkService:37,50`, `FearGreedService:35`, `TradingReporter:108`, `ManualTradingService:80,117`, `PrivacyBaseGuard:35`, `CryptoDcaRunner:79,94`, `TradingService:351,451`, `VrCycleRolloverService:51,85`, `TradingOrderExecutor:197`, `OrderSubmitter:77,153`, `VrReconfigureService:142`, `support/TradingBatchGuard:32` |
| B 분기·집계·재시도 | 22 | `MarketIndexPriceSyncService:34`, `BulkFinanceRegisterService:58`, `FearGreedQueryService:75`(실패 캐시), `BrokerRouter:141`(부분 결과), `CryptoDcaRoundExecutor:128,194`, `TradingPriceFetcher:191`, `OrderSubmissionReconciler:79,99`, `PrivacyBaseGuard:49`, `OrderCancelService:138`, `TradingPreviewService:165,187`, `OrderSubmitter:45,66,111,168`, `UserTradingDataEraseService:48`, `CycleRotationService:97,107,115`, `support/TradingParallelRunner:83` |
| S 로그 후 삼킴(void) | 8 | `FinanceRegistrationReminderNotifier:69`, `CryptoOrderSynchronizer:56`, `CancelledOrderFillRecorder:40,54`, `MarketEventNotifier:64`, `TradingBatchGuard:43,50,60`(이벤트 발행 자체를 보호) |
| T tryGet 대체 후보 | 1 | `TradingPriceFetcher:177`(로그 후 빈 맵 대체, 이벤트·분기 없음 → `constraints.md`상 `tryGet` 대상) |
| G | 2 | `BrokerCallGuard:27,43` |

`S`는 모두 반환값이 없어 `tryGet`(Optional 반환)으로 바꿀 수 없고, 알림 보호용이라 정당. 규칙 위반 후보는 `TradingPriceFetcher:177` 1곳. 어댑터층 삼킴 휴리스틱 후보 20, 기타 10(휴리스틱 분류 — body에 throw/publish/return/대입이 없는 catch, 일부 오탐 있음: `CryptoDcaRoundExecutor:128`은 `handleFailure` 호출이라 오탐).

### D-5. 로그 규칙

`log.error` 50곳 중 catch 블록 안 33곳에서 예외 객체를 마지막 인자로 넘기지 않는 것은 2곳: `API/notify/adapter/out/gateway/FcmAdapter.java:70`(`e.getMessage()`만 — 규칙 위반), `SH/platform/telegram/TelegramHttpClient.java:90`(`redactToken`으로 토큰을 가리려는 의도적 생략, 정당). 나머지 17곳은 catch 밖(조건 오류·캐시·한도 초과 등).

### 숫자 요약
- 커스텀 예외 36개: throw 0곳 2개, throw 1곳뿐 10개, 매핑 없음 2개. ErrorCode 14개 중 UI 분기 6개, 사용처 0은 0개.
- `BrokerCallGuard` wrap 8·tryGet 28. 서비스 raw 광역 catch 51곳 중 규칙 위반 후보 1곳, log.error 예외 미전달 위반 1곳.

### 통합(05) 뒤에도 남는 문제 목록
1. "이미 ~ 상태" 충돌이 전용 예외(409)와 ISE(400)로 갈림 — `TC/trading/application/service/StrategyService.java:97,117,160`.
2. 매핑 없는 `OrderStateConflictException`·`PrivacyValuationException`이 HTTP로 새면 400 — `TC/trading/domain/model/OrderStateConflictException.java`, `TC/matching/domain/strategy/PrivacyValuationException.java`.
3. `TradingPriceFetcher:177` raw catch(`tryGet` 규칙 대상) — `TC/trading/application/service/TradingPriceFetcher.java:177`.
4. `FcmAdapter` log.error 예외 객체 미전달 — `API/notify/adapter/out/gateway/FcmAdapter.java:70`.
5. `BrokerCallGuard.wrap`이 비증권사 예외를 ISE로 바꿔 400으로 노출 — `TC/broker/application/service/BrokerCallGuard.java:32-33`.
6. UI가 분기하지 않는 ErrorCode 8개 — `SH/platform/web/ErrorCode.java`.
7. 통합 전에도 죽은 `Admin*` 예외 2개 — `API/admin/domain/model/AdminBrokerCredentialException.java`, `AdminBrokerRateLimitException.java`(X2에서 삭제).

## E. 메서드·필드 단위 미사용 코드

방법: scratchpad의 정규식 스크립트(`unused.py`·`unused2.py`·`unused3.py`·`unused4.py`)가 main 3개 서브프로젝트(주석·문자열 제거 후)에서 선언 추출 → 이름 출현 수를 main·test 전체와 대조해 후보를 뽑고, **후보는 하나씩 `grep -w`로 다시 확인**했다. `@Bean`·`@EventListener`·`@Scheduled`·매핑 핸들러·`@Override`·`@Query`는 제외, JPA 엔티티 컬럼 필드·JSON/외부 응답 DTO record·Spring이 생성하는 `@Component` 계열 클래스는 별도 분류. **한계**: 이름 기준이라 "다른 클래스에 같은 이름의 호출이 있는" 메서드(`get`·`save`·`of` 등)는 놓친다(거짓 음성). 정확한 도달성 분석(IDE 미사용 검사)은 하지 않았다. 따라서 아래 수치는 **하한**이다.

### E-1. main 호출 0인 메서드

| 메서드 | file:line | main 호출 | test 호출 | 분류 |
|---|---|---|---|---|
| `FinanceAccount.withAccountNo` | `API/finance/domain/model/FinanceAccount.java:35` | 0 | 0 | **완전 미사용**(3줄) |
| `UserSettingsPort.findOrDefaultByUserIds` | `API/user/application/port/output/UserSettingsPort.java:21`, 구현 `UserSettingsPersistenceAdapter.java:60` | 0 | 0 | **미사용 포트 메서드**(포트 1 + 어댑터 1) |
| `AccountBalance.hasSufficientDepositFor` | `TC/matching/domain/model/AccountBalance.java:61` | 0 | 6 | **test-only** |
| `ReverseModePosition.calcLocBuyAmount` | `TC/matching/domain/model/ReverseModePosition.java:42` | 0 | 1 | test-only |
| `BrokerApiException.isInsufficientFunds` | `TC/broker/domain/model/BrokerApiException.java:38` | 0 | 8 | test-only |
| `BrokerApiException.isRejected`(인스턴스) | `BrokerApiException.java:42` | 0 | 48 | test-only — main은 정적 `isRejection(e)`를 쓴다(`OrderSubmitter.java:67` 등). 같은 판정의 인스턴스·정적 2벌 |
| `DstInfo.lastSessionOpenInstantAt` | `TC/trading/domain/model/DstInfo.java:65` | 0 | 2 | test-only(`TossPriceApi.java:163,178` 주석이 "DstInfo와 같은 계산을 복제"라 밝힘 — 같은 로직이 어댑터에 따로 구현됨) |
| `DstInfo.marketCloseAt` | `DstInfo.java:139` | 0 | 2 | test-only |
| `DstInfo.immediateClose` | `DstInfo.java:155` | 0 | 1 | test-only |

후보로 잡았으나 사용 중이거나 프레임워크 진입점이라 제외한 것: `isBuyQuantityValid`(3곳, `@AssertTrue` 빈 검증 — `FidaOrderRequest.java:29`), `buildEarlyStageOrders`·`computeLateBuys`·`resolveOrderDvsn`(private인데 같은 클래스에서 호출됨), `loadByUserId`·`findActiveById`·`findInvitationByCode`(포트의 `default` 메서드가 호출), `resolveTypeFields`(구현 5곳을 `StrategyCreationResolver`가 호출).

집계: 완전 미사용 메서드 2(포트 메서드 1 포함, 구현 포함 약 3개 선언·약 12줄), test-only 7. 총 줄 수 추정 약 70줄(test-only 7개 메서드 본문 포함, 정밀 집계 안 함).

### E-2. main에서 호출 0이고 test에서만 쓰이는 클래스·타입

Spring이 만드는 클래스를 제외한 **일반 타입(record·enum·interface·static 유틸) 중 main 외부 참조 0인 것은 0개**였다(스크립트 1절 결과 전부 `@Component`/`@Service`/`@RestController`/`@Configuration` 계열 — 프레임워크 생성이라 호출 0이 정상). 즉 test-only 클래스는 발견되지 않았다. 미확인: `@Component`이면서 아무도 주입받지 않는 빈(주입 지점 분석 필요, 이름 grep으로는 생성자 주입이 안 잡힌다).

### E-3. 쓰기만 있고 읽기가 없는 필드, 접근자 호출 0인 record 컴포넌트

대상: 도메인·Command·내부 record(`adapter/in/web`·`contract`·`persistence`·`*Response`·`*Request` 제외).

| 컴포넌트 | file:line | main 접근자 호출 | 비고 |
|---|---|---|---|
| `MarginItem.integratedOrderableAmount` | `TC/broker/domain/model/MarginItem.java:9` | 0(test 1) | 브로커 응답에서 채우나 읽는 곳 없음 |
| `MarginItem.foreignOrderableAmount` | `MarginItem.java:10` | 0(test 1) | 같음 |
| `CycleCloser.CycleEndResult.ended` | `TC/trading/application/service/support/CycleCloser.java:31` | 0(test 3) | test-only |
| `TossTokenStore.CanonicalToken.expiresAtEpochMillis` | `TC/broker/adapter/out/toss/TossTokenStore.java:27` | 0 | 토큰 TTL은 Redis TTL이 담당하는 것으로 보임, 미확인 |
| `AlpacaIndexPriceAdapter.Bar.v`, `TelegramUpdate.updateId`, `TelegramUpdate.Message.messageId` | `API/benchmark/…/AlpacaIndexPriceAdapter.java`, `API/admin/adapter/in/telegram/TelegramUpdate.java` | 0 | 외부 JSON 역직렬화 DTO라 제외 대상(구조 유지가 자연스러움) |

private 필드 중 선언 1회·참조 0인 것: JPA 엔티티 컬럼을 제외하면 **0개**(Lombok 접근자로 읽는 enum `label` 필드 등은 getter 경유라 미집계, 정확 판정 불가 — 미확인). 쓰기 전용 필드는 이 도구로 가려내지 못했다.

### E-4. 사용처 0인 enum 상수·상수·static 헬퍼

| 항목 | file:line | 참조 | DB·JSON 유입 가능성 |
|---|---|---|---|
| `FinanceCategory.SYSTEM_LOAN_ID`(상수) | `API/finance/domain/model/FinanceCategory.java:23` | main 0, test 0(주석 1곳만 언급: `AssetSnapshotResponse.java:9`) | — **완전 미사용 상수** |
| `CryptoOrderResult.State.WATCH` | `TC/broker/domain/model/CryptoOrderResult.java:18` | main·test 0 | 업비트 응답 상태 매핑일 수 있음(미확인) |
| `Currency.JPY` | `SH/kernel/Currency.java:12` | main 참조 0(test 3) | enum 순회(`values()`)로 쓰이면 메타 번들에 노출 — 미확인 |
| `EtfBenchmarkSymbol.IBIT`·`ETHA` | `API/benchmark/domain/model/EtfBenchmarkSymbol.java` | 선언만 | **쿼리 파라미터로 입력 가능**(UI 심볼 선택) — 유지 |
| finance enum: `AssetClass.FIXED_INCOME/COMMODITY/CRYPTO/REAL_ESTATE`, `FinanceCategory.Type.SAVING`, `FinanceAccount.Type.INSURANCE/EXCHANGE` | `API/finance/domain/model/` | 선언만 | **DB·요청 JSON으로 유입**(사용자 입력 선택지) — 유지 |

상수 이름이 선언부에서만 출현하는 `static final` 상수는 `SYSTEM_LOAN_ID` 1개. 사용처 0인 `static` 헬퍼는 E-1의 `BrokerApiException.isRejected`(인스턴스)·`DstInfo.immediateClose`뿐.

### E-5. 모듈별 합계와 줄 수 추정

| 서브프로젝트 | 완전 미사용(main·test 0) | test-only | 줄 수 추정 |
|---|---|---|---|
| root `:api` | `withAccountNo`, `findOrDefaultByUserIds`(+구현), `SYSTEM_LOAN_ID` | — | 약 15줄 |
| `:trading-core` | `MarginItem` 컴포넌트 2(필드 제거 후보), `CanonicalToken.expiresAtEpochMillis`(미확인) | 7개 메서드(`hasSufficientDepositFor`, `calcLocBuyAmount`, `isInsufficientFunds`, `isRejected`, `lastSessionOpenInstantAt`, `marketCloseAt`, `immediateClose`) | 약 60줄 |
| `:shared` | 없음 | — | 0 |

테스트까지 지우면 줄어드는 양은 test-only 7개의 단위 테스트 합 약 70회 호출(`isRejected` 48 포함). 미사용 코드의 규모는 **작다**(전체의 0.1% 미만으로 추정, 정확한 전체 줄 수 대비 비율은 미집계). 05가 지울 큰 덩어리(내부 HTTP·contract·스트림)에 비하면 메서드·필드 단위 군더더기는 거의 없다.

### 숫자 요약
- 완전 미사용: 메서드 2(포트 메서드 1 포함), 상수 1, record 컴포넌트 2(`MarginItem`), 일반 클래스 0.
- test-only 메서드 7(`BrokerApiException.isRejected` 48회 테스트 호출이 최다). 한계: 이름 기준 하한 수치.
- 통합(05)이 지우는 것과 겹치는 항목 없음(전부 trading-core·finance 도메인 내부).

### 통합(05) 뒤에도 남는 문제 목록
1. `UserSettingsPort.findOrDefaultByUserIds` 포트·구현 미사용 — `API/user/application/port/output/UserSettingsPort.java:21`, `API/user/adapter/out/persistence/settings/UserSettingsPersistenceAdapter.java:60`.
2. `FinanceAccount.withAccountNo`, `FinanceCategory.SYSTEM_LOAN_ID` 미사용 — `API/finance/domain/model/FinanceAccount.java:35`, `API/finance/domain/model/FinanceCategory.java:23`.
3. 같은 판정이 인스턴스(`isRejected`, test-only)·정적(`isRejection`) 2벌 — `TC/broker/domain/model/BrokerApiException.java:42`.
4. `DstInfo`의 세션 계산이 test-only 메서드와 `TossPriceApi` 복제 구현으로 이원화 — `TC/trading/domain/model/DstInfo.java:65,139,155`, `TC/broker/adapter/out/toss/TossPriceApi.java:163-178`.
5. test-only 도메인 메서드 — `TC/matching/domain/model/AccountBalance.java:61`, `TC/matching/domain/model/ReverseModePosition.java:42`.
6. 읽지 않는 브로커 응답 필드 — `TC/broker/domain/model/MarginItem.java:9-10`.
7. 미확인 항목 3: `CanonicalToken.expiresAtEpochMillis`(`TossTokenStore.java:27`), `Currency.JPY`(`SH/kernel/Currency.java:12`), `CryptoOrderResult.State.WATCH`(`CryptoOrderResult.java:18`) — 도구로 입력 경로를 확정하지 못했다.
