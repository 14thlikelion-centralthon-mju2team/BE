package com.hq.backend.setting;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingRepository extends JpaRepository<UserSetting, UUID> {
}
