## 테스트

### 네이밍·구성
- 단위 `*Test`, 통합 `*IT` + Docker 필요 테스트는 `@Tag("integration")`(`./gradlew integration`)
- 지원 코드: `:shared` testFixtures(`DataJpaTestBase`/`WebMvcTestSupport`/`application-test.yml`), trading-core `TradingFixtures`·`StubBrokerApiException`, 루트 `DomainFixtures`(User). 루트 테스트는 `:shared` testFixtures에만 의존한다(trading-core testFixtures 금지)
- 슬라이스 테스트(`@DataJpaTest`/`@WebMvcTest`)는 상향 탐색으로 앱 클래스를 찾으므로 `@ContextConfiguration`을 쓰지 않는다. 예외: trading-core `SchedulerLockService{Test,DbTest}`(패키지가 `com.kista.shared.platform.scheduling`이라 `@ContextConfiguration(classes = TradingApplication.class)`)
- `:shared` 빈은 `PlatformAutoConfiguration`이 올린다. `@WebMvcTest`는 `JwtAuthFilter`/`InternalTokenAuthFilter`가 필요하면 `@Import`하고, 보안이 필요 없는 자체 `@EnableAutoConfiguration` 테스트는 `PlatformAutoConfiguration`을 exclude한다(예: `HousingBenchmarkApiDocsTest`)
- Clock: `DataJpaTestBase`가 `FixedClockTestConfig`(고정 KST)를 포함한다. `@WebMvcTest`는 필요하면 `@Import(FixedClockTestConfig.class)`
- ArchUnit `failOnEmptyShould=true` — 규칙 대상이 비면 실패한다
- 매매 테스트 패턴 → `modules/tradingcore/trading-testing.md`, 매칭 커널 → `modules/tradingcore/matching.md` "테스트 패턴"
- broker 밖 trading-core 테스트는 `broker.domain.model.kis/toss` 벤더 타입을 쓰지 않는다. 외부 API 실패는 `StubBrokerApiException`(가드: `tests_outside_broker_must_not_use_vendor_models`)

### @WebMvcTest + Spring Security
- 슬라이스는 커스텀 `SecurityConfig` 대신 Boot 기본 Security가 적용된다. POST는 `.with(csrf())` 필수(없으면 403)
- **`@WithMockUser` 금지**(principal이 UserDetails라 `@AuthenticationPrincipal UUID`에서 ClassCastException). `.with(authentication(new UsernamePasswordAuthenticationToken(UUID.fromString(uuid), null, List.of())))`, principal은 항상 `UUID`
- `JwtAuthFilter`를 `@Import`하면 `@MockitoBean JwtDecoder jwtDecoder` 필수. `hasRole` 인가 검증은 `@Import({SecurityConfig.class, JwtAuthFilter.class})` 필수(없으면 ROLE 검사 없이 200 — `AdminPingControllerTest` 참고)
- `@SpringBootTest @ActiveProfiles("test")`는 `application-test.yml`에 `jwt.signing-key` EC JWK가 필요하다

### Mockito 주의
- interface default 메서드는 mock이 override한다. 내부에서 부르는 메서드를 stub해도 연결되지 않으므로 default 메서드 자체를 stub한다(예: `findByIdOrThrow`, 안 하면 NPE)
- `@WebMvcTest`와 `DataJpaTestBase` 상속 테스트는 `@Execution(ExecutionMode.SAME_THREAD)` 필수(병렬 시 mock 오염, `Cannot start new transaction`)
- `ArgumentCaptor<Map>`(raw) + `any()`는 concurrent 모드에서 오작동 → `ArgumentCaptor.forClass(Map.class)` + `@SuppressWarnings("unchecked")`

### 테스트 DB
docker-compose 로컬 PostgreSQL(`localhost:5432/kistadb_test`)에 직접 연결한다(Testcontainers 아님, `docker compose up -d postgres` 선행). `integration` 태스크(`*IT`, 행을 커밋하고 teardown으로 지움)는 `kistadb_it` 별도 DB — 최초 1회 `docker exec <postgres> psql -U kista -d kistadb -c "CREATE DATABASE kistadb_it OWNER kista;"`. 실제 증권사 API 통합 테스트(`*LiveIT`)는 실전계좌로 돈다. 루트 persistence 테스트는 `RootDataJpaTestBase`(root 엔티티만 스캔), trading-core `test` 태스크의 `workingDir`은 `rootProject.projectDir`.
- `*JpaRepository`는 package-private이라 FK 선행 행은 `@Autowired JdbcTemplate`으로 삽입하고 `@Transactional` 롤백을 쓴다. `users`는 root `public`이라 루트 테스트에서만, trading-core는 `accounts`부터 삽입한다(`accounts.user_id`에 FK 없음). 순서는 `OrderPersistenceAdapterDbTest`·`UserSettingsPersistenceAdapterIT` 참고

### JPA·record
- UUID PK 엔티티를 저장할 때 도메인 모델 `id`는 반드시 `null`(non-null이면 `merge()` → `StaleObjectStateException`)
- `@Transactional` 테스트에서 `insertable=false, updatable=false` 필드(예: `createdAt`)는 DB DEFAULT가 1차 캐시에 반영되지 않아 `isNotNull()` 단언 금지
- record 필드 변경 영향은 문자열 grep이 아니라 `compileTestJava`로 검증한다
