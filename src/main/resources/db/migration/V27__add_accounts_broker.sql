-- Phase 1: accounts.broker
ALTER TABLE accounts ADD COLUMN broker VARCHAR(20) NOT NULL DEFAULT 'KIS';
