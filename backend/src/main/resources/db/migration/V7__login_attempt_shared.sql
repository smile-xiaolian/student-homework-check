-- 登录限速跨实例共享（R13）：进程内 ConcurrentHashMap 改为 DB 读写，
-- 使多实例部署下登录失败计数与锁定状态一致。
CREATE TABLE app.login_attempt (
    id uuid PRIMARY KEY,
    login_name varchar(128) NOT NULL,
    failed_count integer NOT NULL DEFAULT 0,
    locked_until timestamptz,
    last_attempt_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_login_attempt_name ON app.login_attempt (login_name);
