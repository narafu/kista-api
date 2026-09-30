package com.kista.trading.adapter.out.persistence.policy;

import org.springframework.data.jpa.repository.JpaRepository;

interface TradingPolicySettingsJpaRepository extends JpaRepository<TradingPolicySettingsEntity, String> {
}
