-- 컬럼 순서 규칙: pk → fk → 비즈니스 컬럼 → created_at (updated_at/deleted_at 없음)
CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,  -- SHA-256 hex = 64자
    user_agent  VARCHAR(512),           -- nullable: 디바이스 식별용
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
