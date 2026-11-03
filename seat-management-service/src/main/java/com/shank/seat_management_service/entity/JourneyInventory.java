package com.shank.seat_management_service.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.*;

@Entity
@Table(name = "journey_inventory", uniqueConstraints = @UniqueConstraint(columnNames = {"busId", "journeyDate"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JourneyInventory {
    @Id
    private String id;
    @Column(nullable = false)
    private Long busId;
    @Column(nullable = false)
    private LocalDate journeyDate;

    public JourneyInventory(Long busId, LocalDate journeyDate) {
        this.id = key(busId, journeyDate);
        this.busId = busId;
        this.journeyDate = journeyDate;
    }

    public static String key(Long busId, LocalDate journeyDate) {
        return busId + ":" + journeyDate;
    }
}