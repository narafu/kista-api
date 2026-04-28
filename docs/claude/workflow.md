## shrimp-task-manager 워크플로
- 태스크 시작: `execute_task(taskId)` 호출 → `in_progress` 상태로 전환
- 태스크 완료: `verify_task(taskId, score, summary)` 호출 — `pending` 상태에서 바로 `verify_task` 불가
- 완료 후 `.shrimp-data/tasks.json` 변경분은 별도 `chore(tasks):` 커밋으로 관리
