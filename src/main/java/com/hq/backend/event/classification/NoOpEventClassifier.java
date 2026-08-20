package com.hq.backend.event.classification;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NoOpEventClassifier implements EventClassifier {

    @Override
    public Optional<EventClassificationResult> classify(EventClassificationInput input) {
        return Optional.empty();
    }
}
