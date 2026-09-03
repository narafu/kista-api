package com.kista.finance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface FinanceGroupInvitationJpaRepository extends JpaRepository<FinanceGroupInvitationEntity, UUID> {

    Optional<FinanceGroupInvitationEntity> findByCode(String code);
}
