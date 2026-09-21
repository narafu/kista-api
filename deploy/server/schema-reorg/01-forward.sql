-- 스키마 재편 정방향: kista→trading, reference→trading_ref/kista_ref, broker_tokens→trading,
-- accounts→users FK 제거, event_publication/scheduler_locks trading 사본 생성.
-- 이름 변경·소유 이동만 수행하고 데이터는 복사하지 않는다(event_publication 리스너 행 이동 제외).
-- 트랜잭션 제어 없음 — 호출자가 BEGIN … COMMIT 으로 감싼다(RUNBOOK.md 참고).

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname IN ('trading', 'trading_ref', 'kista_ref')) THEN
        RAISE EXCEPTION '대상 스키마가 이미 존재 — 이미 적용됐거나 부분 적용 상태';
    END IF;
    IF (SELECT count(*) FROM pg_namespace WHERE nspname IN ('kista', 'reference')) <> 2 THEN
        RAISE EXCEPTION '원본 스키마(kista/reference) 누락';
    END IF;
END $$;

-- 1) kista → trading (테이블·인덱스·제약·FK 전부 보존)
ALTER SCHEMA kista RENAME TO trading;

-- 2) reference 분할: trading 소유 3개 → trading_ref, 나머지(root 소유) → kista_ref
CREATE SCHEMA trading_ref;
ALTER TABLE reference.us_market_holidays        SET SCHEMA trading_ref;
ALTER TABLE reference.privacy_trade_bases       SET SCHEMA trading_ref;
ALTER TABLE reference.privacy_trade_base_orders SET SCHEMA trading_ref;
ALTER SCHEMA reference RENAME TO kista_ref;

-- 3) broker_tokens → trading (accounts FK가 같은 스키마 안으로 들어옴)
ALTER TABLE public.broker_tokens SET SCHEMA trading;

-- 4) trading이 root(public.users)에 의존하지 않도록 cross-schema FK 제거
--    users/accounts는 소프트 삭제라 ON DELETE CASCADE 실사용 경로 없음(설계 문서 "FK 제거 근거")
ALTER TABLE trading.accounts DROP CONSTRAINT accounts_user_id_fkey;

-- 5) trading 전용 공유 인프라 테이블 사본 — trading baseline V1과 동일 DDL
CREATE TABLE trading.event_publication
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON trading.event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON trading.event_publication (completion_date);

CREATE TABLE trading.scheduler_locks (
    name       VARCHAR(100) PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
CREATE INDEX idx_scheduler_locks_lock_until ON trading.scheduler_locks(lock_until);

-- 6) trading 리스너 소속 event_publication 행만 이동 (listener_id = 클래스FQCN#메서드)
--    root 소유 리스너 행은 public에 남는다. 이동 전후 합계는 RUNBOOK 검증 쿼리로 대조.
WITH moved AS (
    DELETE FROM public.event_publication
    WHERE listener_id ~ '^com\.kista\.(trading|matching|broker|account|privacy|marketcalendar)\.'
    RETURNING *
)
INSERT INTO trading.event_publication SELECT * FROM moved;

-- 7) role 기본 search_path 제거 — 앱은 Hikari connection-init-sql로 자체 지정한다
ALTER ROLE kista RESET search_path;
