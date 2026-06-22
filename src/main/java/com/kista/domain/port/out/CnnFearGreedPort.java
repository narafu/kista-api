package com.kista.domain.port.out;

import com.kista.domain.model.market.FearGreedRating;

// CNN Fear & Greed Index API
public interface CnnFearGreedPort {
    record CnnFearGreedData(int value, FearGreedRating rating) {}
    CnnFearGreedData fetch();
}
