#!/usr/bin/env bash
# kista-infra reconcile.yml이 서버 적용 직전 실행하는 kista-api hook — 미완료 EPR 고아 검사.
# 이벤트·리스너 패키지 이동/개명 커밋이 재발행 role(kista-trading=trading, kista-scheduler=public 스키마) 재기동 시
# ClassNotFoundException 반복·영구 미매칭 고아를 만들지 않는지 확인. 정리 절차 → docs/agents/docker-infra.md "배포 직전 EPR 정리 런북"
# 입력: HOOK_ROLES("role=sha" 공백 구분 — 이번에 이미지가 바뀌는 role), PATH의 remote(서버 SSH)
set -euo pipefail

dir=$(dirname "${BASH_SOURCE[0]}")
rc=0
for pair in ${HOOK_ROLES:-}; do
  role=${pair%%=*} sha=${pair#*=}
  case "$role" in
    kista-trading) schema=trading ;;
    kista-scheduler) schema=public ;;
    *) continue ;;
  esac
  pending=$(remote "docker exec kista-postgres psql -U kista -d kistadb -tA -F '|' -c 'SELECT DISTINCT event_type, listener_id FROM ${schema}.event_publication WHERE completion_date IS NULL'") \
    || { echo "::error::${schema}.event_publication 조회 실패 — 고아 여부를 판정할 수 없어 차단(fail-closed). 확인 후 kista-infra Reconcile App force=true로 우회"; exit 1; }
  if ! out=$(EPR_REF="$sha" bash "$dir/epr-orphans.sh" <<<"$pending"); then
    echo "$out"
    echo "::error::미완료 EPR row가 ${role}(${sha:0:8})에 없는 이벤트·리스너를 참조 — 런북대로 ${schema}.event_publication 정리 후 재실행"
    rc=1
  fi
done
exit "$rc"
