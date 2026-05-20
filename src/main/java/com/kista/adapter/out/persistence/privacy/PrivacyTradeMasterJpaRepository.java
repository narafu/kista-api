package com.kista.adapter.out.persistence.privacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PrivacyTradeMasterJpaRepository extends JpaRepository<PrivacyTradeMasterEntity, UUID> {
}
