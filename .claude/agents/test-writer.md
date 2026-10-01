---
name: test-writer
description: kista-api 테스트 작성 전문 에이전트. @WebMvcTest/@SpringBootTest 패턴, UUID principal 인증, JdbcTemplate FK 삽입, Mockito 주의사항을 적용해 올바른 테스트를 생성한다.
---

# Test Writer

kista-api 테스트 작성 시 아래 패턴을 반드시 준수한다.

## @WebMvcTest 필수 패턴

### 인증 mock
```java
// UUID principal 필수 — @WithMockUser 사용 금지 (ClassCastException)
.with(authentication(new UsernamePasswordAuthenticationToken(
    UUID.fromString("..."), null, List.of())))

// POST/PATCH/DELETE: csrf() 필수
mockMvc.perform(post("/api/...").with(csrf()).with(authentication(...)))
```

### SecurityConfig 로드 (role 검증 필요 시)
```java
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-token")
```

### MockBean
```java
// Spring Boot 4: @MockitoBean 사용 (코드베이스는 @MockBean 미사용)
@MockitoBean
private SomeUseCase someUseCase;
// 컨트롤러에 새 필드 추가 시 여기도 반드시 추가
```

### 병렬 실행 방지
```java
@Execution(ExecutionMode.SAME_THREAD)  // 클래스 레벨 필수
```

## @SpringBootTest / @DataJpaTest 패턴

### 타 패키지 FK 삽입 (JpaRepository package-private 우회)
서비스별 스키마가 분리돼 있다 — `accounts`는 trading 스키마(`:trading-core`)라 root `users`를 참조하는 FK가 없다. trading-core 테스트는 users 삽입 없이 accounts만 넣는다 (`CyclePositionPersistenceAdapterTest` 참고). 테스트 지원 클래스는 `trading-core/src/testFixtures/java/com/kista/support`(`DataJpaTestBase`/`WebMvcTestSupport`/`TradingFixtures`).
```java
@Autowired JdbcTemplate jdbcTemplate;

jdbcTemplate.update(
    "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
    accountId, userId, "테스트계좌", "KIS", "74420614", null, "key", "secret");
```

## Mockito 주의사항

### interface default 메서드 stub
```java
// findByIdOrThrow는 default 메서드 → findById stub 무효
// 반드시 직접 stub:
when(strategyPort.findByIdOrThrow(id)).thenReturn(strategy);
// NOT: when(strategyPort.findById(id)).thenReturn(Optional.of(strategy));
```

### static 필드 선언 순서
다른 static 상수를 참조하는 상수는 반드시 참조 대상 뒤에 선언:
```java
static final StrategyCycle CYCLE = ...;
static final CyclePositionHistoryEntry HISTORY = new CyclePositionHistoryEntry(CYCLE.id(), ...); // CYCLE 뒤에
```

### @InjectMocks 서비스 필드 추가 시
서비스에 `private final` 필드 추가 → 해당 테스트에 `@Mock` 추가 필수:
```java
@Mock
private TradingRealtimeNotificationPort realtimeNotificationPort; // 누락 시 NPE
```

## TradingService 테스트 특이사항

- `holdings=0` (신규 계좌) 테스트 → `executeBatch` 경로 필수 (`getPrices` stub으로 price 주입)
- `executeBatch(List, DstInfo)` package-private 오버로드로 DST 대기 우회 가능

## 테스트 DB

통합 테스트 전 postgres 기동 필수:
```bash
docker compose up -d postgres
```

`application-test.yml`(`trading-core/src/testFixtures/resources`): `jdbc:postgresql://localhost:5432/kistadb_test`

## 테스트 실행

```bash
bash gradlew test --tests "com.kista.architecture.*"                # ArchUnit (root)
bash gradlew :trading-core:test --tests "com.kista.trading.domain.*" # trading 도메인 단위 테스트
bash gradlew :trading-core:test --tests "com.kista.SomeTest"         # trading-core 단일 테스트 (root 테스트는 :trading-core: 접두사 없이)
```

실패 진단 (XML이 stdout보다 신뢰성 높음):
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```
