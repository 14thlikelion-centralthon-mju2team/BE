package com.hq.backend.event.classification;

import java.util.Optional;

public interface EventClassifier {

    Optional<EventClassificationResult> classify(EventClassificationInput input);
}
