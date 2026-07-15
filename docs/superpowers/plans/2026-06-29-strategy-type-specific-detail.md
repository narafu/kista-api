# 전략 버전 기반 상세 테이블 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 전략 설정 이력을 `strategy_version` 부모 구조로 재설계하고, INFINITE 계산이 공통 전략이 아니라 적용된 버전 상세를 참조하도록 전환한다.

**Architecture:** `strategy`는 루트 전략으로 유지하고, 변경 이력은 `strategy_version` 공통 부모와 전략별 상세 버전 테이블로 이동한다. `strategy_cycle`은 실행 시점의 `strategy_version_id`를 고정 저장하고, 서비스는 현재 활성 버전과 사이클에 고정된 버전을 구분해 읽는다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Flyway, JUnit 5, Mockito, Testcontainers

## Global Constraints

- `strategy.status`, `strategy.cycle_seed_type`는 즉시 반영이다.
- `division_count`, `recurring_amount`, `band_width`, `interval_weeks`는 다음 사이클부터 반영한다.
- `strategy_cycle.start_amount`는 `holdings = 0`일 때만 수정 가능하다.
- 운영 DB에 이미 적용된 마이그레이션 파일은 절대 수정 금지하고 새 Flyway 파일만 추가한다.
- DB enum은 PostgreSQL 네이티브 ENUM이 아니라 `VARCHAR(20)` + `@Enumerated(EnumType.STRING)` 규칙을 유지한다.
- 컬럼 순서는 `pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at` 순서를 유지한다.
- PK/FK 제약명과 FK `ON DELETE` 동작을 명시적으로 선언한다.
- soft-deleted 이력도 백필 대상에서 제외하지 않는다.
- Java 수정 후 최소 `./gradlew compileJava` 또는 해당 테스트를 실행해 검증한다.

---

### Task 1: `strategy_version` 도메인/퍼시스턴스 뼈대 도입

핵심 결과:

- `StrategyVersion`, `StrategyInfiniteVersion` 모델 추가
- `strategy_version`, `strategy_infinite_version` 엔티티/리포지토리/포트 추가
- 기존 direct `strategy_infinite` 구조 제거 또는 대체

### Task 2: `strategy_cycle.strategy_version_id` 마이그레이션과 엔티티 전환

핵심 결과:

- Flyway에서 초기 버전 백필
- `strategy_cycle`이 `strategy_version_id`를 저장
- 기존 `strategy_cycle.seed_resolved_by` 제거 유지

### Task 3: 전략 등록/상세 조회를 활성 버전 기반으로 전환

핵심 결과:

- 전략 등록 시 초기 `strategy_version` + `strategy_infinite_version` 생성
- `StrategyDetail`가 현재 활성 버전의 `divisionCount`를 사용
- `start_amount` 수정은 `holdings = 0`일 때만 허용

### Task 4: 매매 계산과 리포트를 사이클 고정 버전 기준으로 전환

핵심 결과:

- INFINITE 계산은 `strategy_cycle.strategy_version_id`에 연결된 `division_count` 사용
- 리버스모드 상태는 `cycle_position_infinite` 사용
- 사이클 재생성 시 현재 활성 버전을 새 `strategy_cycle`에 고정

### Task 5: 테스트/문서 마무리

핵심 결과:

- persistence/DataJpa 테스트 갱신
- 서비스 테스트 갱신
- 아키텍처 문서 갱신
