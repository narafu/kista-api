-- refresh_tokens에 rotated_at 컬럼 추가 (RTR grace window 복원)
-- 컬럼 순서: id, user_id, token_hash, user_agent, expires_at, rotated_at, created_at
-- ADD COLUMN은 항상 맨 뒤에 붙으므로 재생성으로 순서를 맞춤
-- 기존 RT 데이터는 INSERT SELECT로 보존 (rotated_at = NULL 미회전 상태)

ALTER TABLE refresh_tokens RENAME TO refresh_tokens_old;

-- named 제약조건은 스키마 전역이므로 새 테이블 CREATE 전에 제거
ALTER TABLE refresh_tokens_old DROP CONSTRAINT uq_refresh_tokens_token_hash;
ALTER TABLE refresh_tokens_old DROP CONSTRAINT refresh_tokens_user_id_fkey;
DROP INDEX idx_refresh_tokens_user_id;

CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    user_agent  VARCHAR(512),
    expires_at  TIMESTAMPTZ  NOT NULL,
    rotated_at  TIMESTAMPTZ,            -- null=미회전, 값 있음=회전됨 (60초 이내 재제시는 동시 경쟁 패자로 허용)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 기존 RT 보존 (rotated_at = NULL 미회전 상태로 이관)
INSERT INTO refresh_tokens (id, user_id, token_hash, user_agent, expires_at, rotated_at, created_at)
SELECT id, user_id, token_hash, user_agent, expires_at, NULL, created_at
FROM refresh_tokens_old;

DROP TABLE refresh_tokens_old;

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
