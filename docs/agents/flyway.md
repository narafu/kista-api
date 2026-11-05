## Flyway 상세 규칙

핵심 금지 규칙은 `constraints.md` "Flyway"에 요약돼 있고 이 문서는 마이그레이션을 작성·배포할 때 필요한 상세다. 파일 생성 절차는 `.claude/skills/flyway-migration`.

### 서비스별 Flyway
- 운영 DB에 **이미 적용된 마이그레이션 파일은 절대 수정 금지**(V1 포함) — checksum 불일치로 기동 즉시 크래시한다. 새 마이그레이션은 소유 서비스 디렉토리의 최신 버전 다음 번호로 만든다.
- 신규 테이블은 소유 서비스 디렉토리에만 추가한다(스키마 소유 → 이 문서 "DB 스키마 5분리").
- root는 `src/main/resources/db/migration`(이력 `flyway_schema_history_api`, 기본 스키마 `public`), trading-core는 `trading-core/src/main/resources/db/migration-trading`(이력 `flyway_schema_history_trading`, 기본 스키마 `trading`)이다. 스키마 소유 표 → 이 문서 "DB 스키마 5분리".
- 다른 서비스 소유 스키마 테이블을 참조하면(FK 포함) trading baseline이 root 테이블에 의존해 fresh DB 기동이 실패한다.
- 두 서비스 모두 `V1__init.sql` baseline 하나로 스쿼시돼 있다(옛 공용 `public.flyway_schema_history`는 롤백 증거로 운영 DB에 보존, 수정·삭제 금지). 스키마 재편 이행 릴리스는 자동 롤백이 불가능하다 → `deploy/server/schema-reorg/RUNBOOK.md`
- 이력이 스쿼시된 적이 있어 문서에 특정 버전 번호(`V24` 등)를 근거로 쓰지 않는다. 버전은 `git log`로 추적하고 문서에는 "어떤 컬럼/제약이 어느 파일에 있는지"만 기록한다.
- 새 마이그레이션 번호는 소유 서비스 디렉토리의 최신 버전 다음 번호다(`ls`로 확인).

### CI 강제 (`.github/scripts/check-migrations.sh`, `deploy-checks` 잡)
① 이미 존재하던 마이그레이션 파일(양 서비스)의 수정·삭제·이름변경은 실패한다. ② root(`db/migration`, V1 baseline 제외)에서 DROP TABLE·DROP COLUMN·RENAME·SET NOT NULL·ALTER COLUMN TYPE·DEFAULT 없는 NOT NULL 컬럼 추가는 실패한다. 두 role을 같은 커밋에서 함께 배포하는 의도적 contract 단계면 파일에 `-- contract-ok: <사유>` 주석으로 예외를 표시한다.

### 2-role 배포 backward-compat (expand/contract)
root(`db/migration`) 마이그레이션에만 해당한다. trading-core(`db/migration-trading`)는 자기 스키마·이력을 독립으로 가져 이 제약 밖이다.
- `kista-api`·`kista-scheduler`가 독립 배포되므로 root 새 마이그레이션은 직전 배포 이미지와 호환돼야 한다. 컬럼 추가는 nullable 또는 DEFAULT, 컬럼/테이블 드롭·리네임은 두 배포에 분리한다(먼저 코드 참조 제거 → 다음 배포에서 스키마 변경). 이를 못 지키는 마이그레이션을 실은 커밋은 두 role을 함께 배포한다.
- `ddl-auto: validate`는 기동 시에만 검사하므로, 스큐 상태의 스케쥴러는 드롭된 컬럼을 매매 도중 런타임에 만날 때까지 계속 돈다.
- **스키마뿐 아니라 "이벤트로 채워지는 복제 테이블"도 순서 제약을 만든다**: 복제본을 채우는 발행이 한쪽 role에만 있으면, 그 role이 구버전인 채로 반대쪽만 신버전이 되면 복제본이 비어 신버전이 깨진다. `user_notify_profile`이 그 사례 — 발행은 `kista-api` 경로(가입·승인·거절·재신청·설정변경)에만 있으므로 **`kista-api`를 `kista-scheduler`보다 먼저(또는 동시에) 배포해야 한다**. 반대 순서는 무해하다(구 스케쥴러는 `users`/`user_settings`를 직접 읽음) — 단방향 제약이다. 새 복제 테이블을 추가할 때 "발행 주체가 어느 role인가"를 먼저 확인하고 마이그레이션 헤더에 순서를 명시한다. 복제본 백필·전수 발행 조건은 `own-type-ledger.md`.

### 이벤트 클래스·리스너 패키지 이동/개명은 `event_publication`에 고아 row를 남긴다
Modulith EPR은 이벤트를 FQCN(`event_type`), 리스너를 FQCN+메서드(`listener_id`)로 저장해 재기동 republish 시 그 이름으로 resolve한다. 옛 이름으로 남은 미완료 row는 `ClassNotFoundException`으로 반복 실패하거나 어떤 리스너와도 매칭되지 않아 자연 치유되지 않는다. 이런 커밋은 소유 서비스(`trading.event_publication`=kista-trading, `public.event_publication`=kista-scheduler) 이미지 교체 직전에 확인·정리해야 한다. `completion_date`를 채워 완료로 위장하지 말고 삭제(재시도 포기)로 처리한다(리스너 FQCN만 바뀌고 이벤트 FQCN·메서드 시그니처·의미가 불변이며 리스너가 멱등이면 삭제 대신 `listener_id`를 새 id로 UPDATE해 재시도를 유지한다 — 완료 위장 아님). 절차와 건별 SQL → `docker-infra.md` "배포 직전 EPR 정리 런북". 패키지 루트 분리(2026-10) 같은 루트 이동은 모든 미완료 row를 고아로 만든다 → `docker-infra.md` "패키지 루트 분리 배포".

### Entity ↔ Flyway 크로스체크
- Entity의 `nullable`, `length`, `precision`, `scale`을 바꾸면 Flyway SQL과 반드시 대조한다. `ddl-auto: validate`는 타입 불일치를 부팅 시 즉시 `SchemaManagementException`으로 잡지만, `NOT NULL` 불일치만 런타임 무증상이라 실제 null 삽입 시 `DataIntegrityViolationException`이 난다.
- `@Column(scale)`은 DDL 힌트일 뿐이다. JPA 1차 캐시엔 원본 BigDecimal이 유지되므로 `@Transactional` 내 저장 직후 읽으면 DB 반올림 전 값이 반환된다.

### 작성 규칙
- enum 컬럼은 `VARCHAR(20)`(값 길이 여유 있게) + `@Enumerated(EnumType.STRING)` 단독. PostgreSQL 네이티브 ENUM(`CREATE TYPE ... AS ENUM`)과 `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`은 금지다(전 프로젝트 통일).
- **컬럼 순서**: `pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at` 순서 고정. Entity 필드 선언 순서와 일치시킨다.
- PostgreSQL `ADD COLUMN`은 항상 맨 뒤다. 특정 위치를 강제하려면 테이블 재생성 패턴(`CREATE TABLE _new + INSERT SELECT + DROP + RENAME`)을 쓴다. 재생성 패턴에서 named UNIQUE 제약은 `ALTER TABLE xxx_old DROP CONSTRAINT foo;`를 RENAME 직후·CREATE 전에 넣어야 한다(unnamed UNIQUE는 Postgres가 자동으로 새 이름을 만든다).
- **FK 추가 시 `ON DELETE CASCADE` 여부를 반드시 명시**한다(기본값 `ON DELETE RESTRICT`).
- 암호화 저장 컬럼은 VARCHAR(512) 이상 → `modules/shared/platform.md` "AES-256 암호화 위치·컬럼 크기".

### 로컬 checksum mismatch (로컬 전용)
로컬 파일을 수정해 Flyway checksum이 어긋나면 서비스별 이력 테이블(`flyway_schema_history_api`/`flyway_schema_history_trading`)에서 `DELETE ... WHERE version = 'N'` + 해당 테이블 DROP 후 앱을 재시작한다.

## DB 스키마 5분리

DB 스키마 5분리, 소유 서비스별(같은 DB `kistadb`·같은 DB 유저). root(`:api`): `public`(users/refresh_tokens/user_settings/user_notification_prefs/admin_runtime_settings/audit_logs/app_error_logs/fcm_device_tokens 등 인증/관리자/로그/알림 성격 플랫폼 공통 + `scheduler_locks`/`event_publication`)·`finance`(가계부)·`kista_ref`(외부 참조·시장 데이터 — housing_benchmark_prices/housing_price_indices/market_index_prices/fear_greed_snapshots). `:trading-core`: `trading`(accounts/broker_tokens/strategy·strategy_version 계열/strategy_cycle 계열/cycle_position 계열/orders + `user_notify_profile` 복제본 + `scheduler_locks`/`event_publication` **사본** — root `public`에 의존하지 않도록 자체 보유)·`trading_ref`(us_market_holidays + FIDA PRIVACY 기준 매매표 privacy_trade_bases/privacy_trade_base_orders, 전역 공유·비개인 데이터가 기준). `KisTokenEntity`(`broker_tokens`)는 trading 소유. Flyway도 서비스별(이력 `flyway_schema_history_api`/`flyway_schema_history_trading`, 디렉토리·절차는 이 문서 상단)이며 각자 소유 스키마 테이블만 만든다. **다른 서비스 소유 스키마 테이블은 참조 금지(FK 포함)**. `event_publication`이 서비스별 2개라 EPR 재발행 소유도 갈린다(root=`kista-scheduler`, trading=`kista-trading` → constraints.md Git 규칙). 신규 테이블은 소유 서비스 기준으로 분류해 Entity에 `@Table(schema=...)` 명시(public도 명시 — search_path 첫 스키마가 root는 `finance`·trading은 `trading`이라 생략 시 validate 실패) — nativeQuery/JdbcTemplate/raw SQL은 Hikari `connection-init-sql`의 search_path(root `finance, kista_ref, public` / trading `trading, trading_ref`)로 unqualified 이름이 자동 해석되므로 스키마 접두사 불필요(trading 프로세스는 root `public` 테이블을 볼 수 없다). 재편 경위 → `docs/history/modulith-migration-history.md` "DB 스키마 재편"
