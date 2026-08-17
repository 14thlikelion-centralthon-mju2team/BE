package com.hq.backend.place;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPlaceRepository extends JpaRepository<UserPlace, UUID> {
}
