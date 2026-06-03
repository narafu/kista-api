# JPA·Flyway·보안 제약

## Flyway
- 현재 최신: `V51__drop_trade_histories.sql`
- `V1__`~`V5__` 절대 수정 금지
- `ddl-auto: validate` — Entity ↔ Flyway 크로스체크 필수
- PostgreSQL `ADD COLUMN`은 항상 맨 뒤 추가 (`AFTER` 절 없음)
- 테이블 재생성 패턴: `CREATE TABLE _new + INSERT SELECT + DROP + RENAME` (V22/V36 참고)
- FK `ON DELETE CASCADE` 여부 반드시 명시 (기본값 RESTRICT → 부모 삭제 시 500)
- `CONSTRAINT 명시_fkey FOREIGN KEY (...)` 형식 사용 권장 (인라인 `REFERENCES`는 충돌 위험)
- `.env` 환경변수 값: 반드시 한 줄 직렬화 (멀티라인 금지)

## JPA Entity
- `@Enumerated(EnumType.STRING)` + VARCHAR(20). `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 금지
- AES-256 암호화 컬럼: `length=512` 이상
- `BaseAuditEntity`: `createdAt`+`updatedAt` / `BaseCreatedAtEntity`: `createdAt`만
- `@SQLRestriction("deleted_at IS NULL")` 소프트 삭제 엔티티 — nativeQuery에서 수동 추가 필수
- `@Column(scale)`: DDL 힌트일 뿐, Hibernate 자동 반올림 안 함 (PostgreSQL이 INSERT 시 처리)

## 소프트 삭제 (V43 이후)
- 대상: `users`, `accounts`, `trading_cycle`
- Repository: `@Modifying @Query("UPDATE XxxEntity SET deletedAt = :now WHERE id = :id")`
- Cascade 순서: 서비스에서 사이클→계좌→사용자 명시 처리 (DB CASCADE 미동작)

## Account ↔ TradingCycle 분리 (V38 이후)
- `Account` 필드 10개: id/userId/nickname/accountNo/kisAppKey/kisSecretKey/kisAccountType/broker/createdAt/updatedAt
- `TradingCycle` 필드 8개: id/accountId/type/status/ticker/initialUsdDeposit/createdAt/updatedAt
- `MAX_CYCLES_PER_ACCOUNT = 1` (운영 정책)

## KIS 계좌번호
- `accounts.account_no` (8자리, AES-256) + `accounts.kis_account_type` (평문 "01") 분리 저장
- KIS API: `CANO = account.accountNo()`, `ACNT_PRDT_CD = account.kisAccountType()`

## CORS (SecurityConfig)
- `allowedMethods`: GET/POST/PUT/PATCH/DELETE/OPTIONS (PATCH 미포함 시 pause/resume 403)
- `allowCredentials: true`
- `authenticationEntryPoint` 반드시 설정 (미설정 시 인증 실패가 403 반환)

## application-local.yml
- datasource url: `${DB_URL:...}` 형식 유지 (하드코딩 시 Docker 주입 무시됨)
