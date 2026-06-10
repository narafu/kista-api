---
name: flyway-migration
description: Flyway 마이그레이션 파일 생성 — V1__init.sql 규칙, Entity 크로스체크, named FK, VARCHAR(20) enum, 암호화 컬럼 VARCHAR(512) 패턴 준수
---

# Flyway Migration 생성 스킬

## 1. 버전 번호 확인

bash 명령으로 최신 버전 확인 후 +1:
  ls src/main/resources/db/migration/ | sort -V | tail -3
파일명: V{N+1}__<영문_설명>.sql

## 2. Entity ↔ SQL 크로스체크 (필수)

- Entity @Column의 nullable/length/precision/scale 값이 SQL 컬럼 정의와 일치하는지 확인
- ddl-auto: validate 가 precision/scale 불일치를 부팅 시 SchemaManagementException으로 즉시 잡음
- NOT NULL 불일치는 런타임까지 무증상 → null 삽입 시 DataIntegrityViolationException

## 3. 필수 규칙

ENUM 컬럼: PostgreSQL 네이티브 ENUM(CREATE TYPE) 금지 → VARCHAR(20) 사용

암호화 컬럼: AES-256 저장 컬럼은 VARCHAR(512) 이상
  대상: account_no, kis_app_key, kis_secret_key, telegram_bot_token

FK 선언: 반드시 명시적 이름 사용
  CONSTRAINT <table>_<col>_fkey FOREIGN KEY (...) REFERENCES ... ON DELETE <CASCADE|RESTRICT>
  인라인 REFERENCES 사용 시 테이블 재생성 패턴에서 제약명 충돌(_fkey1 자동 부여)

컬럼 타입 변경: USING 캐스팅 필수
  ALTER TABLE t ALTER COLUMN c TYPE VARCHAR(20) USING c::text

컬럼 순서: CREATE TABLE 컬럼 순서 = Entity 필드 선언 순서
  모든 테이블 공통 순서: pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at
  (감사·삭제 컬럼은 반드시 이 순서로 맨 뒤, 없는 컬럼은 생략)

## 4. 테이블 재생성 패턴 (컬럼 순서 변경 필요 시)

  -- named UNIQUE 제약 먼저 제거
  ALTER TABLE xxx_old DROP CONSTRAINT IF EXISTS uq_xxx;
  ALTER TABLE xxx RENAME TO xxx_old;
  -- PK 인덱스명은 스키마 전역 — 새 테이블 생성 전 리네임 필수, named 인덱스는 DROP
  ALTER INDEX xxx_pkey RENAME TO xxx_old_pkey;
  DROP INDEX idx_xxx_...;
  CREATE TABLE xxx (...);
  INSERT INTO xxx SELECT ... FROM xxx_old;
  DROP TABLE xxx_old;
  -- 인덱스·타 테이블에서 참조하던 FK 재생성 (V6__strategy_cycle_lifecycle_columns.sql 참고)

## 5. 검증

./gradlew compileJava — BOM 없는지, Entity 필드 타입 컴파일 오류 없는지 확인
