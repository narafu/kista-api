CREATE TABLE strategy_version (
    id UUID NOT NULL,
    strategy_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT strategy_version_pkey PRIMARY KEY (id),
    CONSTRAINT strategy_version_strategy_id_fkey
        FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_strategy_version_strategy_version_no
    ON strategy_version (strategy_id, version_no);

CREATE TABLE strategy_infinite_version (
    strategy_version_id UUID NOT NULL,
    division_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT strategy_infinite_version_pkey PRIMARY KEY (strategy_version_id),
    CONSTRAINT strategy_infinite_version_strategy_version_id_fkey
        FOREIGN KEY (strategy_version_id) REFERENCES strategy_version(id) ON DELETE CASCADE
);

ALTER TABLE strategy_cycle
    ADD COLUMN strategy_version_id UUID;

INSERT INTO strategy_version (id, strategy_id, version_no, created_at, deleted_at)
SELECT gen_random_uuid(), s.id, 1, s.created_at, s.deleted_at
FROM strategy s;

INSERT INTO strategy_infinite_version (strategy_version_id, division_count, created_at, updated_at, deleted_at)
SELECT sv.id, s.division_count, s.created_at, s.updated_at, s.deleted_at
FROM strategy s
JOIN strategy_version sv ON sv.strategy_id = s.id
WHERE s.type = 'INFINITE';

UPDATE strategy_cycle sc
SET strategy_version_id = sv.id
FROM strategy_version sv
WHERE sc.strategy_id = sv.strategy_id
  AND sv.version_no = 1;

ALTER TABLE strategy_cycle
    ALTER COLUMN strategy_version_id SET NOT NULL;

ALTER TABLE strategy_cycle
    ADD CONSTRAINT strategy_cycle_strategy_version_id_fkey
        FOREIGN KEY (strategy_version_id) REFERENCES strategy_version(id) ON DELETE CASCADE;

CREATE TABLE cycle_position_infinite (
    cycle_position_id UUID NOT NULL,
    is_reverse_mode BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT cycle_position_infinite_pkey PRIMARY KEY (cycle_position_id),
    CONSTRAINT cycle_position_infinite_cycle_position_id_fkey
        FOREIGN KEY (cycle_position_id) REFERENCES cycle_position(id) ON DELETE CASCADE
);

INSERT INTO cycle_position_infinite (cycle_position_id, is_reverse_mode, created_at, deleted_at)
SELECT id, is_reverse_mode, created_at, deleted_at
FROM cycle_position;

ALTER TABLE strategy DROP COLUMN division_count;
ALTER TABLE cycle_position DROP COLUMN is_reverse_mode;
ALTER TABLE strategy_cycle DROP COLUMN seed_resolved_by;
