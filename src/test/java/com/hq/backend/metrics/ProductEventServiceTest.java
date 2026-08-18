package com.hq.backend.metrics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ProductEventServiceTest {

    @Mock private ProductEventWriter productEventWriter;

    private ProductEventService productEventService;

    @BeforeEach
    void setUp() {
        productEventService = new ProductEventService(productEventWriter);
    }

    @Test
    void 별도_writer의_DB_실패는_호출자에게_전파하지_않는다() {
        doThrow(new DataIntegrityViolationException("product_event write failed"))
                .when(productEventWriter).persist(any(ProductEvent.class));

        assertThatCode(() -> productEventService.record(
                UUID.randomUUID(), "plan_created", Map.of("revisionNo", 1)))
                .doesNotThrowAnyException();
    }
}
