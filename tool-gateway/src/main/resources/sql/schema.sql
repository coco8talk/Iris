-- CMDB 服务表：存储 cmdb.yaml 中的服务记录，启动时由 CmdbLoader 全量重建(T17)
CREATE TABLE IF NOT EXISTS cmdb_service
(
    -- 服务名，主键
    name        TEXT PRIMARY KEY,

    -- 服务完整记录，JSON 字符串
    record_json TEXT NOT NULL
);

-- 审计日志表：记录 Agent 调用上游服务的请求信息、响应状态及性能指标
CREATE TABLE IF NOT EXISTS audit_log
(
    -- 自增主键
    id              INTEGER PRIMARY KEY AUTOINCREMENT,

    -- 日志时间戳，建议使用 ISO 8601 格式，例如：2026-07-14T10:30:00.123Z
    ts              TEXT    NOT NULL,

    -- 关联的事件或故障唯一标识，用于串联同一事件下的多条审计记录
    incident_id     TEXT    NOT NULL,

    -- 执行调用的 Agent 角色，例如：planner、executor、reviewer
    agent_role      TEXT    NOT NULL,

    -- 调用的上游服务端点，例如 API URL、接口路径或模型名称
    endpoint        TEXT    NOT NULL,

    -- 请求使用的模板标识或原始请求内容
    template_or_raw TEXT,

    -- 请求参数，使用 JSON 字符串存储
    params_json     TEXT,

    -- 上游服务调用耗时，单位：毫秒
    upstream_ms     INTEGER,

    -- 调用结果状态码，例如 HTTP 状态码或内部业务状态码
    status          INTEGER,

    -- 返回结果大小，单位：字节
    result_bytes    INTEGER,

    -- 是否发生降级：
    -- 0 = 未降级，1 = 已降级
    degraded        INTEGER NOT NULL DEFAULT 0
);

-- 变更事件表：发版/配置调优/重启等运维动作，供 F09 变更溯源(T17)
CREATE TABLE IF NOT EXISTS change_event
(
    -- 变更唯一标识，主键
    change_id TEXT PRIMARY KEY,

    -- 变更发生时间，ISO 8601 格式
    ts        TEXT,

    -- 变更类型：deploy / config_change / restart 等
    type      TEXT,

    -- 关联服务名
    service   TEXT,

    -- 变更摘要
    summary   TEXT,

    -- 变更操作人
    operator  TEXT
);