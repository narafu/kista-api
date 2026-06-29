-- fear_greed snapshot_date를 시각 단위 저장으로 변경
ALTER TABLE fear_greed_snapshots
    ALTER COLUMN snapshot_date TYPE TIMESTAMPTZ
    USING (snapshot_date::timestamp AT TIME ZONE 'Asia/Seoul');
