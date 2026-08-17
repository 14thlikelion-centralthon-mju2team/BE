package com.hq.backend.wellness;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

// user_wellness_pref의 복합 PK(user_id, wellness_topic) — 항목별로 한 행.
@NoArgsConstructor
@AllArgsConstructor
public class UserWellnessPrefId implements Serializable {

    private UUID userId;
    private String wellnessTopic;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserWellnessPrefId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(wellnessTopic, that.wellnessTopic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, wellnessTopic);
    }
}
