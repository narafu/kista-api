-- PostgreSQL 네이티브 ENUM → VARCHAR 변환
-- USING 절로 기존 값을 text로 캐스팅 후 타입 변경, 이후 ENUM 타입 제거

ALTER TABLE users ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
ALTER TABLE users ALTER COLUMN role   TYPE VARCHAR(20) USING role::text;

ALTER TABLE strategies ALTER COLUMN type   TYPE VARCHAR(20) USING type::text;
ALTER TABLE strategies ALTER COLUMN status TYPE VARCHAR(20) USING status::text;

DROP TYPE user_status;
DROP TYPE user_role;
DROP TYPE strategy_type;
DROP TYPE strategy_status;
