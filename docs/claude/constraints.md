## 핵심 제약 사항

### Virtual Thread
- `spring.threads.virtual.enabled=true` (application.yml에 설정됨)
- `TradingService` 내부 대기: `Thread.sleep()` 사용
- `@Async`, `CompletableFuture` **사용 금지**

### 매매 공식 (변경 금지 — 단위 테스트로 검증)
```
A = avgPrice (qty==0이면 currentPrice)  Q = soxlQty
M = A × Q   D = effectiveAmt
B = D + M   K = B ÷ 20  (scale=2, HALF_UP)
T = Q==0 ? 0 : floor(M ÷ K)
S = (20 - T×2) ÷ 100  (scale=4, HALF_UP)
P = A × 1.2  (scale=2, HALF_UP)
```

### Flyway
- `V1__`~`V3__.sql` **절대 수정 금지** — 새 마이그레이션은 `V4__...` 이후로
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화

### application-local.yml Docker 호환성
- datasource url/username/password는 반드시 `${DB_URL:...}` 형식 유지 — 하드코딩 시 Docker에서 주입한 `DB_URL=postgres:5432`가 무시되고 `localhost:5432`로 접속 시도

### springdoc 버전 제약
- Spring Boot 3.4.x(Spring Framework 6.2)는 springdoc **2.7.0 이상** 필요 — 2.6.x는 `NoSuchMethodError: ControllerAdviceBean` 발생
- 현재: `springdoc = "2.8.4"` (`gradle/libs.versions.toml`)
