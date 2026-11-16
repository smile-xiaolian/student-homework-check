package com.edu.submission.file.application;

import com.edu.submission.shared.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 上传文件扫描状态机（FR-S-06）。
 *
 * <p>上传插库时 {@code file_object.scan_status} 置为 {@code PENDING}，由本调度器异步驱动：
 * 每 5 秒捞取最近 10 分钟内上传、仍处于 {@code PENDING} 的文件，逐条按策略扫描：
 * <ul>
 *   <li>{@code file.scan.policy = PASS}（默认）或未配置 ClamAV：直接置 {@code CLEAN}，不阻塞提交；</li>
 *   <li>{@code file.scan.policy = REJECT}（测试用）：置 {@code INFECTED}，演示下载拦截；</li>
 *   <li>{@code file.scan.policy = CLAMAV} 且配置了 {@code file.scan.clamav_host}：
 *       通过 {@code java.net.Socket} 走 ClamAV {@code INSTREAM} 协议，OK→CLEAN、FOUND→INFECTED；
 *       超时/异常视为瞬时失败，保持 {@code PENDING} 供下次重扫，连续失败达
 *       {@value #MAX_SCAN_ATTEMPTS} 次置 {@code FAILED}（下次不再自动重试，下载被拦截）。</li>
 * </ul>
 *
 * <p>所有状态更新都带 {@code AND scan_status = 'PENDING'} 条件，多副本部署时不会重复结算同一文件。
 * 不新增任何依赖（ClamAV 客户端为 JDK 内置 {@link Socket}）。</p>
 */
@Service
public class FileScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(FileScanScheduler.class);

    /** 连续扫描失败（超时/异常）超过该次数则置 FAILED，不再自动重试。 */
    static final int MAX_SCAN_ATTEMPTS = 3;

    /** 单轮最多处理的 PENDING 文件数，避免单次轮询占用过久。 */
    private static final int BATCH_LIMIT = 50;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int IO_CHUNK = 8192;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectStorageService storageService;

    public FileScanScheduler(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                             ObjectStorageService storageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.storageService = storageService;
    }

    /** 每 5 秒扫描最近 10 分钟内上传、仍处于 PENDING 的文件（延迟启动，等待应用就绪）。 */
    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void scanPendingFiles() {
        String policy = configString("file.scan.policy", "PASS");
        String clamavHost = configString("file.scan.clamav_host", "");
        List<PendingFile> pending;
        try {
            pending = jdbcTemplate.query(
                    "SELECT id, object_key FROM app.file_object " +
                            "WHERE scan_status = 'PENDING' AND created_at > now() - interval '10 minutes' " +
                            "ORDER BY created_at LIMIT " + BATCH_LIMIT,
                    (rs, rowNum) -> new PendingFile(
                            rs.getObject("id", UUID.class),
                            rs.getString("object_key")));
        } catch (RuntimeException exception) {
            log.warn("Failed to load pending files for scan: {}", exception.getMessage());
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        for (PendingFile file : pending) {
            try {
                scanOne(file, policy, clamavHost);
            } catch (RuntimeException exception) {
                log.warn("Scan failed for file {}: {}", file.id(), exception.getMessage());
                bumpFailure(file.id());
            }
        }
    }

    private void scanOne(PendingFile file, String policy, String clamavHost) {
        if ("REJECT".equalsIgnoreCase(policy)) {
            // 测试策略：模拟命中病毒，演示下载拦截
            settle(file.id(), "INFECTED");
            return;
        }
        if (clamavHost != null && !clamavHost.isBlank() && !"PASS".equalsIgnoreCase(policy)) {
            // 配置了 clamd：走真实 INSTREAM 扫描
            ScanOutcome outcome;
            try (InputStream content = storageService.getStream(file.objectKey())) {
                outcome = clamavScan(clamavHost, content);
            } catch (IOException exception) {
                log.warn("ClamAV scan I/O error for file {}: {}", file.id(), exception.getMessage());
                outcome = ScanOutcome.ERROR;
            }
            switch (outcome) {
                case CLEAN -> settle(file.id(), "CLEAN");
                case INFECTED -> settle(file.id(), "INFECTED");
                case ERROR -> bumpFailure(file.id());
            }
            return;
        }
        // 默认 PASS（或未配置 ClamAV）：直接标记为安全
        settle(file.id(), "CLEAN");
    }

    /** 终态结算：置 scan_status 并清空重试计数；仅当仍为 PENDING 时生效（幂等、多副本安全）。 */
    private void settle(UUID id, String status) {
        int updated = jdbcTemplate.update(
                "UPDATE app.file_object SET scan_status = ?, scan_attempt = 0 " +
                        "WHERE id = ? AND scan_status = 'PENDING'",
                status, id);
        if (updated > 0) {
            log.info("File {} scan settled to {}", id, status);
        }
    }

    /**
     * 瞬时失败（超时/异常）：原子地计数 +1，达到 {@link #MAX_SCAN_ATTEMPTS} 次时同一条语句置 FAILED，
     * 否则保持 PENDING 供下次重扫。后续因 scan_status 不再是 PENDING 而自动停止计数。
     */
    private void bumpFailure(UUID id) {
        int updated = jdbcTemplate.update(
                "UPDATE app.file_object SET " +
                        "scan_attempt = scan_attempt + 1, " +
                        "scan_status = CASE WHEN scan_attempt + 1 >= ? THEN 'FAILED' ELSE scan_status END " +
                        "WHERE id = ? AND scan_status = 'PENDING'",
                MAX_SCAN_ATTEMPTS, id);
        if (updated == 0) {
            return;
        }
        Integer attempts = jdbcTemplate.query(
                "SELECT scan_attempt FROM app.file_object WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, id);
        if (attempts != null && attempts >= MAX_SCAN_ATTEMPTS) {
            log.warn("File {} exceeded {} scan attempts, marked FAILED", id, MAX_SCAN_ATTEMPTS);
        }
    }

    /** ClamAV {@code INSTREAM} 协议：发送 {@code zINSTREAM\0} + 分块数据（4 字节大端长度前缀）+ 零长度终止。 */
    private ScanOutcome clamavScan(String hostPort, InputStream content) throws IOException {
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0 || colon == hostPort.length() - 1) {
            throw new IllegalArgumentException("Invalid clamav host:port: " + hostPort);
        }
        String host = hostPort.substring(0, colon).trim();
        int port = Integer.parseInt(hostPort.substring(colon + 1).trim());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] buffer = new byte[IO_CHUNK];
            int read;
            while ((read = content.read(buffer)) != -1) {
                out.write(intToBytesBE(read));
                out.write(buffer, 0, read);
            }
            out.write(new byte[]{0, 0, 0, 0});
            out.flush();
            String response = readResponse(socket.getInputStream());
            if (response.startsWith("OK")) {
                return ScanOutcome.CLEAN;
            }
            if (response.startsWith("FOUND")) {
                return ScanOutcome.INFECTED;
            }
            log.warn("Unexpected ClamAV response: {}", response);
            return ScanOutcome.ERROR;
        }
    }

    /** 读取 ClamAV 响应（以 {@code \0} 或换行结尾），上限 1024 字节防止异常流。 */
    private static String readResponse(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        int value;
        while ((value = in.read()) != -1) {
            if (value == 0 || value == '\n') {
                break;
            }
            builder.append((char) value);
            if (builder.length() > 1024) {
                break;
            }
        }
        return builder.toString();
    }

    private static byte[] intToBytesBE(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    /** 读取系统参数（jsonb 字符串值）；缺失或非法时返回 fallback。 */
    private String configString(String key, String fallback) {
        String raw;
        try {
            raw = jdbcTemplate.query(
                    "SELECT value::text FROM app.system_config WHERE config_key = ?",
                    rs -> rs.next() ? rs.getString(1) : null, key);
        } catch (RuntimeException exception) {
            return fallback;
        }
        if (raw == null) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node.isTextual() ? node.asText() : node.asText(fallback);
        } catch (Exception exception) {
            return fallback;
        }
    }

    private enum ScanOutcome {
        CLEAN, INFECTED, ERROR
    }

    private record PendingFile(UUID id, String objectKey) {
    }
}
