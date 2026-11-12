-- 作业级提交文件类型白名单：空数组表示不限制（跟随全局 system_config 的 file.allowed_extensions）
ALTER TABLE app.assignment
    ADD COLUMN allowed_file_types jsonb NOT NULL DEFAULT '[]'::jsonb;

-- 定时发布时间：草稿态作业到达该时间后由调度器自动发布
ALTER TABLE app.assignment
    ADD COLUMN scheduled_publish_at timestamptz;

CREATE INDEX ix_assignment_scheduled_publish
    ON app.assignment (scheduled_publish_at) WHERE status = 'DRAFT';
