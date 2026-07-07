## 테스트

### 네이밍·구성
- 단위 테스트 `*Test`, 통합 테스트 `*IT` + Docker 필요 테스트는 `@Tag("integration")` (`./gradlew integration`으로 실행)
- 테스트 지원 코드는 `src/test/java/com/kista/support`

### static 필드 forward reference 주의
- Mockito 테스트 클래스에서 static 상수가 다른 static 상수를 참조할 때 선언 순서 중요 — `CYCLE.id()`를 참조하는 `NORMAL_HISTORY` 등은 반드시 `CYCLE` 선언 뒤에 위치해야 함 (위반 시 `illegal forward reference` 컴파일 오류)

### TradingService execute() 가격 주입 패턴
- `execute(cycle, account, user, DstInfo)` — price=null로 전달 (lazy getPrice 없음)
- holdings=0 && price=null → `IllegalStateException("현재가 조회 실패")` — holdings=0 테스트는 `executeBatch` 경로 사용, `getPrices` stub으로 주입
- `executeBatch(List, DstInfo)` package-private 오버로드 — DstInfo 직접 주입으로 sleep 우회

### @WebMvcTest + Spring Security 패턴
- `@WebMvcTest` 슬라이스에서 커스텀 `SecurityConfig`가 로드되지 않음 — Spring Boot 기본 Security 적용
- POST 요청 테스트: `.with(csrf())` 추가 필수 (없으면 403) — `import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf`
- **`@WithMockUser` 사용 금지** (principal이 UserDetails → `@AuthenticationPrincipal UUID`로 바인딩 시 ClassCastException) — 대신 `.with(authentication(new UsernamePasswordAuthenticationToken(UUID.fromString(uuidString), null, List.of())))` 패턴 사용
- `JwtAuthFilter`는 principal을 `UUID` 타입으로 저장 — 테스트 mock도 반드시 `UUID` 사용 (`String` 사용 시 컨트롤러에서 ClassCastException)
- JwtAuthFilter를 `@Import`하는 `@WebMvcTest`: `@MockBean JwtDecoder jwtDecoder` 필수 — `JwtDecoderConfig`가 슬라이스 컨텍스트에 자동 로드되지 않으므로
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
- `ArgumentCaptor<Map>` (raw) + `any()` — concurrent 모드에서 오작동 → `ArgumentCaptor.forClass(Map.class)` + `@SuppressWarnings("unchecked")` 사용
- `AccountBalance(q>0, 전반)` 잔고는 전략 계산 시 최대 4건 (LOC매수×2 + LOC매도 + 지정가매도)

### 통합 테스트에서 타 패키지 FK 삽입 패턴
- `AccountJpaRepository`·`UserJpaRepository`는 package-private → `trade` 등 다른 패키지의 `@SpringBootTest`에서 직접 주입 불가
- FK 제약이 필요한 선행 행은 `@Autowired JdbcTemplate`으로 직접 SQL 삽입 후 `@Transactional` 롤백 활용:
  ```java
  jdbcTemplate.update("INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())", userId, "kakao_" + userId, "ACTIVE", "USER");
  jdbcTemplate.update("INSERT INTO accounts (id, user_id, nickname, account_no, app_key, secret_key, kis_account_type, broker, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())", accountId, userId, "테스트계좌", "74420614", "key", "secret", "01", "KIS");
  ```
- 타 패키지 FK 삽입 패턴: `OrderPersistenceAdapterTest` 참고 (TradeHistoryPersistenceAdapterTest 삭제됨)

### 테스트 DB

`application-test.yml` 기준 테스트 DB는 **docker-compose로 기동한 로컬 PostgreSQL** 사용. `DataJpaTestBase`를 상속한 persistence 테스트도 Testcontainers가 아니라 `localhost:5432/kistadb_test`에 직접 연결된다. 실제 KIS API 통합 테스트는 **실전계좌**로 실행 (모의투자 계좌는 지정가 주문만 지원해 LOC/MOC 테스트 불가).

```bash
docker-compose up -d postgres   # 테스트 전 postgres 기동 필수
```

### 전략 테스트 분리 원칙
- `InfinitePositionTest` (`domain/model`): 매매 변수 계산 검증 (averagePrice, currentRound, priceOffsetRate 등)
- `InfiniteStrategyTypeTest` (`domain/strategy`): 주문 생성 시나리오만 검증 (buildOrders 반환 Order 목록)

### InfiniteStrategy 테스트 패턴
- `currentRound`(double) 단언: 정확한 정수 결과는 `isEqualTo(5.0)`, 소수점은 `isCloseTo(1.33, within(0.01))`
- `TradingServiceTest`는 `when(tradingStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))` 패턴 사용
- `AccountBalance` 테스트 데이터: `usdDeposit = 통합주문가능금액(현금 대용)`; quantity=0이면 usdDeposit만 의미 있음

### JPA 엔티티 저장 패턴
- `@GeneratedValue(strategy = GenerationType.UUID)` 엔티티 저장 시 도메인 모델의 `id`는 반드시 `null` — non-null UUID 전달 시 Spring Data JPA가 `merge()` 호출 → `StaleObjectStateException` 발생
- `@Transactional` 테스트 내에서 `insertable=false, updatable=false` 필드(예: `createdAt`)는 DB DEFAULT 값이 JPA 1차 캐시에 반영되지 않음 → 해당 필드 `isNotNull()` 단언 금지

### record 필드 수정 시 주의
- 필드에 들어가는 string 값을 grep하면 테스트에서 임의 값(예: `"preOpen"`, `"correction"`)을 쓰는 케이스를 누락할 수 있음 → string 값 grep보다 `compileTestJava`로 검증하는 것이 신뢰성 높음
- `KisOrderApiTest`는 `Order` 객체를 생성자로 직접 생성 — `Order` record 변경 시 반드시 확인
