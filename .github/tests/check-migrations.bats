#!/usr/bin/env bats
# .github/scripts/check-migrations.sh — expand/contract 규칙과 적용 이력 불변 검사

SCRIPT="$BATS_TEST_DIRNAME/../scripts/check-migrations.sh"

setup() {
  cd "$BATS_TEST_TMPDIR" && git init -q repo && cd repo
  git config user.email t@t && git config user.name t
  export ROOT_MIGRATIONS=root TRADING_MIGRATIONS=trading
  mkdir -p root trading
  echo "CREATE TABLE a (id INT); ALTER TABLE a DROP COLUMN x;" > root/V1__init.sql   # baseline은 검사 제외
  echo "CREATE TABLE t (id INT);" > trading/V1__init.sql
  git add -A && git commit -qm base
}

migrate() { printf '%s\n' "$2" > "root/$1"; }

@test "허용: nullable·DEFAULT 컬럼 추가, 인덱스, 제약 해제" {
  migrate V2__ok.sql "ALTER TABLE public.a ADD COLUMN y TIMESTAMPTZ;
ALTER TABLE a ADD COLUMN z VARCHAR(20) NOT NULL DEFAULT 'X';
CREATE INDEX i ON a (y);
ALTER TABLE a DROP CONSTRAINT fk_x, ALTER COLUMN y DROP NOT NULL, ALTER COLUMN z DROP DEFAULT;
ALTER TABLE a ADD CONSTRAINT uq UNIQUE (z);"
  run bash "$SCRIPT"
  [ "$status" -eq 0 ]
}

@test "금지: DROP TABLE·DROP COLUMN·RENAME·SET NOT NULL·TYPE 변경·DEFAULT 없는 NOT NULL 추가" {
  for sql in "DROP TABLE a;" "alter table a drop column y;" "ALTER TABLE a DROP y;" "ALTER TABLE a RENAME COLUMN y TO w;" \
             "ALTER TABLE a RENAME TO b;" "ALTER TABLE a ALTER COLUMN y SET NOT NULL;" "ALTER TABLE a ALTER COLUMN y TYPE BIGINT;" \
             "ALTER TABLE a ADD COLUMN w INT NOT NULL;" "ALTER TABLE a ADD COLUMN v INT, DROP COLUMN y;" \
             "ALTER TABLE a ADD COLUMN p NUMERIC(10,2) NOT NULL;" "ALTER TABLE a ADD COLUMN c VARCHAR(10) CHECK (c <> 'x') NOT NULL;"; do
    migrate V2__bad.sql "$sql"
    run bash "$SCRIPT"
    [ "$status" -eq 1 ] || { echo "통과하면 안 됨: $sql"; return 1; }
  done
}

@test "주석 속 금지어는 무시, contract-ok 표시 파일은 예외" {
  migrate V2__c.sql "-- 다음 배포에서 DROP COLUMN 예정
ALTER TABLE a ADD COLUMN y INT;"
  run bash "$SCRIPT"
  [ "$status" -eq 0 ]
  migrate V3__contract.sql "-- contract-ok: 참조 제거 배포(abc123) 이후 두 role 동시 배포
ALTER TABLE a DROP COLUMN y;"
  run bash "$SCRIPT"
  [ "$status" -eq 0 ]
}

@test "trading expand/contract: 컷오프 이하는 제외, 초과는 금지, contract-ok는 예외" {
  export TRADING_CHECK_AFTER=24
  echo "ALTER TABLE t DROP COLUMN x;" > trading/V24__old.sql
  run bash "$SCRIPT"
  [ "$status" -eq 0 ]
  echo "ALTER TABLE t DROP COLUMN y;" > trading/V25__bad.sql
  run bash "$SCRIPT"
  [ "$status" -eq 1 ]
  [[ "$output" == *"trading/V25__bad.sql"* ]]
  [[ "$output" != *"trading/V24__old.sql"* ]]
  printf '%s\n' "-- contract-ok: 두 role 동시 배포" "ALTER TABLE t DROP COLUMN y;" > trading/V25__bad.sql
  run bash "$SCRIPT"
  [ "$status" -eq 0 ]
}

@test "정수가 아닌 버전은 검사를 건너뛰지 않고 실패" {
  echo "ALTER TABLE t ADD COLUMN y INT;" > trading/V25_1__dotted.sql
  run bash "$SCRIPT"
  [ "$status" -eq 1 ]
  [[ "$output" == *"trading/V25_1__dotted.sql"* ]]
}

@test "적용 이력 불변: base 이후 기존 파일 수정·삭제는 양 서비스 모두 실패, 신규 추가는 통과" {
  base=$(git rev-parse HEAD)
  echo "CREATE TABLE t2 (id INT);" > trading/V2__new.sql && git add -A && git commit -qm add
  run bash "$SCRIPT" "$base"
  [ "$status" -eq 0 ]
  echo "-- 수정" >> trading/V1__init.sql && git commit -qam modify
  run bash "$SCRIPT" "$base"
  [ "$status" -eq 1 ]
  [[ "$output" == *"trading/V1__init.sql"* ]]
}

@test "적용 이력 불변: 마이그레이션 디렉토리의 SQL 아닌 파일 삭제는 통과" {
  echo "# 문서" > root/CLAUDE.md && git add -A && git commit -qm doc
  base=$(git rev-parse HEAD)
  git rm -q root/CLAUDE.md && git commit -qm rmdoc
  run bash "$SCRIPT" "$base"
  [ "$status" -eq 0 ]
}
