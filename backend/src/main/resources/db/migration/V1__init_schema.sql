CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE SCHEMA IF NOT EXISTS app;
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE app.user_account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_no varchar(32) NOT NULL UNIQUE,
    login_name citext NOT NULL UNIQUE,
    display_name varchar(64) NOT NULL,
    email citext,
    phone varchar(32),
    role varchar(16) NOT NULL CHECK (role IN ('STUDENT','TEACHER','ASSISTANT','ADMIN')),
    password_hash text,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACTIVE','LOCKED','DISABLED')),
    token_version integer NOT NULL DEFAULT 1 CHECK (token_version > 0),
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_user_account_display_name ON app.user_account (display_name);
CREATE INDEX ix_user_account_email ON app.user_account (email);

CREATE TABLE app.file_object (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket varchar(128) NOT NULL,
    object_key varchar(1024) NOT NULL,
    original_name text NOT NULL,
    mime_type varchar(255) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    scan_status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (scan_status IN ('PENDING','CLEAN','INFECTED','FAILED')),
    created_by uuid REFERENCES app.user_account(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_file_object_bucket_key UNIQUE (bucket, object_key)
);
CREATE INDEX ix_file_object_sha256 ON app.file_object (sha256);
CREATE INDEX ix_file_object_scan_status ON app.file_object (scan_status);

CREATE TABLE app.course (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    semester varchar(32) NOT NULL,
    owner_id uuid NOT NULL REFERENCES app.user_account(id) ON DELETE RESTRICT,
    description text,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    invite_code varchar(32),
    archived_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_course_code_semester UNIQUE (course_code, semester)
);
CREATE INDEX ix_course_name ON app.course (name);
CREATE INDEX ix_course_owner ON app.course (owner_id);
CREATE INDEX ix_course_status ON app.course (status);
CREATE UNIQUE INDEX uk_course_invite_code ON app.course (invite_code)
    WHERE invite_code IS NOT NULL;

CREATE TABLE app.course_member (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id uuid NOT NULL REFERENCES app.course(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES app.user_account(id) ON DELETE RESTRICT,
    member_role varchar(16) NOT NULL CHECK (member_role IN ('STUDENT','ASSISTANT','INSTRUCTOR')),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_course_member UNIQUE (course_id, user_id)
);
CREATE INDEX ix_course_member_user_status ON app.course_member (user_id, status);
CREATE INDEX ix_course_member_role ON app.course_member (course_id, member_role, status);

CREATE TABLE app.assignment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id uuid NOT NULL REFERENCES app.course(id) ON DELETE RESTRICT,
    title varchar(256) NOT NULL,
    description text,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','PUBLISHED','CLOSED','ARCHIVED')),
    publish_at timestamptz,
    open_at timestamptz,
    deadline_at timestamptz NOT NULL,
    late_deadline_at timestamptz,
    allow_late boolean NOT NULL DEFAULT false,
    allow_multi_version boolean NOT NULL DEFAULT true,
    effective_rule varchar(16) NOT NULL DEFAULT 'LAST_SUBMISSION'
        CHECK (effective_rule IN ('LAST_SUBMISSION','MANUAL')),
    check_enabled boolean NOT NULL DEFAULT false,
    check_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    rubric_definition jsonb NOT NULL DEFAULT '{}'::jsonb,
    weight numeric(5,2) NOT NULL DEFAULT 0 CHECK (weight BETWEEN 0 AND 100),
    created_by uuid NOT NULL REFERENCES app.user_account(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_assignment_late_deadline CHECK (
        late_deadline_at IS NULL OR late_deadline_at > deadline_at
    )
);
CREATE INDEX ix_assignment_course_status ON app.assignment (course_id, status, deadline_at);
CREATE INDEX ix_assignment_deadline ON app.assignment (deadline_at);

CREATE TABLE app.assignment_attachment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id uuid NOT NULL REFERENCES app.assignment(id) ON DELETE RESTRICT,
    file_object_id uuid NOT NULL REFERENCES app.file_object(id) ON DELETE RESTRICT,
    display_name text NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_assignment_attachment UNIQUE (assignment_id, file_object_id)
);

CREATE TABLE app.submission (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id uuid NOT NULL REFERENCES app.assignment(id) ON DELETE RESTRICT,
    submitter_id uuid NOT NULL REFERENCES app.user_account(id) ON DELETE RESTRICT,
    version_no integer NOT NULL CHECK (version_no > 0),
    status varchar(20) NOT NULL DEFAULT 'PENDING_SCAN'
        CHECK (status IN ('PENDING_SCAN','SUBMITTED','CHECKING','CHECK_COMPLETED',
            'CHECK_FAILED','REVIEWED','PUBLISHED','CLOSED','WITHDRAWN')),
    is_effective boolean NOT NULL DEFAULT false,
    is_late boolean NOT NULL DEFAULT false,
    submitted_at timestamptz NOT NULL DEFAULT now(),
    effective_deadline_at timestamptz NOT NULL,
    online_text text CHECK (online_text IS NULL OR length(online_text) <= 1000000),
    online_text_digest char(64),
    input_digest char(64),
    integrity_consent boolean NOT NULL DEFAULT false,
    integrity_consent_at timestamptz,
    idempotency_key varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_submission_version UNIQUE (assignment_id, submitter_id, version_no)
);
CREATE UNIQUE INDEX uk_submission_effective
    ON app.submission (assignment_id, submitter_id) WHERE is_effective = true;
CREATE UNIQUE INDEX uk_submission_idempotency
    ON app.submission (submitter_id, assignment_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_submission_assignment_status
    ON app.submission (assignment_id, status, submitted_at DESC);

CREATE TABLE app.submission_file (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id uuid NOT NULL REFERENCES app.submission(id) ON DELETE RESTRICT,
    file_object_id uuid NOT NULL REFERENCES app.file_object(id) ON DELETE RESTRICT,
    display_name text NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_submission_file UNIQUE (submission_id, file_object_id)
);

CREATE TABLE app.check_library (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(128) NOT NULL,
    library_type varchar(16) NOT NULL
        CHECK (library_type IN ('CLASS','HISTORICAL','INTERNET','LITERATURE','WHITELIST')),
    scope_key varchar(128) NOT NULL,
    course_id uuid REFERENCES app.course(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    source_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid REFERENCES app.user_account(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_check_library_type_scope UNIQUE (library_type, scope_key)
);
CREATE INDEX ix_check_library_course ON app.check_library (course_id);
CREATE INDEX ix_check_library_status ON app.check_library (status);

CREATE TABLE app.library_entry (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    library_id uuid NOT NULL REFERENCES app.check_library(id) ON DELETE RESTRICT,
    submission_id uuid REFERENCES app.submission(id) ON DELETE RESTRICT,
    file_object_id uuid REFERENCES app.file_object(id) ON DELETE RESTRICT,
    owner_hash char(64) NOT NULL,
    content_hash char(64) NOT NULL,
    language varchar(32),
    fingerprint_key varchar(128),
    seq bigint NOT NULL CHECK (seq > 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED')),
    index_status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (index_status IN ('PENDING','INDEXED','FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_library_entry_seq UNIQUE (library_id, seq),
    CONSTRAINT ck_library_entry_source CHECK (submission_id IS NOT NULL OR file_object_id IS NOT NULL)
);
CREATE UNIQUE INDEX uk_library_entry_submission
    ON app.library_entry (library_id, submission_id) WHERE submission_id IS NOT NULL;
CREATE INDEX ix_library_entry_owner_hash ON app.library_entry (owner_hash);
CREATE INDEX ix_library_entry_content_hash ON app.library_entry (content_hash);
CREATE INDEX ix_library_entry_status ON app.library_entry (status, index_status);

CREATE TABLE app.check_task (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('SUBMISSION','PRECHECK')),
    submission_id uuid REFERENCES app.submission(id) ON DELETE RESTRICT,
    assignment_id uuid NOT NULL REFERENCES app.assignment(id) ON DELETE RESTRICT,
    requester_id uuid REFERENCES app.user_account(id) ON DELETE RESTRICT,
    task_type varchar(16) NOT NULL CHECK (task_type IN ('FORMAL','PRECHECK','RECHECK')),
    status varchar(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','PREPROCESSING','COMPARING','REPORTING','SUCCEEDED','FAILED','CANCELLED')),
    task_version integer NOT NULL DEFAULT 0,
    attempt smallint NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts smallint NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    config_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    config_digest char(64) NOT NULL CHECK (config_digest ~ '^[0-9a-f]{64}$'),
    input_file_object_id uuid REFERENCES app.file_object(id),
    input_text_digest char(64),
    library_watermarks jsonb NOT NULL DEFAULT '{}'::jsonb,
    scheduled_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    lease_owner varchar(128),
    lease_until timestamptz,
    heartbeat_at timestamptz,
    error_code varchar(64),
    error_detail text,
    created_by uuid REFERENCES app.user_account(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_check_task_subject CHECK (
        (subject_type = 'SUBMISSION' AND submission_id IS NOT NULL)
        OR (subject_type = 'PRECHECK' AND submission_id IS NULL
            AND requester_id IS NOT NULL
            AND (input_file_object_id IS NOT NULL OR input_text_digest IS NOT NULL))
    )
);
CREATE UNIQUE INDEX uk_check_task_submission_type
    ON app.check_task (submission_id, task_type)
    WHERE submission_id IS NOT NULL AND task_type IN ('FORMAL','RECHECK');
CREATE INDEX ix_check_task_queue
    ON app.check_task (status, scheduled_at, created_at) WHERE status = 'QUEUED';
CREATE INDEX ix_check_task_lease
    ON app.check_task (lease_until) WHERE status IN ('PREPROCESSING','COMPARING','REPORTING');
CREATE INDEX ix_check_task_assignment ON app.check_task (assignment_id, status);
CREATE INDEX ix_check_task_config_digest ON app.check_task (config_digest);

CREATE TABLE app.check_report (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    check_task_id uuid NOT NULL UNIQUE REFERENCES app.check_task(id) ON DELETE RESTRICT,
    submission_id uuid REFERENCES app.submission(id) ON DELETE RESTRICT,
    assignment_id uuid NOT NULL REFERENCES app.assignment(id) ON DELETE RESTRICT,
    requester_id uuid REFERENCES app.user_account(id) ON DELETE RESTRICT,
    report_version integer NOT NULL CHECK (report_version > 0),
    is_current boolean NOT NULL DEFAULT false,
    overall_score numeric(5,2) NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
    text_score numeric(5,2) CHECK (text_score BETWEEN 0 AND 100),
    structure_score numeric(5,2) CHECK (structure_score BETWEEN 0 AND 100),
    level varchar(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (level IN ('PASS','WARNING','SEVERE','UNKNOWN')),
    algorithm_version varchar(64) NOT NULL,
    params jsonb NOT NULL DEFAULT '{}'::jsonb,
    params_digest char(64) NOT NULL,
    source_count integer NOT NULL DEFAULT 0 CHECK (source_count >= 0),
    top_source_type varchar(24),
    top_source_similarity numeric(5,2) CHECK (top_source_similarity BETWEEN 0 AND 100),
    report_file_id uuid NOT NULL REFERENCES app.file_object(id) ON DELETE RESTRICT,
    report_digest char(64) NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_check_report_version
    ON app.check_report (submission_id, report_version) WHERE submission_id IS NOT NULL;
CREATE UNIQUE INDEX uk_check_report_current
    ON app.check_report (submission_id) WHERE is_current = true AND submission_id IS NOT NULL;
CREATE INDEX ix_check_report_level
    ON app.check_report (assignment_id, level, generated_at DESC);

CREATE OR REPLACE FUNCTION app.guard_report_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'is_current') IS DISTINCT FROM (to_jsonb(OLD) - 'is_current') THEN
        RAISE EXCEPTION 'check_report is immutable after generation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_report_immutable
BEFORE UPDATE ON app.check_report
FOR EACH ROW EXECUTE FUNCTION app.guard_report_immutable();

CREATE TABLE app.file_classification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id uuid NOT NULL REFERENCES app.check_report(id) ON DELETE RESTRICT,
    submission_id uuid REFERENCES app.submission(id) ON DELETE RESTRICT,
    path varchar(1024) NOT NULL,
    file_name text NOT NULL,
    extension varchar(32),
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    sha256 char(64),
    bucket char(1) NOT NULL CHECK (bucket IN ('A','B','C','D','E','F')),
    included boolean NOT NULL DEFAULT false,
    exclude_reason varchar(64),
    language varchar(32),
    is_binary boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_file_classification_report_bucket
    ON app.file_classification (report_id, bucket, included);
CREATE INDEX ix_file_classification_report_path ON app.file_classification (report_id, path);

CREATE TABLE app.grade (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id uuid NOT NULL UNIQUE REFERENCES app.submission(id) ON DELETE RESTRICT,
    reviewer_id uuid NOT NULL REFERENCES app.user_account(id) ON DELETE RESTRICT,
    reviewer_type varchar(16) NOT NULL DEFAULT 'TEACHER'
        CHECK (reviewer_type IN ('TEACHER','ASSISTANT','STUDENT')),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','PUBLISHED')),
    raw_score numeric(6,2) CHECK (raw_score >= 0),
    late_deduction numeric(6,2) NOT NULL DEFAULT 0 CHECK (late_deduction >= 0),
    final_score numeric(6,2) NOT NULL CHECK (final_score >= 0),
    dimension_scores jsonb NOT NULL DEFAULT '[]'::jsonb,
    annotations jsonb NOT NULL DEFAULT '[]'::jsonb,
    check_verdict varchar(32)
        CHECK (check_verdict IS NULL OR check_verdict IN ('ORIGINAL','SUSPECTED_PLAGIARISM','INTERVIEW_REQUIRED')),
    comment text,
    feedback_file_id uuid REFERENCES app.file_object(id) ON DELETE RESTRICT,
    published boolean NOT NULL DEFAULT false,
    published_at timestamptz,
    published_by uuid REFERENCES app.user_account(id) ON DELETE RESTRICT,
    submitted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_grade_reviewer ON app.grade (reviewer_id, status);
CREATE INDEX ix_grade_status ON app.grade (status);
CREATE INDEX ix_grade_published ON app.grade (published);

CREATE TABLE app.extension_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id uuid NOT NULL REFERENCES app.assignment(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES app.user_account(id) ON DELETE RESTRICT,
    reason text NOT NULL,
    expected_deadline timestamptz NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED','APPROVED','REJECTED','CLOSED')),
    approved_deadline timestamptz,
    handler_id uuid REFERENCES app.user_account(id) ON DELETE RESTRICT,
    submitted_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_extension_request UNIQUE (assignment_id, user_id)
);

CREATE TABLE app.appeal (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tracking_no varchar(64) NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES app.user_account(id) ON DELETE RESTRICT,
    submission_id uuid NOT NULL REFERENCES app.submission(id) ON DELETE RESTRICT,
    grade_id uuid REFERENCES app.grade(id) ON DELETE RESTRICT,
    appeal_type varchar(16) NOT NULL CHECK (appeal_type IN ('CHECK_AGAINST','GRADE')),
    reason text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED','IN_REVIEW','APPROVED','REJECTED','CLOSED')),
    handler_id uuid REFERENCES app.user_account(id) ON DELETE RESTRICT,
    conclusion text,
    submitted_at timestamptz NOT NULL DEFAULT now(),
    deadline_at timestamptz NOT NULL,
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_appeal_grade_required CHECK (appeal_type <> 'GRADE' OR grade_id IS NOT NULL)
);
CREATE INDEX ix_appeal_user ON app.appeal (user_id, status);
CREATE INDEX ix_appeal_submission ON app.appeal (submission_id);

CREATE TABLE app.notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id uuid NOT NULL REFERENCES app.user_account(id) ON DELETE RESTRICT,
    notification_type varchar(32) NOT NULL,
    title varchar(256) NOT NULL,
    content text NOT NULL,
    target_type varchar(64),
    target_id uuid,
    is_read boolean NOT NULL DEFAULT false,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notification_recipient_created
    ON app.notification (recipient_id, created_at DESC);
CREATE INDEX ix_notification_unread
    ON app.notification (recipient_id, is_read) WHERE is_read = false;
CREATE INDEX ix_notification_type ON app.notification (notification_type, created_at DESC);

CREATE TABLE audit.audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid,
    actor_type varchar(16) NOT NULL DEFAULT 'USER' CHECK (actor_type IN ('USER','SYSTEM','WORKER')),
    action varchar(128) NOT NULL,
    target_type varchar(64) NOT NULL,
    target_id uuid,
    result varchar(16) NOT NULL DEFAULT 'SUCCESS' CHECK (result IN ('SUCCESS','FAILED','DENIED')),
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    ip inet,
    trace_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_actor_created ON audit.audit_log (actor_id, created_at DESC);
CREATE INDEX ix_audit_target_created ON audit.audit_log (target_type, target_id, created_at DESC);
CREATE INDEX ix_audit_action_created ON audit.audit_log (action, created_at DESC);
CREATE INDEX ix_audit_created_brin ON audit.audit_log USING brin (created_at);

CREATE TABLE app.system_config (
    config_key varchar(128) PRIMARY KEY,
    value jsonb NOT NULL,
    value_type varchar(16) NOT NULL
        CHECK (value_type IN ('BOOLEAN','INTEGER','DECIMAL','STRING','OBJECT','ARRAY')),
    config_group varchar(64) NOT NULL,
    description text,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by uuid REFERENCES app.user_account(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_system_config_group ON app.system_config (config_group);

CREATE OR REPLACE FUNCTION app.touch_updated_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_account_touch BEFORE UPDATE ON app.user_account
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_course_touch BEFORE UPDATE ON app.course
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_course_member_touch BEFORE UPDATE ON app.course_member
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_assignment_touch BEFORE UPDATE ON app.assignment
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_submission_touch BEFORE UPDATE ON app.submission
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_check_library_touch BEFORE UPDATE ON app.check_library
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_library_entry_touch BEFORE UPDATE ON app.library_entry
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_check_task_touch BEFORE UPDATE ON app.check_task
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_grade_touch BEFORE UPDATE ON app.grade
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_extension_request_touch BEFORE UPDATE ON app.extension_request
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();
CREATE TRIGGER trg_appeal_touch BEFORE UPDATE ON app.appeal
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

