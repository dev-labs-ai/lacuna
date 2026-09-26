package com.lacuna;

import com.lacuna.support.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

@SpringBootTest
@ImportTestcontainers(TestInfrastructure.class)
class LacunaApplicationTests {

    @Test
    void contextLoads() {
    }

}
