package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface PortfolioSnapshotJpaRepository extends JpaRepository<PortfolioSnapshotEntity, UUID> {

    @Query("SELECT e FROM PortfolioSnapshotEntity e WHERE e.snapshotDate >= :since ORDER BY e.snapshotDate DESC")
    List<PortfolioSnapshotEntity> findRecent(@Param("since") LocalDate since);
}
