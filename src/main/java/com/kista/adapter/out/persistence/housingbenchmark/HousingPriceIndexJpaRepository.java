package com.kista.adapter.out.persistence.housingbenchmark;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface HousingPriceIndexJpaRepository extends JpaRepository<HousingPriceIndexEntity, UUID> {
}
