package com.hq.backend.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConditionRuleEngineTest {

    private final ConditionRuleEngine engine = new ConditionRuleEngine();

    @Test
    void 가용시간이_15분_미만이면_TIRED() {
        assertThat(engine.infer(14)).isEqualTo(Condition.TIRED);
        assertThat(engine.infer(0)).isEqualTo(Condition.TIRED);
    }

    @Test
    void 가용시간이_15분_이상_45분_미만이면_NORMAL() {
        assertThat(engine.infer(15)).isEqualTo(Condition.NORMAL);
        assertThat(engine.infer(44)).isEqualTo(Condition.NORMAL);
    }

    @Test
    void 가용시간이_45분_이상이면_GOOD() {
        assertThat(engine.infer(45)).isEqualTo(Condition.GOOD);
        assertThat(engine.infer(120)).isEqualTo(Condition.GOOD);
    }

    @Test
    void 값이_없으면_NORMAL로_기본_처리한다() {
        assertThat(engine.infer(null)).isEqualTo(Condition.NORMAL);
    }
}
