package com.artinus.subscription;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SubscriptionApplicationTests {

    @Test
    void contextLoads() {
        // Spring context가 정상 부팅되는지 검증한다. (Phase 1 스모크)
    }
}
