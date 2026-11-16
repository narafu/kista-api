-- contract-ok: af9fe34e9 배포 이미지부터 이 컬럼을 매핑하지 않음
-- 배포 전제: kista-api·kista-scheduler 둘 다 af9fe34e9 이상이 떠 있어야 한다. 적용 뒤에는 deleted_at을 매핑하는 7150d7fa8 이하로 롤백할 수 없다(validate 기동 실패).
-- app_error_logs의 "처리함" 표시는 V7의 resolved_at으로 옮겼다(값은 V7에서 백필). deleted_at에 걸린 인덱스는 없다.
ALTER TABLE public.app_error_logs DROP COLUMN deleted_at;
