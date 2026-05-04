CREATE TABLE kis_tokens (
    id           INT         PRIMARY KEY,
    access_token TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
