#!/usr/bin/env bash
# Flyway 마이그레이션 배포 안전 검사 — 위반 시 사유를 출력하고 exit 1.
#
# 사용: check-migrations.sh [base-ref]
#   1) 적용 이력 불변: base-ref가 주어지면 base-ref..HEAD 사이에 기존 마이그레이션 파일(양 서비스)이 수정·삭제·이름변경됐는지 검사.
#      이미 적용된 파일이 바뀌면 Flyway 체크섬 불일치로 기동 즉시 크래시한다(운영 사고 이력 — constraints.md "Flyway").
#   2) root expand/contract: kista-api·kista-scheduler가 독립 배포라 root(db/migration) 마이그레이션은 직전 이미지와 호환돼야 한다.
#      DROP TABLE·DROP COLUMN·RENAME·SET NOT NULL·ALTER COLUMN TYPE·DEFAULT 없는 NOT NULL 컬럼 추가를 금지한다.
#      두 role을 같은 커밋에서 함께 배포하는 의도적 contract 단계면 파일에 `-- contract-ok: <사유>` 주석으로 예외 표시.
#      V1__init.sql은 스쿼시 baseline이라 제외. trading-core(db/migration-trading)는 단일 프로세스 소유라 대상 아님.
set -euo pipefail

ROOT_DIR=${ROOT_MIGRATIONS:-src/main/resources/db/migration}
ALL_DIRS=("$ROOT_DIR" "${TRADING_MIGRATIONS:-trading-core/src/main/resources/db/migration-trading}")
base=${1:-}
failed=0

fail() { echo "::error file=$1::$2"; failed=1; }

# 1) 적용 이력 불변
if [ -n "$base" ] && git cat-file -e "${base}^{commit}" 2>/dev/null; then
  while IFS=$'\t' read -r status path _; do
    fail "$path" "이미 존재하던 마이그레이션이 변경됨(${status}) — 적용된 파일은 수정·삭제 금지, 새 버전 파일을 추가할 것"
  done < <(git diff --name-status --diff-filter=MDR "$base" HEAD -- "${ALL_DIRS[@]}")
else
  # 수동 실행·신규 브랜치(0000…)·force push로 base가 없으면 불변 검사를 못 한다 — 조용히 넘기지 않고 알린다
  echo "::warning::base(${base:-없음})를 히스토리에서 찾을 수 없어 마이그레이션 적용 이력 불변 검사를 생략합니다"
fi

# 2) root expand/contract — 주석 제거·대문자화 후 문(statement)·절(clause) 단위로 검사
violation() {
  local stmt=$1 clause token
  [[ $stmt =~ ^\ *DROP\ +TABLE ]] && { echo "DROP TABLE"; return; }
  [[ $stmt =~ ^\ *ALTER\ +TABLE ]] || return 0
  [[ $stmt =~ \ RENAME\  ]] && { echo "RENAME"; return; }
  IFS=',' read -ra clauses <<<"$stmt"
  for clause in "${clauses[@]}"; do
    if [[ $clause =~ \ DROP\ +(COLUMN\ +)?(IF\ +EXISTS\ +)?([A-Z_\"]+) ]]; then
      token=${BASH_REMATCH[3]}
      case "$token" in CONSTRAINT|DEFAULT|NOT|IDENTITY|EXPRESSION) ;; *) echo "DROP COLUMN"; return ;; esac
    fi
    [[ $clause =~ SET\ +NOT\ +NULL ]] && { echo "SET NOT NULL"; return; }
    [[ $clause =~ ALTER\ +(COLUMN\ +)?[A-Z_\"]+\ +(SET\ +DATA\ +)?TYPE\  ]] && { echo "ALTER COLUMN TYPE"; return; }
    # 테이블 제약 추가(ADD CONSTRAINT/PRIMARY/UNIQUE/FOREIGN/CHECK)만 제외 — 컬럼 정의에 붙은 CHECK·UNIQUE는 검사 대상
    if [[ $clause =~ (^|\ )ADD\ + ]] && [[ ! $clause =~ (^|\ )ADD\ +(CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK) ]] \
       && [[ $clause =~ NOT\ +NULL ]] && [[ ! $clause =~ DEFAULT ]]; then
      echo "DEFAULT 없는 NOT NULL 컬럼 추가"; return
    fi
  done
}

for file in "$ROOT_DIR"/V*.sql; do
  [ -e "$file" ] || continue
  if [[ $(basename "$file") == V1__* ]]; then continue; fi
  if grep -q -- '-- contract-ok:' "$file"; then
    echo "::notice file=$file::contract-ok 표시 — expand/contract 검사 생략(두 role 동시 배포 전제)"
    continue
  fi
  while IFS= read -r stmt; do
    v=$(violation "$stmt")
    if [ -n "$v" ]; then fail "$file" "root 마이그레이션 backward-compat 위반: ${v} — 직전 이미지와 호환되도록 expand/contract로 나누거나, 두 role 동시 배포가 의도면 '-- contract-ok: <사유>' 표시"; fi
  # 괄호 안(NUMERIC(10,2)·CHECK(...))은 지운다 — 남겨두면 절을 쉼표로 나눌 때 정의가 쪼개져 위반을 놓친다
  done < <(sed 's/--.*$//' "$file" | tr '\n\t' '  ' | sed -E ':a;s/\([^()]*\)//g;ta' \
           | tr ';' '\n' | tr '[:lower:]' '[:upper:]' | tr -s ' ')
done

exit "$failed"
