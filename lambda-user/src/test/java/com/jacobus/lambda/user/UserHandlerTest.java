package com.jacobus.lambda.user;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.jacobus.common.db.DbConfig;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

// UserHandler の統合テスト（embedded-postgres 使用、Dockerなし）
class UserHandlerTest {

    static EmbeddedPostgres pg;
    static UserHandler handler;

    @BeforeAll
    static void setUp() throws Exception {
        pg = EmbeddedPostgres.start();

        // DDLとDMLをクラスパスから順番に流し込む
        try (Connection conn = pg.getPostgresDatabase().getConnection()) {
            executeSqlFromClasspath(conn, "ddl/0001_create_users.sql");
            executeSqlFromClasspath(conn, "dml/0001_seed_users.sql");
        }

        // テスト用DataSourceでMyBatisを初期化する
        DbConfig.initForTest(pg.getPostgresDatabase());
        handler = new UserHandler();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    void handleRequest_existingUserId_returns200WithUser() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withBody("{\"userId\":1}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Taro Yamada"));
        assertTrue(response.getBody().contains("30"));
        assertTrue(response.getBody().contains("MALE"));
    }

    @Test
    void handleRequest_nonExistentUserId_returns404() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withBody("{\"userId\":999}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(404, response.getStatusCode());
        assertTrue(response.getBody().contains("User not found"));
    }

    @Test
    void handleRequest_invalidJson_returns500() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withBody("invalid json");

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, null);

        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("Internal server error"));
    }

    // クラスパスからSQLファイルを読み込んで実行するユーティリティ
    private static void executeSqlFromClasspath(Connection conn, String resourcePath) throws Exception {
        try (InputStream is = UserHandlerTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("SQLファイルが見つかりません: " + resourcePath);
            }
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        }
    }
}
