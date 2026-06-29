# 전략 버전 기반 상세 테이블 설계

## 범위

이번 변경에서는 기존 `strategy_infinite` 직접 분리안 대신, 전략 설정 버전 부모를 두는 구조로 재설계한다.

- `strategy_version` 공통 부모 테이블 도입
- `strategy_cycle.strategy_version_id` 도입
- `strategy_infinite_version` 도입
- `cycle_position_infinite` 유지
- VR은 설계만 반영하고 이번 구현 범위에서는 스키마 예약 수준까지 검토

## 목표

전략 설정값 이력과 사이클 실행 이력을 명확히 분리한다. 진행 중인 사이클은 시작 시점의 설정 버전에 고정되고, 전략 설정 변경은 다음 사이클부터 적용되도록 만든다.

## 목표 스키마

### 전략 루트

`strategy`

- `id`
- `account_id`
- `type`
- `ticker`
- `status`
- `cycle_seed_type`

규칙:

- 사용자 입장에서 하나의 전략을 나타내는 루트다.
- `status`, `cycle_seed_type`는 즉시 반영되는 공통 설정이다.

### 전략 설정 버전 부모

`strategy_version`

- `id`
- `strategy_id`
- `version_no`
- `created_at`
- `deleted_at`

규칙:

- 한 전략의 설정 변경 이력을 표현하는 공통 부모다.
- 같은 `strategy_id` 아래 활성 버전은 한 번에 1개만 존재해야 한다.
- 설정 변경 시 기존 활성 버전은 종료되고 새 버전이 생성된다.

### INFINITE 전략 설정 상세

`strategy_infinite_version`

- `strategy_version_id`
- `division_count`

규칙:

- `strategy.type = INFINITE`인 버전에만 존재한다.
- `strategy_version`과 1:1 관계다.
- `division_count`는 공통 `strategy`가 아니라 버전 상세에서 관리한다.

### VR 전략 설정 상세

`strategy_vr_version`

- `strategy_version_id`
- `interval_weeks`
- `recurring_amount`
- `band_width`

규칙:

- `strategy.type = VR`인 버전에만 존재한다.
- `strategy_version`과 1:1 관계다.
- `interval_weeks`, `recurring_amount`, `band_width`는 다음 사이클부터 반영된다.

### 사이클 이력

`strategy_cycle`

- `id`
- `strategy_id`
- `strategy_version_id`
- `start_amount`
- `end_amount`
- `start_date`
- `end_date`

규칙:

- 실제 실행된 사이클 이력을 저장한다.
- 사이클 시작 시점의 `strategy_version_id`를 고정 저장한다.
- 진행 중 설정 변경이 발생해도 이미 생성된 `strategy_cycle.strategy_version_id`는 바뀌지 않는다.
- `start_amount`는 사이클 값이며, `holdings = 0`일 때만 수정 가능하다.

### 공통 포지션 스냅샷

`cycle_position`

- `id`
- `strategy_cycle_id`
- `usd_deposit`
- `closing_price`
- `avg_price`
- `holdings`

규칙:

- 전략 타입과 무관한 포지션 스냅샷 정보만 유지한다.

### INFINITE 포지션 상세

`cycle_position_infinite`

- `cycle_position_id`
- `is_reverse_mode`

규칙:

- INFINITE 전략 포지션일 때만 존재한다.
- `cycle_position`과 1:1 관계를 가진다.

### VR 포지션 상세

`cycle_position_vr`

- `cycle_position_id`
- `value`
- `gradient`
- `pool_limit`

규칙:

- VR 전략 포지션일 때만 존재한다.
- 계산 결과 스냅샷을 저장한다.

## 반영 정책

즉시 반영:

- `strategy.status`
- `strategy.cycle_seed_type`

다음 사이클 반영:

- `strategy_infinite_version.division_count`
- `strategy_vr_version.recurring_amount`
- `strategy_vr_version.band_width`
- `strategy_vr_version.interval_weeks`

조건부 직접 수정:

- `strategy_cycle.start_amount`
- 단, 최신 포지션의 `holdings = 0`일 때만 허용

## 도메인 방향

이번 변경 이후 도메인은 아래 방향을 따라야 한다.

- `Strategy`는 루트 전략 필드만 가진다.
- `StrategyVersion` 공통 부모 모델을 추가한다.
- `StrategyInfiniteVersion` 상세 모델을 추가한다.
- `StrategyCycle`은 `strategyVersionId`를 가진다.
- `CyclePosition`은 공통 포지션 스냅샷만 가진다.
- `CyclePositionInfiniteDetail`은 `isReverseMode`를 가진다.

즉, 전략 설정 이력과 사이클 실행 이력을 분리하는 것이 핵심이다.

## 퍼시스턴스 방향

퍼시스턴스 계층은 아래 불변식을 보장해야 한다.

- 전략 등록 시 `strategy` 저장 후 활성 `strategy_version` 1개를 생성한다.
- INFINITE 전략 등록 시 같은 버전에 `strategy_infinite_version`을 함께 저장한다.
- 새 사이클 생성 시 현재 활성 `strategy_version.id`를 `strategy_cycle.strategy_version_id`에 저장한다.
- 진행 중 사이클은 기존 `strategy_version_id`를 유지한다.
- INFINITE 계산은 공통 `strategy`가 아니라 `strategy_cycle.strategy_version_id`에 연결된 `strategy_infinite_version.division_count`를 사용한다.

## 마이그레이션 방향

기존 direct-detail 분리안 대신 아래 순서로 반영한다.

1. `strategy_version` 테이블 생성
2. 기존 `strategy` 행마다 현재 상태를 나타내는 초기 `strategy_version` 1건 생성
3. `strategy_infinite_version` 테이블 생성
4. 기존 INFINITE 전략의 `division_count`를 `strategy_version` 기준으로 백필
5. `strategy_cycle.strategy_version_id` 컬럼 추가
6. 기존 `strategy_cycle` 행을 `strategy_id` 기준 초기 버전에 연결해 백필
7. `cycle_position_infinite` 테이블 생성 또는 기존 direct-detail 안을 버전 구조와 정합되게 조정
8. 기존 `strategy.division_count`, `cycle_position.is_reverse_mode`, `strategy_cycle.seed_resolved_by` 제거

마이그레이션 제약:

- 기존 전략/사이클/포지션 데이터는 보존해야 한다.
- soft-deleted 이력도 함께 백필해야 한다.
- PK/FK 제약명은 명시적으로 부여한다.
- FK의 삭제 동작은 명시적으로 선언한다.
- Entity의 nullable, length, 컬럼 순서를 Flyway SQL과 반드시 교차 검증한다.

## 애플리케이션 영향 범위

영향이 예상되는 영역은 다음과 같다.

- 전략 등록 경로
- 전략 상세 조회 경로
- 사이클 생성/재생성 경로
- 현재 `strategy.divisionCount()`를 직접 읽는 매매 계산 경로
- 현재 `cycle_position.is_reverse_mode`를 직접 읽는 리포트/상태 계산 경로
- 공통 `divisionCount`를 노출하던 API DTO

## 선택한 접근과 트레이드오프

### 선택안

`strategy_version` 공통 부모 + 전략별 상세 버전 테이블 구조를 사용한다.

장점:

- 새 전략 타입 추가 시 `strategy_cycle` 스키마를 바꾸지 않아도 된다.
- 과거 사이클과 당시 설정 버전이 FK로 명확히 연결된다.
- JPA/Flyway/정규화와 가장 잘 맞는다.

단점:

- direct-detail 분리보다 구조가 한 단계 더 무겁다.
- 버전 생성 시 부모/상세를 함께 다뤄야 한다.

### 제외한 대안

`strategy_detail_type + strategy_detail_id` 폴리모픽 참조:

- 확장성은 좋다.
- DB FK 무결성을 직접 강제하기 어렵다.

JSON 설정 컬럼 통합:

- 테이블 수는 줄어든다.
- DB 제약과 정규화가 약해지고 JPA 매핑 이후 분석/검증 쿼리가 불편해진다.

## 테스트 방향

다음 테스트를 추가하거나 수정한다.

- `strategy_version` / `strategy_infinite_version` persistence round-trip 검증
- `strategy_cycle.strategy_version_id` 백필 검증
- INFINITE 계산이 `strategy_version` 기준 `division_count`를 읽는지 검증
- 설정 변경 후 새 사이클만 새 버전을 쓰는지 검증
- `holdings = 0`일 때만 `start_amount` 수정 허용 검증

## 비목표

- 이번 단계에서 VR 전체 비즈니스 로직 구현은 하지 않는다.
- 매매 공식 자체를 재설계하지 않는다.
