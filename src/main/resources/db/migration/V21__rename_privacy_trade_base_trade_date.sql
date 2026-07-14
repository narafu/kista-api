ALTER TABLE privacy_trade_bases
    RENAME COLUMN trade_date TO release_date;

ALTER TABLE privacy_trade_bases
    DROP CONSTRAINT IF EXISTS uq_privacy_trade_bases_date_ticker;

ALTER TABLE privacy_trade_bases
    ADD CONSTRAINT uq_privacy_trade_bases_release_date_ticker UNIQUE (release_date, ticker);
