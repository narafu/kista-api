package com.kista.user.application.usecase;

// 4a 배포(2026-09-16 이전) 이후 root→trading-core 이벤트 전달 경로가 없어 쌓인 드리프트를
// 일회성으로 복구한다 — Redis Stream 배선(Task 2~5) 완료 후에는 재발 방지되므로 이 usecase는
// 배포 직후 admin이 한 번만 트리거하면 된다. 이미 정상 동기화된 사용자에게 재실행해도
// 멱등(cascade 재발행은 idempotent, 프로필 재발행은 upsert)하므로 여러 번 눌러도 안전하다.
public interface UserSyncBackfillUseCase {

    BackfillResult runOnce();

    record BackfillResult(int cascadeRepublished, int profileBackfilled) {
    }
}
