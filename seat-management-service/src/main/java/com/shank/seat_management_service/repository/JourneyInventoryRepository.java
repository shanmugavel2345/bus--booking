package com.shank.seat_management_service.repository;

import com.shank.seat_management_service.entity.JourneyInventory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface JourneyInventoryRepository extends JpaRepository<JourneyInventory, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inventory from JourneyInventory inventory where inventory.id = :id")
    Optional<JourneyInventory> lock(@Param("id") String id);
}