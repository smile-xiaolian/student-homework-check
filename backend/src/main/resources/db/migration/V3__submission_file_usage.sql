CREATE UNIQUE INDEX IF NOT EXISTS uk_submission_file_object
    ON app.submission_file (file_object_id);
