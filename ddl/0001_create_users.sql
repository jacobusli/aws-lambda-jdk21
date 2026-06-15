-- ユーザーテーブルの定義
CREATE TABLE IF NOT EXISTS users (
    id     BIGSERIAL    PRIMARY KEY,
    name   VARCHAR(100) NOT NULL,
    age    INTEGER      NOT NULL,
    gender VARCHAR(10)  NOT NULL
);
