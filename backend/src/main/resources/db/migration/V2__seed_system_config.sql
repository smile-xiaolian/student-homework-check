INSERT INTO app.system_config (config_key, value, value_type, config_group, description)
VALUES
    ('file.max_size_bytes', '104857600'::jsonb, 'INTEGER', 'storage', 'Single uploaded file size limit in bytes'),
    ('file.allowed_extensions', '["pdf","docx","doc","txt","md","java","py","c","cpp","h","js","ts","go","zip"]'::jsonb, 'ARRAY', 'storage', 'Allowed file extensions'),
    ('check.text_warning_threshold', '20'::jsonb, 'INTEGER', 'checking', 'Default text similarity warning threshold'),
    ('check.text_severe_threshold', '50'::jsonb, 'INTEGER', 'checking', 'Default text similarity severe threshold'),
    ('check.structure_warning_threshold', '30'::jsonb, 'INTEGER', 'checking', 'Default code structure warning threshold'),
    ('check.structure_severe_threshold', '60'::jsonb, 'INTEGER', 'checking', 'Default code structure severe threshold'),
    ('submission.late_penalty', '{"type":"DAILY_PERCENT","value":5,"maxDeduction":20}'::jsonb, 'OBJECT', 'submission', 'Late submission deduction rule'),
    ('course.require_review', 'false'::jsonb, 'BOOLEAN', 'course', 'Whether new courses require administrator review'),
    ('security.login_max_failures', '5'::jsonb, 'INTEGER', 'security', 'Consecutive login failures before lock'),
    ('security.login_lock_minutes', '30'::jsonb, 'INTEGER', 'security', 'Account lock duration in minutes'),
    ('system.demo_data_enabled', 'true'::jsonb, 'BOOLEAN', 'system', 'Demo data bootstrap switch')
ON CONFLICT (config_key) DO NOTHING;

