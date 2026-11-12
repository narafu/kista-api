# 15. DB 테이블 전수 조사 (스키마 5개, 운영 kistadb 기준)

조사 기준: 2026-11-13 main `1db0d8db1`. 읽기 전용 조사(코드·운영 DB 모두 SELECT만). 운영 DB는 `ssh kista-server` → `kista-postgres` 컨테이너(PostgreSQL 18.6, DB명 kistadb)에서 조회했다. 시각은 모두 UTC 표기 그대로다.
경로 약어: `API/`=`src/main/java/com/kista/api/`, `TC/`=`trading-core/src/main/java/com/kista/tradingcore/`, `SH/`=`shared/src/main/java/com/kista/shared/`, `MIG-R/`=`src/main/resources/db/migration/`, `MIG-T/`=`trading-core/src/main/resources/db/migration-trading/`. Entity 위치는 `파일:class 선언 줄`.
이미 다룬 것: 지침서 02(Store 통합·Entity 44개·소프트 삭제 29곳)·03(테이블은 그대로 둔다)·05(D2 스키마 5개 유지, M1 orders 유일 제약 없음, D3 trading에도 expand/contract)는 결론만 인용하고 다시 조사하지 않는다.

## A. 테이블 전수표

운영 `information_schema.columns` 기준 스키마 5개에 테이블 51개(Flyway 이력 3개 포함). Entity(`@Entity`+`@Table`)가 있는 테이블 42개 — Entity 클래스 44개 중 `@Table` 없는 2개는 `@MappedSuperclass`(`BaseAuditEntity`·`BaseCreatedAtEntity`, `SH/platform/persistence/`). Entity 없는 테이블은 JDBC 직접 접근(`scheduler_locks`는 `SH/platform/scheduling/SchedulerLockService`, `trading_batch_run`·`trading_batch_report`는 `TC/trading/adapter/out/persistence/TradingBatchRunPersistenceAdapter.java:28-54`)이거나 라이브러리 소유(`event_publication`=Spring Modulith, `flyway_*`)다.
"컬럼 수"는 운영 실측. "최근 쓰기"는 `created_at`/`updated_at` 중 최대값(둘 다 없으면 `-`).

#### public

| 테이블 | 컬럼 | Entity | 모듈 | 행 수 | 크기 | 최근 쓰기(UTC) |
|---|---|---|---|---|---|---|
| admin_runtime_settings | 4 | `RuntimeSettingsEntity.java:19` | api.admin | 1 | 32KB | 2026-10-26 04:47 |
| app_error_logs | 7 | `AppErrorLogEntity.java:22` | api.admin | 567 | 1.0MB | 2026-11-11 23:36 |
| audit_logs | 7 | `AuditLogEntity.java:18` | api.admin | 37 | 32KB | 2026-10-30 21:14 |
| event_publication | 9 | Entity 없음 | (JDBC/라이브러리) | 833 | 536KB | - |
| fcm_device_tokens | 5 | `FcmDeviceTokenEntity.java:16` | api.notify | 2 | 48KB | 2026-10-21 23:17 |
| flyway_schema_history | 10 | Entity 없음 | (JDBC/라이브러리) | 22 | 48KB | - |
| flyway_schema_history_api | 10 | Entity 없음 | (JDBC/라이브러리) | 6 | 48KB | - |
| refresh_tokens | 7 | `RefreshTokenEntity.java:17` | api.user | 6 | 96KB | 2026-11-12 09:48 |
| scheduler_locks | 5 | Entity 없음 | (JDBC/라이브러리) | 15 | 72KB | - |
| user_notification_prefs | 3 | `UserNotificationPrefJpaEntity.java:17` | api.user | 2 | 24KB | - |
| user_settings | 2 | `UserSettingsJpaEntity.java:22` | api.user | 2 | 32KB | - |
| users | 15 | `UserEntity.java:23` | api.user | 2 | 48KB | 2026-09-18 04:17 |

#### finance

| 테이블 | 컬럼 | Entity | 모듈 | 행 수 | 크기 | 최근 쓰기(UTC) |
|---|---|---|---|---|---|---|
| finance_accounts | 13 | `FinanceAccountEntity.java:26` | api.finance | 32 | 48KB | 2026-11-01 11:16 |
| finance_asset_snapshots | 14 | `FinanceAssetSnapshotEntity.java:25` | api.finance | 1,056 | 448KB | 2026-11-10 09:40 |
| finance_budgets | 9 | `FinanceBudgetEntity.java:22` | api.finance | 37 | 56KB | 2026-10-07 23:40 |
| finance_categories | 10 | `FinanceCategoryEntity.java:24` | api.finance | 75 | 112KB | 2026-09-07 02:55 |
| finance_group_invitations | 10 | `FinanceGroupInvitationEntity.java:22` | api.finance | 4 | 56KB | 2026-08-22 12:13 |
| finance_group_members | 8 | `FinanceGroupMemberEntity.java:23` | api.finance | 4 | 72KB | 2026-08-22 12:13 |
| finance_groups | 5 | `FinanceGroupEntity.java:22` | api.finance | 3 | 24KB | 2026-08-22 12:12 |
| finance_monthly_closings | 8 | `FinanceMonthlyClosingEntity.java:21` | api.finance | 21 | 48KB | 2026-11-04 08:45 |
| finance_transactions | 10 | `FinanceTransactionEntity.java:23` | api.finance | 804 | 272KB | 2026-11-11 14:31 |

#### kista_ref

| 테이블 | 컬럼 | Entity | 모듈 | 행 수 | 크기 | 최근 쓰기(UTC) |
|---|---|---|---|---|---|---|
| fear_greed_snapshots | 6 | `FearGreedSnapshotEntity.java:25` | api.market | 1,640 | 784KB | 2026-11-12 03:00 |
| housing_benchmark_prices | 16 | `HousingBenchmarkPriceEntity.java:26` | api.benchmark | 2,236 | 968KB | 2026-11-06 23:00 |
| housing_price_indices | 11 | `HousingPriceIndexEntity.java:26` | api.benchmark | 22,227 | 13.2MB | 2026-11-06 23:10 |
| market_index_prices | 5 | `MarketIndexPriceEntity.java:25` | api.benchmark | 21,891 | 2.6MB | 2026-11-12 00:00 |

#### trading

| 테이블 | 컬럼 | Entity | 모듈 | 행 수 | 크기 | 최근 쓰기(UTC) |
|---|---|---|---|---|---|---|
| accounts | 13 | `AccountEntity.java:24` | tc.account | 13 | 96KB | 2026-10-24 04:49 |
| broker_tokens | 5 | `KisTokenEntity.java:19` | tc.broker | 5 | 32KB | 2026-11-12 13:45 |
| cycle_capital_event | 9 | `CycleCapitalEventEntity.java:21` | tc.trading | 5 | 40KB | 2026-11-10 09:51 |
| cycle_position | 8 | `CyclePositionEntity.java:22` | tc.trading | 680 | 184KB | 2026-11-12 00:10 |
| cycle_position_infinite | 4 | `CyclePositionInfiniteEntity.java:23` | tc.trading | 283 | 64KB | 2026-11-11 21:10 |
| dca_execution | 12 | `DcaExecutionEntity.java:26` | tc.trading | 84 | 80KB | 2026-11-12 00:00 |
| event_publication | 9 | Entity 없음 | (JDBC/라이브러리) | 335 | 384KB | - |
| flyway_schema_history_trading | 10 | Entity 없음 | (JDBC/라이브러리) | 20 | 48KB | - |
| orders | 17 | `OrderEntity.java:26` | tc.trading | 3,520 | 1.5MB | 2026-11-12 14:30 |
| scheduler_locks | 5 | Entity 없음 | (JDBC/라이브러리) | 7 | 72KB | - |
| strategy | 9 | `StrategyEntity.java:25` | tc.trading | 71 | 88KB | 2026-11-09 08:46 |
| strategy_cycle | 10 | `StrategyCycleEntity.java:23` | tc.trading | 98 | 96KB | 2026-11-06 05:08 |
| strategy_cycle_vr | 6 | `StrategyCycleVrEntity.java:21` | tc.trading | 42 | 24KB | 2026-11-02 02:46 |
| strategy_dca_condition | 8 | `StrategyDcaConditionEntity.java:26` | tc.trading | 19 | 40KB | 2026-11-05 03:56 |
| strategy_dca_rule | 15 | `StrategyDcaRuleEntity.java:34` | tc.trading | 31 | 56KB | 2026-11-06 05:08 |
| strategy_infinite_version | 5 | `StrategyInfiniteEntity.java:23` | tc.trading | 22 | 24KB | 2026-11-04 08:28 |
| strategy_version | 5 | `StrategyVersionEntity.java:21` | tc.trading | 82 | 40KB | 2026-11-06 05:08 |
| strategy_vr_version | 14 | `StrategyVrVersionEntity.java:21` | tc.trading | 30 | 24KB | 2026-11-02 02:46 |
| trading_batch_report | 3 | Entity 없음 | (JDBC/라이브러리) | 159 | 56KB | 2026-11-11 21:10 |
| trading_batch_run | 4 | Entity 없음 | (JDBC/라이브러리) | 59 | 56KB | 2026-11-12 14:30 |
| trading_runtime_settings | 4 | `TradingPolicySettingsEntity.java:21` | tc.trading | 1 | 32KB | 2026-10-26 04:47 |
| user_notify_profile | 7 | `UserNotifyProfileEntity.java:28` | tc.trading | 2 | 32KB | 2026-09-20 23:24 |

#### trading_ref

| 테이블 | 컬럼 | Entity | 모듈 | 행 수 | 크기 | 최근 쓰기(UTC) |
|---|---|---|---|---|---|---|
| privacy_trade_base_orders | 7 | `PrivacyTradeBaseOrderEntity.java:21` | tc.privacy | 1,385 | 224KB | 2026-11-11 22:01 |
| privacy_trade_bases | 8 | `PrivacyTradeBaseEntity.java:27` | tc.privacy | 377 | 128KB | 2026-11-11 22:01 |
| us_market_holidays | 3 | `UsMarketHolidayEntity.java:16` | tc.marketcalendar | 29 | 40KB | 2026-11-11 09:45 |
| us_market_sessions | 3 | `UsMarketSessionEntity.java:17` | tc.marketcalendar | 1,096 | 128KB | - |

#### A 보충: 드리프트·이상 점검

| 항목 | 결과 |
|---|---|
| Entity 필드 ↔ 운영 컬럼(이름 기준, 상속 `created_at`/`updated_at` 포함) | Entity가 있는 42개 중 41개 **완전 일치**(운영에만 있는 컬럼 0, Entity에만 있는 컬럼 0). 예외 1건은 `trading_ref.privacy_trade_bases`의 Entity 필드 `orders`로 컬럼이 아니라 `@OneToMany`다(`PrivacyTradeBaseEntity`). `ddl-auto: validate`가 걸려 있어 당연한 결과 |
| 마이그레이션 `CREATE TABLE` ↔ 운영 테이블 | root 23개, trading 26개(`DROP`된 `strategy_dca_version`·구 `strategy_dca_condition` 제외) 모두 운영에 존재. 운영에만 있는 테이블 없음(Flyway 이력 제외) |
| 구 이력 `public.flyway_schema_history` | 22행, 마지막 버전 23(`add telegram to user notify profile`). 현재 root 이력은 `flyway_schema_history_api`(6행, V1 baseline~V6)이고 trading은 `trading.flyway_schema_history_trading`(20행). 옛 단일 이력이 `public`에 남아 있다. 읽는 코드는 없고, `src/main/resources/application.yml:34` 주석이 "롤백 증거로 보존"이라 적었다(의도된 잔존) |
| 같은 이름 테이블이 스키마 2개에 | `scheduler_locks`(public 15행·trading 7행), `event_publication`(public 833행·trading 335행)은 서비스별 복제(지침서 05 D2에 따라 유지) |
| 행 0인 테이블 | 없음(가장 적은 것: `admin_runtime_settings`·`trading_runtime_settings` 1행) |

숫자 요약: 테이블 51개(Entity 42, JDBC·라이브러리 9) / 컬럼 드리프트 0, 테이블 드리프트 0 / 운영 총 행 약 6.0만 중 `housing_price_indices` 22,227행(13.8MB)·`market_index_prices` 21,891행이 대부분.

## B. 쓰이지 않는 컬럼

방법: Entity 필드 ↔ 운영 컬럼은 A에서 1:1로 확인했으므로 "Entity 필드 없음" 컬럼은 0개다. 읽기 판정은 (1) 도메인 record 접근자 `.필드()`·메서드 참조를 `toDomain` 밖 main 전체에서 grep, (2) native SQL·`@Query`·derived finder(`existsBy...`)가 그 컬럼을 쓰는지 확인했다. 운영 값은 전 컬럼에 대해 행 수·NULL 수·distinct 수만 조회했다(354개 컬럼, 값은 읽지 않음). 운영은 사용자 2명·계좌 13개 규모라 "단일 값"은 소량 데이터의 영향이 크다.

### B-1. 읽기 0 (쓰기만 있고 도메인·SQL 어디서도 읽지 않음)

| 컬럼 | Entity/쓰기 위치 | 읽는 곳 | 운영 |
|---|---|---|---|
| `finance.finance_groups.owner_user_id` | `FinanceGroupEntity.java:27-28`, `FinanceGroupPersistenceAdapter.java:37`(생성 시 저장) | `FinanceGroup.ownerUserId()`를 호출하는 곳 0(`\.ownerUserId\(\)`·`::ownerUserId` main 전체 0건), SQL 0 | 3행 전부 NOT NULL |
| `finance.finance_group_invitations.invitee_user_id` | `FinanceGroupPersistenceAdapter.java:117-118`(수락 시 set) | `FinanceGroupInvitation.inviteeUserId()` 호출 0, SQL 0 | 4행 |
| `trading.cycle_capital_event.kind` | `CycleCapitalEventPersistenceAdapter.java:36`(`setKind`). 값 생성 4곳: `CycleSnapshotCreator.java:132`(RESET), `CapitalLedgerService.java:103`(INJECT)·`:166`(WITHDRAW), `StrategyUpdateWriter.java:129`(RESET) | `CycleCapitalEvent.kind()` 소비 0(`.kind()` 호출은 Meritz 응답 DTO뿐) | 5행, distinct 1 |

### B-2. 쓰기 경로가 있으나 운영에서 전 행 NULL(또는 사실상 미사용)

| 컬럼 | 쓰기 경로 | 운영 |
|---|---|---|
| `finance.finance_accounts.memo` | 응답에 실림(`FinanceAccountResponse.java:27`). 쓰기는 요청 DTO 경유 | 32행 중 NULL 32 |
| `finance.finance_group_invitations.deleted_at` | `@SQLRestriction` 선언됨 | 4행 NULL 4(삭제 한 번도 안 일어남) |
| `trading.strategy_dca_rule.day_of_month` | `DcaRuleRequest.java:24`로 입력 가능, `DcaSchedule.java:43`이 소비 | 31행 NULL 31 — MONTHLY 규칙이 운영에 없다 |
| `public.users.reject_reason`·`last_reapplied_at` | `UserService.java:143` reject 때 기록, `:187-189` 쿨다운에서 읽음 | 2행 NULL 2 — 거절된 사용자가 아직 없음 |
| `trading.user_notify_profile.notification_channel` | `UserNotifyProfileEntity.java:51-63` — `channel != null`일 때만 덮어씀 | 2행 NULL 2(주석상 "채널 복제 전 행"). 정작 `users.notification_channel`은 2행 모두 값 있음(D절) |
| `trading.accounts.broker_account_code` | Toss 계좌 연결 시 저장(`TossAuthApi.java:159`) | 13행 중 NULL 12 — Toss 계좌 1개뿐 |
| `trading.strategy_infinite_version.deleted_at`·`trading.cycle_position_infinite.deleted_at` | **쓰기 0** — main 코드에 `setDeletedAt`·UPDATE 없음(F-1). `@SQLRestriction`은 선언됨 | 삭제 행 8/22·16/283(과거 경로 산물) |
| `trading.strategy_dca_rule.percent` | HOLDING_PERCENT 사이징 전용 | 31행 중 NULL 28 |
| `trading.strategy_dca_rule.shortfall_policy` | `DcaCycleOrderStrategy.java:126,140,153`이 읽음 | 31행 distinct 1 |

### B-3. 운영에서 단일 값(distinct 1)인 컬럼 (행 3개 이상)

모두 읽는 코드가 있다. "값이 하나뿐"인 사실만 기록한다.

| 컬럼 | 행 수 | 비고 |
|---|---|---|
| `kista_ref.fear_greed_snapshots.source` | 1,640 | `MIG-R/V5__fear_greed_crypto_snapshots_delete.sql`이 crypto 행을 지운 뒤 source가 하나 |
| `kista_ref.housing_price_indices.source`·`metric_code` | 22,227 | 유일 키 구성요소(E절) |
| `kista_ref.housing_benchmark_prices.source`·`metric_code` | 2,236 | 유일 키 구성요소 |
| `trading_ref.privacy_trade_base_orders.order_type` | 1,385 | 1열 전부 같은 값 |
| `trading_ref.privacy_trade_bases.ticker` | 377 | 정책상 PRIVACY ticker SOXL 고정(constraints.md) |
| `trading.trading_batch_run.phase` | 59 | |
| `trading.strategy_infinite_version.division_count` | 22 | 사용자 1명의 설정값 |
| `trading.strategy_vr_version.band_width` | 30 | 전부 같은 값 |
| `public.audit_logs.admin_id` | 37 | 관리자 1명 |

### B-4. `created_at`·`updated_at` 감사 컬럼의 소비

| 구분 | 사실 |
|---|---|
| `finance_*` 9개 테이블 | 도메인 record가 `created_at`을 `toDomain`에서 읽는다(각 Entity `toDomain`에서 `getCreatedAt()` 1회). `updated_at`은 `toDomain`·main 어디서도 읽지 않는다 |
| trading 테이블 | `toDomain`이 `created_at`·`updated_at`을 읽는 곳 0. 정렬·조회용 SQL(`ORDER BY created_at` 등)로만 쓰인다(E절) |
| `public.admin_runtime_settings`·`trading.trading_runtime_settings`·`trading.broker_tokens` | `created_at`·`updated_at` 읽기 0 |
| `kista_ref.*`·`trading_ref.*` | `created_at` 읽기 0(`housing_*`의 `updated_at`도 `ON CONFLICT DO UPDATE`로 쓰기만) |

숫자 요약: Entity 필드 없는 컬럼 0 / 읽기 0 컬럼 3개(`owner_user_id`·`invitee_user_id`·`cycle_capital_event.kind`) / 운영 전 행 NULL 5개(`finance_accounts.memo`·`strategy_dca_rule.day_of_month`·`users.reject_reason`·`users.last_reapplied_at`·`user_notify_profile.notification_channel`).
미확인: 컬럼 약 380개 전부에 대한 호출 지점 수동 추적은 하지 않았다. 접근자명이 흔한 이름(`status`, `amount` 등)이면 grep이 다른 타입과 섞이므로 "읽기 0" 판정은 이름이 고유한 컬럼에 한해 확정이다.

## C. 전략 상세 테이블의 3단 분산

`TC/trading/adapter/out/persistence/` 기준. "3단"은 전략(strategy) → 버전(strategy_version) → 종류별 버전 상세, 그리고 사이클(strategy_cycle) → 종류별 사이클 상세, 포지션(cycle_position) → 종류별 포지션 상세로 갈라지는 구조다. 테이블은 지침서 03이 "그대로 둔다"고 정했고 저장 형태(Store 통합)는 지침서 02 2-3절이 정한다 — 여기는 현황 측정만 한다.

### C-1. 테이블 전수 (13개)

| 단 | 테이블 | 컬럼(운영 실측, `*`=PK 또는 PK 겸 FK) | 운영 행 | 종류별 행 수 |
|---|---|---|---|---|
| 전략 | `strategy` | `id*` `account_id` `type` `ticker` `status` `cycle_seed_type` `created_at` `updated_at` `deleted_at` (9) | 71 | DCA 7 / INFINITE 22 / PRIVACY 16 / VR 26 (삭제 행 포함, 아래 참고) |
| 전략·버전 | `strategy_version` | `id*` `strategy_id` `version_no` `created_at` `deleted_at` (5) — 종류 무관 껍데기 | 82 | DCA 14 / INFINITE 22 / PRIVACY 16 / VR 30 |
| 버전 상세 | `strategy_infinite_version` | `strategy_version_id*` `division_count` `created_at` `updated_at` `deleted_at` (5, 의미 컬럼 1개) | 22 | INFINITE 22 |
| 버전 상세 | `strategy_vr_version` | `strategy_version_id*` `interval_weeks` `band_width` `recurring_amount` `initial_gradient` `g_grace_weeks` `g_step_weeks` `g_max` `initial_pool_limit_rate` `p_grace_weeks` `p_step_weeks` `pool_limit_floor` `created_at` `updated_at` (14) | 30 | VR 30 |
| 버전 상세 | `strategy_dca_rule` | `id*` `strategy_version_id` `rule_key` `sort_order` `direction` `frequency` `day_of_week` `day_of_month` `order_type` `execution_hour` `sizing_type` `amount` `percent` `shortfall_policy` `created_at` (15) | 31 | DCA 31 (버전 14개당 규칙 평균 2.2개) |
| 버전 상세 | `strategy_dca_condition` | `id*` `rule_id` `sort_order` `indicator` `operator` `sma_period` `threshold` `created_at` (8) | 19 | DCA 19 |
| (PRIVACY) | 버전 상세 테이블 **없음** | PRIVACY 버전 16개는 `strategy_version` 껍데기만 있고 상세 행이 없다 | 0 | — |
| 사이클 | `strategy_cycle` | `id*` `strategy_id` `strategy_version_id` `start_amount` `end_amount` `start_date` `end_date` `created_at` `deleted_at` `open_amount` (10) | 98 | DCA 7 / INFINITE 23 / PRIVACY 26 / VR 42 |
| 사이클 상세 | `strategy_cycle_vr` | `strategy_cycle_id*` `value` `gradient` `pool_limit_rate` `created_at` `updated_at` (6) | 42 | VR 42 |
| 포지션 | `cycle_position` | `id*` `strategy_cycle_id` `deposit` `closing_price` `avg_price` `holdings` `created_at` `deleted_at` (8) | 680 | DCA 71 / INFINITE 267 / PRIVACY 175 / VR 167 |
| 포지션 상세 | `cycle_position_infinite` | `cycle_position_id*` `is_reverse_mode` `created_at` `deleted_at` (4, 의미 컬럼 1개) | 283 | INFINITE 266 / **PRIVACY 17** |
| 원장 이벤트 | `cycle_capital_event` | `id*` `strategy_cycle_id` `cycle_position_id` `kind` `deposit_delta` `holdings_delta` `principal_delta` `occurred_at` `created_at` (9) | 5 | DCA 3 / PRIVACY 2 |
| 실행 기록 | `dca_execution` | `id*` `strategy_id` `trade_date` `status` `skip_reason` `order_id` `quantity` `reference_price` `detail` `created_at` `rule_key` `direction` (12) | 84 | DCA 84 |
| 주문 | `orders` | 17컬럼(E절) | 3,520 | DCA 65 / INFINITE 1,361 / PRIVACY 561 / VR 1,533 |

삭제 행 포함 수치: `strategy` 71행 중 삭제 63행(live 8: DCA·INFINITE·PRIVACY·VR 각 2). `strategy_version` 82행 중 삭제 20, `strategy_cycle` 98행 중 삭제 87(NULL 11). 위 "종류별 행 수"는 삭제 포함 전체다.

### C-2. 같은 모양이 반복되는 컬럼·1:1로 나뉜 관계

| 관찰 | 근거 |
|---|---|
| 1:1 관계인데 테이블이 갈라진 쌍 4개: `strategy_version`↔`strategy_infinite_version`(PK=FK), `strategy_version`↔`strategy_vr_version`, `strategy_cycle`↔`strategy_cycle_vr`, `cycle_position`↔`cycle_position_infinite` | 상세 테이블 PK가 부모 id를 그대로 씀(위 표 `*`). INFINITE 22:22, VR 30:30, VR 사이클 42:42로 부모와 상세가 항상 같이 생성됨(운영에서 "INFINITE 버전인데 상세 없음" 0건) |
| 의미 컬럼이 1개인 상세 테이블 2개 | `strategy_infinite_version.division_count`(Entity 34줄), `cycle_position_infinite.is_reverse_mode`(Entity 34줄). 지침서 02도 "필드 1개짜리 `StrategyInfiniteDetail`도 5파일 145줄"로 지적 |
| 행이 비어 있는 상세 | INFINITE 포지션 267개 중 상세 행이 없는 것 1개(`pos inf type w/o detail`=1). 코드 주석은 "수동 보정 행(상세 없음)이 최신 포지션이어도 유지"(`StrategyDetailAssembler.java:56`)라 의도된 경우가 있다 |
| 종류가 다른 전략이 INFINITE용 상세 테이블을 씀 | `cycle_position_infinite` 283행 중 17행이 **PRIVACY** 전략의 포지션에 달림(INFINITE 266). 테이블명과 실제 사용 범위가 어긋남 |
| 감사 컬럼 3종 반복 | 이 13개 중 `created_at`은 13개 전부에, `updated_at`은 5개(`strategy`·`strategy_infinite_version`·`strategy_vr_version`·`strategy_cycle_vr`·`orders`)에, `deleted_at`은 6개(`strategy`·`strategy_version`·`strategy_infinite_version`·`strategy_cycle`·`cycle_position`·`cycle_position_infinite`)에 같은 정의로 반복. `strategy_vr_version`·`strategy_cycle_vr`·`strategy_dca_*`는 `deleted_at`이 없어 부모 버전·사이클의 삭제에 의존 |
| `@SQLRestriction` 불균일 | 6개 `deleted_at` 테이블 전부에 선언(F절). 상세 쪽은 native `JOIN strategy_version sv ... sv.deleted_at IS NULL`로 부모 필터를 다시 건다(`StrategyVrVersionJpaRepository.java:14-22`, `StrategyInfiniteJpaRepository.java:12-20`) |
| VR 파라미터 쌍 반복 | `strategy_vr_version`에 `g_grace_weeks`·`g_step_weeks`·`g_max`(gradient 램프)와 `p_grace_weeks`·`p_step_weeks`·`pool_limit_floor`(pool 상한 램프)가 같은 모양(grace/step/한계)으로 두 벌. `initial_gradient`·`initial_pool_limit_rate`는 사이클 쪽 `strategy_cycle_vr.gradient`·`pool_limit_rate`와 짝(D절) |
| 사이클 금액 3종 | `strategy_cycle.start_amount`·`open_amount`·`end_amount`(전부 `numeric(20,2)`) + `cycle_position.deposit`. 원장 기준 값이 사이클과 포지션 양쪽에 있다(D절) |
| `division_count`·`bandWidth`는 DB엔 각 1곳 | DB 컬럼은 `strategy_infinite_version.division_count`·`strategy_vr_version.band_width` 한 곳뿐. 지침서 02의 "main 37개 파일" 반복은 Java 계층에서 생긴 것 |

### C-3. 조회 한 번당 쿼리 수 (코드 기준, `StrategyDetailAssembler`)

| 경로 | 쿼리 수 | 근거 |
|---|---|---|
| 단건 `toDetail`(전략은 이미 로드된 상태) — INFINITE | 4 | 최신 사이클 1 + 최신 포지션 1 + 포지션 상세(is_reverse_mode) 1 + `strategy_infinite_version`↔`strategy_version` native JOIN 1. `StrategyDetailAssembler.java:41,49,54,58` |
| 단건 — VR | 6 | 사이클 1 + 개장 포지션 1 + 최신 포지션 1 + 포지션 상세 1 + `findActiveByStrategyId`(VR 상세) 1 + `strategyCycleVrPort.findByCycleId` 1. `:41-65`, `VrStrategyDetailService.java:43-48` |
| 단건 — PRIVACY | 3 | 사이클·최신 포지션·포지션 상세(PRIVACY도 `cycle_position_infinite`를 조회함) |
| 단건 — DCA | 7 | 위 3 + 최근 실행 1 + 활성 링크 1 + 규칙 1 + 조건 1. `StrategyDcaDetailPersistenceAdapter.java:51-55` |
| 목록 `toDetails` | 고정 8 + DCA 있으면 4 | 최신 사이클·개장 포지션·최신 포지션·활성 버전·INFINITE 상세·VR 상세·VR 사이클 상세·포지션 상세 각 1, DCA는 링크·규칙·조건·최근 실행. `:91-116` 전부 IN 일괄 조회(N+1 없음). 개장 포지션 조회는 VR이 없어도 빈 목록으로 호출됨(어댑터 가드 미확인) |

### C-4. 영속 파일 수와 줄 수

전략 축(위 표에서 `orders`·`dca_execution` 제외 11개 테이블) 기준.

| 구분 | 파일 | 줄 |
|---|---|---|
| Entity | 11 | 586 |
| JpaRepository | 11(`StrategyDcaConditionJpaRepository` 13줄·`StrategyCycleVrJpaRepository` 9줄 포함) | 521 |
| PersistenceAdapter | 10(`Strategy`·`StrategyVersion`·`StrategyInfiniteDetail`·`StrategyVrDetail`·`StrategyDcaDetail`·`StrategyCycle`·`StrategyCycleVr`·`CyclePosition`·`CyclePositionInfiniteDetail`·`CycleCapitalEvent`) | 933 |
| 아웃바운드 포트 | 10 | 334 |
| 합계 | 42 | 2,374 |

`orders`·`dca_execution`까지 더하면 Entity 13(731줄)·JpaRepository 13(743줄)·Adapter 12(1,350줄)·Port 12(474줄)=50파일 3,298줄. 디렉토리 전체는 `TC/trading/adapter/out/persistence/*.java` 43파일 3,083줄(`UserNotifyProfile*`·`TradingBatchRun*`·`policy/` 포함). 지침서 02의 "Strategy 하나에 포트 6, 어댑터 5, JpaRepository 6"은 위 표와 일치한다.

숫자 요약: 전략 축 테이블 13개 / 1:1 분리 쌍 4개(의미 컬럼 1개짜리 상세 2개) / 상세 단건 조회 3~7쿼리, 목록 8~12쿼리 / 영속 파일 42개 2,374줄.
미확인: `findFirstByCycleIds`가 빈 목록일 때 쿼리를 생략하는지(어댑터 가드). 단건 쿼리 수는 JpaRepository 메서드 1개=SQL 1개로 센 것이며 Hibernate 로그로 확인하지 않았다.

## D. 정규화·중복

### D-1. 같은 값이 둘 이상의 테이블에 저장되는 것

운영 불일치 건수는 SELECT 조인으로 셌다(값 자체는 조회하지 않음).

| # | 중복 | 위치 | 동기화 코드 | 운영 일치도 |
|---|---|---|---|---|
| 1 | **사용자 복제본**: `public.users`(`telegram_bot_token`·`telegram_chat_id`·`notification_channel`·`status`→`is_active`)와 `public.user_notification_prefs`(유형별 enabled) → `trading.user_notify_profile`(`telegram_bot_token`·`chat_id`·`notification_channel`·`is_active`·`notification_prefs` text) | root→trading-core 복제(서비스 간 FK 금지 규칙의 대체물) | 발행 `API/user/application/service/UserNotifyProfilePublisher.java:30-52` → `API/user/adapter/out/redis/UserEventStreamPublisher.java:37` → Redis 스트림 → `TC/trading/adapter/in/redis/UserNotifyProfileStreamConsumer`·`UserEventStreamConsumer` → `TC/trading/adapter/in/event/UserNotifyProfileSyncListener` → `TC/trading/adapter/out/persistence/UserNotifyProfilePersistenceAdapter.java:80-93`(`updated_at`을 스냅샷 시각 버전으로 써 옛 스냅샷 무시) · 재시도 `API/web/UserEventResubmitScheduler.java`·`TC/trading/adapter/in/schedule/UserEventResubmitScheduler.java` | 사용자 2명 모두 복제본 존재. **`notification_channel`은 복제본 2행 모두 NULL**, 원본 `users.notification_channel`은 2행 모두 값 있음(FCM·ALL). 활성 여부·텔레그램 연결 유무는 일치. 토큰은 두 테이블에 각각 AES 암호문으로 저장된다(암호문은 IV가 달라 값 비교 불가) |
| 2 | `finance_groups.owner_user_id` ↔ `finance_group_members.role='OWNER'` | 그룹 소유자가 두 곳에 | 둘 다 그룹 생성 시 기록(`FinanceGroupPersistenceAdapter.java:36-37`, `FinanceGroupService.java:50`). 소유자 판정은 members의 OWNER 역할만 읽는다(`FinanceGroupService.java:57,102`). `owner_user_id`는 읽기 0(B-1) | 3개 그룹 모두 owner가 members에 존재(불일치 0) |
| 3 | `orders.account_id` ↔ `strategy_cycle→strategy.account_id` | 주문이 계좌를 직접 가짐 | 생성 시 복사(별도 동기화 없음, 계좌는 전략 생성 후 불변) | 불일치 0/3,520 |
| 4 | `orders.ticker` ↔ `strategy.ticker` | 주문이 종목을 직접 가짐 | 생성 시 복사 | 불일치 0/3,520 |
| 5 | `strategy_cycle.strategy_id` ↔ `strategy_version.strategy_id`(`strategy_cycle.strategy_version_id` 경유) | 사이클이 전략과 버전을 둘 다 가리킴 | 사이클 생성 시 기록 | 불일치 0/98 |
| 6 | `cycle_capital_event.strategy_cycle_id` ↔ `cycle_position.strategy_cycle_id`(이벤트의 `cycle_position_id` 경유) | 이벤트가 사이클과 포지션을 둘 다 가리킴 | 이벤트 생성 시 기록(`CapitalLedgerService.java:103,166`, `CycleSnapshotCreator.java:132`, `StrategyUpdateWriter.java:129`) | 불일치 0/5 |
| 7 | `strategy_vr_version.initial_gradient`·`initial_pool_limit_rate` ↔ `strategy_cycle_vr.gradient`·`pool_limit_rate` | 사이클 시작 시 버전 초기값이 사이클 상세로 복사됨 | 사이클 생성 시 `VrStrategyDetailService.java:37-41` `saveInitialCycleDetail`이 `vrDetail.gradientAt(0)`·`poolLimitRateAt(0)`을 사이클 상세로 복사. 사이클 상세는 `updated_at`이 있어 이후 갱신됨 | 사이클 42개 중 gradient 42개·pool_limit_rate 41개가 초기값과 같음(1개는 갱신 후 값) |
| 8 | `dca_execution.rule_key`·`direction` ↔ `strategy_dca_rule.rule_key`·`direction` | 실행 기록이 규칙 식별자를 복사 | 실행 시 기록 | `rule_key`가 현재 규칙에 없는 실행 0/84 |
| 9 | `finance_transactions`·`finance_asset_snapshots`·`finance_budgets`의 `user_id`·`group_id` ↔ 카테고리·계좌의 `user_id`·`group_id` | 가계부는 모든 테이블이 `user_id`와 `group_id`(공유 축)를 따로 가짐(옛 단일 이력 V17 "finance ownership dual axis", 현재 root V1 baseline에 흡수되어 DDL 원본은 확인 불가) | 서비스가 `shareToGroup ? currentGroupId : null`로 채움(`FinanceTransactionService.java:48-49`, `FinanceAccountService.java:43-44`) | 거래 804건 중 `group_id`가 카테고리의 `group_id`와 같은 것 445, 거래만 NULL 28, 카테고리만 NULL 298, 둘 다 NULL 33(축이 서로 독립이라 같아야 할 필요는 없는 설계) · 거래 `user_id`≠카테고리 `user_id` 0 · 스냅샷 `user_id`≠계좌 `user_id` 0 |
| 10 | `accounts.user_id` → `public.users.id` | 서비스 간 FK 금지라 FK 없음 | 없음(고아 감지 코드 미확인) | `users`에 없는 계좌 소유자 0/13 |

### D-2. 파생값을 저장한 컬럼

| 컬럼 | 파생 원본 | 근거 |
|---|---|---|
| `kista_ref.housing_benchmark_prices.fifth_quintile_ratio` | `fifth_quintile_price / first_quintile_price` | 2,236행 모두 절대오차 8e-5 이내로 일치. 원천(KB부동산 "5분위배율" `KbLandHousingBenchmarkAdapter.java:222`)이 주는 값을 그대로 저장 — 계산 컬럼이 아니라 원천값이지만 두 가격에서 재계산 가능 |
| `public.finance_accounts.account_no_hash`·`trading.accounts.account_no_hash` | `account_no`(암호문)의 HMAC-SHA256(`AccountNoHasher`) | 암호문 컬럼을 쿼리 조건에 쓰지 못하는 규칙 때문에 저장. 쓰기 `FinanceAccountPersistenceAdapter.java:56`, `AccountPersistenceAdapter.java:52-75`. 부분 유일 인덱스 `uq_accounts_account_no_hash`가 받침(E절) |
| `trading.user_notify_profile.is_active` | `users.status = 'ACTIVE'` | 복제본(D-1 #1) |
| `trading.strategy_cycle.end_date`·`end_amount` | 마지막 포지션·종료 시점 | 완료 사이클에서만 채워짐(98행 중 NULL 67 = 진행 중 또는 삭제 사이클). 읽기·쓰기 경로 모두 코드에 있음 |
| `trading.cycle_position.deposit`·`avg_price`·`holdings`·`closing_price` | `orders` 체결 누적에서 이론상 재계산 가능하나 원장으로 쓰이는 **정본 스냅샷** | 쓰기 위치는 `CyclePositionPersistenceAdapter`(F·H와 별개). 재계산 가능 여부는 미확인(원장 상한 정책 의존) |

### D-3. JSON·텍스트에 구조 데이터를 넣은 곳

| 컬럼 | 타입 | 담는 것 | 구조화 여부 |
|---|---|---|---|
| `public.admin_runtime_settings.setting_value`·`trading.trading_runtime_settings.setting_value` | jsonb | 키 → 설정값(각 1행) | key-value 설정 테이블. `Entity` 필드는 `String`(`RuntimeSettingsEntity`·`TradingPolicySettingsEntity`) |
| `trading.user_notify_profile.notification_prefs` | text(NOT NULL) | `NotificationType → Boolean` 맵을 JSON 문자열로 | 원본은 `public.user_notification_prefs(user_id, type, enabled)` 행 단위 — 같은 구조를 한쪽은 행으로, 복제본은 JSON 텍스트로 저장 |
| `public.user_settings.strategy_suggestions` | jsonb(nullable) | 전략 제안 문자열 목록(`List<String>`) | 컬럼 2개짜리 테이블(`user_id`, 이 컬럼). 운영 2행 |
| `public.app_error_logs.context` | jsonb | 오류 컨텍스트 | 비정형 로그 |
| `public.audit_logs.payload` | jsonb | 관리자 행위 페이로드(`Map<String,Object>`) | 비정형 로그 |
| `trading.dca_execution.detail` | varchar(500) | 스킵 사유 설명 문장 | 사람이 읽는 문장(`DcaExecutionPersistenceAdapter.java:37`에서 길이 절단) |

숫자 요약: 복제·중복 컬럼 쌍 10건 중 운영 불일치 1건(사용자 복제본의 `notification_channel` 2/2 NULL) / 읽기 0인 중복 1건(`finance_groups.owner_user_id`) / 파생 저장 4건 / JSON·텍스트 구조 데이터 6건.
미확인: ① `users.notification_channel` 변경이 복제본 `notification_channel`에 반영되지 않은 원인(`UserNotifyProfilePersistenceAdapter.java:93` `applyChange`는 `channel != null`일 때만 덮어쓰고, V5 이후 변경 이벤트가 한 번도 안 왔을 가능성 — 운영 Redis 스트림·EPR 확인 필요). ② 고아 계좌 소유자 감지 코드 유무.

## E. 인덱스·유일 제약

운영 인덱스는 105개(Flyway 이력 제외) — 그중 `idx_scan=0`이 51개. 통계 리셋 시각은 조회되지 않았고(`pg_stat_database.stats_reset` 빈 값) 서버 기동은 2026-10-13이다. 테이블이 수천 행 이하라 플래너가 순차 스캔을 택하는 탓이 커서 `idx_scan=0`은 "불필요"의 증거가 아니라 "현재 규모에서 안 쓰임"의 증거일 뿐이다.

### E-1. 테이블별 인덱스·유일 제약 (운영)

| 테이블 | 인덱스·유일 제약(운영 `pg_indexes`, `[scan]`=`pg_stat_user_indexes.idx_scan` 누적) |
|---|---|
| finance.finance_accounts | PK `finance_accounts_pkey` (id) [scan 0]<br>UQ `uq_finance_accounts_account_no_hash` (account_no_hash) WHERE ((deleted_at IS NULL) AND (account_no_hash IS NOT NULL)) [scan 0] |
| finance.finance_asset_snapshots | PK `finance_asset_snapshots_pkey` (id) [scan 152]<br>IDX `idx_finance_asset_snapshots_category_id` (category_id) [scan 0]<br>IDX `idx_finance_asset_snapshots_group_entry_date` (group_id, entry_date DESC) WHERE (deleted_at IS NULL) [scan 2]<br>IDX `idx_finance_asset_snapshots_personal_entry_date` (user_id, entry_date DESC) WHERE ((group_id IS NULL) AND (deleted_at IS NULL)) [scan 0] |
| finance.finance_budgets | IDX(gist) `finance_budgets_group_no_overlap` (group_id, category_id, daterange(start,end)) WHERE (group_id IS NOT NULL) [scan 0]<br>IDX(gist) `finance_budgets_personal_no_overlap` (user_id, category_id, daterange(start,end)) WHERE (group_id IS NULL) [scan 0]<br>PK `finance_budgets_pkey` (id) [scan 0]<br>IDX `idx_finance_budgets_group_category_start` (group_id, category_id, apply_start_date DESC) [scan 0] |
| finance.finance_categories | PK `finance_categories_pkey` (id) [scan 0]<br>IDX `idx_finance_categories_group_type` (group_id, type) WHERE (deleted_at IS NULL) [scan 0]<br>IDX `idx_finance_categories_parent_id` (parent_id) [scan 0]<br>UQ `uq_finance_categories_group_parent_name` (group_id, COALESCE(parent_id,nil), name) WHERE ((group_id IS NOT NULL) AND (deleted_at IS NULL)) [scan 0]<br>UQ `uq_finance_categories_personal_parent_name` (user_id, COALESCE(parent_id,nil), name) WHERE ((group_id IS NULL) AND (user_id IS NOT NULL) AND (deleted_at IS NULL)) [scan 0] |
| finance.finance_group_invitations | PK `finance_group_invitations_pkey` (id) [scan 0]<br>IDX `idx_finance_group_invitations_group_id` (group_id) WHERE (deleted_at IS NULL) [scan 0]<br>UQ `uq_finance_group_invitations_code` (code) [scan 0] |
| finance.finance_group_members | PK `finance_group_members_pkey` (id) [scan 0]<br>IDX `idx_finance_group_members_user_id` (user_id) WHERE (deleted_at IS NULL) [scan 0]<br>UQ `uq_finance_group_members_group_user` (group_id, user_id) WHERE (deleted_at IS NULL) [scan 0]<br>UQ `uq_finance_group_members_one_active_group` (user_id) WHERE (deleted_at IS NULL) [scan 0] |
| finance.finance_groups | PK `finance_groups_pkey` (id) [scan 2288] |
| finance.finance_monthly_closings | PK `finance_monthly_closings_pkey` (id) [scan 0]<br>UQ `uq_finance_monthly_closings_group_month` (group_id, month) WHERE (group_id IS NOT NULL) [scan 59]<br>UQ `uq_finance_monthly_closings_personal_month` (user_id, month) WHERE ((group_id IS NULL) AND (user_id IS NOT NULL)) [scan 0] |
| finance.finance_transactions | PK `finance_transactions_pkey` (id) [scan 77]<br>IDX `idx_finance_transactions_category_id` (category_id) [scan 0]<br>IDX `idx_finance_transactions_group_date` (group_id, transaction_date DESC) WHERE (deleted_at IS NULL) [scan 55]<br>IDX `idx_finance_transactions_personal_date` (user_id, transaction_date DESC) WHERE ((group_id IS NULL) AND (deleted_at IS NULL)) [scan 0] |
| kista_ref.fear_greed_snapshots | PK `fear_greed_snapshots_pkey` (id) [scan 0]<br>UQ `uq_fear_greed_source_date` (source, snapshot_date) [scan 742] |
| kista_ref.housing_benchmark_prices | PK `housing_benchmark_prices_pkey` (id) [scan 1]<br>IDX `idx_housing_benchmark_prices_metric_region_month` (metric_code, region_code, base_month) [scan 5]<br>UQ `uq_housing_benchmark_prices_source_metric_region_month` (source, metric_code, region_code, base_month) [scan 680] |
| kista_ref.housing_price_indices | PK `housing_price_indices_pkey` (id) [scan 0]<br>IDX `idx_housing_price_indices_metric_region_date` (metric_code, region_code, base_date) [scan 4]<br>UQ `uq_housing_price_indices_source_metric_region_date` (source, metric_code, region_code, base_date) [scan 32064] |
| kista_ref.market_index_prices | PK `market_index_prices_pkey` (id) [scan 0]<br>UQ `uq_market_index_prices_symbol_date` (symbol, trade_date) [scan 193] |
| public.admin_runtime_settings | PK `admin_runtime_settings_pkey` (setting_key) [scan 7] |
| public.app_error_logs | PK `app_error_logs_pkey` (id) [scan 50]<br>IDX `idx_app_error_logs_created_at` (created_at DESC) [scan 169] |
| public.audit_logs | PK `audit_logs_pkey` (id) [scan 0] |
| public.event_publication | IDX `event_publication_by_completion_date_idx` (completion_date) [scan 78]<br>PK `event_publication_pkey` (id) [scan 636]<br>IDX(hash) `event_publication_serialized_event_hash_idx` (serialized_event) [scan 0] |
| public.fcm_device_tokens | PK `fcm_device_tokens_pkey` (id) [scan 0]<br>UQ `fcm_device_tokens_token_key` (token) [scan 36] |
| public.refresh_tokens | IDX `idx_refresh_tokens_user_id` (user_id) [scan 0]<br>PK `refresh_tokens_pkey` (id) [scan 0]<br>UQ `uq_refresh_tokens_token_hash` (token_hash) [scan 0] |
| public.scheduler_locks | IDX `idx_scheduler_locks_lock_until` (lock_until) [scan 0]<br>PK `scheduler_locks_pkey` (name) [scan 160] |
| public.user_notification_prefs | PK `user_notification_prefs_pkey` (user_id, type) [scan 0] |
| public.user_settings | PK `user_settings_pkey` (user_id) [scan 0] |
| public.users | UQ `uq_users_kakao_id_active` (kakao_id) WHERE (deleted_at IS NULL) [scan 0]<br>PK `users_pkey` (id) [scan 4] |
| trading.accounts | PK `accounts_pkey` (id) [scan 5201]<br>IDX `idx_accounts_user_id` (user_id) [scan 0]<br>UQ `uq_accounts_account_no_hash` (account_no_hash) WHERE ((deleted_at IS NULL) AND (account_no_hash IS NOT NULL)) [scan 0] |
| trading.broker_tokens | PK `broker_tokens_pkey` (account_id) [scan 12] |
| trading.cycle_capital_event | PK `cycle_capital_event_pkey` (id) [scan 0]<br>IDX `idx_cycle_capital_event_strategy_cycle_id` (strategy_cycle_id) [scan 81] |
| trading.cycle_position | PK `cycle_position_pkey` (id) [scan 8095]<br>IDX `idx_cycle_position_strategy_cycle_id` (strategy_cycle_id) [scan 21762] |
| trading.cycle_position_infinite | PK `cycle_position_infinite_pkey` (cycle_position_id) [scan 8315] |
| trading.dca_execution | PK `dca_execution_pkey` (id) [scan 0]<br>UQ `uq_dca_execution_strategy_rule_trade_date` (strategy_id, rule_key, trade_date) [scan 1993] |
| trading.event_publication | IDX `event_publication_by_completion_date_idx` (completion_date) [scan 136]<br>PK `event_publication_pkey` (id) [scan 495]<br>IDX(hash) `event_publication_serialized_event_hash_idx` (serialized_event) [scan 0] |
| trading.orders | IDX `idx_orders_account_date_status_direction` (account_id, trade_date, status, direction) [scan 1228]<br>IDX `idx_orders_account_date_ticker_direction_status` (account_id, trade_date, ticker, direction, status) [scan 2611]<br>IDX `idx_orders_cycle_date_status` (strategy_cycle_id, trade_date, status) [scan 2146]<br>IDX `idx_orders_cycle_date_timing_status` (strategy_cycle_id, trade_date, timing, status) [scan 6264]<br>IDX `idx_orders_trade_date` (trade_date) [scan 12053]<br>PK `orders_pkey1` (id) [scan 9535] |
| trading.scheduler_locks | IDX `idx_scheduler_locks_lock_until` (lock_until) [scan 0]<br>PK `scheduler_locks_pkey` (name) [scan 3859] |
| trading.strategy | IDX `idx_strategy_account_id` (account_id) [scan 8084]<br>PK `strategy_pkey` (id) [scan 740]<br>UQ `uq_strategy_account_ticker_active` (account_id, ticker) WHERE ((deleted_at IS NULL) AND ((status)::text <> 'ARCHIVED'::text)) [scan 0] |
| trading.strategy_cycle | IDX `idx_strategy_cycle_strategy_id` (strategy_id) [scan 508]<br>PK `strategy_cycle_pkey` (id) [scan 1946] |
| trading.strategy_cycle_vr | PK `strategy_cycle_vr_pkey` (strategy_cycle_id) [scan 0] |
| trading.strategy_dca_condition | PK `strategy_dca_condition_pkey` (id) [scan 0]<br>UQ `uq_strategy_dca_condition_rule_sort` (rule_id, sort_order) [scan 4123] |
| trading.strategy_dca_rule | PK `strategy_dca_rule_pkey` (id) [scan 19]<br>UQ `uq_strategy_dca_rule_version_rule_key` (strategy_version_id, rule_key) [scan 0]<br>UQ `uq_strategy_dca_rule_version_sort` (strategy_version_id, sort_order) [scan 7777] |
| trading.strategy_infinite_version | PK `strategy_infinite_version_pkey` (strategy_version_id) [scan 6] |
| trading.strategy_version | PK `strategy_version_pkey` (id) [scan 10]<br>UQ `uq_strategy_version_strategy_version_no` (strategy_id, version_no) [scan 3641] |
| trading.strategy_vr_version | PK `strategy_vr_version_pkey` (strategy_version_id) [scan 8] |
| trading.trading_batch_report | PK `pk_trading_batch_report` (trade_date, strategy_id) [scan 124] |
| trading.trading_batch_run | PK `pk_trading_batch_run` (job_name, trade_date) [scan 181] |
| trading.trading_runtime_settings | PK `trading_runtime_settings_pkey` (setting_key) [scan 0] |
| trading.user_notify_profile | PK `user_notify_profile_pkey` (user_id) [scan 14892] |
| trading_ref.privacy_trade_base_orders | PK `privacy_trade_base_orders_pkey` (id) [scan 0] |
| trading_ref.privacy_trade_bases | PK `privacy_trade_bases_pkey` (id) [scan 1131]<br>UQ `uq_privacy_trade_bases_release_date_ticker` (release_date, ticker) [scan 3887] |
| trading_ref.us_market_holidays | PK `us_market_holidays_pkey` (id) [scan 0]<br>UQ `us_market_holidays_trade_date_key` (trade_date) [scan 0] |
| trading_ref.us_market_sessions | PK `us_market_sessions_pkey` (trade_date) [scan 1280] |

배타 제약 2개(`EXCLUDE USING gist`): `finance_budgets_group_no_overlap`·`finance_budgets_personal_no_overlap`(카테고리·기간 겹침 금지).

### E-2. 인덱스가 받쳐 주지 않는 조회

JpaRepository 메서드·`@Query`의 WHERE/ORDER BY를 현재 인덱스와 대조했다(`TC/trading/adapter/out/persistence/*JpaRepository.java`, `API/*/adapter/out/persistence/*JpaRepository.java`). 행 수가 작아 지금 체감은 없고, 증가 시 먼저 걸릴 곳 순이다.

| 조회 | 위치 | 컬럼 조합 | 받치는 인덱스 | 행 수 |
|---|---|---|---|---|
| `PrivacyTradeBaseEntity.orders` `@OneToMany` 로드 | `PrivacyTradeBaseEntity`(`orders` 필드) | `privacy_trade_base_orders.privacy_trade_id = ?` | **없음**(FK 컬럼, PK만 있음) | 1,385 |
| `findNotActiveWithReportableOrders`·`findAllActiveStrategies` | `StrategyJpaRepository.java:46-66` | `strategy.status` 조건 + `JOIN accounts`·`user_notify_profile` | `status` 인덱스 없음(`idx_strategy_account_id`만) | 71 |
| `findByStrategyIdAndDateRange`·`findByUserIdAndRange` 등 | `CyclePositionJpaRepository.java:53-129` | `cycle_position.created_at` 범위·`ORDER BY created_at` | `idx_cycle_position_strategy_cycle_id (strategy_cycle_id)`만 — 사이클 단위 필터 후 정렬 | 680 |
| 사이클 최신 1건 | `StrategyCycleJpaRepository.java:24-40`(`findTop1ByStrategyIdOrderByCreatedAtDesc`, `DISTINCT ON (strategy_id) … ORDER BY created_at DESC`) | `(strategy_id, created_at)` | `idx_strategy_cycle_strategy_id (strategy_id)`만 | 98 |
| `findAllByUserId`·`deleteByUserIdAndToken`·`deleteAllByUserId` | `FcmDeviceTokenJpaRepository.java:12-14` | `fcm_device_tokens.user_id` | **없음**(`token` 유일뿐) | 2 |
| `findMyScope` | `FinanceAccountJpaRepository.java:17-19` | `finance_accounts` `user_id`·`group_id` | **없음** | 32 |
| `existsByAccountIdAndDeletedAtIsNull` | `FinanceAssetSnapshotJpaRepository.java:30` | `finance_asset_snapshots.account_id` | **없음**(FK 컬럼) | 1,056 |
| `findTop100ByOrderByCreatedAtDesc`·기간 조회 | `AuditLogJpaRepository.java:13-17` | `audit_logs.created_at` | **없음**(PK만) | 37 |
| `findByTelegramChatId`·`findAllByStatus*` | `UserJpaRepository.java:17-22` | `users.telegram_chat_id`·`status` | **없음** | 2 |
| `deleteAllByExpiresAtBefore`·`deleteAllByRotatedAtBefore` | `RefreshTokenJpaRepository.java:40,51` | `expires_at`·`rotated_at` 범위 삭제 | **없음** | 6 |

`orders`는 전 조회가 받쳐진다: `idx_orders_cycle_date_status`·`idx_orders_cycle_date_timing_status`(사이클·거래일·상태·타이밍), `idx_orders_account_date_status_direction`·`idx_orders_account_date_ticker_direction_status`(예산 합계 쿼리 `OrderJpaRepository.java:65-87`), `idx_orders_trade_date`(범위). 기간 조회의 `ORDER BY trade_date DESC, created_at DESC`는 정렬 단계가 남는다.

### E-3. FK 컬럼 중 선두 인덱스가 없는 것

운영 FK 46개 중 선두 컬럼 인덱스가 없는 FK 12개: `audit_logs.admin_id`, `fcm_device_tokens.user_id`, `finance_accounts.user_id`·`group_id`, `finance_asset_snapshots.account_id`, `finance_budgets.category_id`, `finance_group_invitations.invited_by`·`invitee_user_id`, `finance_groups.owner_user_id`, `trading.cycle_capital_event.cycle_position_id`, `trading.strategy_cycle.strategy_version_id`, `trading_ref.privacy_trade_base_orders.privacy_trade_id`. 부모 행 삭제·갱신 때 자식 스캔 비용인데 부모가 소프트 삭제라 실삭제는 사용자 탈퇴 cascade에서만 일어난다.

### E-4. `idx_scan=0` 인덱스 (51개, 테이블 크기 순 주요 항목)

| 인덱스 | 크기 | 비고 |
|---|---|---|
| `kista_ref.housing_price_indices_pkey` | 1.4MB | 조회는 `uq_…_source_metric_region_date`(32,064회)와 `idx_…_metric_region_date`(4회)가 받음. PK 인덱스는 조회에 안 쓰임 |
| `kista_ref.market_index_prices_pkey` | 0.7MB | 조회는 `uq_market_index_prices_symbol_date`가 받음 |
| `kista_ref.fear_greed_snapshots_pkey` | 168KB | 동일 |
| `trading_ref.privacy_trade_base_orders_pkey` | 64KB | |
| `public.event_publication_serialized_event_hash_idx`·`trading.event_publication_…_hash_idx` | 64KB·32KB | Modulith 라이브러리 소유 |
| `finance_*`·`public.*`·`trading.*`의 나머지 45개 | 각 8~16KB | 전부 행 100건 미만 테이블 위(가계부 유일·부분 인덱스 포함). `housing_price_indices` 안에서 `idx_…_metric_region_date`가 `uq_…`와 앞 3열이 같아 사실상 중복(`metric_code, region_code, base_date` vs `source, metric_code, region_code, base_date`, 둘 다 사용 중) |

### E-5. 업무상 유일해야 하는데 제약이 없는 것

| 후보 | 현재 제약 | 운영 위반 조회 결과 | 근거 |
|---|---|---|---|
| **`orders`의 (사이클, 거래일, 슬롯)** — 슬롯 식별 컬럼은 **`order_leg`**(varchar(50) NOT NULL, `OrderEntity.java:58-59` 기본값 `Order.UNKNOWN_LEG`="UNKNOWN"). 값은 `PlannedOrder.leg(prefix, 순번)`이 만든다(`INFINITE_CORRECTION_01`, `VR_BUY_01`, `PRIVACY_BUY_01`, DCA는 `DcaRule.orderLeg()` = 접두사+ruleKey). PLANNED 저장 경로는 `OrderPersistenceAdapter.saveAll`(`:42`)·`save`(`:48`)로 중복 검사 없이 INSERT하고, 재계산은 `deletePlannedByCycleAndDate`(`:177`)로 PLANNED만 지운 뒤 다시 넣는다 | `orders`에 PK 외 유일 제약 0(인덱스 6개 전부 비유일, `orders_pkey1` 포함). 지침서 05 M1이 이미 지적 | 전체 3,520행: `(strategy_cycle_id, trade_date, order_leg)` 중복 **216그룹/619행** — 그중 `UNKNOWN` leg(2026-06-17~07-17, 342행, leg 도입 전 주문) 제외 137그룹/277행. 이 137그룹 중 **1그룹(FAILED+FILLED)만 취소 아닌 행을 가지고 나머지 136그룹은 전부 CANCELLED끼리의 중복**(같은 슬롯에 취소 행이 누적됨). 상태 `PLANNED·SUBMITTING·PLACED·FILLED`(살아 있는 행)만 보면 UNKNOWN 제외 중복 **0건**. UNKNOWN 중 살아 있는 행은 `(cycle,date,direction,order_type,timing,price)` 기준 9그룹/18행 중복(레거시) | 
| `strategy_cycle`당 열린 사이클 1개 | 없음(`idx_strategy_cycle_strategy_id` 비유일) | 위반 0: 살아 있는 사이클이 전략당 최대 1개, 살아 있는 전략 중 열린 사이클 0개인 것 0 | `strategy_cycle` 쿼리(`StrategyCycleJpaRepository.java:24-40`)가 "최신 1건"에 의존 |
| `cycle_position`의 (사이클, 순번) | 없음 | `(strategy_cycle_id, created_at)` 중복 0 | 포지션 순서는 `created_at`뿐(순번 컬럼 없음) |
| `trading.orders.external_order_id`(계좌·거래일) | 없음(`existsByStrategyCycleIdAndTradeDateAndExternalOrderId`로 앱이 확인, `OrderPersistenceAdapter.java:114`) | `(account_id, trade_date, external_order_id)` 중복 0 | |

이미 유일 제약이 있는 것(참고): `strategy`(`uq_strategy_account_ticker_active`), `strategy_version`(`(strategy_id, version_no)`), `dca_execution`(`(strategy_id, rule_key, trade_date)`), `strategy_dca_rule`(`(version, rule_key)`·`(version, sort_order)`), `strategy_dca_condition`(`(rule_id, sort_order)`), `trading_batch_run`·`trading_batch_report` PK, `privacy_trade_bases`(`(release_date, ticker)`), `accounts`·`finance_accounts` 계좌번호 해시.

### E-6. soft delete 테이블의 유일 제약이 삭제 행을 고려하는지

| 테이블 | 유일 제약 | 삭제 행 고려 |
|---|---|---|
| `public.users` | `uq_users_kakao_id_active (kakao_id) WHERE deleted_at IS NULL` | 예(부분 인덱스) |
| `trading.accounts` | `uq_accounts_account_no_hash WHERE deleted_at IS NULL AND account_no_hash IS NOT NULL` | 예 |
| `trading.strategy` | `uq_strategy_account_ticker_active (account_id, ticker) WHERE deleted_at IS NULL AND status <> 'ARCHIVED'` | 예 |
| `trading.strategy_version` | `uq_strategy_version_strategy_version_no (strategy_id, version_no)` 비부분 | **의도적으로 삭제 행 포함**(`StrategyVersionJpaRepository.java:27-28` 주석: 삭제 버전 포함 MAX로 `version_no` 재사용 방지) |
| `finance.finance_accounts` | `uq_finance_accounts_account_no_hash WHERE deleted_at IS NULL AND account_no_hash IS NOT NULL` | 예 |
| `finance.finance_categories` | `uq_…_group_parent_name`·`uq_…_personal_parent_name` 모두 `deleted_at IS NULL` | 예 |
| `finance.finance_group_members` | `uq_…_group_user`·`uq_…_one_active_group` 모두 `deleted_at IS NULL` | 예 |
| `finance.finance_group_invitations` | `uq_…_code (code)` 비부분 | 삭제 행 포함(초대 코드는 재사용 안 함) |
| `strategy_cycle`·`cycle_position`·`app_error_logs`·`finance_groups`·`finance_asset_snapshots`·`finance_transactions` | 업무 유일 제약 자체가 없음 | 해당 없음 |

숫자 요약: 운영 인덱스 105개 중 `idx_scan=0` 51개(전부 소규모 테이블 위 + PK/유일 인덱스 중 조회 미사용) / FK 46개 중 선두 인덱스 없음 12개 / `orders` 슬롯 유일 제약 없음 — 살아 있는 행 기준 위반 0(UNKNOWN 제외), 취소 포함 시 137그룹 / 소프트 삭제 테이블의 유일 제약 8개 중 6개가 부분 인덱스, 2개(`strategy_version`·`finance_group_invitations`)가 의도적 비부분.
미확인: 실제 쿼리 플랜(`EXPLAIN`)은 돌리지 않았다. E-2는 컬럼 조합 대조만이다. `privacy_trade_base_orders` 로드 쿼리가 실제로 `privacy_trade_id` 조건을 쓰는지(Hibernate 생성 SQL) 확인하지 못했다.

## F. soft delete 일관성

### F-1. `deleted_at` 테이블 전수 — 문서(6개)와 실제(16개) 대조

`docs/agents/constraints.md`는 "`users`, `accounts`, `strategy`, `strategy_cycle`, `cycle_position`, `app_error_logs`" 6개라고 적었다. 운영에는 `deleted_at` 컬럼이 **16개 테이블**에 있다. `@SQLRestriction("deleted_at IS NULL")` 애너테이션은 14개 Entity에 선언돼 있다(`^@SQLRestriction` grep 14건, 지침서 02의 "29곳"은 주석·javadoc 언급까지 센 값으로 보인다 — 애너테이션 기준은 14). 삭제 방식은 전부 **update**(`SET deleted_at = :now`)이고 `@SQLDelete`는 0건이다.

| 테이블 | `@SQLRestriction` | 삭제 쓰기 위치 | 운영 삭제/전체 |
|---|---|---|---|
| `public.users` | 있음(`UserEntity.java:20`) | `UserPersistenceAdapter.java:73` `softDeleteById` | 0/2 |
| `public.app_error_logs` | 있음(`AppErrorLogEntity.java:17`) | `AdminQueryService.java:153` `appErrorLogPort.softDelete`(관리자가 "처리" 표시용) | **566/567** — 삭제가 곧 처리 완료 표시라 사실상 상태값 |
| `trading.accounts` | 있음(`AccountEntity.java:20`) | `AccountPersistenceAdapter.java:59,64` | 5/13 |
| `trading.strategy` | 있음(`StrategyEntity.java:21`) | `StrategyPersistenceAdapter.java:85,90,95` | 63/71 |
| `trading.strategy_version` | 있음(`StrategyVersionEntity.java:17`) | `StrategyVersionPersistenceAdapter.java:55`(`softDeleteActiveByStrategyId`) — 재버전·DCA 재설정 때 호출(`CycleSnapshotCreator.java:147`, `DcaConfigService.java:47`) | 20/82 |
| `trading.strategy_infinite_version` | 있음(`StrategyInfiniteEntity.java:19`) | **현재 main 코드에 쓰기 없음**(`setDeletedAt` 호출 0, 트리거 0) | 8/22 — 과거 경로로 만들어진 값(원인 미확인) |
| `trading.strategy_cycle` | 있음(`StrategyCycleEntity.java:19`) | `StrategyCyclePersistenceAdapter.java:98,103,108` | 87/98 |
| `trading.cycle_position` | 있음(`CyclePositionEntity.java:18`) | `CyclePositionPersistenceAdapter.java:141,146,151` | 484/680 |
| `trading.cycle_position_infinite` | 있음(`CyclePositionInfiniteEntity.java:19`) | **현재 main 코드에 쓰기 없음** | 16/283 |
| `finance.finance_groups` | 있음 | `FinanceGroupPersistenceAdapter.java:93` | 2/3 |
| `finance.finance_group_members` | 있음 | `FinanceGroupPersistenceAdapter.java:88` | 2/4 |
| `finance.finance_group_invitations` | 있음 | 쓰기 경로 미발견(`softDelete*` 호출 없음) | 0/4 |
| `finance.finance_transactions` | 있음 | `FinanceTransactionPersistenceAdapter.java:43,48` | 88/804 |
| `finance.finance_asset_snapshots` | 있음 | `AssetSnapshotPersistenceAdapter.java:48,53` | 202/1,056 |
| `finance.finance_accounts` | **의도적으로 없음**(`FinanceAccountEntity.java:16-19` 주석: 계좌 삭제 후에도 남은 스냅샷 조회가 깨지므로) | `FinanceAccountPersistenceAdapter.java:63,68` | 4/32 |
| `finance.finance_categories` | **의도적으로 없음**(`FinanceCategoryEntity.java:15-18` 주석: 삭제 카테고리를 참조하는 거래 조회 보존) | `FinanceCategoryPersistenceAdapter.java:54,59`(재귀 CTE `softDeleteWithChildren`) | 11/75 |

`@SQLRestriction`이 없는 두 테이블(`finance_accounts`, `finance_categories`)은 조회 쿼리가 `deleted_at IS NULL`을 직접 쓴다(`FinanceAccountJpaRepository.java:17`·`:21`, `FinanceCategoryJpaRepository.java:22`·`:30`).

### F-2. `nativeQuery = true` 쿼리의 `deleted_at IS NULL` 누락

`@Query(nativeQuery = true)` 전수 55개를 스크립트로 읽어 소프트 삭제 테이블을 FROM/JOIN하는 44개에서 `deleted_at` 언급 수가 참조 테이블 수에 못 미치는 것을 골랐다. JdbcTemplate·`EntityManager` native는 소프트 삭제 테이블을 건드리지 않는다(5개 파일 모두 `housing_*`·`trading_batch_*`·`scheduler_locks`·`orders` 한정).

| 위치 | 내용 | 판정 |
|---|---|---|
| `StrategyVersionJpaRepository.java:28` | `SELECT COALESCE(MAX(version_no),0) FROM strategy_version WHERE strategy_id = …` | 의도적 누락 — 삭제 버전 포함 MAX로 `version_no` 재사용 방지(`:27` 주석, `uq_strategy_version_strategy_version_no`가 삭제 행도 포함) |
| `FinanceCategoryJpaRepository.java:48,57` | `shareToGroupWithChildren`·`unshareWithChildren` 재귀 CTE UPDATE에 `deleted_at` 조건 없음 | 삭제된 하위 카테고리의 `group_id`까지 갱신된다(의도 여부 미확인, 삭제 행이라 조회에는 영향 없음) |
| 그 외 41개 | 참조하는 소프트 삭제 테이블마다 `deleted_at IS NULL`이 있다(대표: `CyclePositionJpaRepository.java:66,78,104`는 4개 테이블 모두) | 누락 없음 |

한 가지 구조적 사실: `OrderJpaRepository.java:93-160`의 `orders` 조회는 JOIN한 `accounts`·`strategy_cycle`만 `deleted_at`으로 거른다. `orders`는 소프트 삭제 컬럼이 없다.

### F-3. cascade를 서비스에서 처리하는 위치

| 흐름 | 코드 | 순서 |
|---|---|---|
| trading 사용자 삭제 | `TC/trading/application/service/UserTradingDataEraseService.java:64-67` | 포지션 → 사이클 → 전략 → 계좌(`accountUseCase.deleteAllByUserId`) |
| 이벤트 진입 | `TC/trading/adapter/in/event/TradingUserCascadeListener.java`·`AccountCascadeListener.java`, Redis `UserEventStreamConsumer` | root 사용자 삭제 이벤트를 trading이 받아 위 서비스 호출 |
| 전략 단건 삭제 | `TC/trading/application/service/StrategyStateWriter.java:41-43` | 포지션(`deleteByStrategyId`) → 사이클 → 전략 — **버전·버전 상세는 건드리지 않음** |
| 가계부 사용자 삭제 | `API/finance/application/service/FinanceUserDataEraseService.java:35-38` | 거래 → 스냅샷 → 계좌 → 카테고리 |
| 가계부 멤버십 | `GroupMembershipSupport.java:25,32` | 멤버십 삭제, 마지막 멤버면 그룹 삭제 |

DB FK는 `ON DELETE CASCADE`(`c`)가 trading 쪽 대부분에 걸려 있으나(`confdeltype` 조회, 예: `orders_strategy_cycle_id_fkey`) 소프트 삭제라 실행되지 않는다.

### F-4. 부모만 삭제되고 자식이 살아 있는 관계 (운영 SELECT 결과)

| 관계(부모 삭제 / 자식 생존) | 건수 | 비고 |
|---|---|---|
| `strategy` 삭제 / `strategy_version` 생존 | **54** | `StrategyStateWriter.java:41-43`이 버전을 안 지운다. 생존 버전이 달린 삭제 전략의 삭제 일자는 2026-06-30~11-06 |
| `strategy` 삭제 / `strategy_cycle` 생존 | **3** | 모두 2026-07-26 같은 날 삭제된 VR·PRIVACY·INFINITE 전략의 사이클(각각 생존 포지션 1개, 주문 0). 단발 사고로 보임 |
| `cycle_position` 삭제 / `cycle_position_infinite` 생존 | **173** | 포지션만 지우고 상세는 안 지움. 상세 조회가 `cp.deleted_at IS NULL`을 함께 걸어(`CyclePositionInfiniteJpaRepository.java:18-19,33-34`) 가려질 뿐 |
| `strategy_version` 삭제 / `strategy_infinite_version` 생존 | 0 | 현재는 일치(과거 경로로 동기화됨) |
| `strategy_cycle` 삭제 / `cycle_position` 생존 | 0 | 위 사고 제외 |
| `accounts` 삭제 / `strategy` 생존 | 0 | |
| `users` 삭제 / `accounts` 생존 | 0 | 삭제 사용자 0명 |
| `finance_groups` 삭제 / `finance_group_invitations` 생존 | **2** | 초대는 삭제 경로 자체가 없음 |
| `finance_categories` 삭제 / `finance_budgets` 생존 | **2** | `finance_budgets`는 `deleted_at` 없음(하드 삭제, `FinanceBudgetJpaRepository.java:40`) |
| `finance_groups` 삭제 / `finance_group_members` 생존 | 0 | |
| `finance_accounts` 삭제 / `finance_asset_snapshots` 생존 | 0 | |
| `finance_categories` 삭제 / `finance_transactions` 생존 | 0 | |

소프트 삭제 컬럼이 없는 자식 테이블이 삭제된 부모 아래 쌓여 있는 양:

| 자식(삭제 컬럼 없음) | 삭제된 부모 아래 행 수 | 전체 |
|---|---|---|
| `orders` — 삭제 사이클 아래 | 2,293 | 3,520 |
| `orders` — 삭제 계좌 아래 | 949 | 3,520 |
| `trading_batch_report` — 삭제 전략 | 84 | 159 |
| `strategy_cycle_vr` — 삭제 사이클 | 39 | 42 |
| `strategy_dca_rule` — 삭제 버전 | 15 | 31 |
| `dca_execution` — 삭제 전략 | 12 | 84 |
| `strategy_vr_version` — 삭제 버전 | 4 | 30 |
| `cycle_capital_event`·`broker_tokens` | 0 | 5·5 |

`orders`는 삭제 사이클 아래 2,293행(65%)이라 조회가 `strategy_cycle.deleted_at`을 조인으로 걸러야만 삭제 데이터가 안 보인다(`OrderJpaRepository.java:135,153`). `user_notify_profile`·`refresh_tokens`는 삭제 사용자 0명이라 해당 없음.

숫자 요약: `deleted_at` 테이블 16개(문서 6개) / `@SQLRestriction` 14개, 의도적 제외 2개(`finance_accounts`·`finance_categories`) / native 55개 중 의도적 누락 1건·미확인 의도 1건(카테고리 공유 CTE 2쿼리) / 부모 삭제·자식 생존 불일치 `strategy_version` 54, `cycle_position_infinite` 173, `strategy_cycle` 3, 초대 2, 예산 2 / 현재 코드에서 `deleted_at` 쓰기 없는 컬럼 2개(`strategy_infinite_version`·`cycle_position_infinite`).
미확인: `strategy_infinite_version`·`cycle_position_infinite`의 삭제 행 24건(8+16)을 만든 과거 코드 경로(git log 미조사). `finance_group_invitations`의 삭제 경로(호출 없음 — 만료·수락 시 상태값만 바꾸는 것으로 보이나 확인하지 않음).

## G. 금액·수량 타입과 자릿수

방법: 운영 `information_schema.columns`에서 `numeric`·`bigint`·`integer`·`smallint`·`double precision`·`real` 컬럼 70개(Flyway·`event_publication` 정수 컬럼 11개 제외 시 59개)를 뽑아 Entity `@Column(precision, scale)`과 Java 타입을 대조했다. 기준값: `Currency.USD.amountScale()`=2(`SH/kernel/Currency.java:11`; KRW 0, JPY 0, USDT 2), `AccountBalance.LEDGER_SCALE`=2(`TC/matching/domain/model/AccountBalance.java:17`).

**DB 타입 ↔ Entity `@Column(precision, scale)` 불일치: 0건**(`numeric` 36개 전부 일치). **`double`/`real` 컬럼: 0개.** DB·Entity에 `double`·`float` 필드는 없다. 부동소수는 계산용 지역 변수와 wire 타입에만 있다(아래 G-6).

### G-1. 금액 (원장·주문 금액)

| 컬럼 | DB 타입 | Java | Entity |
|---|---|---|---|
| `cycle_position.deposit` | numeric(20,2) | BigDecimal | `CyclePositionEntity.java:33` |
| `cycle_position.avg_price` | numeric(20,2) | BigDecimal | `:39` — 가격이지만 원장 평단이라 20 자리 |
| `strategy_cycle.start_amount`·`end_amount`·`open_amount` | numeric(20,2) | BigDecimal | `StrategyCycleEntity.java:37,40,53` |
| `cycle_capital_event.deposit_delta`·`principal_delta` | numeric(20,2) | BigDecimal | `CycleCapitalEventEntity.java:39,45` |
| `strategy_cycle_vr.value` | numeric(20,2) | BigDecimal | `StrategyCycleVrEntity.java:28` |
| `strategy_dca_rule.amount` | numeric(20,2) | BigDecimal | `StrategyDcaRuleEntity.java:77` |
| `strategy_vr_version.recurring_amount` | **integer** | int | `StrategyVrVersionEntity.java:34` — 같은 "금액"인데 정수(운영 값 100~1000) |
| `finance_transactions.amount`·`finance_budgets.amount`·`finance_asset_snapshots.amount` | **bigint** | **long** | `FinanceTransactionEntity.java:43`, `FinanceBudgetEntity.java:45`, `FinanceAssetSnapshotEntity.java:62` — 원화 정수(주석 "원화 정수 절대값") |
| `trading_ref.privacy_trade_bases.current_cycle_start`·`current_cycle_realized_pnl` | numeric(12,2) | BigDecimal | `PrivacyTradeBaseEntity.java:42,45` — 원장 금액인데 precision 12 |
| `kista_ref.housing_benchmark_prices.*_quintile_price`(5개) | numeric(18,6) | BigDecimal | `HousingBenchmarkPriceEntity.java:48-60` |

원장 금액 계열은 `numeric(20,2)` 단일 형태로 일관된다. 예외는 `recurring_amount`(integer), 가계부(bigint/long, 통화 KRW 가정), PRIVACY 기준표(12,2), 벤치마크(18,6).

### G-2. 가격

| 컬럼 | DB 타입 | Entity |
|---|---|---|
| `orders.price`·`orders.filled_price` | numeric(12,2) | `OrderEntity.java:62,78` |
| `cycle_position.closing_price` | numeric(12,2) | `CyclePositionEntity.java:36` |
| `dca_execution.reference_price` | numeric(12,2) | `DcaExecutionEntity.java:54` |
| `privacy_trade_bases.avg_price`·`privacy_trade_base_orders.price` | numeric(12,2) | `PrivacyTradeBaseEntity.java:48`, `PrivacyTradeBaseOrderEntity.java:41` |
| `market_index_prices.close_price` | numeric(12,2) | `MarketIndexPriceEntity.java:38` |
| `cycle_position.avg_price` | **numeric(20,2)** | 위 G-1 — 같은 "가격"인데 정수부 자리수가 다름 |
| `housing_price_indices.index_value` | numeric(18,12) | `HousingPriceIndexEntity.java:48` |

가격 scale은 전부 2다. 운영에서 사용 중인 가격 scale 최대값은 2(`orders.price`·`filled_price`·`cycle_position.*`). 종목은 `StrategyTicker`에 BTC·ETH(코인 DCA 전용)가 있고 `Market.CRYPTO_KRW`·`CRYPTO_USDT`를 지원하지만, 운영 `orders.ticker`는 ETH·MAGX·QLD·SOXL·TQQQ·USD다. 소수점 아래 가격이 필요한 코인·종목이 추가되면 scale 2가 한계(미확인: 현재 지원 종목 중 해당 없음).

### G-3. 수량

| 컬럼 | DB 타입 | Java | Entity |
|---|---|---|---|
| `orders.quantity`·`filled_quantity` | numeric(20,8) | BigDecimal | `OrderEntity.java:65,75` |
| `cycle_position.holdings` | numeric(20,8) | BigDecimal | `CyclePositionEntity.java:42` |
| `cycle_capital_event.holdings_delta` | numeric(20,8) | BigDecimal | `CycleCapitalEventEntity.java:42` |
| `dca_execution.quantity` | numeric(20,8) | BigDecimal | `DcaExecutionEntity.java:51` |
| `privacy_trade_bases.holdings` | **integer** | **int** | `PrivacyTradeBaseEntity.java:51` |
| `privacy_trade_base_orders.quantity` | **integer** | **Integer** | `PrivacyTradeBaseOrderEntity.java:44` |

`numeric(20,8)`은 4개 컬럼에서 일관되고, PRIVACY 기준표(`trading_ref`) 두 컬럼만 정수다. 운영 값: `orders.quantity` 중 소수 수량 55행, `cycle_position.holdings` 중 소수 56행(코인 DCA).

### G-4. 비율·퍼센트·지표

| 컬럼 | DB 타입 | 단위 | 운영 값 범위 |
|---|---|---|---|
| `strategy_cycle_vr.pool_limit_rate` | numeric(6,2) | **비율(0~1)** | 0.70~1.00(`initial_pool_limit_rate`) |
| `strategy_vr_version.initial_pool_limit_rate`·`pool_limit_floor` | numeric(6,2) | 비율(0~1) | 0.70~1.00 / 0.00~0.75 |
| `strategy_vr_version.band_width` | **numeric(20,2)** | **퍼센트**(`StrategyVrDetail.java:14` "% 단위, 15.00=15%") | 15.00 |
| `strategy_dca_rule.percent` | numeric(5,2) | 퍼센트 | 10.00 |
| `strategy_dca_condition.threshold` | numeric(10,2) | 지표값(RSI 등) | 0.00~40.00 |
| `housing_benchmark_prices.fifth_quintile_ratio` | numeric(18,12) | 배율 | — |
| `fear_greed_snapshots.value` | integer | 지표 0~100 | — |
| `strategy_cycle_vr.gradient`·`strategy_vr_version.initial_gradient` | integer | 퍼센트포인트 정수 | — |

같은 "비율"이 표기가 두 가지다 — pool 계열은 0~1 비율(6,2), `band_width`·`percent`는 퍼센트(각각 20,2·5,2). `band_width`만 금액 컬럼과 같은 `numeric(20,2)`다.

### G-5. 정수 설정값

`strategy_infinite_version.division_count`(int)·`strategy_vr_version`의 `interval_weeks`·`g_grace_weeks`·`g_step_weeks`·`g_max`·`p_grace_weeks`·`p_step_weeks`(전부 int)·`strategy_version.version_no`(int)·`*.sort_order`(int)·`strategy_dca_rule.day_of_month`(Integer)·`execution_hour`(smallint/Short)·`strategy_dca_condition.sma_period`(Integer). 같은 의미(주 단위 기간 등)의 자릿수 불일치는 없다. 시각 표현만 `execution_hour`가 smallint, 나머지 시각은 `timestamptz`.

### G-6. 부동소수 사용처 (DB 컬럼 아님)

| 위치 | 용도 |
|---|---|
| `SH/contract/notify/TradeEventMessage.java:17-18` `Double price`·`Double amount` | 알림 wire 타입(체결가·금액). 값은 `BigDecimal.doubleValue()`로 변환(`TC/notify/adapter/out/gateway/CryptoTradeNotifier.java:34-35`) |
| `TC/matching/domain/model/InfinitePosition.java:52,58` `double currentRound` | 회차 계산. 응답 `TradingCycleResponse.java:36 Double currentRound`·`NextOrdersResponse.java:54 double currentRound`·`StrategyDetail.java:14` |
| `TC/matching/domain/model/DcaIndicators.java:26-40` | RSI 계산 중간값 |
| `TC/matching/domain/model/VrPosition.java:72` | `√G`를 `Math.sqrt`(double)로 계산 후 BigDecimal 변환 |
| `API/benchmark/application/service/BenchmarkComparisonBuilder.java:32,94` | 연환산 계수 |

숫자 요약: Entity↔DB 자릿수 불일치 0 / double·real DB 컬럼 0 / 같은 의미 다른 정의: 평단 `avg_price`(20,2) vs 가격 `(12,2)` 7곳, 수량 numeric(20,8) vs PRIVACY integer 2곳, 금액 numeric(20,2) vs `recurring_amount` integer·가계부 bigint 4곳, 비율 0~1(6,2) vs 퍼센트(20,2·5,2) 3곳.
미확인: 가계부(`finance_*`)가 KRW 외 통화를 쓰는지(`bigint`라 소수 불가). `orders.price` scale 2가 지원 코인 가격에 충분한지.

## H. 운영 상태 보충

### H-1. `event_publication`(Spring Modulith 이벤트 레지스트리)

| 스키마 | 행 수 | 미완료(`completion_date IS NULL`) | 가장 오래된 `publication_date` | 가장 최근 | 최근 30일 증가 |
|---|---|---|---|---|---|
| public | 837 | 0 | 2026-09-04 13:30 | 2026-11-12 19:00 | 316 |
| trading | 335 | 0 | 2026-09-28 13:30 | 2026-11-12 14:30 | 247 |

미완료 0이라 막힌 이벤트는 없다. 완료 행을 지우는 코드는 없다(`completion-mode` 설정·`purge`·`deleteCompleted…` grep 0건, `application.yml:41-46`은 `republish-outstanding-events-on-restart`만 설정) — 완료 행이 계속 쌓이며 하루 약 8~10행씩 늘고, 크기는 public 536KB·trading 384KB다.

### H-2. 가장 큰 테이블 5개와 증가 속도

| 순위(크기) | 테이블 | 크기 | 행 수 | 최근 30일 신규 행 | 가장 오래된 행 |
|---|---|---|---|---|---|
| 1 | `kista_ref.housing_price_indices` | 13.2MB | 22,227 | 100 | 2026-08-11(데이터 시점은 과거 지수, 적재일 기준) |
| 2 | `kista_ref.market_index_prices` | 2.6MB | 21,891 | 110 | 2026-07-20 |
| 3 | `trading.orders` | 1.5MB | 3,520 | 1,117 | 2026-06-16 |
| 4 | `public.app_error_logs` | 1.0MB | 567 | 99 | 2026-06-26 |
| 5 | `kista_ref.housing_benchmark_prices` | 968KB | 2,236 | (updated_at 갱신만 있음) | — |

행 증가가 가장 빠른 것은 `orders`(월 약 1,100행, 그중 약 80%가 CANCELLED — E-5)이고, `housing_price_indices`는 2,079 dead tuple이 쌓여 있다(`pg_stat_user_tables.n_dead_tup`, upsert 갱신 탓으로 추정). 그 외 30일 증가: `cycle_position` 204, `fear_greed_snapshots` 59, `finance_asset_snapshots` 44.

### H-3. 보존 정책(삭제 배치) 코드 유무

| 테이블 | 삭제 코드 |
|---|---|
| `public.refresh_tokens` | **있음** — `API/user/adapter/in/schedule/RefreshTokenCleanupScheduler.java:26`(`refresh-token-expired-cleanup`)이 `deleteAllByExpiresAtBefore`·`deleteAllByRotatedAtBefore`(`RefreshTokenJpaRepository.java:40,51`) 호출 |
| `public.app_error_logs` | 하드 삭제 없음 — 관리자 `DELETE /errors/{id}`가 소프트 삭제(`AdminObservabilityController.java:75-78`) |
| `public.event_publication`·`trading.event_publication` | **없음**(H-1) |
| `trading.orders`·`cycle_position`·`strategy_*` | 없음(소프트 삭제 행도 영구 보존, F절) |
| `public.audit_logs` | 없음 |
| `kista_ref.*`·`trading_ref.*` | 없음(참조 데이터 적재 전용) |
| `trading.scheduler_locks`·`public.scheduler_locks` | 없음(키 고정 테이블, 7·15행) |
| `finance_budgets` | 사용자 삭제 시 하드 삭제(`FinanceBudgetJpaRepository.java:40`) |

### H-4. FK 중 스키마를 넘는 것

스키마를 넘는 FK는 **10개 테이블 11개 FK, 전부 `finance.*` → `public.users`**(finance_accounts·asset_snapshots·budgets·categories·group_invitations 2개·group_members·groups·monthly_closings·transactions). 둘 다 root 소유 스키마라 "다른 서비스 소유 스키마 참조 금지" 규칙 위반은 아니다. trading·trading_ref에서 다른 스키마를 가리키는 FK는 0개(`accounts.user_id`도 FK 없음, D-1 #10).
추가 발견: `trading.strategy.ticker`에 코드의 `StrategyTicker` enum에 없는 값 `FNGU`가 2행 있다(`grep FNGU` 코드 0건). 둘 다 소프트 삭제 행(`@SQLRestriction`에 가려짐)이라 현재 로드 오류는 없다.

숫자 요약: `event_publication` public 837·trading 335행, 미완료 0, 삭제 코드 없음(월 약 560행 증가) / 큰 테이블 상위 2개가 `housing_price_indices`·`market_index_prices`(전체 행의 73%), 증가 최속은 `orders` 월 1,117행 / 보존 배치는 `refresh_tokens` 1개뿐 / 스키마 간 FK 11개(전부 finance→public.users).
미확인: `housing_price_indices` dead tuple의 원인(upsert 경로 `ON CONFLICT DO UPDATE` 추정만).
