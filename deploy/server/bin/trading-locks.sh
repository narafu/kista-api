#!/usr/bin/env bash
# 실행 중인 매매 배치 락 목록(쉼표 구분) 출력 — 조회 실패는 non-zero exit(호출측 가드가 fail-closed로 차단).
# SchedulerLockService는 성공 후에도 중복 실행 방지로 락을 TTL까지 유지하므로, 완료 기록(finished_at)이 없거나
# 이번 획득(locked_at)보다 이전 것(구 이미지가 재획득해 리셋 못 한 값)만 "실행 중"으로 본다.
# to_jsonb로 읽는 이유: finished_at을 추가하는 trading 마이그레이션보다 가드가 먼저 도는 배포에서도 동작하도록.
set -euo pipefail

docker exec kista-postgres psql -U kista -d kistadb -tAc "
  SELECT coalesce(string_agg(name, ','), '')
    FROM trading.scheduler_locks l
   WHERE name IN ('trading-open', 'trading-close')
     AND lock_until > now()
     AND coalesce((to_jsonb(l) ->> 'finished_at')::timestamptz < locked_at, true)"
