-- SMALLINT -> INTEGER (Entity int 타입과 일치)
ALTER TABLE fear_greed_snapshots
    ALTER COLUMN value TYPE INTEGER USING value::integer;
