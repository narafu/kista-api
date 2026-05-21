-- Phase 3: orders/kis_tokens에 updated_at 컬럼 추가 (JPA Auditing용)
ALTER TABLE orders ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE kis_tokens ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
