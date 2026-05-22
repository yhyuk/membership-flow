package com.artinus.membership.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * {@link Clock} Bean 등록.
 *
 * <p>운영에서는 시스템 기본 시계, 테스트에서는 {@code Clock.fixed(...)} 빈을
 * 교체 주입함으로써 occurredAt을 결정적으로 검증할 수 있다.</p>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
