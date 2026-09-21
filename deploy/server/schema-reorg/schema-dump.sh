#!/usr/bin/env bash
# 사용법: [SCHEMAS="public finance kista_ref"] schema-dump.sh <postgres-컨테이너> <db> <출력.sql>
# baseline 생성과 리허설 diff 게이트가 같은 덤프 형식을 쓰도록 공용화. 이력 테이블은 항상 제외한다.
# pg_dump 17은 덤프마다 무작위 토큰이 든 restrict/unrestrict 줄을 넣어 diff가 항상 갈리므로 제거한다.
set -euo pipefail
container=$1; db=$2; out=$3
schemas=${SCHEMAS:-"public finance kista_ref trading trading_ref"}
args=()
for s in $schemas; do args+=(-n "$s"); done
docker exec "$container" pg_dump -U kista -d "$db" --schema-only --no-owner --no-privileges "${args[@]}" \
  -T public.flyway_schema_history -T public.flyway_schema_history_api -T trading.flyway_schema_history_trading \
  | grep -vE '^\\(un)?restrict ' > "$out"
