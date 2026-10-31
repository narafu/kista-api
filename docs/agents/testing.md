## 테스트

### 네이밍·구성
- 단위 테스트 `*Test`, 통합 테스트 `*IT` + Docker 필요 테스트는 `@Tag("integration")` (`./gradlew integration`으로 실행)
- 테스트 지원 코드: `:shared` testFixtures(`DataJpaTestBase`/`WebMvcTestSupport`/`application-test.yml`), trading-core `TradingFixtures`·`StubBrokerApiException`, 루트 `DomainFixtures`(User). 루트 테스트는 `testFixtures(project(":shared"))`에 의존하고 trading-core testFixtures에는 의존하지 않는다
- 슬라이스 테스트(`@DataJpaTest`/`@WebMvcTest`)는 상향 탐색이 각 루트의 앱 클래스를 찾으므로 `@ContextConfiguration`을 쓰지 않는다. 예외: trading-core `SchedulerLockService{Test,DbTest}`(패키지가 `com.kista.shared.platform.scheduling`이라 `@ContextConfiguration(classes = TradingApplication.class)` 유지)
- `:shared` 빈은 `PlatformAutoConfiguration`이 올린다 — `@WebMvcTest`는 `JwtAuthFilter`/`InternalTokenAuthFilter`를 스캔으로 얻지 못해 필요 시 `@Import`하고, 보안·JWT가 필요 없는 자체 `@EnableAutoConfiguration` 테스트는 `PlatformAutoConfiguration`을 exclude한다(예: `HousingBenchmarkApiDocsTest`)
- ArchUnit `failOnEmptyShould=true`(양 프로젝트 test resources) — 규칙 대상이 비면 실패한다

### 매매·전략 테스트 패턴
- 매매 테스트 패턴 → `modules/tradingcore/trading.md` "테스트 패턴", 매칭 커널 → `modules/tradingcore/matching.md` "테스트 패턴"

### @WebMvcTest + Spring Security 패턴
- `@WebMvcTest` 슬라이스는 커스텀 `SecurityConfig`를 로드하지 않고 Boot 기본 Security가 적용된다. POST는 `.with(csrf())` 필수(없으면 403)
- **`@WithMockUser` 금지**(principal이 UserDetails라 `@AuthenticationPrincipal UUID`에서 ClassCastException) — `.with(authentication(new UsernamePasswordAuthenticationToken(UUID.fromString(uuidString), null, List.of())))`를 쓴다. principal은 항상 `UUID`
- `JwtAuthFilter`를 `@Import`하는 `@WebMvcTest`는 `@MockitoBean JwtDecoder jwtDecoder` 필수(`JwtDecoderConfig`가 슬라이스에 로드되지 않는다)
- `@SpringBootTest @ActiveProfiles("test")`는 `application-test.yml`에 `jwt.signing-key` EC JWK 필요
- `hasRole` 인가 검증이 필요한 `@WebMvcTest`는 `@Import({SecurityConfig.class, JwtAuthFilter.class})` 필수 — 없으면 ROLE 검사 없이 200이 반환된다(`AdminPingControllerTest` 참고)

### Mockito + interface default 메서드 주의
- interface의 default 메서드는 mock이 override하므로 내부에서 호출하는 메서드를 stub해도 연결되지 않는다(예: `findByIdOrThrow`는 `findById` stub을 무시) — default 메서드 자체를 stub한다(안 하면 NPE)

### Mockito 병렬 테스트 주의
- `@WebMvcTest` 클래스와 `DataJpaTestBase` 상속(DB 직결) 테스트에는 `@Execution(ExecutionMode.SAME_THREAD)` 필수 — 병렬 시 mock 오염, `Cannot start new transaction without ending existing transaction`
- `ArgumentCaptor<Map>`(raw) + `any()`는 concurrent 모드에서 오작동 → `ArgumentCaptor.forClass(Map.class)` + `@SuppressWarnings("unchecked")`

### 통합 테스트 FK 선행 행 삽입 패턴
- `*JpaRepository`는 package-private이라 다른 패키지 테스트에서 주입할 수 없다 — FK 선행 행은 `@Autowired JdbcTemplate`으로 SQL 삽입 후 `@Transactional` 롤백을 활용한다
- `users`는 root `public`, `accounts`·`strategy*`·`orders`는 trading-core `trading` 스키마라 `users`는 루트 테스트에서만 삽입하고 trading-core는 `accounts`부터 삽입한다(`accounts.user_id`에 users FK 없음). 순서는 `OrderPersistenceAdapterDbTest`, root `users`는 `UserSettingsPersistenceAdapterIT` 참고

### 테스트 DB

테스트 DB는 **docker-compose로 기동한 로컬 PostgreSQL**이다. `DataJpaTestBase` 상속 persistence 테스트도 Testcontainers가 아니라 `localhost:5432/kistadb_test`에 직접 연결된다. 실제 KIS API 통합 테스트는 **실전계좌**로 실행한다(모의투자는 지정가만 지원해 LOC/MOC 테스트 불가). `integration` 태스크(`*IT`, 행을 커밋하고 teardown으로 지운다)는 `kista.test-db-name=kistadb_it`로 별도 DB를 써서 중단된 IT 잔재가 롤백 기반 `test` 스위트의 건수 단언을 깨지 않게 한다. 로컬 최초 1회 생성: `docker exec <postgres 컨테이너> psql -U kista -d kistadb -c "CREATE DATABASE kistadb_it OWNER kista;"`

```bash
docker-compose up -d postgres   # 테스트 전 postgres 기동 필수
```

루트 persistence 테스트는 `RootDataJpaTestBase`를 상속해 엔티티 스캔이 root 범위로 좁혀진다(`:trading-core` 엔티티는 검증 대상 아님). trading-core `test` 태스크는 `workingDir = rootProject.projectDir`다.

### trading-core 테스트 벤더 타입 금지
- broker 밖 테스트는 `broker.domain.model.kis/toss` 벤더 타입을 쓰지 않는다(가드 `TradingCoreTestVendorModelTest`) — 외부 API 실패는 `StubBrokerApiException`으로 만든다

### JPA 엔티티 저장 패턴
- UUID PK 엔티티(`@UuidGenerator(style = UuidGenerator.Style.VERSION_7)` — 시간순 v7, `@GeneratedValue`는 `HexagonalArchitectureTest`가 금지) 저장 시 도메인 모델의 `id`는 반드시 `null` — non-null UUID 전달 시 Spring Data JPA가 `merge()` 호출 → `StaleObjectStateException` 발생
- `@Transactional` 테스트 내에서 `insertable=false, updatable=false` 필드(예: `createdAt`)는 DB DEFAULT 값이 JPA 1차 캐시에 반영되지 않음 → 해당 필드 `isNotNull()` 단언 금지

### record 필드 수정 시 주의
- record 필드 변경 영향은 string 값 grep이 아니라 `compileTestJava`로 검증한다(테스트가 임의 값 `"preOpen"` 등을 쓰는 경우를 grep이 놓친다)
