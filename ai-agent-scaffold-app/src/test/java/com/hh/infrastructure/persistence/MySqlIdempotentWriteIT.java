package com.hh.infrastructure.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 联合唯一键的并发验收。
 *
 * <p>该类采用 {@code *IT} 命名，默认 Surefire 不会启动 Docker。需要验收数据库语义时显式执行本类；
 * Docker 不可用时由 Testcontainers 跳过，避免把开发机环境问题误判成业务回归。</p>
 */
@Tag("persistence-it")
@Testcontainers(disabledWithoutDocker = true)
class MySqlIdempotentWriteIT {

    private static final int CONCURRENT_WRITERS = 16;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
            .withDatabaseName("agent_test");

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            // 此处只保留 DATA-01 所需的键和列，避免测试依赖 SchemaInitializer 的启动期 DDL。
            statement.execute("""
                    CREATE TABLE conversations (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        app_name VARCHAR(128) NOT NULL,
                        user_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(128) NOT NULL,
                        title VARCHAR(255) NULL,
                        created_at DATETIME NOT NULL,
                        updated_at DATETIME NOT NULL,
                        UNIQUE KEY uk_conversations_session (app_name, user_id, session_id)
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE session_events (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        app_name VARCHAR(128) NOT NULL,
                        user_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(128) NOT NULL,
                        event_id VARCHAR(128) NOT NULL,
                        event_json JSON NOT NULL,
                        created_at DATETIME NOT NULL,
                        UNIQUE KEY uk_session_events_event_id (app_name, user_id, session_id, event_id)
                    ) ENGINE=InnoDB
                    """);
        }
    }

    @Test
    void concurrentConversationAndEventWritesProduceOneRowPerIdentity() throws Exception {
        runConcurrently(() -> insertConversation("app-a", "user-a", "session-a"));
        runConcurrently(() -> insertEvent("app-a", "user-a", "session-a", "event-a"));

        assertThat(count("conversations")).isEqualTo(1);
        assertThat(count("session_events")).isEqualTo(1);
    }

    private static void insertConversation(String appName, String userId, String sessionId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO conversations
                         (app_name, user_id, session_id, title, created_at, updated_at)
                     VALUES (?, ?, ?, ?, NOW(), NOW())
                     ON DUPLICATE KEY UPDATE id = id
                     """)) {
            statement.setString(1, appName);
            statement.setString(2, userId);
            statement.setString(3, sessionId);
            statement.setString(4, "conversation " + sessionId);
            statement.executeUpdate();
        }
    }

    private static void insertEvent(String appName, String userId, String sessionId, String eventId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO session_events
                         (app_name, user_id, session_id, event_id, event_json, created_at)
                     VALUES (?, ?, ?, ?, JSON_OBJECT(), NOW())
                     ON DUPLICATE KEY UPDATE id = id
                     """)) {
            statement.setString(1, appName);
            statement.setString(2, userId);
            statement.setString(3, sessionId);
            statement.setString(4, eventId);
            statement.executeUpdate();
        }
    }

    private static void runConcurrently(ThrowingRunnable operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < CONCURRENT_WRITERS; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    operation.run();
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static long count(String tableName) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }
}
