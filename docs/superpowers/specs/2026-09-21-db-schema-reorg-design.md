# DB 스키마 재편 설계 — 서비스별 스키마 소유 + 서비스별 Flyway baseline

`kista-trading`(`:trading-core`)과 root(`:api`)가 같은 DB(`kistadb`)·같은 DB 유저·같은 Flyway 이력을 공유하는 현재 구조에서, 스키마 이름과 마이그레이션 소유를 서비스 경계에 맞춘다. 물리 DB 분리(`2026-09-14-kista-trading-stage4-db-split-design.md` 4b-2, 보류 중)의 선행 작업이며, 그 계획을 `pg_dump --schema` 단위로 단순화하는 것이 목적이다. 별도 릴리스이고, 마이그레이션이 스키마명을 바꾸므로 옛 이미지와 호환되지 않는 **일회성 비호환 변경**이다.

## 확정된 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 스키마명 | `kista`→`trading` | 서비스명과 일치 |
| reference 분할 | trading 소유(`us_market_holidays`, `privacy_trade_bases`, `privacy_trade_base_orders`)→`trading_ref`, root 소유(`housing_benchmark_prices`, `housing_price_indices`, `market_index_prices`, `fear_greed_snapshots`)→`kista_ref` | 소유 서비스별로 스키마가 갈라져야 `pg_dump --schema` 단위 분리가 가능 |
| root 소유 그대로 | `public`, `finance` | 접두사 통일은 기능 영향 없이 churn만 늘어 제외 |
| `public.broker_tokens` | `trading`으로 이동 | trading 전용 테이블. `accounts` FK가 같은 스키마 안으로 들어옴 |
| 공유 인프라 테이블 | `event_publication`, `scheduler_locks`를 서비스별로 분리(root=`public` 유지, trading=`trading`에 사본) | 마이그레이션 소유가 완전히 갈라지고 trading 단독 fresh DB가 root에 의존하지 않음 |
| Flyway | root: `flyway_schema_history_api`(기본 스키마 `public`), trading: `flyway_schema_history_trading`(기본 스키마 `trading`). 둘 다 새 V1로 스쿼시 | root V1~V23이 trading 소유 테이블까지 만들고 있어 스쿼시하지 않으면 소유 분리가 성립하지 않음 |
| 옛 이력 | `public.flyway_schema_history`(22행)는 수정·삭제하지 않고 보존 | 운영 이력 수술 회피 + 롤백 증거 |
| DB 계정 | 1개 유지 | 계정 분리는 범위 밖 |
| `accounts.user_id → public.users` FK | 운영·fresh 모두 제거 | trading baseline이 root 테이블에 의존하면 안 됨. 아래 "FK 제거 근거" 참고 |
| 배포 | 이번 릴리스만 워크플로 우회 — 수동 SSH 런북, 매매 시간대 밖 전 서비스 정지, api → trading → scheduler 순 | 옛 이미지의 `@Table(schema="kista")`가 즉시 깨지고 헬스게이트 자동 롤백도 옛 스키마명을 기대해 무력화됨 |

## 실측 근거

**Flyway baseline 동작 — 로컬 스크래치 DB, Flyway 12.4.0, `defaultSchema=trading`, `table=flyway_schema_history_trading`, `baselineOnMigrate=true`, `baselineVersion=1`:**

| 시나리오 | 결과 |
|---|---|
| DB에 아무것도 없음 | V1 실행 (`SCHEMA`, `SQL` 행 생성) |
| `public`에 다른 테이블만 있고 `trading` 없음 | V1 실행 — `public`의 root 테이블은 기본 스키마가 아니라 영향 없음 |
| `trading` 스키마 존재 + 테이블 있음, 이력 테이블 없음 | `BASELINE` 행만 생성, V1 스킵 |
| `trading` 스키마 존재 + 비어 있음 | V1 실행 |

→ 기본 스키마를 서비스 소유 스키마로 잡으면 "운영(비어있지 않음)=baseline / fresh=V1 실행"이 프로브 없이 갈린다. trading 기본 스키마를 `public`으로 두면 root 테이블 때문에 fresh에서도 baseline되어 V1이 스킵되는 함정이 있었으므로 `trading`으로 한다.

**trading-core가 지금 root 이력 검증을 통과하는 이유(추정, 런북 사전 점검에서 로그로 확인):** `db/migration-trading`에 해석된 마이그레이션이 0개라 이력의 22행이 전부 "future"로 분류되고 Flyway 기본 `ignoreMigrationPatterns=*:future`에 걸려 무시된다. 이력 테이블을 분리하면 이 우연에 기대지 않게 된다.

**스키마를 가로지르는 접근 (grep 실측):**
- 운영 FK: `accounts.user_id → users(id) ON DELETE CASCADE`(V1:38), `broker_tokens.account_id → accounts(id) ON DELETE CASCADE`(V1:54). 후자는 `broker_tokens` 이동으로 스키마 내부 FK가 된다. `ALTER SCHEMA ... RENAME`/`ALTER TABLE ... SET SCHEMA`는 인덱스·제약·FK를 그대로 보존한다.
- **trading-core → root 테이블 런타임 의존이 1곳 있다**: `StrategyJpaRepository.findAllActiveStrategies()`(스케쥴러 핵심 쿼리)가 native SQL로 `JOIN users u`(root `public.users`)를 한다(`trading-core/.../StrategyJpaRepository.java:59`). 스키마 재편 릴리스 전에 이 의존을 끊어야 한다 — 이미 복제본 `user_notify_profile.is_active`가 있다. → 계획서 Phase 0.
- trading-core·`:shared`의 나머지 SQL(native/JdbcTemplate)은 전부 trading 소유 테이블을 unqualified 이름으로 쓰거나(`orders`, `cycle_position`, `strategy_cycle` …) `scheduler_locks`(사본 신설)다. 스키마명이 하드코딩된 SQL은 Java 코드에 없다. `UserSyncBackfillService`의 `kista.` 언급은 주석뿐이고 실제 SQL은 `FROM users`.
- 스키마명이 박힌 곳: 엔티티 `@Table` 18곳, Hikari `connection-init-sql`(root·trading-core `application.yml` 각 1곳), 적용 완료 마이그레이션 V15의 `ALTER ROLE kista SET search_path`, 그리고 V15/V17/V22/V23 본문(수정 금지 — V1 스쿼시로 함께 폐기됨).
- kista-infra `backup.sh`는 `pg_dump -U kista kistadb -Fc`(DB 전체, `--schema` 없음) — 스키마명 의존 없음. 단 복원 절차 주석(`backup.sh:29`)이 `flyway_schema_history`를 조회하므로 새 이력 테이블명 반영 필요.

**FK 제거 근거:** `users`·`accounts` 모두 `deleted_at` 소프트 삭제(`UserEntity`, `AccountEntity`의 `@SQLRestriction`)이고, 사용자 삭제 시 계좌 정리는 `UserDeletedEvent` → `AccountUserCascadeListener` → `AccountPort.deleteByUserId`(소프트 삭제)가 담당한다. `users`를 하드 삭제하는 코드는 없다(`UserPersistenceAdapter.delete`도 `deleted_at` UPDATE). 따라서 `ON DELETE CASCADE`는 실사용 경로가 없는 죽은 제약이다. 롤백 시엔 `ADD CONSTRAINT ... ON DELETE CASCADE`로 복원 가능(고아 행이 없으면 통과).

## 운영 이행 메커니즘

Flyway 마이그레이션이 아니라 **수동 SQL 런북**(단일 트랜잭션)이다. 서비스 정지 상태에서 다음을 수행한다.

1. `ALTER SCHEMA kista RENAME TO trading`
2. `CREATE SCHEMA trading_ref` 후 3개 테이블 `SET SCHEMA`, `ALTER SCHEMA reference RENAME TO kista_ref`
3. `public.broker_tokens` → `trading`
4. `accounts_user_id_fkey` DROP
5. `trading.event_publication`, `trading.scheduler_locks` 생성(trading V1과 동일 DDL). `event_publication`은 trading 리스너 행만 이동(`listener_id` 접두사: `com.kista.trading.`/`matching.`/`broker.`/`account.`/`privacy.`/`marketcalendar.`)
6. `ALTER ROLE kista RESET search_path`(앱은 Hikari `connection-init-sql`로 search_path를 세팅하므로 role 기본값 불필요)

그 후 새 이미지를 **첫 기동에만** `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`, `SPRING_FLYWAY_BASELINE_VERSION=1`로 띄워 새 이력 테이블에 `BASELINE` 행을 만든다(V1 스킵). 이 플래그는 이력 테이블이 생긴 뒤엔 효과가 없다.

**baseline V1 작성 방식:** 손으로 쓰지 않는다. 옛 마이그레이션 V1~V23을 스크래치 DB에 적용 → 위 런북 SQL 적용 → 서비스별 `pg_dump --schema-only`로 뽑아 baseline을 만든다. 운영 이행 결과와 fresh V1 결과가 같은 DDL에서 나오므로 두 경로의 동치가 구조적으로 보장되고, 게이트(schema-only diff)로 재확인한다.

**search_path:** root `finance, kista_ref, public`, trading `trading, trading_ref, public`(Phase 0 완료 + grep 게이트 통과 시 `public` 제거). 신규 baseline DDL은 전부 스키마 한정으로 쓴다.

**EPR 재발행 공백 해소:** 지금은 세 프로세스가 한 `event_publication`을 공유하고 `kista-trading`(`republish=true`)만 재발행한다. 테이블을 분리하면 root의 `public.event_publication`을 재발행하는 프로세스가 없어진다 → `kista-scheduler`를 `republish=true`로 되켠다(`kista-api`는 false 유지 — 둘 다 true면 이중 claim). scheduler는 비-매매 스케쥴러만 돌려 배포 시간대 제약과 무관하다.

## 롤백

자동 롤백은 불가능하다(헬스게이트가 옛 스키마명을 기대). 대신 이행이 **이름 변경과 소유 이동뿐이라 데이터를 복사하지 않으므로**, 역방향 SQL(`02-rollback.sql`)이 언제든(새 이미지에서 쓰기가 발생한 뒤에도) 대칭으로 되돌린다. 유일한 비가역 성분은 FK DROP(재추가로 복원)과 새 이력 테이블(무해, 삭제 가능). 옛 `flyway_schema_history` 22행이 그대로라 이전 이미지가 검증을 통과한다. 롤백 시 `trading.event_publication`의 사후 발생 행은 `public`으로 되돌려 병합한다.

## 검증 게이트

1. 운영 덤프 복제 DB에서 전체 리허설(정방향·역방향).
2. `pg_dump --schema-only` diff: (리허설 DB) vs (fresh DB에 새 V1 두 개 적용) — 이력 테이블·옛 이력 테이블 제외 후 차이 0.
3. 이행 전후 전 테이블 row count 일치, `event_publication` 이동 건수 합 일치.
4. 컷오버 후 첫 개장·마감 사이클 관측(로그·heartbeat·텔레그램 리포트·`orders`/`cycle_position` 증가).

## 범위 밖 (후속)

- 물리 DB 분리(4b-2) 계획 재작성 — 이 재편 후엔 `pg_dump --schema trading --schema trading_ref` 단위로 단순화.
- 옛 `public.flyway_schema_history` 삭제(≥1주 관측 후).
- DB 계정 분리·GRANT 정리.
