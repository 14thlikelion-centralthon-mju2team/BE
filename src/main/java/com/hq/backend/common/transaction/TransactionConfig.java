package com.hq.backend.common.transaction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 외부 API 호출(구글 토큰 검증 등)을 트랜잭션 밖에서 하고, DB 쓰기만 짧게 감싸고 싶을 때 쓴다
// (FCM 전송을 트랜잭션 밖에 두는 것과 같은 원칙). @Transactional로 메서드 전체를 감싸면
// 클래스 내부 호출(self-invocation)엔 프록시가 안 걸려서 TransactionTemplate으로 대신한다.
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
