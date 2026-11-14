# 11. DB 테이블: 삭제의 뜻을 하나로, 유일 제약으로 원장 보호, 1:1 상세 테이블 접기

> **실행 세션은 먼저 맨 끝의 9절 "실행 전 보정"과 `00-roadmap.md` 9절(공통 규칙)을 읽는다.** 본문과 다르면 그쪽이 우선한다. (2026-11-14)

작성 2026-11-13 (Fable). 근거는 `findings/15-db-tables.md`(운영 DB 읽기 전용 조회 포함)와 코드 직접 확인. 기준 커밋 `1db0d8db1`.

기존 지침서가 정한 것은 그대로다: 스키마 5개 유지(05 D2), 저장소는 애그리게이트당 Store(02 2-3), 전략 종류별 상세를 JSONB로 바꾸지 않는다(03 기각한 대안). 이 지침서는 그 아래 테이블·컬럼·제약 단위를 정한다. 03의 "테이블·컬럼을 바꾸지 않는다"는 03 태스크 안에서의 금지이고, 테이블 변경은 여기 태스크로만 한다.

## 0. 먼저 알아둘 규모

운영 DB는 테이블 51개, 전체 약 6만 행, 사용자 2명이다. 가장 큰 테이블이 22,227행(`housing_price_indices`)이고 주문은 3,520행에 월 1,100행씩 는다. **성능을 위한 인덱스·파티션·보존 작업은 지금 할 이유가 없다.** 이 지침서가 다루는 것은 정합성(잘못된 행이 들어갈 수 있는가)과 개념 수(같은 뜻을 여러 방식으로 표현하는가)다.

Entity와 운영 컬럼의 어긋남은 0건, 자릿수 어긋남도 0건, `double` 컬럼도 0개다. 스키마의 기본 품질은 좋다.

## 1. 현재 구조와 문제

### 1-1. `deleted_at`이 세 가지 뜻으로 쓰인다

`constraints.md`는 소프트 삭제 테이블이 6개라고 적었지만 실제는 16개다(`@SQLRestriction` 14개). 쓰임을 나누면 셋이다.

| 뜻 | 테이블 | 근거 |
|---|---|---|
| 사용자가 지운 루트 | `users`, `accounts`, `strategy`, 가계부 6개(`finance_transactions`·`_asset_snapshots`·`_accounts`·`_categories`·`_groups`·`_group_members`) | 삭제 진입점이 사용자 요청이다 |
| 루트가 지워질 때 따라 찍는 표시 | `strategy_cycle`, `cycle_position`, `cycle_position_infinite`, `strategy_infinite_version`, `finance_group_invitations` | 운영에서 살아 있는 전략 아래 삭제된 사이클 0건, 살아 있는 사이클 아래 삭제된 포지션 0건(2026-11-13 조회). 루트의 삭제와 항상 같은 값이어야 하는 중복이다 |
| 삭제가 아닌 상태 | `strategy_version`(새 버전이 생기면 옛 버전을 삭제 처리 = "현재 버전이 아님"), `app_error_logs`(관리자가 "처리함" 표시, 567행 중 566행 삭제) | `CycleSnapshotCreator.java:147`, `DcaConfigService.java:47`, `AdminQueryService.java:153` |

두 번째 부류가 실제 문제를 만든다.

- 따라 찍기가 빠진 곳이 있다. 전략 삭제(`StrategyStateWriter.java:41-43`)는 포지션·사이클·전략만 찍고 버전은 안 찍는다. 삭제된 전략 아래 살아 있는 버전 54행, 삭제된 포지션 아래 살아 있는 `cycle_position_infinite` 173행, 삭제된 전략 아래 살아 있는 사이클 3행(2026-07-26 하루치)이 운영에 있다.
- `strategy_infinite_version.deleted_at`·`cycle_position_infinite.deleted_at`은 지금 코드에 쓰는 곳이 없는데 `@SQLRestriction`은 걸려 있다.
- 삭제 컬럼이 없는 자식은 조인으로 걸러야 한다. `orders` 3,520행 중 2,293행(65%)이 삭제된 사이클 아래에 있고, 조회마다 `strategy_cycle.deleted_at IS NULL` 조인이 이를 가린다. native 쿼리 55개 중 44개가 테이블마다 `deleted_at IS NULL`을 손으로 적는다.
- cascade 순서를 서비스가 손으로 지킨다(`UserTradingDataEraseService.java:64-67`, `StrategyStateWriter.java:41-43`).

부수 발견: 삭제된 계좌 5개의 증권사 자격증명(암호문)이 그대로 남아 있다(`AccountJpaRepository.java:23`은 `deletedAt`만 찍는다). 삭제된 전략 2행의 `ticker`가 지금 enum에 없는 `FNGU`다(가려져 있어 로드 오류는 없다).

### 1-2. 원장을 지키는 유일 제약이 없다

- `orders`에는 PK 말고 유일 제약이 없다. 슬롯을 식별하는 컬럼은 `order_leg`다(`PlannedOrder.leg(...)`가 만든 `VR_BUY_01` 같은 값). PLANNED 저장(`OrderPersistenceAdapter.java:42,48`)은 중복 검사 없이 INSERT한다. 지금은 `AccountBudgetLock`(JVM 락)과 "PLANNED 지우고 다시 넣기"가 이중 저장을 막는다(05 M1).
- 운영 데이터는 깨끗하다. 살아 있는 상태(PLANNED·SUBMITTING·PLACED·FILLED)에서 `(strategy_cycle_id, trade_date, order_leg)` 중복은 0건이다(leg 도입 전 `UNKNOWN` 행 제외). 중복 137그룹은 1그룹(FAILED+FILLED)을 빼면 전부 CANCELLED끼리다. 즉 "같은 슬롯에 살아 있는 주문은 하나"가 이미 사실이고, DB가 그것을 보장하지 않을 뿐이다.
- 전략당 열린 사이클이 하나라는 규칙도 제약이 없다(위반 0건). "최신 1건" 조회(`StrategyCycleJpaRepository.java:24-40`)가 이 규칙에 기대고 있다.

### 1-3. 1:1인데 나뉜 상세 테이블 4개

| 부모 | 상세 | 의미 컬럼 | 운영 |
|---|---|---|---|
| `strategy_version` | `strategy_infinite_version` | 1개(`division_count`) | 22:22 |
| `strategy_version` | `strategy_vr_version` | 12개 | 30:30 |
| `strategy_cycle` | `strategy_cycle_vr` | 3개(`value`·`gradient`·`pool_limit_rate`) | 42:42 |
| `cycle_position` | `cycle_position_infinite` | 1개(`is_reverse_mode`) | 283행, 그중 17행은 PRIVACY 전략 것 |

부모와 상세는 항상 함께 만들어진다. 나뉜 대가는 상세 조회 한 번에 쿼리 3~7개(`StrategyDetailAssembler.java:41-65`), 부모 삭제 필터를 다시 거는 native JOIN(`StrategyVrVersionJpaRepository.java:14-22`, `StrategyInfiniteJpaRepository.java:12-20`), 1-1의 고아 173행, 이름과 쓰임이 어긋난 테이블(`cycle_position_infinite`를 PRIVACY도 쓴다)이다. 02가 "필드 1개짜리 `StrategyInfiniteDetail`도 5파일 145줄"이라고 적은 것의 DB 쪽 원인이다.

DCA의 `strategy_dca_rule`·`strategy_dca_condition`은 1:N이라 해당하지 않는다.

### 1-4. 작은 것들

| 항목 | 사실 |
|---|---|
| 읽지 않는 컬럼 | `finance_groups.owner_user_id`(소유자는 `finance_group_members.role='OWNER'`만 읽는다. 값이 두 곳에 있다), `finance_group_invitations.invitee_user_id`, `cycle_capital_event.kind` |
| 사용자 복제본 불일치 | `trading.user_notify_profile.notification_channel`이 2행 모두 NULL인데 원본 `users.notification_channel`은 FCM·ALL이다. `TradingUserProfile.telegramEnabled()`는 NULL이면 채널을 보지 않고 봇 연결만 본다. 채널을 FCM으로만 둔 사용자도 봇이 연결돼 있으면 매매 알림이 텔레그램으로 간다 |
| 쌓이기만 하는 행 | `event_publication` 완료 행(public 837·trading 335, 월 약 560행, 정리 코드 없음), `scheduler_locks`의 코드에 없는 이름 7행, 옛 단일 Flyway 이력 `public.flyway_schema_history` 22행 |
| 같은 뜻 다른 타입 | 가격은 `numeric(12,2)`인데 평단 `cycle_position.avg_price`만 `(20,2)`. 비율이 0~1(`pool_limit_rate` `(6,2)`)과 퍼센트(`band_width` `(20,2)`, `percent` `(5,2)`)로 섞였다. `strategy_vr_version.recurring_amount`는 금액인데 `integer` |
| 인덱스 | 105개 중 51개가 `idx_scan=0`. 전부 100행 미만 테이블이거나 조회에 안 쓰이는 PK다. FK 46개 중 12개는 선두 인덱스가 없다 |

## 2. 목표 구조

### 2-1. 삭제 규칙 하나: `deleted_at`은 사용자가 지우는 루트에만 둔다

1. **루트만 `deleted_at`을 가진다**: `users`, `accounts`, `strategy`, 가계부의 사용자 기록 6개. 지우는 동작은 루트 한 행의 UPDATE 하나다.
2. **자식은 `deleted_at`을 갖지 않는다**: `strategy_version`, `strategy_infinite_version`, `strategy_cycle`, `cycle_position`, `cycle_position_infinite`에서 컬럼과 `@SQLRestriction`을 없앤다. 자식이 보이느냐는 루트가 정한다. 전략 id로 들어가는 조회는 이미 살아 있는 전략에서 출발하므로 필터가 필요 없고, 계좌·사용자·기간으로 가로지르는 조회(통계, 일별 체결, 원장 합계)만 `strategy`를 조인해 `s.deleted_at IS NULL` 하나를 건다. 지금 테이블마다 거는 필터 3~4개가 하나가 된다.
3. **상태는 상태 컬럼으로 표현한다**:
   - 현재 버전 = 전략의 `version_no`가 가장 큰 버전. `softDeleteActiveByStrategyId`를 없앤다. 사이클은 자기 버전을 `strategy_version_id`로 계속 가리키므로 이력은 그대로다.
   - `app_error_logs.deleted_at` → `resolved_at`. Entity의 `@SQLRestriction`을 없애고 조회가 `resolved_at IS NULL`을 명시한다.
4. 계좌를 지울 때 자격증명을 함께 지운다(사용자 결정 D3). 지운 계좌의 키를 보관할 이유가 없다.
5. cascade 순서 코드(`StrategyStateWriter`의 포지션→사이클, `UserTradingDataEraseService`의 포지션→사이클→전략)는 루트 UPDATE 하나로 줄어든다. 계좌 삭제는 "그 계좌의 전략 삭제 + 계좌 삭제" 둘이다.

이 규칙은 삭제된 데이터를 보존한다. 실제 돈이 오간 주문 기록(삭제된 사이클 아래 2,293행)을 지우지 않는다.

기각한 대안:

- **전부 실제 삭제(FK `ON DELETE CASCADE`)**: 가장 단순하고 `@SQLRestriction`이 통째로 사라져 02가 미룬 JDBC 전환의 조건도 풀린다. 그러나 실계좌 주문 이력이 사라지고 되돌릴 수 없다. 운영자가 사고 원인을 지난 주문에서 찾는 일이 잦은 시스템이라 잃는 것이 크다. 사용자가 원하면 D1에서 고를 수 있게 남긴다.
- **지금 구조에서 빠진 따라 찍기만 보충**: 고아 행은 없어지지만 "루트와 항상 같아야 하는 값"을 5개 테이블에 계속 복제한다. 다음 테이블이 추가될 때 같은 누락이 다시 난다.
- **보관 테이블로 옮기기**: 삭제 시 행을 `*_archive`로 옮기면 조회 필터가 0이 된다. 테이블 수가 두 배가 되고 스키마 변경을 양쪽에 해야 한다. 6만 행 규모에 맞지 않는다.

### 2-2. 유일 제약 2개

```sql
-- 같은 슬롯에 살아 있는 주문은 하나
CREATE UNIQUE INDEX uq_orders_live_slot ON trading.orders (strategy_cycle_id, trade_date, order_leg)
  WHERE status IN ('PLANNED','SUBMITTING','PLACED','PARTIALLY_FILLED','FILLED') AND order_leg <> 'UNKNOWN';

-- 전략당 열린 사이클은 하나 (2-1 적용 전이면 AND deleted_at IS NULL을 붙인다)
CREATE UNIQUE INDEX uq_strategy_cycle_open ON trading.strategy_cycle (strategy_id) WHERE end_date IS NULL;
```

- 05는 매매 엔진을 worker 한 프로세스에 두어 M1 경합을 없앴다. 이 제약은 그 전제가 깨졌을 때(재개 로직 결함, 수동 실행과 배치의 겹침, 미래의 다중 인스턴스) 이중 주문 대신 저장 실패가 나게 하는 마지막 방어선이다. 05 단계 C의 선행 조건이기도 하다.
- 위반 시 동작을 정한다: PLANNED 저장에서 유일 제약 위반이 나면 그 계좌의 그 배치 단계를 실패로 끝내고 `TradingErrorEvent`로 관리자에게 알린다. 조용히 삼키거나 재시도하지 않는다.
- FAILED·CANCELLED는 제약 밖이다. 실패 뒤 재접수와 미체결 취소 누적(운영 주문의 80%가 CANCELLED)을 막지 않는다.

기각: `(account_id, trade_date, external_order_id)` 유일 제약. 앱이 이미 확인하고(`OrderPersistenceAdapter.java:114`) 위반 0건이며, 외부 주문번호 형식이 브로커마다 달라 얻는 것이 작다.

### 2-3. 1:1 상세 테이블을 부모로 접는다 (사용자 결정 D2)

| 없애는 테이블 | 옮기는 곳 |
|---|---|
| `strategy_infinite_version` | `strategy_version.division_count integer NULL` |
| `strategy_vr_version` | `strategy_version`에 VR 컬럼 12개(전부 NULL 허용, 이름 유지). 옮기면서 `band_width`만 `numeric(6,2)`로 |
| `strategy_cycle_vr` | `strategy_cycle.vr_value numeric(20,2)`, `vr_gradient integer`, `vr_pool_limit_rate numeric(6,2)` (NULL 허용) |
| `cycle_position_infinite` | `cycle_position.reverse_mode boolean NULL` |

- 종류에 없는 컬럼은 NULL이다. 어떤 컬럼이 채워져야 하는지는 도메인이 `strategy.type`으로 검증한다(03의 종류별 핸들러가 자기 컬럼을 읽고 쓴다). DB CHECK는 걸지 않는다(`strategy_version`에 type이 없고, 조인 CHECK는 불가능하다).
- 테이블 13개 → 9개. 상세 조회 쿼리는 INFINITE 4 → 2, VR 6 → 3. `cycle_position_infinite`의 이름 문제와 고아 173행이 사라진다.
- 03이 JSONB를 기각한 이유(조회 조건, 타입 보장)와 충돌하지 않는다. 컬럼은 타입을 가진 채 남는다.
- DCA 규칙·조건, `cycle_capital_event`, `dca_execution`은 1:N이라 그대로 둔다.

기각: **그대로 두기**. 02의 Store 통합만으로 포트·어댑터는 줄지만 Entity·JpaRepository 8개와 부모 필터 JOIN, 쿼리 수는 남는다. **VR만 남기고 필드 1개짜리 둘만 접기**: 같은 문제(1:1 상세)를 두 방식으로 풀게 된다.

### 2-4. 컬럼·행 정리

| 대상 | 처리 | 이유 |
|---|---|---|
| `finance_groups.owner_user_id` | 삭제(쓰기 중단 → 다음 배포에서 drop) | 같은 사실이 `finance_group_members`에 있고 읽는 곳이 없다 |
| `finance_group_invitations.invitee_user_id` | 유지 | 누가 수락했는지는 다른 곳에서 알 수 없는 기록이다 |
| `cycle_capital_event.kind` | 유지 | 원장 이벤트의 종류(INJECT·WITHDRAW·RESET)를 델타 부호로 추정하지 않게 하는 기록이다. 운영 조회에서 쓴다 |
| `user_notify_profile` | 05 X6·X7에서 테이블째 사라진다. 여기서는 손대지 않는다 | 1-4의 채널 NULL 결함도 그때 없어진다. X6이 한 달 넘게 밀리면 프로필 재발행으로 임시 보정한다(T8) |
| `event_publication` 완료 행 | Spring Modulith `completion-mode`를 삭제 방식으로 | 완료 행을 읽는 코드가 없다. 설정 한 줄이다 |
| `scheduler_locks` 죽은 행 7개, `public.flyway_schema_history` | 운영 SQL로 삭제(사용자 승인 D4) | 코드가 읽지 않는다 |
| `app_error_logs` | 2-1의 `resolved_at` | |

### 2-5. 타입·인덱스: 바꾸지 않고 기준만 적는다

- 새 컬럼의 기준: 원장 금액 `numeric(20,2)`, 가격 `numeric(12,2)`, 수량 `numeric(20,8)`, 비율은 0~1로 `numeric(6,4)` 이하, enum은 `VARCHAR(20)`. 가계부 금액 `bigint`(원화 정수)와 PRIVACY 기준표의 정수 수량은 원천 데이터의 모양이라 예외로 둔다.
- 기존 불일치(`avg_price (20,2)`, `recurring_amount integer`, 퍼센트 표기 `band_width`·`percent`)는 고치지 않는다. 값 범위가 넘칠 일이 없고, 단위를 바꾸면 매매 공식(08)과 UI 계약(10)을 함께 건드린다. `band_width`의 자릿수만 2-3에서 옮길 때 고친다.
- 가격 scale 2는 "소수 둘째 자리보다 잘게 호가하는 종목은 추가할 수 없다"는 제약이다. `StrategyTicker`에 종목을 추가할 때 확인할 항목으로 `flyway.md`에 적는다.
- 인덱스는 추가도 삭제도 하지 않는다(2-2의 유일 인덱스 제외). 기준: 인덱스는 유일성을 보장하거나, 운영에서 느린 쿼리가 측정됐을 때만 추가한다.

## 3. 예상 감량과 위험

| 묶음 | 감량(추정) | 위험 |
|---|---|---|
| 2-1 삭제 규칙 | 컬럼 6개, `@SQLRestriction` 6개, native 쿼리의 `deleted_at` 조건 약 60줄, soft delete 메서드(포트·어댑터·JpaRepository) 약 15개 120줄, cascade 코드 약 30줄. 합 약 250줄 | 가로지르는 조회에서 `strategy` 조인을 빠뜨리면 삭제된 전략의 행이 통계에 섞인다. 4절의 선행 테스트로 막는다 |
| 2-2 유일 제약 | 0줄(추가) | 정상 경로에 같은 슬롯 재저장이 숨어 있으면 배치가 실패한다. T1에서 저장 경로를 전수 확인한 뒤 건다 |
| 2-3 접기 | 테이블 4개, Entity 4·JpaRepository 4개 약 350줄, 부모 필터 JOIN 쿼리 4개 | 원장 테이블 마이그레이션. 행이 22~283개라 백필은 즉시 끝난다. 두 배포로 나눈다 |
| 2-4 정리 | 약 20줄 | 낮음 |

줄 수보다 개념 수가 준다: 삭제 표시를 가진 테이블 16 → 10, 전략 축 테이블 13 → 9, "루트와 자식의 삭제가 어긋날 수 있다"는 상태 자체가 없어진다.

## 4. 동작 보존용 선행 테스트

전부 실 DB 슬라이스(`*DbTest`, `DataJpaTestBase`)로 쓴다. 변이 확인(수정을 잠시 되돌려 실패하는지)을 한다.

1. 삭제된 전략의 사이클·포지션·주문·자본 이벤트가 다음 조회에 나오지 않는다: 사용자 통계 적재(`CyclePositionJpaRepository`의 사용자·기간 조회), 계좌 원장 합계, 일별 체결(`OrderJpaRepository.java:93-160`), 예산 합계(`:65-87`), 활성 전략 목록(`StrategyJpaRepository.java:46-66`). 지금 코드에서 통과하는 테스트를 먼저 만들고 T3 뒤에도 통과해야 한다.
2. 버전을 두 번 올린 전략에서 "현재 버전"이 가장 큰 `version_no`이고, 옛 사이클은 자기 버전의 설정을 읽는다(INFINITE·VR·DCA 각각).
3. 같은 `(사이클, 거래일, leg)`로 PLANNED를 두 번 저장하면 두 번째가 실패한다. CANCELLED·FAILED 행이 있는 슬롯에는 새 PLANNED가 들어간다. PLANNED 지우고 다시 넣기(`deletePlannedByCycleAndDate` 뒤 `saveAll`)가 한 트랜잭션에서 통과한다.
4. 열린 사이클이 있는 전략에 두 번째 열린 사이클 저장이 실패하고, 사이클 종료 뒤 새 사이클 저장은 통과한다(사이클 회전 경로 `VrCycleRolloverService`, INFINITE 회전 포함).

## 5. 태스크

trading 스키마도 expand/contract를 따른다(05 D3). 한 배포에서 컬럼 추가와 코드 전환을, 다음 배포에서 drop을 한다. kista-trading은 stop-first 단일 인스턴스라 새 코드와 옛 코드가 겹치지 않지만 롤백을 위해 나눈다. 마이그레이션 파일은 `flyway-migration` 스킬 규칙을 따른다.

| # | 내용 | 대상 | 모델 | 완료 판정 |
|---|---|---|---|---|
| T1 | 유일 제약 2개. ① `orders` INSERT 경로 전수(`save`·`saveAll` 호출처)와 사이클 생성 경로 전수를 표로 만든다 ② 운영에서 위반 0건 재확인(2-2의 조건 그대로 `GROUP BY … HAVING count(*)>1`) ③ 4절 테스트 3·4 ④ 마이그레이션 ⑤ 위반 시 실패·알림 처리 | `MIG-T`, `OrderPersistenceAdapter`, `TradingCandidatePlanner`, `ManualTradingService`, 사이클 생성 서비스 | Opus | `./gradlew :trading-core:test --tests '*Order*DbTest' --tests '*StrategyCycle*DbTest' 2>&1 \| grep -E "FAILED\|BUILD"` |
| T2 | 4절 테스트 1·2 작성(코드 불변) | trading-core persistence 테스트 | Sonnet | 새 테스트가 현재 코드에서 통과 |
| T3 | 삭제 규칙 expand: 자식 5개 Entity의 `@SQLRestriction`·`deletedAt` 필드 제거, 자식 soft delete 메서드 제거, 가로지르는 native 쿼리를 `strategy` 조인 하나로, 현재 버전을 최대 `version_no`로, cascade 코드 축소. 컬럼은 아직 남긴다(Entity에서만 뺀다. `ddl-auto: validate`는 남는 컬럼을 문제 삼지 않는다) | `TC/trading/adapter/out/persistence/*`, `StrategyStateWriter`, `UserTradingDataEraseService`, `CycleSnapshotCreator`, `DcaConfigService` | Opus | T2 테스트 통과 + `./gradlew :trading-core:test 2>&1 \| grep -E "FAILED\|BUILD"` |
| T4 | 삭제 규칙 contract: 자식 5개 테이블의 `deleted_at` drop. T3 배포 뒤 2거래일 관찰 후 | `MIG-T` | Sonnet | 마이그레이션 검사(`.github` 스크립트) 통과 |
| T5 | `app_error_logs`: `resolved_at` 추가·백필(`deleted_at` 복사) → 코드 전환 → 다음 배포에서 `deleted_at` drop. root 스키마 expand/contract | `MIG-R`, `AppErrorLog*`, `AdminQueryService`, `AdminObservabilityController` | Sonnet | `./gradlew :test --tests '*AppErrorLog*' 2>&1 \| grep -E "FAILED\|BUILD"` |
| T6 | 계좌 삭제 시 자격증명 삭제(D3 승인 뒤): 삭제 UPDATE에서 키·시크릿·계좌번호 컬럼을 비운다. NOT NULL이면 먼저 NULL 허용으로 바꾼다. 이미 삭제된 5행은 승인된 운영 SQL로 정리 | `AccountJpaRepository`, `AccountEntity`, `MIG-T` | Opus | 삭제 뒤 행의 자격증명 컬럼이 NULL인 DbTest |
| T7 | 1:1 접기(D2 승인 뒤, **02 T6 뒤**): ① 부모에 컬럼 추가 + 백필 ② Store가 부모 컬럼을 읽고 쓰게 전환, 상세 Entity·JpaRepository 삭제 ③ 운영에서 부모 컬럼과 옛 상세 테이블 값이 전 행 일치하는지 SELECT로 확인 ④ 다음 배포에서 상세 테이블 4개 drop | `MIG-T`, 전략 Store(02 T6의 결과물), `StrategyDetailAssembler`, `VrStrategyDetailService` | Opus | `./gradlew :trading-core:test 2>&1 \| grep -E "FAILED\|BUILD"` + ③의 불일치 0 |
| T8 | 정리: `finance_groups.owner_user_id` 쓰기 중단 → drop, `event_publication` 완료 행 삭제 설정(Modulith 버전의 프로퍼티 이름을 문서로 확인), D4 승인된 운영 SQL(죽은 락 7행, 옛 Flyway 이력) | `FinanceGroup*`, `application.yml` 양쪽 | Sonnet | 기동 후 완료 이벤트가 남지 않는지 로컬에서 확인 |
| T9 | 문서: `constraints.md` 소프트 삭제 절을 2-1 규칙으로 교체, `flyway.md`에 2-5 기준과 종목 추가 확인 항목, `flows.yml`의 삭제 흐름 | docs | Sonnet | 09의 크기 예산 가드 통과 |

중단 조건(걸리면 `questions.md`에 적고 그 태스크를 멈춘다):

- T1 ①에서 같은 슬롯에 살아 있는 주문 둘이 정상인 경로가 나온다(예: 부분 체결 뒤 잔량 재주문이 같은 leg를 쓴다).
- T3에서 "삭제되지 않은 버전이 최대 `version_no`가 아닌" 전략이 운영에 있다. 확인 쿼리: 전략별로 `deleted_at IS NULL`인 버전이 정확히 1개이고 그것이 최대 `version_no`인지.
- T3에서 삭제된 사이클·포지션을 **일부러** 읽는 코드가 나온다(삭제 버전 포함 MAX `version_no`는 예외로 이미 알려져 있고, 규칙 변경 뒤에는 자연히 전체 MAX가 된다).
- T7 ③에서 불일치가 1건이라도 나온다.

## 6. 하지 말 것

- 운영 DB에 쓰는 SQL을 실행 세션이 직접 돌리지 않는다. 마이그레이션 파일로 하거나, 일회성 정리는 SQL을 사용자에게 보여주고 승인받는다.
- 이미 적용된 마이그레이션 파일을 고치지 않는다.
- 성능을 이유로 인덱스를 추가하지 않는다.
- 가계부의 `finance_accounts`·`finance_categories`에 `@SQLRestriction`을 붙이지 않는다. 삭제된 계좌·카테고리의 이름을 남은 거래가 보여줘야 해서 일부러 뺀 것이다. 가계부 삭제 방식의 정리는 13의 가계부 절이 다룬다.
- T3과 T7을 한 배포에 묶지 않는다.
- 금액·비율의 단위나 Java 타입을 바꾸지 않는다.

## 7. 다른 지침서와의 순서

- **T1은 지금 시작할 수 있다.** 다른 묶음과 파일이 거의 겹치지 않는다(`OrderPersistenceAdapter` 한 곳). 주문 경로라 매매 시간대 밖에 배포한다.
- T2·T3·T4는 05 P3(native SQL에 스키마 이름 붙이기) **뒤**, 02 T6(Store 통합) **앞**. 셋 다 같은 JpaRepository 파일을 고친다. P3이 먼저면 기계적 치환이 깨끗하고, T3이 02 T6보다 먼저면 Store로 옮길 쿼리가 줄어든다.
- T7은 02 T6 뒤, 03 T6과 같은 주에 하지 않는다. 03의 종류별 핸들러가 생긴 뒤면 컬럼을 읽는 곳이 핸들러 하나라 더 쉽다(03 뒤 권장).
- T5·T8은 독립이다. T5는 05 단계 R(admin 해체)에서 `AppErrorLog`가 옮겨지기 전이나 후 어느 쪽이든 되지만 같은 주에 하지 않는다.
- 12에서 기능을 지우기로 하면 그 기능의 테이블은 여기서 손대지 않는다(예: 가계부 그룹을 지우면 `owner_user_id` drop은 테이블 drop에 흡수된다).

## 8. 사용자 결정

| # | 결정 | 권고 | 고르지 않으면 |
|---|---|---|---|
| D1 | 삭제된 전략·계좌의 데이터를 어떻게 둘까: (가) 루트에만 삭제 표시, 자식은 보존(2-1) (나) 전부 실제 삭제 (다) 지금 구조 유지하고 누락만 보충 | (가) | T2~T4를 시작하지 않는다 |
| D2 | 1:1 상세 테이블 4개를 부모로 접을까 | 접는다. 순서는 맨 뒤 | T7을 하지 않는다. 나머지는 영향 없음 |
| D3 | 계좌를 지울 때 증권사 자격증명도 지울까. 이미 지운 계좌 5개의 키도 정리할까 | 둘 다 지운다 | T6을 하지 않는다 |
| D4 | 운영 정리 SQL 2건(죽은 락 7행 삭제, 옛 `public.flyway_schema_history` drop) | 승인 | 그대로 둔다(해는 없다) |

T1(유일 제약)과 T5·T8·T9는 결정 없이 진행한다.

## 9. 실행 전 보정 (2026-11-14 — 본문과 다르면 이 절이 우선)

공통 규칙은 `00-roadmap.md` 9절. 측정 원본은 `findings/24`. 사용자 결정: D1 (가) 루트에만 삭제 표시, D2 권고대로(맨 뒤), D3 삭제, D4 승인(SQL을 보여 준 뒤).

전제 정정:

- `@SQLRestriction` 선언은 지금 32줄(25파일)이다. 본문의 14개는 테이블 기준이다. T3의 제거 범위는 착수할 때 `git grep -n "@SQLRestriction" -- '*.java'`로 다시 만든다.
- 마이그레이션 번호는 착수할 때 센다(로드맵 9-3). 04·13·14·15와 같은 디렉토리를 쓴다.
- trading 스키마의 expand/contract는 `check-migrations.sh`가 검사하지 않는다(`:10`). 05 D3에 따라 스스로 지킨다: 컬럼·테이블 drop은 그것을 읽는 코드가 사라진 **다음 배포**에 한다.
- 15(크립토 삭제)의 C2는 이 지침서 D1의 예외다. 코인 계좌·전략 행은 완전 삭제한다.

T1 배포 전 확인 SQL(읽기 전용, 2-2절의 인덱스 정의와 같은 조건). 인덱스를 걸기 전에 위반이 0건이어야 한다.

```sql
-- 같은 슬롯에 살아 있는 주문이 둘 이상
SELECT strategy_cycle_id, trade_date, order_leg, count(*)
FROM trading.orders
WHERE status IN ('PLANNED', 'SUBMITTING', 'PLACED', 'PARTIALLY_FILLED', 'FILLED')
  AND order_leg <> 'UNKNOWN'
GROUP BY 1, 2, 3 HAVING count(*) > 1;

-- 한 전략에 열린 사이클이 둘 이상
SELECT strategy_id, count(*)
FROM trading.strategy_cycle
WHERE end_date IS NULL AND deleted_at IS NULL
GROUP BY 1 HAVING count(*) > 1;
```

T3의 중단 조건 확인 SQL(삭제되지 않은 버전이 최대 `version_no`가 아닌 전략). `version_no`는 `trading.strategy_version`에 있다.

```sql
SELECT v.strategy_id
FROM trading.strategy_version v
GROUP BY v.strategy_id
HAVING max(v.version_no) FILTER (WHERE v.deleted_at IS NULL) < max(v.version_no);
```

중단 조건의 대체 경로(5절 끝에 더한다):

- T1에서 같은 슬롯에 살아 있는 주문 둘이 정상인 경로가 나온다(위 SQL이 0건이 아니거나 저장 경로 전수 확인에서 발견): 인덱스를 걸지 않는다. 그 경로의 `파일:줄`과 운영 건수를 `questions.md`에 적고 T1의 주문 쪽만 멈춘다. 열린 사이클 유일 제약은 독립이라 따로 진행한다.
- T3에서 위 버전 SQL이 행을 돌려준다: 그 전략 id를 적고 T3을 멈춘다. 데이터를 손으로 맞추지 않는다. T2(읽기 쪽 준비)와 T5·T6·T8은 계속한다.
- T7 ③에서 접기 전후 값이 1건이라도 다르다: 새 컬럼을 읽는 코드를 배포하지 않는다. 옛 테이블은 그대로 있으므로 backfill을 고쳐 다시 대조한다. 세 번 안에 맞지 않으면 그 테이블은 접지 않는다(네 테이블은 서로 독립이다).
- "삭제된 사이클·포지션을 일부러 읽는 코드"는 T2에서 `@SQLRestriction`을 떼기 전에 찾는다: native 쿼리 중 `deleted_at` 조건이 **없는** 것이 후보다. 발견하면 그 쿼리는 지금 결과를 유지하도록 조건을 명시하고 목록을 T2 커밋 메시지에 적는다.

승인이 필요한 것(로드맵 9-4): T6의 "이미 삭제된 계좌 5개의 자격증명 정리", T8의 운영 정리 SQL 2건. SQL 본문과 `SELECT count(*)` 결과를 사용자에게 보인 뒤 돌린다. T1·T4는 스키마 변경이고 주문 경로라 매매 시간대 밖에 배포한다. "T3 배포 뒤 2거래일 관찰"은 실행 세션이 한다(삭제·재등록이 그 사이 없었으면 관찰할 것이 없으므로, 로컬에서 전략 삭제 → 조회 → 재등록을 한 번 돌린 것으로 갈음하고 T4로 간다).
