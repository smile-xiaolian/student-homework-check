-- 作业级评审结果可见性开关：true 时学生可查看已提交（SUBMITTED/PUBLISHED）的评审与成绩，
-- 无需等待教师执行"发布成绩"；DRAFT 评审草稿始终不对学生可见
ALTER TABLE app.assignment
    ADD COLUMN results_visible boolean NOT NULL DEFAULT false;
