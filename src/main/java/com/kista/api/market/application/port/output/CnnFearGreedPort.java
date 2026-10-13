package com.kista.api.market.application.port.output;

import com.kista.api.market.domain.model.FearGreedRating;

// CNN Fear & Greed Index API
public interface CnnFearGreedPort {
    record CnnFearGreedData(int value, FearGreedRating rating) {}
    CnnFearGreedData fetch();
}
