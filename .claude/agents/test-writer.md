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
// Spring Boot 3.4+: @MockitoBean 권장
@MockitoBean
private SomeUseCase someUseCase;
// 컨트롤러에 새 필드 추가 시 여기도 반드시 추가
```

### 병렬 실행 방지
```java
@Execution(ExecutionMode.SAME_THREAD)  // 클래스 레벨 필수
```

## @SpringBootTest 패턴

### 타 패키지 FK 삽입 (JpaRepository package-private 우회)
```java
@Autowired JdbcTemplate jdbcTemplate;

// users 먼저 삽입
jdbcTemplate.update(
    "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
    userId, "kakao_" + userId, "ACTIVE", "USER");
// accounts 삽입
jdbcTemplate.update(
    "INSERT INTO accounts (id, user_id, nickname, account_no, app_key, secret_key, kis_account_type, broker, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
    accountId, userId, "테스트계좌", "74420614", "key", "secret", "01", "KIS");
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
private RealtimeNotificationPort realtimeNotificationPort; // 누락 시 NPE
```

## TradingService 테스트 특이사항

- `holdings=0` (신규 계좌) 테스트 → `executeBatch` 경로 필수 (`getPrices` stub으로 price 주입)
- `portfolioSnapshotPort.save()`는 `price != null` 조건 가드 → 단건 경로에서 `verify(…, never()).save(any())` 정상
- `executeBatch(List, DstInfo)` package-private 오버로드로 DST 대기 우회 가능

## 테스트 DB

통합 테스트 전 postgres 기동 필수:
```bash
docker compose up -d postgres
```

`application-test.yml`: `jdbc:postgresql://localhost:5432/kistadb` (kista/kista)

## 테스트 실행

```bash
bash gradlew test --tests "com.kista.domain.*"      # 도메인 단위 테스트
bash gradlew test --tests "com.kista.architecture.*" # ArchUnit
bash gradlew test --tests "com.kista.SomeTest"       # 단일 테스트
```

실패 진단 (XML이 stdout보다 신뢰성 높음):
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```
