## com.kista.shared.platform (`:shared`)

### 빈 등록·구성
- 앱(root·trading-core)은 `com.kista.shared`를 컴포넌트 스캔하지 않는다. 빈은 `PlatformAutoConfiguration`의 `@Import` 목록으로만 올라간다 — **platform에 새 빈(@Component/@Configuration)을 추가하면 이 목록에도 넣는다**(누락 시 두 앱에서 조용히 빠진다). `@Import`로 등록된 빈 이름은 FQCN이다. 슬라이스 테스트 처리 → `docs/agents/testing.md`
- `ProblemDetailAutoConfiguration`(공용 advice)은 `AutoConfiguration.imports`와 `AutoConfigureWebMvc.imports` 양쪽에 올라 `@WebMvcTest` 슬라이스에도 들어간다
- `@Scheduled`를 쓰는 프로세스는 `@EnableScheduling` 필수 — 빠지면 에러 없이 조용히 무시된다(신규 부트 클래스 확인). 실행 시각·락 TTL → `docs/agents/scheduler-time-table.md`
- `UuidV7.next()`는 `@UuidGenerator`를 못 타는 native INSERT·도메인 부여 id 전용이다. DB 기본값 `gen_random_uuid()`는 v4라 native INSERT는 id를 직접 넘긴다
- `TtlResultCache`는 비-빈 인메모리 캐시이고 단일 인스턴스 전제다 — 소비자가 각자 `new`로 소유한다
- Redis Stream 컨슈머(`RedisStreamSubscriber` 상속)의 실패 정책: 유실 허용 소비자(app.error·푸시)는 기본 `ackOnFailure=true`, 정합성 소비자는 `false` + `maxDeliveries()` + `onGiveUp` 관리자 알림으로 override한다
- `internalapi`(root→trading-core 클라이언트)는 trading-core에서 참조 금지 (가드: `GradleModuleBoundaryTest.tradingCoreMustNotCallRootInternalApi`)
- `@NullMarked` record 스키마 계약 (가드: `OpenApiNullMarkedRecordContractTest`)

### 보안·필터 (SecurityConfig·JWT·내부 인증·CORS)
- **필터 이중 등록 방지**: `@Component` Filter + `addFilterBefore()` 조합은 `FilterRegistrationBean.setEnabled(false)` 필수. `SecurityConfig`에 새 Filter를 추가하면 `@Import(SecurityConfig.class)`를 쓰는 **모든** `@WebMvcTest`에도 그 Filter를 `@Import`한다(누락 시 `NoSuchBeanDefinitionException`이 다른 테스트까지 전파)
- **내부 인증**: `/api/internal/**`는 `X-Internal-Token`을 `INTERNAL_API_TOKEN`과 대조하고(`hasRole("INTERNAL")`, `InternalTokenAuthFilter`는 JWT 필터 뒤) 빈값이면 기동 실패한다. `@WebMvcTest` 패턴: `@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})` + `@TestPropertySource(properties = "internal.api.token=test-token")` + `.header("X-Internal-Token", "test-token")` (`FidaOrderControllerTest` 참고)
- **JWT(ES256)**: `JwtAuthFilter`는 principal을 `UUID`로 저장하고 catch 절은 `Exception`이다(`JwtException`만 잡으면 NPE·IAE가 익명 사용자 → 403). `JwtDecoder` @Bean은 `JwtDecoderConfig`에 분리한다(`SecurityConfig`에 두면 `JwtAuthFilter` 순환 참조로 기동 실패)
- **인증 실패 코드**: `SecurityConfig`에 `.exceptionHandling()` + `authenticationEntryPoint`를 반드시 설정한다(미설정 시 401 대신 403)
- **CORS**: `CORS_ALLOWED_ORIGINS` 빈 목록이면 기동 실패(trading-core만 비-prod 기본값 `http://localhost:3000`). allowedMethods에 **PATCH 필수**(미포함 시 전략 중지/재개 등 PATCH 403)
- **라우트 규칙 순서(첫 매치 우선)**: 공통 permitAll → `/api/internal/**` → `/api/admin/**` → 셸 기여 `SecurityRoutePolicy` → `anyRequest().authenticated()` — 셸 정책이 admin/internal을 permitAll로 뚫지 못하게. 프로세스 고유 permitAll은 `SecurityConfig`에 쓰지 말고 셸 정책으로 기여한다 (가드: `SecurityConfigOrderingTest`). permitAll 경로를 검증하는 `@WebMvcTest`는 해당 정책도 `@Import`
- **익명 폴백**: `SecurityRoutePolicy.anonymousFallbackMatchers()` 경로에서만 무효 Bearer를 401 대신 익명으로 통과시킨다(만료 AT 쿠키가 남은 비로그인 사용자의 강제 로그아웃 방지). 공개 인증 경로(로그인·갱신·로그아웃)는 넣지 않는다
- ADMIN 권한 관리(seed·`/api/admin/**`·audit_logs) → `docs/agents/modules/api/admin.md`

### AES-256 암호화 위치·컬럼 크기
- 브로커 자격증명·계좌번호·텔레그램 봇 토큰·이메일은 엔티티 컬럼에 `@Convert(converter = AesStringConverter.class)`(autoApply 없음)를 명시해 암복호화한다. 어댑터·매퍼는 평문 복사만 하고 `AesCryptoService`를 직접 쓰지 않는다(`@DataJpaTest`는 `DataJpaTestBase`가 `AesCryptoService` 빈을 import)
- AES-256 GCM + Base64는 입력 ~180자 → 출력 ~260자라 VARCHAR(255)를 넘는다. 암호화 컬럼은 Entity `length=512` 이상, Flyway도 동일
- AES-GCM은 비결정론적이라 암호화 컬럼을 쿼리 조건에 쓰면 매칭되지 않는다 — 조회는 `*_hash`(`AccountNoHasher`) 경유, 해시는 어댑터가 평문으로 계산한다
- 컨버터는 엔티티를 로드할 때마다 복호화한다 — 테스트·시드의 raw SQL INSERT는 반드시 `crypto.encrypt(...)` 암호문을 넣는다. 복호화 불가 값이 한 행이라도 있으면 그 엔티티를 읽는 모든 경로(배치 재개 포함)가 실패한다

### 시간 기준 정책 (KST 단일 기준)
- **거래일(tradeDate) = KST 일자** — 매매가 실행·정산되는 KST 아침이 속한 날. DB(`orders.trade_date`)·도메인·API 모두 같은 값, 변환 없음
- **`privacy_trade_bases.release_date` = FIDA 발행일 원본(KST)** — 거래일 아님. 발행일↔거래일(+1일)은 `PrivacyDates.releaseDateFor()/tradeDateOf()`만 사용
- 외부 원본 참조 데이터(`us_market_holidays` 등 US 달력일)는 원본 기준 유지. KST↔US 변환은 허용 어댑터 내부에서만 `UsTradeDates`로 한다 (가드: `usTradeDates_must_only_be_used_by_allowlisted_adapters`)
- Instant ↔ KST 일자 경계는 `atStartOfDay(TimeZones.KST)` 단일 관용구 — `ZoneOffset.UTC` 자정 경계 금지. 당일 포함 상한은 `KstDays.endExclusive(day)` — `plusDays(1).atStartOfDay(...)` 인라인 금지
- `ZoneId.of`는 `TimeZones` 상수(`KST`·`NY`)로만 (가드: `zoneId_of_must_only_be_called_in_TimeZones`)
- 거래일 경계: preview·수동 실행·DCA 요약·VR 재설정은 `SessionTradeDates.nextTradeDate`(그 거래일 실제 마감 30분 전 경계), 개장 배치만 `DstInfo.nextTradeDate`(`SCHEDULER_RUN_TIME = 04:30 KST` 고정)
- 현재 시각은 KST `Clock` 빈을 주입해 `X.now(clock)`으로 읽는다. 도메인·정적 유틸은 시각을 파라미터로 받는다. 테스트는 `Clock.fixed`(`FixedClockTestConfig`) (가드: `main_code_must_not_read_system_clock_directly`)
- 인라인 `.minusDays(1)`/`.plusDays(1)`로 날짜 기준 변환 금지 — `UsTradeDates`/`PrivacyDates` 경유

### JPA Auditing
- `BaseAuditEntity`(`createdAt`+`updatedAt`) 또는 `BaseCreatedAtEntity`(`createdAt`만)를 상속한다. `updated_at` 컬럼이 없는 엔티티에 `BaseAuditEntity` 금지(`ddl-auto: validate` 실패)
- 서비스에서 domain record를 만들 때 `updatedAt=null`(`@LastModifiedDate`가 채움), `createdAt`은 update면 기존 값 보존·register면 `null`
- `toEntity()`에서 `setCreatedAt()`/`setUpdatedAt()`을 호출하지 않는다(setter 없음, Spring Data Auditing이 필드에 직접 쓴다)
- `@EnableJpaAuditing`은 `@SpringBootApplication`이 아니라 `JpaAuditingConfig`에 단독 선언한다(아니면 `@WebMvcTest` `BeanCreationException`)
