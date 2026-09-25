package com.bilicharge.archive;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class MySqlTestBase {
    static final String USERNAME = "m1-admin";
    static final String PASSWORD = "m1-test-password";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:13306/bili_charge_archive_m1_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "bili_dev"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "local-dev-only"));
        registry.add("app.admin.username", () -> USERNAME);
        registry.add("app.admin.password-bcrypt", () -> new BCryptPasswordEncoder().encode(PASSWORD));
        registry.add("app.monitor-token", () -> "m1-test-token-32-characters-long!");
    }
}
