package com.hq.backend.routine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// place_id는 FK로 존재하지만 places 엔티티는 아직 없다(feat/places, Phase 2 예정) —
// 지금은 place 스케줄 루틴을 만들 수 없으므로 raw UUID로만 들고 있고, JPA 연관관계는
// places 엔티티가 생긴 뒤 필요해지면 추가한다. 지금 관계를 매핑해봤자 검증할 방법이 없다.
@Entity
@Table(name = "routines")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Routine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    private UUID placeId;

    @Setter
    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String scheduleType;

    private String rrule;
    private LocalTime anchorTime;

    @Setter
    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;
}
