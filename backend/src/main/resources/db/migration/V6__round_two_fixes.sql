-- 成绩更正留痕表：记录每次成绩修改的前后值与更正原因
CREATE TABLE app.grade_modification (
    id uuid PRIMARY KEY,
    grade_id uuid NOT NULL,
    submission_id uuid NOT NULL,
    assignment_id uuid NOT NULL,
    modified_by uuid NOT NULL,
    before_raw_score numeric,
    before_final_score numeric,
    after_raw_score numeric,
    after_final_score numeric,
    before_check_verdict varchar(32),
    after_check_verdict varchar(32),
    before_comment text,
    after_comment text,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_grade_modification_submission ON app.grade_modification (submission_id, created_at);

-- 查重任务：人工重入队计数与最近一次失败类别（供运维兜底与监控）
ALTER TABLE app.check_task ADD COLUMN requeue_count integer NOT NULL DEFAULT 0;
ALTER TABLE app.check_task ADD COLUMN last_error_category varchar(16);

-- 文件分类：补记所属作业（便于按作业维度排查与统计；V1 仅有 report_id/submission_id）
ALTER TABLE app.file_classification ADD COLUMN assignment_id uuid;
