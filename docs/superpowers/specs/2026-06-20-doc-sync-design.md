# doc-sync 설계 문서

**날짜:** 2026-06-20  
**목적:** git commit 후 CLAUDE.md @참조 문서를 코드 변경사항과 자동 동기화

---

## 배경

CLAUDE.md에 `@docs/claude/*.md` 형태로 참조된 문서들이 매 대화마다 컨텍스트에 자동 로드된다.
코드가 바뀌어도 문서가 수동으로 관리되다 보니 점점 오래된 내용이 쌓여 토큰을 낭비하고 신뢰도가 떨어진다.

---

## 목표

- git commit 직후 자동으로 참조 문서를 코드와 비교
- 불일치·삭제된 엔티티·새 패턴을 적극적으로 수정
- 모든 프로젝트에서 동작하되, @참조가 없는 프로젝트는 자동 스킵

---

## 전체 흐름

```
[Claude Code에서 Bash 도구로 git commit 실행]
            ↓
[PostToolUse 훅: ~/.claude/settings.json]
  - stdin JSON에서 실행 명령 추출
  - git commit 명령이 아니면 → 즉시 종료
  - CLAUDE.md 없으면 → 즉시 종료
  - 해당되면 → stdout으로 서브에이전트 실행 지시 메시지 출력
            ↓
[Claude가 메시지 수신 → doc-sync 서브에이전트 spawn]
            ↓
[서브에이전트: ~/.claude/agents/doc-sync.md]
  1. CLAUDE.md 읽어서 @ 참조 파일 목록 추출
  2. git diff HEAD~1 로 변경된 코드 파악
  3. 각 참조 문서 × 변경 코드 교차 분석
  4. 불일치 발견 시 적극적으로 문서 수정
  5. 수정 있으면 git commit ("docs: sync with [변경 파일명]")
  6. 변경 없으면 조용히 종료
```

---

## 컴포넌트 설계

### 1. 훅 설정 (`~/.claude/settings.json`)

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "~/.claude/hooks/doc-sync-trigger.sh"
          }
        ]
      }
    ]
  }
}
```

### 2. 트리거 스크립트 (`~/.claude/hooks/doc-sync-trigger.sh`)

```bash
#!/bin/bash
input=$(cat)
command=$(echo "$input" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tool_input',{}).get('command',''))" 2>/dev/null)

# git commit 명령이 아니면 종료
echo "$command" | grep -qE '^git commit' || exit 0

# CLAUDE.md 없는 프로젝트 스킵
[ -f "CLAUDE.md" ] || exit 0

echo "doc-sync: git commit 감지됨. doc-sync 서브에이전트로 문서 동기화를 실행하세요."
```

### 3. 서브에이전트 정의 (`~/.claude/agents/doc-sync.md`)

**실행 순서:**

1. CLAUDE.md 읽기 → `@` 참조 파일 목록 추출 (없으면 종료)
2. `git diff HEAD~1 --name-only` → 변경된 파일 목록
3. `git diff HEAD~1` → diff 내용 (클래스명·패키지·메서드명 변경 파악)
4. 각 참조 문서 분석:
   - 문서에 언급된 코드 엔티티가 실제로 존재하는지 확인
   - 변경 코드에 새 패턴/클래스/구조가 생겼는지 확인
5. 수정 (적극적 원칙):
   - 코드에서 제거된 엔티티 → 문서에서 즉시 삭제
   - 이름이 바뀐 엔티티 → 문서에서 즉시 갱신
   - 새 패턴 등장 → 관련 섹션에 즉시 추가
6. 수정된 파일이 있으면 `git commit -m "docs: sync with [변경된 파일명]"`
7. 변경 없으면 조용히 종료

---

## 판단 기준 (적극적 수정 원칙)

| 감지 상황 | 동작 |
|-----------|------|
| 문서의 클래스명이 코드에 없음 | 해당 줄 삭제 |
| 문서의 패키지 경로가 바뀜 | 새 경로로 갱신 |
| 문서의 메서드명이 리네임됨 | 새 이름으로 갱신 |
| 새 `@Service`/`@Component`/record 등장 | 관련 섹션에 추가 |
| 새 아키텍처 패턴 도입 | architecture.md에 반영 |
| 새 제약사항 코드에 추가 | constraints.md에 반영 |

오탐보다 최신화를 우선한다. 잘못된 수정은 `git revert`로 복원 가능.

---

## 스코프 제한

- 분석 범위: `git diff HEAD~1` 변경 파일로 한정 (전체 코드베이스 full scan 금지)
- 수정 범위: 해당 줄만 최소 수정 (문서 전체 재작성 금지)
- 트리거 조건: Claude Code Bash 도구로 실행한 `git commit`만 (터미널 직접 실행 제외)
- 프로젝트 필터: CLAUDE.md + @ 참조 없으면 자동 스킵

---

## 파일 위치 요약

| 파일 | 위치 | git 포함 |
|------|------|----------|
| 훅 설정 | `~/.claude/settings.json` | ❌ (글로벌) |
| 트리거 스크립트 | `~/.claude/hooks/doc-sync-trigger.sh` | ❌ (글로벌) |
| 서브에이전트 | `~/.claude/agents/doc-sync.md` | ❌ (글로벌) |

모두 글로벌 위치 → 이 PC의 모든 프로젝트에서 자동 동작.
