#!/usr/bin/env bash
# 배포하려는 소스(현재 작업 트리)에서 미완료 EPR row가 참조하는 이벤트 클래스·리스너 클래스·리스너 메서드를 찾을 수 없으면
# 그 row를 출력하고 exit 1 — 재발행 role 재기동 시 ClassNotFoundException 반복이나 영구 미매칭 고아가 된다.
# 이벤트·리스너 패키지 이동·개명 커밋을 배포 전에 잡기 위한 검사. 정리 절차 → docs/agents/docker-infra.md "배포 직전 EPR 정리 런북".
#
# 사용: epr-orphans.sh   (stdin: "event_type|listener_id" — deploy/server/bin/epr-pending.sh 출력)
#   listener_id 형식: com.kista.x.Listener.method(com.kista.y.SomeEvent)
set -euo pipefail

# FQCN → 소스 파일(서브프로젝트 무관). 중첩 클래스(Outer$Inner)는 바깥 파일로 찾는다
source_of() {
  local path=${1%%\$*}
  git ls-files -- "src/main/java/${path//.//}.java" "*/src/main/java/${path//.//}.java" | sed -n 1p   # head는 pipefail에서 SIGPIPE로 실패할 수 있다
}

orphans=0
while IFS='|' read -r event_type listener_id; do
  [ -n "${event_type:-}" ] || continue
  missing=""
  [ -n "$(source_of "$event_type")" ] || missing="이벤트 ${event_type}"
  if [ -n "${listener_id:-}" ]; then
    target=${listener_id%%(*}              # com.kista.x.Listener.method
    listener=${target%.*} method=${target##*.}
    file=$(source_of "$listener")
    if [ -z "$file" ]; then
      missing="${missing:+$missing, }리스너 ${listener}"
    elif ! grep -Eq "[[:space:]]${method}[[:space:]]*\(" "$file"; then
      missing="${missing:+$missing, }리스너 메서드 ${listener}.${method}"
    fi
  fi
  if [ -n "$missing" ]; then
    echo "미완료 EPR 고아 예정: ${missing} (event_type=${event_type}, listener_id=${listener_id})"
    orphans=1
  fi
done

exit "$orphans"
