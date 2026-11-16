-- 上传文件扫描状态机（FR-S-06）：扫描策略与 ClamAV 地址种子值。
-- 默认 PASS：上传后由 FileScanScheduler 在数秒内置 CLEAN，提交不会被阻塞；
-- 需要演示下载拦截时把 file.scan.policy 改为 REJECT（模拟 INFECTED）；
-- 接入真实杀毒时可改为 CLAMAV 并配置 file.scan.clamav_host（如 192.168.1.10:3310）。
INSERT INTO app.system_config (config_key, value, value_type, config_group, description)
VALUES
    ('file.scan.policy', '"PASS"'::jsonb, 'STRING', 'file',
     'Uploaded file scan policy: PASS (mark clean, no-op) | REJECT (simulate infected) | CLAMAV (real scan via clamd)'),
    ('file.scan.clamav_host', '""'::jsonb, 'STRING', 'file',
     'ClamAV clamd INSTREAM host:port (e.g. 192.168.1.10:3310); empty disables real scan')
ON CONFLICT (config_key) DO NOTHING;

-- 扫描重试计数：连续失败（超时/异常）达到 3 次后置 FAILED，供下载拦截与运维观察。
ALTER TABLE app.file_object ADD COLUMN scan_attempt integer NOT NULL DEFAULT 0;
