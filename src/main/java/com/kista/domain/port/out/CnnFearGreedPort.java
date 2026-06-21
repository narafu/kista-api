package com.kista.domain.port.out;

import com.kista.domain.model.market.FearGreedRating;

import java.math.BigDecimal;

// CNN Fear & Greed Index API
public interface CnnFearGreedPort {
    record CnnFearGreedData(BigDecimal score, FearGreedRating rating) {}
    CnnFearGreedData fetch();
}
