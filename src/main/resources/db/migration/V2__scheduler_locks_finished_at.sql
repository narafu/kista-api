-- 배치 완료 시각 — SchedulerLockService는 성공 후에도 중복 실행 방지를 위해 락을 TTL까지 유지하므로
-- lock_until만으로는 "실행 중"과 "완료 후 보유"를 구분할 수 없다. 배포 가드가 실행 중 배치만 막도록 완료 시각을 기록한다.
-- nullable 추가라 2-role 배포 backward-compat(구 이미지는 컬럼을 모름 → NULL 유지)
ALTER TABLE public.scheduler_locks ADD COLUMN finished_at TIMESTAMPTZ;
