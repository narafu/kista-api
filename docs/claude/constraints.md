## 핵심 제약 사항

### Virtual Thread
- `spring.threads.virtual.enabled=true` (application.yml에 설정됨)
- `TradingService` 내부 대기: `Thread.sleep()` 사용
- `@Async`, `CompletableFuture` **사용 금지**

### JPA 설정
- `spring.jpa.open-in-view: false` 명시 — REST API이므로 불필요, Supabase PgBouncer Transaction Mode에서 트랜잭션 외부 커넥션 점유 방지

### 매매 공식 (변경 금지 — 단위 테스트로 검증)
```
A = averagePrice (qty==0이면 currentPrice)   Q = quantity
M = A × Q (purchaseAmount)   D = currentPrice × Q (evaluationAmount, 정보성)
B = usdDeposit + M (totalAssets)   K = B ÷ 20 (unitAmount, scale=2, HALF_UP)
T = Q==0 ? 0.0 : M ÷ K  (currentRound, double, 소수점 허용)
S = 0.20 × (1 - 2T/20)  (priceOffsetRate, scale=4, HALF_UP)
G = A × (1 + S)  (referencePrice, scale=2, HALF_UP — LOC 주문 가격 기준)
P = A × 1.20  (targetPrice, scale=2, HALF_UP)
```
- `usdDeposit` = 통합주문가능금액 (KIS `TTTC2101R` `itgr_ord_psbl_amt`, 미국 행 필터링) — 원화 자동 환전 포함, B 계산에 사용
- `currentRound`(T)는 floor 없이 소수점 허용
- **전반/후반 분기**: `priceOffsetRate > 0` → 전반, `≤ 0` → 후반 (수학적으로 T < 10 / T ≥ 10과 동치)
- **전반**: LOC 매수①(K/2/A, 평단가) + LOC 매수②(K/2/G, 기준가) + LOC 매도(Q/4, G+0.01) + 지정가 매도(Q-Q/4, P)
- **후반 K>D**: MOC 매도(Q/4)만 / **후반 K≤D**: LOC 매수(K/G, G) + LOC 매도 + 지정가 매도

### KIS 계좌번호 환경변수 분리
- `KIS_ACCOUNT_NO=74420614` (8자리만) + `KIS_ACCOUNT_TYPE=01` 별도 설정
- `74420614-01` 형태로 하나의 변수에 넣으면 KIS API CANO 파라미터 오류

### Flyway
- `V1__`~`V3__.sql` **절대 수정 금지** — 새 마이그레이션은 `V4__...` 이후로
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화

### application-local.yml Docker 호환성
- datasource url/username/password는 반드시 `${DB_URL:...}` 형식 유지 — 하드코딩 시 Docker에서 주입한 `DB_URL=postgres:5432`가 무시되고 `localhost:5432`로 접속 시도

### springdoc 버전 제약
- Spring Boot 3.4.x(Spring Framework 6.2)는 springdoc **2.7.0 이상** 필요 — 2.6.x는 `NoSuchMethodError: ControllerAdviceBean` 발생
- 현재: `springdoc = "2.8.4"` (`gradle/libs.versions.toml`)

### 텔레그램 로컬 테스트
- `api.telegram.org:443` TCP가 ISP 레벨에서 차단될 수 있음 (ping은 성공해도 curl 타임아웃)
- 로컬에서 `curl .../sendMessage` 테스트 시 VPN 필요

### Lombok 패턴
- `@Slf4j` + `@RequiredArgsConstructor` 표준 — 수동 로거/생성자 작성 금지
- package-private 타입을 생성자에서 참조 시: `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` (ClassEscapesItsScope 회피)
- `@Value` 필드를 Lombok 생성자에 포함하려면 `lombok.config`에 `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Value` 필요 (이미 설정됨)
- `RestTemplate` 빈이 여러 개(`kisRestTemplate`, `telegramRestTemplate`)이므로 필드명을 빈 이름과 반드시 일치시킬 것 — 불일치 시 NoUniqueBeanDefinitionException

### 주석 규칙
- 신규 코드 작성 시 주석을 함께 작성할 것
- 필드: `// 역할 한 줄` 인라인 주석
- 비즈니스 로직 블록 직전: 단계 설명 한 줄
- API 상수/코드값: `"840" // 국가코드: 미국` 형식
- Javadoc·블록 주석 금지 — `//` 인라인만 사용
