#!/usr/bin/env bats
# .github/scripts의 배포 판정 스크립트 — 순수 로직이라 실제 git 저장소·입력으로 검증

SCRIPTS="$BATS_TEST_DIRNAME/../scripts"

scope() { printf '%s\n' "$@" | bash "$SCRIPTS/detect-deploy-scope.sh" | tr '\n' ' '; }

@test "scope: 문서·런북·대시보드는 아무것도 배포하지 않음" {
  [ "$(scope docs/x.md deploy/server/README.md deploy/server/schema-reorg/RUNBOOK.md deploy/grafana/d.json)" = "verify=false api=false scheduler=false trading=false " ]
}

@test "scope: trading-core 소스는 trading만, 스케쥴러 전용 빈은 scheduler만" {
  [ "$(scope trading-core/src/main/java/A.java)" = "verify=true api=false scheduler=false trading=true " ]
  [ "$(scope src/main/java/com/kista/benchmark/adapter/in/schedule/S.java)" = "verify=true api=false scheduler=true trading=false " ]
}

@test "scope: Caddy 스니펫은 api, compose·배포 스크립트는 전부" {
  [ "$(scope deploy/server/caddy/kista-api.caddy)" = "verify=true api=true scheduler=false trading=false " ]
  [ "$(scope deploy/server/docker-compose.yml)" = "verify=true api=true scheduler=true trading=true " ]
  [ "$(scope deploy/server/bin/health-gate.sh)" = "verify=true api=true scheduler=true trading=true " ]
}

@test "scope: 테스트만 바뀌면 검증만" {
  [ "$(scope src/test/java/T.java trading-core/src/testFixtures/F.java .github/tests/x.bats)" = "verify=true api=false scheduler=false trading=false " ]
}

# 임시 저장소: c1(최초) → c2(trading 변경) → c3(문서)
make_repo() {
  cd "$BATS_TEST_TMPDIR" && git init -q repo && cd repo
  git config user.email t@t && git config user.name t
  mkdir -p trading-core/src/main docs
  echo 1 > trading-core/src/main/A.java && git add -A && git commit -qm c1 && C1=$(git rev-parse HEAD)
  echo 2 > trading-core/src/main/A.java && git commit -qam c2 && C2=$(git rev-parse HEAD)
  echo d > docs/x.md && git add -A && git commit -qm c3 && C3=$(git rev-parse HEAD)
}

plan() { bash "$SCRIPTS/plan-deploy.sh" "$1" 2>/dev/null | tr '\n' ' '; }

@test "plan: 가드에 막혀 trading만 옛 버전이면 docs 커밋에서도 trading을 배포" {
  make_repo
  run plan "$C3" <<<"/kista-api img:$C2
/kista-scheduler img:$C2
/kista-trading img:$C1"
  [ "$output" = "api=false scheduler=false trading=true verify=true " ]
}

@test "plan: 이미 같거나 더 새 커밋이 돌면 배포하지 않음(옛 run 재실행 방지)" {
  make_repo
  run plan "$C2" <<<"/kista-api img:$C3
/kista-scheduler img:$C2
/kista-trading img:$C3"
  [ "$output" = "api=false scheduler=false trading=false verify=false " ]
}

@test "plan: 컨테이너가 없거나 SHA를 모르면 그 role은 배포" {
  make_repo
  run plan "$C3" <<<"/kista-api img:deadbeef"
  [ "$output" = "api=true scheduler=true trading=true verify=true " ]
}

@test "newer-running: 더 새 커밋이 돌면 그 컨테이너를 알려주고, 아니면 exit 1" {
  make_repo
  run bash "$SCRIPTS/newer-running.sh" "$C2" <<<"/kista-api img:$C2
/kista-trading img:$C3"
  [ "$status" -eq 0 ]
  [ "$output" = "kista-trading $C3" ]
  run bash "$SCRIPTS/newer-running.sh" "$C3" <<<"/kista-api img:$C2"
  [ "$status" -eq 1 ]
}

@test "guard: 시각 창·실행 중 락이면 차단, 그 외 통과" {
  run bash "$SCRIPTS/trading-guard.sh" 3 2230 ""
  [ "$status" -eq 1 ] && [[ "$output" == *"개장"* ]]
  run bash "$SCRIPTS/trading-guard.sh" 2 0500 ""
  [ "$status" -eq 1 ] && [[ "$output" == *"마감"* ]]
  run bash "$SCRIPTS/trading-guard.sh" 3 1500 "trading-close"
  [ "$status" -eq 1 ] && [[ "$output" == *"trading-close"* ]]
  run bash "$SCRIPTS/trading-guard.sh" 6 2230 ""   # 토요일 밤 개장 없음
  [ "$status" -eq 0 ]
  run bash "$SCRIPTS/trading-guard.sh" 1 0500 ""   # 월요일 새벽 마감 없음
  [ "$status" -eq 0 ]
  run bash "$SCRIPTS/trading-guard.sh" 3 0800 ""
  [ "$status" -eq 0 ]
}
