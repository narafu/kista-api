-- 기존 테이블 삭제 후 source-per-row 구조로 재생성
DROP TABLE IF EXISTS fear_greed_snapshots;

CREATE TABLE fear_greed_snapshots (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    source        VARCHAR(20) NOT NULL, -- CNN | CRYPTO
    snapshot_date DATE        NOT NULL,
    value         SMALLINT    NOT NULL,
    rating        VARCHAR(20) NOT NULL, -- EXTREME_FEAR | FEAR | NEUTRAL | GREED | EXTREME_GREED
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fear_greed_snapshots_pkey PRIMARY KEY (id),
    CONSTRAINT uq_fear_greed_source_date UNIQUE (source, snapshot_date)
);
