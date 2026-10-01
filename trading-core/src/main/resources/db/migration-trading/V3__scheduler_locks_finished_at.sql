-- 배치 완료 시각 — SchedulerLockService는 성공 후에도 중복 실행 방지를 위해 락을 TTL까지 유지하므로
-- lock_until만으로는 "실행 중"과 "완료 후 보유"를 구분할 수 없다. 배포 가드(kista-api _deploy-role.yml·kista-infra)가
-- trading-open/trading-close의 실행 중 여부를 lock_until > now() AND finished_at IS NULL로 판정한다.
ALTER TABLE trading.scheduler_locks ADD COLUMN finished_at TIMESTAMPTZ;
