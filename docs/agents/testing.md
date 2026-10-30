## 테스트

### 네이밍·구성
- 단위 테스트 `*Test`, 통합 테스트 `*IT` + Docker 필요 테스트는 `@Tag("integration")` (`./gradlew integration`으로 실행)
- 테스트 지원 코드: `com.kista.shared.support`(`DataJpaTestBase`/`WebMvcTestSupport`/`application-test.yml`)는 `:shared` testFixtures, `com.kista.tradingcore.support`(`TradingFixtures`는 trading-core testFixtures, `StubBrokerApiException`은 trading-core test), `com.kista.api.support.DomainFixtures`(User)는 루트 `src/testFixtures`. 루트 테스트는 `testFixtures(project(":shared"))`에 의존하고 trading-core testFixtures에는 의존하지 않는다
- 슬라이스 테스트(`@DataJpaTest`/`@WebMvcTest`)는 `@SpringBootConfiguration` 상향 탐색이 각 루트의 `ApiApplication`/`TradingApplication`을 찾으므로 `@ContextConfiguration`·`TradingCoreJpaTestConfig`를 쓰지 않는다. 예외는 trading-core `SchedulerLockService{Test,DbTest}`(패키지 `com.kista.shared.platform.scheduling` — package-private 접근용이라 상향 탐색이 앱 클래스에 닿지 않아 `@ContextConfiguration(classes = TradingApplication.class)` 유지, `scheduler_locks`는 trading 마이그레이션이 만든다)
- `:shared` 빈은 `PlatformAutoConfiguration`이 올린다 — `@WebMvcTest`는 `JwtAuthFilter`/`InternalTokenAuthFilter`를 스캔으로 얻지 못해 필요 시 `@Import`하고, 자체 `@EnableAutoConfiguration` 컨텍스트 테스트는 보안·JWT가 필요 없으면 `PlatformAutoConfiguration`을 exclude한다(예: `HousingBenchmarkApiDocsTest`)
- ArchUnit `failOnEmptyShould=true`(양 프로젝트 test resources) — 규칙 대상이 비면 실패한다. 컨텍스트 로드 잠금: `com.kista.api.ApplicationContextLoadTest`(리포지토리 21·`SecurityFilterChain` 1·`com.kista.tradingcore` 빈 없음), `com.kista.tradingcore.TradingApplicationContextLoadTest`(리포지토리 18·`SecurityFilterChain` 1)

### static 필드 forward reference 주의
- Mockito 테스트 클래스에서 static 상수가 다른 static 상수를 참조할 때 선언 순서 중요 — `CYCLE.id()`를 참조하는 `NORMAL_HISTORY` 등은 반드시 `CYCLE` 선언 뒤에 위치해야 함 (위반 시 `illegal forward reference` 컴파일 오류)

### TradingService execute() 가격 주입 패턴
- `execute(strategy, tradingAccount, userProfile, DstInfo)`는 단건 래퍼로 `executeBatch`에 위임한다 — 현재가는 `TradingPriceFetcher`가 `BrokerPricePort`로 일괄 조회하므로 테스트는 `BrokerPricePort` mock을 stub한다. 배치 경로 테스트의 계좌는 `TradingFixtures.tradingAccount(id, userId)`(=`TradingAccount.from(kisAccount(...))`)·`tossTradingAccount`를 쓰고, 브로커 포트 stub 매처는 `tradingAccount.brokerRef()`를 쓴다. 소유권 검증 경로(`AccountPort.requireOwnedAccount` 등)만 `kisAccount`(Account)를 유지
- `executeBatch(List, DstInfo)` package-private 오버로드 — DstInfo 직접 주입으로 sleep 우회

### @WebMvcTest + Spring Security 패턴
- `@WebMvcTest` 슬라이스에서 커스텀 `SecurityConfig`가 로드되지 않음 — Spring Boot 기본 Security 적용
- POST 요청 테스트: `.with(csrf())` 추가 필수 (없으면 403) — `import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf`
- **`@WithMockUser` 사용 금지** (principal이 UserDetails → `@AuthenticationPrincipal UUID`로 바인딩 시 ClassCastException) — 대신 `.with(authentication(new UsernamePasswordAuthenticationToken(UUID.fromString(uuidString), null, List.of())))` 패턴 사용
- `JwtAuthFilter`는 principal을 `UUID` 타입으로 저장 — 테스트 mock도 반드시 `UUID` 사용 (`String` 사용 시 컨트롤러에서 ClassCastException)
- JwtAuthFilter를 `@Import`하는 `@WebMvcTest`: `@MockitoBean JwtDecoder jwtDecoder` 필수 — `JwtDecoderConfig`가 슬라이스 컨텍스트에 자동 로드되지 않으므로
- `@SpringBootTest @ActiveProfiles("test")`: `application-test.yml`에 `jwt.signing-key` EC JWK 추가 필요 (`JwtDecoderConfig` 단일 빈이 이 값으로 검증)
- role 기반 인가 규칙(`hasRole`) 검증이 필요한 `@WebMvcTest`는 `@Import({SecurityConfig.class, JwtAuthFilter.class})` 추가 필수 — 미추가 시 `AuthorizationFilter`가 로드되지 않아 ROLE 검사 없이 200 반환 (`AdminPingControllerTest` 패턴 참고)

### 서비스/컨트롤러 필드 추가 시 테스트 동기화
- 서비스에 `private final` 필드 추가 → 해당 테스트에 `@Mock` 추가 필수 (누락 시 NPE)
- 컨트롤러에 새 UseCase 필드 추가 → `*ControllerTest`에 `@MockitoBean` 추가 필수 (누락 시 `ApplicationContext` 로드 실패)

### Mockito + interface default 메서드 주의
- interface의 default 메서드는 Mockito mock이 override — 내부에서 호출하는 기존 메서드를 stub해도 연결 안 됨
- 예: `findByIdOrThrow`(default)는 내부적으로 `findById`를 호출하지만, `when(repo.findById(...))` stub 무시됨
- 반드시 `when(repo.findByIdOrThrow(...)).thenReturn(...)` 직접 stub — 실패 시 NPE(null 반환)로 나타남

### Mockito 병렬 테스트 주의
- `@WebMvcTest` 클래스에 `@Execution(ExecutionMode.SAME_THREAD)` 필수 — 병렬 실행 시 mock이 다른 테스트에 오염됨
- `DataJpaTestBase` 상속(DB 직결) 테스트도 `@Execution(ExecutionMode.SAME_THREAD)` 필수 — JUnit 병렬 모드에서 `Cannot start new transaction without ending existing transaction` 발생
- `ArgumentCaptor<Map>` (raw) + `any()` — concurrent 모드에서 오작동 → `ArgumentCaptor.forClass(Map.class)` + `@SuppressWarnings("unchecked")` 사용
- `AccountBalance(q>0, 전반)` 잔고는 전략 계산 시 최대 4건 (LOC매수×2 + LOC매도 + 지정가매도)

### 통합 테스트 FK 선행 행 삽입 패턴
- `*JpaRepository`는 package-private → 다른 패키지의 테스트에서 직접 주입 불가. FK 선행 행은 `@Autowired JdbcTemplate`으로 직접 SQL 삽입 후 `@Transactional` 롤백 활용
- 서비스별 스키마가 분리돼(`users`=root `public`, `accounts`·`strategy*`·`orders`=trading-core `trading`) `users` 행은 루트 테스트에서만, trading-core 테스트는 `accounts`부터 삽입한다 — `accounts.user_id`에 users FK가 없다. 선행 행 순서·컬럼은 `OrderPersistenceAdapterDbTest`(accounts → strategy → strategy_version → strategy_cycle) 참고, root 쪽 `users` 삽입은 `UserSettingsPersistenceAdapterIT` 참고

### 테스트 DB

`application-test.yml` 기준 테스트 DB는 **docker-compose로 기동한 로컬 PostgreSQL** 사용. `DataJpaTestBase`를 상속한 persistence 테스트도 Testcontainers가 아니라 `localhost:5432/kistadb_test`에 직접 연결된다. 실제 KIS API 통합 테스트는 **실전계좌**로 실행 (모의투자 계좌는 지정가 주문만 지원해 LOC/MOC 테스트 불가). 단 `integration` 태스크(`*IT`, 행을 커밋하고 teardown으로 지운다)는 `kista.test-db-name=kistadb_it` 시스템 프로퍼티로 별도 DB `kistadb_it`를 쓴다 — 동시 실행·중단된 IT 잔재가 롤백 기반 `test` 스위트의 건수 단언을 깨지 않게 하기 위해서다. 로컬 최초 1회 생성: `docker exec <postgres 컨테이너> psql -U kista -d kistadb -c "CREATE DATABASE kistadb_it OWNER kista;"`

```bash
docker-compose up -d postgres   # 테스트 전 postgres 기동 필수
```

서비스별 Flyway baseline이 각자 자기 스키마에 적용된다(root `db/migration`→`public`/`finance`/`kista_ref`, trading-core `db/migration-trading`→`trading`/`trading_ref`). 옛 레이아웃(`kista`/`reference` 스키마 + 공용 `flyway_schema_history`)으로 만들어진 `kistadb_test`가 로컬에 남아 있으면 baseline 판정이 어긋나므로 재생성한다: `DROP DATABASE kistadb_test; CREATE DATABASE kistadb_test OWNER kista;` (`kistadb_it`도 동일). trading-core `test` 태스크는 `workingDir = rootProject.projectDir`로 실행된다.

루트 persistence 테스트는 `com.kista.api.web.RootDataJpaTestBase`(src/test)를 상속한다 — `:shared` 공용 `DataJpaTestBase`를 잇는 얇은 베이스로, 상향 탐색이 `ApiApplication`(루트 `com.kista.api`)을 찾아 엔티티·리포지토리 스캔이 root 범위로 좁혀지므로 classpath의 `:trading-core` 엔티티(`trading.xxx` 테이블)는 검증 대상이 아니다. 옛 `RootJpaScanConfig`는 없다.

### trading-core 테스트 벤더 타입 금지
- trading-core 테스트도 `broker.domain.model.kis/toss` 벤더 타입을 쓰지 않는다(`TradingCoreTestVendorModelTest`, broker 모듈 테스트 제외) — broker 밖 테스트의 외부 API 실패는 `com.kista.tradingcore.support.StubBrokerApiException`(벤더 중립 `BrokerApiException` 최소 서브클래스)으로 만든다

### 전략 테스트 분리 원칙
- `InfinitePositionTest` (`com.kista.tradingcore.matching.domain.model`): 매매 변수 계산 검증 (averagePrice, currentRound, priceOffsetRate 등)
- `InfiniteStrategyTypeTest` (`com.kista.tradingcore.matching.domain.strategy`): 주문 생성 시나리오만 검증 (buildOrders 반환 Order 목록)

### InfiniteStrategy 테스트 패턴
- `currentRound`(double) 단언: 정확한 정수 결과는 `isEqualTo(5.0)`, 소수점은 `isCloseTo(1.33, within(0.01))`
- `TradingServiceTest`는 `when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))` 패턴 사용
- `TradingService` 단위 테스트는 실제 `StrategyLedgerBudget(cyclePositionPort, orderPort, cycleOrderStrategies)`를 주입하고 원장은 `cyclePositionPort.findLatestOne(cycleId)`로 stub한다(미stub이면 `StrategyCycle.startAmount()` 폴백이라 의도한 원장이 아닐 수 있다). 계획 단계는 브로커 잔고·판매가능수량을 조회하지 않으므로 `verifyNoInteractions(sellableQuantityPort)`로 잠근다. 계좌별 병렬 실행은 `TradingCandidatePlanner`가 맡는다 — `TradingCandidatePlanner`(마지막 생성자 인자)와 `TradingService` 양쪽에 `new TradingParallelRunner(0)`(순차 인라인 모드)을 주입해 기존 테스트의 결정적 호출 순서를 보존한다 — 병렬 의미론(동시 상한·그룹 격리·순서 보존·인터럽트 승격)은 별도 `TradingParallelRunnerTest`가 검증한다.
- scheduler leg recovery 테스트는 `Order.withLeg(...)`로 concrete leg를 명시한다. leg 없는 과거(`Order.UNKNOWN_LEG`) 주문은 coarse 슬롯 처리가 없으므로 슬롯·skip 테스트 픽스처로 쓰지 말고, 재주문은 UNKNOWN 원본을 거절하는지만 검증한다.
- INFINITE compute skip 테스트는 correction 포함 complete concrete leg와 partial concrete leg를 모두 둔다. complete는 `buildOrders(...)` 미호출, partial은 호출을 검증해 누락 leg 복구가 막히지 않게 한다. 개장 스케쥴러 회귀는 기존 `AT_CLOSE` BUY만 있을 때 `AT_OPEN` SELL 복구가 막히지 않는지도 검증한다. VR/PRIVACY는 variable ladder라 concrete compute skip을 가정하지 않는다.
- cap 예산 회귀 테스트는 `buildCappedBuyOrders(...)`가 반환하는 base+correction BUY 총액이 원본 BUY보다 커지는 경우를 만들고, `StrategyLedgerBudget`이 최종 총액으로 BUY를 판정하는지 검증한다. 보정 축소(`fitBuysToBudget`) 후에도 base가 원장 잔여를 넘을 때만 거절하고, 축소로 담기면 보정을 덜어낸 BUY가 저장되는지 함께 둔다. VR은 수량을 줄인 새 인스턴스가 원본 BUY 자리에 저장되는지(equals 대조로 조용히 빠지지 않는지) 실제 `VrCycleOrderStrategy`로 검증한다.
- 원장 거절 시 SELL은 유지되고 원래 순서가 보존되는지, 기존 주문이 없는 사이클은 접수·포지션 저장(`cycleHistoryPort.save`)·리포트가 호출되지 않는지, 기존 PLANNED/PLACED 주문이 있으면 후속 처리를 유지하는지 검증한다.
- 배치 실패 격리 테스트는 서로 다른 account의 context를 함께 전달하고 원장 조회·`saveAll`·잔고 부족 알림을 각각 예외 처리해 성공 계좌의 저장·접수와 `TradingErrorEvent` 발행(`eventPublisher.publishEvent`)을 함께 검증한다.
- 신규 양방향 거절 또는 저장 실패 시 기존 주문이 없는 사이클은 접수·포지션 저장·리포트가 호출되지 않아야 하며, 기존 PLANNED/PLACED 주문이 있는 사이클은 후속 처리를 유지해야 한다.
- 주식 DCA BUY 가용 테스트: 커널(`DcaCycleOrderStrategyTest`)은 `availableCash` 직접 주입으로 PARTIAL 축소(캡가 기준)·REJECT·null=DATA_UNAVAILABLE·`plan()` 앞 BUY 누적을, 수집기(`DcaPlanInputCollectorTest`)는 `UnassignedUsdCalculator` mock으로 자기 PLANNED/SUBMITTING만 차감·전략당 1회 조회·실패 skip·MOCK 무조회를 검증한다. 주식 BUY를 쓰는 기존 커널·플래너 테스트는 `DcaCycleOrderStrategy.UNLIMITED_CASH`를 넘겨 상한을 끈다. 미배정 USD 계산(`UnassignedUsdCalculatorTest`)은 시드 점유 전략만 빼고 DCA는 제외하는 경계를 잠근다.
- DCA SELL 규칙 테스트(`DcaPlanInputCollectorTest`)는 브로커 판매가능수량을 쓰지 않는다 — 원장 보유에서 `existingOrders`의 PLANNED/PLACED SELL 수량을 뺀 값이 규칙 입력이 되는 경계(원장 초과 REJECT/PARTIAL 포함)를 검증한다.
- `AccountBalance` 테스트 데이터: `usdDeposit = 통합주문가능금액(현금 대용)`; quantity=0이면 usdDeposit만 의미 있음

### JPA 엔티티 저장 패턴
- UUID PK 엔티티(`@UuidGenerator(style = UuidGenerator.Style.VERSION_7)` — 시간순 v7, `@GeneratedValue`는 `HexagonalArchitectureTest`가 금지) 저장 시 도메인 모델의 `id`는 반드시 `null` — non-null UUID 전달 시 Spring Data JPA가 `merge()` 호출 → `StaleObjectStateException` 발생
- `@Transactional` 테스트 내에서 `insertable=false, updatable=false` 필드(예: `createdAt`)는 DB DEFAULT 값이 JPA 1차 캐시에 반영되지 않음 → 해당 필드 `isNotNull()` 단언 금지

### record 필드 수정 시 주의
- 필드에 들어가는 string 값을 grep하면 테스트에서 임의 값(예: `"preOpen"`, `"correction"`)을 쓰는 케이스를 누락할 수 있음 → string 값 grep보다 `compileTestJava`로 검증하는 것이 신뢰성 높음
- `KisOrderApiTest`는 `Order` 객체를 생성자로 직접 생성 — `Order` record 변경 시 반드시 확인
