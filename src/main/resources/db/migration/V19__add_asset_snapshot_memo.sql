-- 자산 기록에 자유 메모 필드 추가. strategy(운용전략)와는 별개 개념 — 재사용하지 않고 신설한다.
ALTER TABLE finance_asset_snapshots ADD COLUMN memo VARCHAR(255);
