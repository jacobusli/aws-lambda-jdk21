package com.jacobus.common.db.mapper;

import com.jacobus.common.db.DbConfig;
import com.jacobus.common.model.User;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

// UserMapper の統合テスト（embedded-postgres 使用、Dockerなし）
class UserMapperTest {

    static EmbeddedPostgres pg;

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
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    void findById_existingUser_returnsUser() {
        try (SqlSession session = DbConfig.getSqlSessionFactory().openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User user = mapper.findById(1L);

            assertNotNull(user);
            assertEquals("Taro Yamada", user.name());
            assertEquals(30, user.age());
            assertEquals("MALE", user.gender());
        }
    }

    @Test
    void findById_secondUser_returnsCorrectly() {
        try (SqlSession session = DbConfig.getSqlSessionFactory().openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User user = mapper.findById(2L);

            assertNotNull(user);
            assertEquals("Hanako Sato", user.name());
            assertEquals(25, user.age());
            assertEquals("FEMALE", user.gender());
        }
    }

    @Test
    void findById_nonExistentId_returnsNull() {
        try (SqlSession session = DbConfig.getSqlSessionFactory().openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User user = mapper.findById(999L);

            assertNull(user);
        }
    }

    // クラスパスからSQLファイルを読み込んで実行するユーティリティ
    private static void executeSqlFromClasspath(Connection conn, String resourcePath) throws Exception {
        try (InputStream is = UserMapperTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
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
