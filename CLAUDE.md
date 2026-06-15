# AWS Lambda JDK21 プロジェクト

## プロジェクト概要

JBoss（JDK8）から移行した Java コードを AWS Lambda（JDK21）上で動作させるプロジェクト。
ブラウザ・インターネット経由で Lambda Function URL の API に直接アクセスできる。

---

## アーキテクチャ方針

### マルチ Lambda 構成

- このリポジトリのソースコードから **複数の Lambda 関数**をビルドする
- DB アクセス・共通ユーティリティは **共通モジュール（共有パッケージ）** としてまとめる
- Maven の **マルチモジュール構成**を採用する

```
aws-lambda-jdk21/
├── common/                          # 共通モジュール（DB・ユーティリティ等）
│   └── src/
│       ├── main/java/
│       └── test/java/               # 共通モジュールの単体テスト
├── lambda-foo/                      # Lambda 関数 A
│   └── src/
│       ├── main/java/
│       └── test/java/               # Lambda 関数Aの単体テスト
├── lambda-bar/                      # Lambda 関数 B
│   └── src/
│       ├── main/java/
│       └── test/java/               # Lambda 関数Bの単体テスト
├── ddl/                             # テーブル定義（CREATE TABLE等）
│   ├── 0001_create_users.sql
│   └── 0002_create_orders.sql
├── dml/                             # 初期データ・テストデータ（INSERT等）
│   ├── 0001_seed_users.sql
│   └── 0002_seed_orders.sql
└── pom.xml                          # 親 POM
```

### ddl / dml ルール

- `ddl/` — テーブル・インデックス・シーケンスの定義。ファイル名は `連番_内容.sql` 形式
- `dml/` — 初期データおよびテスト用シードデータ。本番データは含めない
- 番号順に実行すれば DB が再構築できる状態を常に保つ
- テスト実行時は embedded-postgres 起動後に ddl → dml の順で流し込む

---

## ビルド・技術スタック

| 項目 | 採用技術 |
|------|---------|
| 言語 | Java 21 |
| ビルドツール | Maven |
| Lambda ランタイム | `java21` |
| DB | PostgreSQL |
| O/R マッパー | MyBatis（XML なし・text block で SQL 管理） |
| DB 接続プール | AWS RDS Proxy |
| コールドスタート対策 | AWS Lambda SnapStart |

---

## データベース

### MyBatis — SQL 管理ルール

XML マッパーは使用しない。SQL は Java の **text block** で記述する。

```java
@Mapper
public interface UserMapper {
    @Select("""
            SELECT id, name, email
            FROM users
            WHERE id = #{id}
            """)
    User findById(long id);
}
```

### AWS RDS Proxy（接続プール）

高並行時に物理 DB 接続を枯渇させないため、すべての Lambda は RDS Proxy 経由でアクセスする。
Lambda 側の `HikariCP` 等の接続プールは最小限（min=1, max=2 程度）に抑える。

```
Lambda → RDS Proxy → PostgreSQL RDS
```

接続情報は AWS Secrets Manager で管理し、環境変数に RDS Proxy のエンドポイントを設定する。

---

## ローカルテスト戦略

### embedded-postgres（Docker 不要）

ローカルに Docker をインストールできない環境を想定し、**embedded-postgres** を採用する。
Maven 依存を追加するだけで、JVM 内部に本物の PostgreSQL を起動できる。

```xml
<!-- テスト用組み込みPostgreSQL（Dockerなし） -->
<dependency>
    <groupId>io.zonky.test</groupId>
    <artifactId>embedded-postgres</artifactId>
    <version>2.0.7</version>
    <scope>test</scope>
</dependency>
```

### テスト起動パターン

```java
class UserMapperTest {

    // テスト用の組み込みPostgreSQLインスタンス（クラス単位で共有）
    static EmbeddedPostgres pg;
    static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        pg = EmbeddedPostgres.start();

        // DDLとDMLを順番に流し込む
        try (Connection conn = pg.getPostgresDatabase().getConnection()) {
            executeSqlFile(conn, "ddl/001_create_users.sql");
            executeSqlFile(conn, "dml/001_seed_users.sql");
        }

        // MyBatisのSqlSessionFactoryを初期化する
        UnpooledDataSourceFactory dsf = new UnpooledDataSourceFactory();
        Properties props = new Properties();
        props.setProperty("url", pg.getJdbcUrl("postgres", "postgres"));
        dsf.setProperties(props);

        Configuration config = new Configuration();
        config.setEnvironment(new Environment("test",
                new JdbcTransactionFactory(), dsf.getDataSource()));
        config.addMapper(UserMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void tearDown() throws Exception {
        pg.close();
    }

    @Test
    void findByIdTest() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User user = mapper.findById(1L);
            assertNotNull(user);
        }
    }
}
```

### ローカルテストの方針

| 用途 | 手段 |
| --- | --- |
| 自動化テスト（単体・結合） | embedded-postgres（Docker 不要） |
| AWS 上のデータ確認 | SSM ポートフォワードで dev RDS に接続 |

---

## JDK8 → JDK21 移行ガイド

### 互換性：基本的に JDK8 コードは JDK21 で動く

Java は後方互換性が高いため、**通常の JDK8 コードはそのままコンパイル・実行できる**。
ただし JBoss 由来のコードは以下の点を確認・修正すること。

### 要修正・要確認ポイント

| 問題 | 対処法 |
|------|--------|
| JBoss / WildFly の API への依存（`javax.ejb`, `javax.inject` 等） | Lambda 向けに書き直す（CDI 不使用） |
| `javax.*` → `jakarta.*` の名前空間変更 | Lambda では不要なものは削除、必要なら `jakarta.*` に変更 |
| `sun.*` / `com.sun.*` 内部 API の使用 | 公開 API に置き換える |
| `SecurityManager`（JDK17 で deprecated、JDK18 で削除） | 使用している場合は削除 |
| `Thread.stop()` / `Thread.suspend()` 等の非推奨スレッド API | 置き換える |
| リフレクションで非公開フィールドにアクセス（モジュールシステムの影響） | `--add-opens` で回避、または設計変更 |
| `finalize()` メソッドのオーバーライド（JDK18 で deprecated） | `Cleaner` API に置き換える |

### 新機能を積極的に使う

JDK21 で利用可能になった機能は積極的に採用する：

- **Text Block**（JDK15〜）：SQL・JSON の記述に使用
- **Records**（JDK16〜）：DTO/値オブジェクトに使用
- **Pattern Matching for instanceof**（JDK16〜）
- **Sealed Classes**（JDK17〜）
- **Virtual Threads**（JDK21〜）：Lambda のスレッド処理に活用可能

---

## AWS Lambda SnapStart（コールドスタート対策）

Java の起動遅延を解消するために **SnapStart** を有効化する。

### 仕組み

1. Lambda がデプロイされると init フェーズを実行してスナップショットを作成
2. 以降の呼び出しはスナップショットから復元するため起動が高速

### 設定

- Lambda の設定で SnapStart を `PublishedVersions` に設定
- `aws lambda publish-version` でバージョンを発行し、エイリアスに向ける

### SnapStart を使う際の注意点

SnapStart はスナップショット復元後にフックを呼び出せる。乱数シードや接続などはフックで再初期化すること。

```java
// CRaC APIを使ったフック例
public class MyHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>,
        org.crac.Resource {

    public MyHandler() {
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {
        // スナップショット前：DB接続を閉じるなどクリーンアップ
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        // スナップショット復元後：DB接続を再初期化
    }
}
```

依存関係に追加：

```xml
<dependency>
    <groupId>io.github.crac</groupId>
    <artifactId>org-crac</artifactId>
    <version>0.1.3</version>
</dependency>
```

---

## Lambda Function URL 設定

- 認証タイプ：`AWS_IAM`（要認証）または `NONE`（パブリック）
- CORS は Function URL の設定で管理
- `Content-Type: application/json` を標準レスポンスとする

---

## コーディング規約

- コードコメントは**日本語**で記述
- SQL は XML マッパーを使わず **text block** でアノテーションに直書き
- DTO には **record** を優先して使用
- 例外処理：Lambda ハンドラーでは必ずキャッチし、適切な HTTP ステータスコードを返す

---

## Maven 依存関係（主要）

```xml
<!-- Lambda SDK -->
<dependency>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-java-core</artifactId>
    <version>1.2.3</version>
</dependency>
<dependency>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-java-events</artifactId>
    <version>3.11.4</version>
</dependency>

<!-- MyBatis -->
<dependency>
    <groupId>org.mybatis</groupId>
    <artifactId>mybatis</artifactId>
    <version>3.5.16</version>
</dependency>

<!-- PostgreSQL ドライバ -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>

<!-- 接続プール（RDS Proxy前提のため最小構成） -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>

<!-- SnapStart CRaC -->
<dependency>
    <groupId>io.github.crac</groupId>
    <artifactId>org-crac</artifactId>
    <version>0.1.3</version>
</dependency>
```
