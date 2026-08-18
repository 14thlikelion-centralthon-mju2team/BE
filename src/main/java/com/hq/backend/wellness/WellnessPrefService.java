package com.hq.backend.wellness;

import com.hq.backend.wellness.dto.WellnessPrefResponse;
import com.hq.backend.wellness.dto.WellnessPrefsRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WellnessPrefService {

    private final UserWellnessPrefRepository userWellnessPrefRepository;

    @Transactional(readOnly = true)
    public List<WellnessPrefResponse> get(UUID userId) {
        Map<String, UserWellnessPref> byTopic = userWellnessPrefRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(UserWellnessPref::getWellnessTopic, pref -> pref));

        return java.util.Arrays.stream(WellnessTopic.values())
                .map(topic -> byTopic.containsKey(topic.name().toLowerCase())
                        ? WellnessPrefResponse.from(byTopic.get(topic.name().toLowerCase()))
                        : WellnessPrefResponse.defaultFor(topic))
                .toList();
    }

    @Transactional
    public List<WellnessPrefResponse> update(UUID userId, WellnessPrefsRequest request) {
        Instant now = Instant.now();
        for (WellnessPrefsRequest.Item item : request.prefs()) {
            String topic = item.wellnessTopic().name().toLowerCase();
            UserWellnessPref pref = userWellnessPrefRepository.findById(new UserWellnessPrefId(userId, topic))
                    .orElseGet(() -> UserWellnessPref.builder().userId(userId).wellnessTopic(topic).build());
            pref.setEnabled(item.isEnabled());
            pref.setRemindIntervalMinutes(item.remindIntervalMinutes());
            pref.setDailyEventCap(item.dailyEventCap());
            pref.setUpdatedAt(now);
            userWellnessPrefRepository.save(pref);
        }
        return get(userId);
    }
}
