package com.hq.backend.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// ProductEventService facade와 분리된 Spring bean이어야 REQUIRES_NEW 프록시가 적용된다.
// saveAndFlush로 DB 제약 오류를 이 트랜잭션 안에서 확정하고, commit 오류 역시 facade가
// catch할 수 있도록 호출자 트랜잭션과 완전히 분리한다.
@Service
@RequiredArgsConstructor
public class ProductEventWriter {

    private final ProductEventRepository productEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(ProductEvent event) {
        productEventRepository.saveAndFlush(event);
    }
}
