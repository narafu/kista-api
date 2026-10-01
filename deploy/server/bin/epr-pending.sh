#!/usr/bin/env bash
# 미완료 이벤트 발행(Modulith EPR) 목록 출력 — "event_type|listener_id" 한 줄씩. 조회 실패는 non-zero exit.
# 사용: epr-pending.sh <schema>   (trading = kista-trading 소유, public = kista-scheduler 소유)
set -euo pipefail

schema=$1
case "$schema" in trading|public) ;; *) echo "알 수 없는 스키마: $schema" >&2; exit 2 ;; esac

docker exec kista-postgres psql -U kista -d kistadb -tA -F '|' -c "
  SELECT DISTINCT event_type, listener_id
    FROM ${schema}.event_publication
   WHERE completion_date IS NULL"
