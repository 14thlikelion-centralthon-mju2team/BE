package com.hq.backend.event.classification;

import java.text.Normalizer;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CalendarTitleNormalizer {

    private static final int MAX_CODE_POINTS = 500;

    public Optional<String> normalize(String title) {
        if (title == null || title.isBlank() || hasUnpairedSurrogate(title)) {
            return Optional.empty();
        }

        String normalized = Normalizer.normalize(title, Normalizer.Form.NFC);
        if (normalized.isBlank()
                || normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS
                || normalized.codePoints().anyMatch(this::isControlCharacter)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private boolean isControlCharacter(int codePoint) {
        return codePoint <= 0x1F || (codePoint >= 0x7F && codePoint <= 0x9F);
    }
}
