package com.kista.tradingcore.marketcalendar.domain.model;

import java.time.Instant;

public record MarketSessionTimes(Instant openAt, Instant closeAt) {}