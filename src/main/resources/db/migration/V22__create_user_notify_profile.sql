-- ⚠ 배포 순서 제약: 이 커밋은 kista-api를 kista-scheduler보다 먼저(또는 동시에) 배포해야 한다.
--   user_notify_profile 동기화 이벤트(UserNotifyProfileChangedEvent)는 kista-api 경로(가입·승인·
--   거절·재신청·설정변경)에서만 발행된다. 구 kista-api + 신 kista-scheduler 조합으로 뒤바뀌면,
--   그 창 동안 가입·승인된 사용자는 구 API 이미지가 이벤트를 발행하지 않아 복제본 row가 누락되고,
--   복제본만 읽는 신 스케쥴러가 그 사용자의 전략마다 NoSuchElementException을 낸다.
--   반대 순서(신 API + 구 스케쥴러)는 무해하다 — 구 스케쥴러는 users/user_settings를 직접 읽으므로
--   복제본이 채워지든 말든 영향이 없다. 즉 이 제약은 단방향이다.
--   (constraints.md "2-role 배포 backward-compat" 참고)
--
-- trading-core가 소유하는 사용자 알림·잔고검증·활성여부 읽기 전용 복제본.
-- 현재는 root와 같은 DB지만 4단계(DB 분리) 이후에도 그대로 남는 trading-core 전용 테이블이라
-- 매매 도메인 스키마(kista)에 둔다. 원본(public.users/user_settings/user_notification_prefs)은
-- 계속 user 모듈 소유이며, 동기화는 UserNotifyProfileChangedEvent/UserDeletedEvent로만 이뤄진다.
--
-- notification_prefs는 Map<NotificationType,Boolean>을 JSON 텍스트로 저장 — 이 테이블은 복제본일 뿐
-- 정규화된 소스오브트루스가 아니므로 별도 자식 테이블을 두지 않는다.
-- is_active는 UserStatus.ACTIVE 여부만 담는다 — TradingUserProfilePort.findAllActive()(장 개장·마감
-- 브로드캐스트 대상)가 이 컬럼으로만 판별하므로 상태 전이마다 반드시 함께 갱신돼야 한다.
CREATE TABLE kista.user_notify_profile (
    user_id               UUID        NOT NULL,
    notification_prefs    TEXT        NOT NULL DEFAULT '{}',
    balance_check_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    is_active             BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_notify_profile_pkey PRIMARY KEY (user_id)
);

-- is_active 인덱스 없음: 사용자당 1행인 작은 테이블이고 findAllActive()는 하루 2회(개장·마감)만
-- 도는 전체 스캔이라 seq scan으로 충분하다. 사용자 수가 크게 늘면 부분 인덱스를 추가한다.

-- 기존 사용자 백필 — 배포 시점에 반드시 채워져 있어야 한다. 비어 있으면:
--   findAllByUserIds() 빈 맵 → BatchContextFactory가 전략마다 NoSuchElementException +
--     errorReportPort.reportError() (매매 전면 중단, 관리자 알림 폭주)
--   findByUserId() 빈 Optional → 전략 등록이 "사용자를 찾을 수 없습니다"로 거부
--   findAllActive() 빈 리스트 → 개장·마감 알림이 조용히 아무에게도 안 나감(무증상)
-- - 소프트 삭제된 사용자 제외: 기존 어댑터가 @SQLRestriction("deleted_at IS NULL")을 거쳤던 것과 동일 스코프
-- - user_settings 미보유 사용자는 UserSettings.defaultFor() 기본값(balance_check_enabled=TRUE)로 채움
-- - notification_prefs는 현재 NotificationType 상수만 채택 — 과거에 제거된 type 값이 남아 있으면
--   읽기 경로의 Jackson 역직렬화가 깨지므로 신뢰할 수 없는 원본 쪽에서 걸러낸다
INSERT INTO kista.user_notify_profile (user_id, notification_prefs, balance_check_enabled, is_active, updated_at)
SELECT u.id,
       COALESCE((SELECT jsonb_object_agg(p.type, p.enabled)::text
                 FROM public.user_notification_prefs p
                 WHERE p.user_id = u.id
                   AND p.type IN ('TRADING_ALERT', 'MARKET_ALERT', 'FINANCE_REMINDER')), '{}'),
       COALESCE(s.balance_check_enabled, TRUE),
       u.status = 'ACTIVE',
       now()
FROM public.users u
         LEFT JOIN public.user_settings s ON s.user_id = u.id
WHERE u.deleted_at IS NULL;
