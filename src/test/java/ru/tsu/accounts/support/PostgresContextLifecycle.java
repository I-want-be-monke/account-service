package ru.tsu.accounts.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInfo;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContextManager;

/**
 * A cached Spring context otherwise outlives a class-scoped PostgreSQL container.
 * Stop its JDBC session scheduler and connection pool before Testcontainers' afterAll.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresContextLifecycle {
    @AfterAll
    protected static void closeSpringBeforePostgres(TestInfo testInfo) {
        // Evict through Spring's own cache API: merely closing the context would
        // leave an inactive entry for SpringExtension.afterAll to encounter.
        new TestContextManager(testInfo.getTestClass().orElseThrow()).getTestContext()
            .markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
    }
}
