package com.jacobus.common.db;

import com.jacobus.common.db.mapper.UserMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.crac.Core;
import org.crac.Resource;

import javax.sql.DataSource;

// MyBatis + HikariCP の静的管理クラス（SnapStart CRaC フック実装）
public final class DbConfig {

    private static volatile SqlSessionFactory sqlSessionFactory;
    private static volatile HikariDataSource hikariDataSource;

    static {
        // CRaCフックを匿名クラスで登録する（シングルトン不要）
        Core.getGlobalContext().register(new Resource() {
            @Override
            public void beforeCheckpoint(org.crac.Context<? extends Resource> context) throws Exception {
                // スナップショット前：DB接続を閉じてクリーンアップ
                closeDataSource();
            }

            @Override
            public void afterRestore(org.crac.Context<? extends Resource> context) throws Exception {
                // スナップショット復元後：DB接続を再初期化
                initWithEnv();
            }
        });

        // 環境変数が存在する場合のみ本番初期化する（テスト環境ではinitForTestを使用）
        if (System.getenv("DB_URL") != null) {
            initWithEnv();
        }
    }

    private DbConfig() {
        // インスタンス化不可
    }

    public static SqlSessionFactory getSqlSessionFactory() {
        return sqlSessionFactory;
    }

    // 本番用：環境変数から接続情報を読み込む
    private static void initWithEnv() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("DB_URL"));
        config.setUsername(System.getenv("DB_USER"));
        config.setPassword(System.getenv("DB_PASSWORD"));
        // RDS Proxy前提のため接続数は最小限に抑える
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);

        hikariDataSource = new HikariDataSource(config);
        buildSqlSessionFactory(hikariDataSource);
    }

    // テスト用：外部からDataSourceを注入する（embedded-postgres 用）
    public static void initForTest(DataSource testDataSource) {
        buildSqlSessionFactory(testDataSource);
    }

    private static void buildSqlSessionFactory(DataSource ds) {
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("default",
                new JdbcTransactionFactory(), ds));
        config.addMapper(UserMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);
    }

    private static void closeDataSource() {
        if (hikariDataSource != null) {
            hikariDataSource.close();
            hikariDataSource = null;
            sqlSessionFactory = null;
        }
    }
}
