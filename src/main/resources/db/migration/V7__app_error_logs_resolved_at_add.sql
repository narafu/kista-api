-- app_error_logs.deleted_at은 삭제가 아니라 관리자의 "처리함" 표시라 상태 컬럼 resolved_at으로 개명한다.
-- expand 단계: resolved_at 추가 + 기존 처리 행 백필. 코드는 resolved_at·deleted_at을 함께 쓴다.
-- deleted_at drop은 다음 배포(contract)에서 한다.
ALTER TABLE public.app_error_logs ADD COLUMN resolved_at timestamp with time zone;

UPDATE public.app_error_logs SET resolved_at = deleted_at WHERE deleted_at IS NOT NULL;
