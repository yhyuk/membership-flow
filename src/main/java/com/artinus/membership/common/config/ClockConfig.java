package com.artinus.membership.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Clock을 빈으로 노출 — 테스트에서 시간 고정(Clock.fixed)을 위해 주입 가능하게 한다. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
