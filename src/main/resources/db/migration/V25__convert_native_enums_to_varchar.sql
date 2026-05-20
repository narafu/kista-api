-- PostgreSQL 네이티브 ENUM → VARCHAR 변환
-- USING 절로 기존 값을 text로 캐스팅 후 타입 변경
-- CASCADE: 컬럼 DEFAULT에 ENUM 타입 캐스팅이 남아있어 의존성 존재 → CASCADE로 제거 후 재설정

ALTER TABLE users ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
ALTER TABLE users ALTER COLUMN role   TYPE VARCHAR(20) USING role::text;

ALTER TABLE strategies ALTER COLUMN type   TYPE VARCHAR(20) USING type::text;
ALTER TABLE strategies ALTER COLUMN status TYPE VARCHAR(20) USING status::text;

-- ENUM 타입 DROP (CASCADE로 의존 DEFAULT 함께 제거)
DROP TYPE user_status CASCADE;
DROP TYPE user_role CASCADE;
DROP TYPE strategy_type CASCADE;
DROP TYPE strategy_status CASCADE;

-- CASCADE로 제거된 DEFAULT 순수 문자열로 재설정
ALTER TABLE users ALTER COLUMN status SET DEFAULT 'PENDING';
ALTER TABLE users ALTER COLUMN role   SET DEFAULT 'USER';
ALTER TABLE strategies ALTER COLUMN status SET DEFAULT 'ACTIVE';
