## 테스트

### @WebMvcTest MockBean 주의
- `@MockBean` (Spring Boot 3.4+)은 deprecated → 대안: `@MockitoBean` 사용 권장 (경고는 기능 무관, 당장은 무시 가능)

### Mockito 병렬 테스트 주의
- `ArgumentCaptor<Map>` (raw) + `any()` 조합은 JUnit 5 concurrent 모드에서 오작동 → `ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class)` + `any(String.class)` + `@SuppressWarnings("unchecked")` 사용
- `AccountBalance(q>0, t>0)` 잔고는 `SoxlDivisionStrategy` 계산 시 BUY+SELL LOC 주문 2건 발생 — 테스트에서 `kisOrderPort.place()` 호출 횟수 주의

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
- `AccountBalance` 테스트 데이터: `effectiveAmt = currentPrice × quantity`, `usdDeposit = 현금`; quantity=0이면 effectiveAmt=0

### JPA 엔티티 저장 패턴
- `@GeneratedValue(strategy = GenerationType.UUID)` 엔티티 저장 시 도메인 모델의 `id`는 반드시 `null` — non-null UUID 전달 시 Spring Data JPA가 `merge()` 호출 → `StaleObjectStateException` 발생
- `@Transactional` 테스트 내에서 `insertable=false, updatable=false` 필드(예: `createdAt`)는 DB DEFAULT 값이 JPA 1차 캐시에 반영되지 않음 → 해당 필드 `isNotNull()` 단언 금지

### record 필드 수정 시 주의
- 필드에 들어가는 string 값을 grep하면 테스트에서 임의 값(예: `"preOpen"`, `"correction"`)을 쓰는 케이스를 누락할 수 있음 → string 값 grep보다 `compileTestJava`로 검증하는 것이 신뢰성 높음
- `KisOrderAdapterTest`는 `Order` 객체를 생성자로 직접 생성 — `Order` record 변경 시 반드시 확인
