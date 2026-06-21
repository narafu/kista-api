-- 공포탐욕지수 일별 스냅샷 (크립토 + CNN)
CREATE TABLE fear_greed_snapshots (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    snapshot_date   DATE         NOT NULL,
    crypto_rating   VARCHAR(20)  NOT NULL,
    crypto_value    INTEGER      NOT NULL,
    cnn_rating      VARCHAR(20)  NOT NULL,
    cnn_score       NUMERIC(5,2) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fear_greed_snapshots_pkey PRIMARY KEY (id),
    CONSTRAINT uq_fear_greed_snapshots_date UNIQUE (snapshot_date)
);
