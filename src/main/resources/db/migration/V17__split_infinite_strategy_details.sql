CREATE TABLE strategy_infinite (
    strategy_id UUID NOT NULL,
    division_count INTEGER NOT NULL,
    CONSTRAINT strategy_infinite_pkey PRIMARY KEY (strategy_id),
    CONSTRAINT strategy_infinite_strategy_id_fkey
        FOREIGN KEY (strategy_id) REFERENCES strategy(id) ON DELETE CASCADE
);

INSERT INTO strategy_infinite (strategy_id, division_count)
SELECT id, division_count
FROM strategy
WHERE type = 'INFINITE' AND deleted_at IS NULL;

CREATE TABLE cycle_position_infinite (
    cycle_position_id UUID NOT NULL,
    is_reverse_mode BOOLEAN NOT NULL,
    CONSTRAINT cycle_position_infinite_pkey PRIMARY KEY (cycle_position_id),
    CONSTRAINT cycle_position_infinite_cycle_position_id_fkey
        FOREIGN KEY (cycle_position_id) REFERENCES cycle_position(id) ON DELETE CASCADE
);

INSERT INTO cycle_position_infinite (cycle_position_id, is_reverse_mode)
SELECT id, is_reverse_mode
FROM cycle_position
WHERE deleted_at IS NULL;

ALTER TABLE strategy DROP COLUMN division_count;
ALTER TABLE cycle_position DROP COLUMN is_reverse_mode;
ALTER TABLE strategy_cycle DROP COLUMN seed_resolved_by;
