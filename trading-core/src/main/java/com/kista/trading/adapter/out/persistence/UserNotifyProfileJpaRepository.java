package com.kista.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface UserNotifyProfileJpaRepository extends JpaRepository<UserNotifyProfileEntity, UUID> {

    List<UserNotifyProfileEntity> findAllByUserIdIn(List<UUID> userIds);

    List<UserNotifyProfileEntity> findAllByActiveTrue();
}
