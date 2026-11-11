CREATE TABLE trading_ref.us_market_sessions (
    trade_date date PRIMARY KEY,
    open_at timestamp with time zone,
    close_at timestamp with time zone,
    CONSTRAINT us_market_sessions_times_check CHECK (
        (open_at IS NULL AND close_at IS NULL)
        OR (open_at IS NOT NULL AND close_at IS NOT NULL AND open_at < close_at)
    )
);