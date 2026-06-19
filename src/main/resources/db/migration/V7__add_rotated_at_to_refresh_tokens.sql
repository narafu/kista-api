-- refresh_tokens에 rotated_at 컬럼 추가 (테이블 재생성 패턴)
-- 컬럼 순서: id, user_id, token_hash, user_agent, expires_at, rotated_at, created_at
-- ADD COLUMN은 항상 맨 뒤에 붙으므로 재생성으로 순서를 맞춤
-- 기존 RT 전멸 → 배포 시 1회 전원 재로그인 발생 (토큰 테이블이므로 수용)

ALTER TABLE refresh_tokens RENAME TO refresh_tokens_old;

-- named 제약조건은 스키마 전역이므로 새 테이블 CREATE 전에 제거
ALTER TABLE refresh_tokens_old DROP CONSTRAINT uq_refresh_tokens_token_hash;
ALTER TABLE refresh_tokens_old DROP CONSTRAINT refresh_tokens_user_id_fkey;
DROP INDEX idx_refresh_tokens_user_id;

CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,  -- SHA-256 hex = 64자
    user_agent  VARCHAR(512),           -- nullable: 디바이스 식별용
    expires_at  TIMESTAMPTZ  NOT NULL,
    rotated_at  TIMESTAMPTZ,            -- 회전 시각 (null=미회전, 60초 이내 재사용은 동시 경쟁 패자로 허용)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 기존 RT 보존 생략 — rotated_at=NULL인 미회전 상태로 SELECT 가능하나
-- 배포 시 전원 재로그인을 허용하는 결정(플랜 확정)에 따라 의도적으로 비움
DROP TABLE refresh_tokens_old;

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
