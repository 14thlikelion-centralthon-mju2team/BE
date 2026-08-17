package com.hq.backend.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// TRD §5.2 PlanOutput의 분해 필드가 그대로 컬럼으로 매핑된다(계산 근거 조인 없이 응답 가능).
// planStatus(active|superseded)는 리비전 관리, EVENT.status는 생명주기 — 다른 축이다(TRD §4.3).
@Entity
@Table(name = "plan_revision")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID planId;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private int revisionNo;

    private UUID originPlaceId;
    private String originSnapshotName;
    private Double originSnapshotLat;
    private Double originSnapshotLng;

    @Setter
    private UUID selectedRouteOptionId;

    @Column(nullable = false)
    private Instant prepStartAt;

    @Column(nullable = false)
    private Instant recommendedDepartAt;

    @Column(nullable = false)
    private Instant targetArriveAt;

    @Column(nullable = false)
    private int estimatedPrepMinutes;

    @Column(nullable = false)
    private int extraPrepMinutes;

    @Column(nullable = false)
    private int personalRoutineMinutes;

    @Column(nullable = false)
    private int travelMinutes;

    @Column(nullable = false)
    private int trafficBufferMinutes;

    @Column(nullable = false)
    private String predictionConfidence; // high | mid | low

    @Setter
    @Column(nullable = false)
    private String planStatus; // active | superseded

    @Column(nullable = false)
    private String calcVersion;

    @Setter
    private Instant nextEvalAt;

    @Setter
    private String inputHash;

    @Column(nullable = false)
    private Instant createdAt;
}
