#!/usr/bin/env bats
# .github/scripts의 배포 판정 스크립트 — 순수 로직이라 실제 git 저장소·입력으로 검증

SCRIPTS="$BATS_TEST_DIRNAME/../scripts"

scope() { printf '%s\n' "$@" | bash "$SCRIPTS/detect-deploy-scope.sh" | tr '\n' ' '; }

@test "scope: 문서·런북·워크플로·배포 스크립트 테스트는 아무것도 배포하지 않음" {
  [ "$(scope docs/x.md deploy/server/README.md deploy/server/schema-reorg/RUNBOOK.md deploy/grafana/d.json .github/workflows/server-deploy.yml .github/scripts/plan-deploy.sh .github/tests/x.bats)" = "verify=false config=false api=false scheduler=false trading=false " ]
}

@test "scope: trading-core 소스는 trading만, 스케쥴러 전용 빈은 scheduler만" {
  [ "$(scope trading-core/src/main/java/A.java)" = "verify=true config=false api=false scheduler=false trading=true " ]
  [ "$(scope src/main/java/com/kista/benchmark/adapter/in/schedule/S.java)" = "verify=true config=false api=false scheduler=true trading=false " ]
}

@test "scope: compose·roles·hook은 config만, Caddy는 config+verify" {
  [ "$(scope deploy/server/docker-compose.yml deploy/server/roles deploy/server/readiness deploy/hooks/pre-apply.sh)" = "verify=false config=true api=false scheduler=false trading=false " ]
  [ "$(scope deploy/server/caddy/kista-api.caddy)" = "verify=true config=true api=false scheduler=false trading=false " ]
}

@test "scope: 테스트 소스는 검증만, 빌드 공용 입력은 3 role 전부" {
  [ "$(scope src/test/java/T.java trading-core/src/testFixtures/F.java)" = "verify=true config=false api=false scheduler=false trading=false " ]
  [ "$(scope Dockerfile)" = "verify=true config=false api=true scheduler=true trading=true " ]
}

# 임시 저장소: c1(최초) → c2(trading 변경) → c3(문서) → c4(Caddy 스니펫)
make_repo() {
  cd "$BATS_TEST_TMPDIR" && git init -q repo && cd repo
  git config user.email t@t && git config user.name t
  mkdir -p trading-core/src/main docs
  echo 1 > trading-core/src/main/A.java && git add -A && git commit -qm c1 && C1=$(git rev-parse HEAD)
  echo 2 > trading-core/src/main/A.java && git commit -qam c2 && C2=$(git rev-parse HEAD)
  echo d > docs/x.md && git add -A && git commit -qm c3 && C3=$(git rev-parse HEAD)
  mkdir -p deploy/server/caddy && echo s > deploy/server/caddy/kista-api.caddy && git add -A && git commit -qm c4 && C4=$(git rev-parse HEAD)
}

state_of() { printf 'config: %s\nroles:\n  kista-trading: %s\n  kista-api: %s\n  kista-scheduler: %s\n' "$1" "$2" "$3" "$4"; }
plan() { bash "$SCRIPTS/plan-deploy.sh" "$1" 2>/dev/null | tr '\n' ' '; }

@test "plan: trading만 바뀐 범위는 trading만 target, 나머지 state 유지, 빌드·배포" {
  make_repo
  out=$(state_of "$C1" "$C1" "$C1" "$C1" | plan "$C3")
  [[ "$out" == *"verify=true"* && "$out" == *"build=true"* && "$out" == *"deploy=true"* && "$out" == *"config=$C1"* ]]
  [[ "$out" == *"roles=kista-trading=$C3 kista-api=$C1 kista-scheduler=$C1"* ]]
}

@test "plan: Caddy만 바뀌면 config만 target, 빌드 없음" {
  make_repo
  out=$(state_of "$C3" "$C3" "$C3" "$C3" | plan "$C4")
  [[ "$out" == *"build=false"* && "$out" == *"deploy=true"* && "$out" == *"config=$C4"* ]]
  [[ "$out" == *"roles=kista-trading=$C3 kista-api=$C3 kista-scheduler=$C3"* ]]
}

@test "plan: state가 target보다 새것(옛 run 재실행)이면 전부 유지·배포 없음" {
  make_repo
  out=$(state_of "$C4" "$C4" "$C4" "$C4" | plan "$C2")
  [[ "$out" == *"deploy=false"* && "$out" == *"build=false"* && "$out" == *"verify=false"* ]]
}

@test "plan: state SHA가 히스토리에 없으면 그 role은 target + verify" {
  make_repo
  out=$(state_of "$C3" deadbeefdeadbeefdeadbeefdeadbeefdeadbeef "$C3" "$C3" | plan "$C3")
  [[ "$out" == *"verify=true"* && "$out" == *"kista-trading=$C3"* && "$out" == *"build=true"* ]]
}

@test "plan: 빈 state(조회 실패)는 실패" {
  make_repo
  run bash -c "printf '' | bash '$SCRIPTS/plan-deploy.sh' '$C3'"
  [ "$status" -ne 0 ]
}
