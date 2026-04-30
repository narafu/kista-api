## 핵심 제약 사항

### Virtual Thread
- `spring.threads.virtual.enabled=true` (application.yml에 설정됨)
- `TradingService` 내부 대기: `Thread.sleep()` 사용
- `@Async`, `CompletableFuture` **사용 금지**

### 매매 공식 (변경 금지 — 단위 테스트로 검증)
```
A = avgPrice (qty==0이면 currentPrice)  Q = soxlQty
M = A × Q   D = usdDeposit
B = D + M   K = B ÷ 20  (scale=2, HALF_UP)
T = Q==0 ? 0 : floor(M ÷ K)
S = (20 - T×2) ÷ 100  (scale=4, HALF_UP)
P = A × 1.2  (scale=2, HALF_UP)
```
- `AccountBalance.effectiveAmt` = SOXL 시가 평가액 (KIS `FRCR_EVLU_AMT2`), `usdDeposit` = 예수금 (KIS `FRCR_DNCL_AMT_2`) — D는 반드시 `usdDeposit` 사용

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

### 주석 규칙
- 신규 코드 작성 시 주석을 함께 작성할 것
- 필드: `// 역할 한 줄` 인라인 주석
- 비즈니스 로직 블록 직전: 단계 설명 한 줄
- API 상수/코드값: `"840" // 국가코드: 미국` 형식
- Javadoc·블록 주석 금지 — `//` 인라인만 사용
