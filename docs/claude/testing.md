## 테스트

### @WebMvcTest MockBean 주의
- `@MockBean` (Spring Boot 3.4+)은 deprecated → 대안: `@MockitoBean` 사용 권장 (경고는 기능 무관, 당장은 무시 가능)

### @WebMvcTest + Spring Security 패턴
- `@WebMvcTest` 슬라이스에서 커스텀 `SecurityConfig`가 로드되지 않음 — Spring Boot 기본 Security 적용
- POST 요청 테스트: `.with(csrf())` 추가 필수 (없으면 403) — `import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf`
- 인증 필요 엔드포인트 테스트: 클래스 또는 메서드에 `@WithMockUser` 추가 (없으면 401)
- `/telegram/webhook` 같은 permitAll 경로도 `@WebMvcTest`에서는 `@WithMockUser` + `csrf()` 필요

### Mockito 병렬 테스트 주의
- `ArgumentCaptor<Map>` (raw) + `any()` 조합은 JUnit 5 concurrent 모드에서 오작동 → `ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class)` + `any(String.class)` + `@SuppressWarnings("unchecked")` 사용
- `AccountBalance(q>0, 전반)` 잔고는 전략 계산 시 최대 4건(LOC매수×2 + LOC매도 + 지정가매도) — `kisOrderPort.place()` 호출 횟수 주의
- `TelegramAdapterTest`는 `new TradingVariables(...)` 생성자를 하드코딩 — `TradingVariables` 필드 추가 시 해당 테스트도 반드시 수정
- `AccountBalance` 생성자 직접 사용: `SoxlDivisionStrategyTest`, `TelegramAdapterTest`, `TradingServiceTest` — 필드 변경 시 3개 모두 수정

### 테스트 DB

통합 테스트는 **docker-compose로 기동한 로컬 PostgreSQL** 사용. 실제 KIS API 통합 테스트는 **실전계좌**로 실행 (모의투자 계좌는 지정가 주문만 지원해 LOC/MOC 테스트 불가).

```bash
docker-compose up -d postgres   # 테스트 전 postgres 기동 필수
```

`application-test.yml`: `jdbc:postgresql://localhost:5432/kistadb` (kista/kista)

### SoxlDivisionStrategy 테스트 패턴
- `currentRound`(double) 단언: 정확한 정수 결과는 `isEqualTo(5.0)`, 소수점은 `isCloseTo(1.33, within(0.01))`
- `priceOffsetRate` 기대값: scale=2 반올림된 currentRound 기준으로 계산 (T=200/150=1.33 → 0.1734, not 0.1733)
- `TradingServiceTest`는 `new SoxlDivisionStrategy().calculate()`로 vars 생성 — 전략 변경 시 자동 반영, 별도 수정 불필요
- `AccountBalance` 테스트 데이터: `usdDeposit = 통합주문가능금액(현금 대용)`; quantity=0이면 usdDeposit만 의미 있음

### JPA 엔티티 저장 패턴
- `@GeneratedValue(strategy = GenerationType.UUID)` 엔티티 저장 시 도메인 모델의 `id`는 반드시 `null` — non-null UUID 전달 시 Spring Data JPA가 `merge()` 호출 → `StaleObjectStateException` 발생
- `@Transactional` 테스트 내에서 `insertable=false, updatable=false` 필드(예: `createdAt`)는 DB DEFAULT 값이 JPA 1차 캐시에 반영되지 않음 → 해당 필드 `isNotNull()` 단언 금지

### record 필드 수정 시 주의
- 필드에 들어가는 string 값을 grep하면 테스트에서 임의 값(예: `"preOpen"`, `"correction"`)을 쓰는 케이스를 누락할 수 있음 → string 값 grep보다 `compileTestJava`로 검증하는 것이 신뢰성 높음
- `KisOrderAdapterTest`는 `Order` 객체를 생성자로 직접 생성 — `Order` record 변경 시 반드시 확인
