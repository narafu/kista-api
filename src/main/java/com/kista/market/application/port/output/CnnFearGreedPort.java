package com.kista.market.application.port.output;

import com.kista.market.domain.model.FearGreedRating;

// CNN Fear & Greed Index API
public interface CnnFearGreedPort {
    record CnnFearGreedData(int value, FearGreedRating rating) {}
    CnnFearGreedData fetch();
}
