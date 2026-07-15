package com.zaid.geode144shizuku;

import android.content.Context;
import android.util.Base64;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Geode144UserService extends IGeode144Service.Stub {
    private static final String TARGET_PACKAGE = "com.geode.launcher";
    private static final String TARGET_RATE = "144.00002";
    private static final int TARGET_WIDTH = 1080;
    private static final int TARGET_HEIGHT = 2392;
    private static final long COMMAND_TIMEOUT_SECONDS = 8;
    private static final File STATE_FILE = new File("/data/local/tmp/geode144-shizuku.state");

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> monitorTask;
    private volatile boolean monitoring;
    private volatile boolean geodeWasForeground;
    private volatile String lastStatus = "Servicio creado.";
    private StateSnapshot snapshot;

    public Geode144UserService() {
        loadSnapshot();
    }

    @Keep
    public Geode144UserService(Context ignored) {
        loadSnapshot();
    }

    @Override
    public synchronized String startMonitor() {
        if (monitoring) {
            return "El monitor ya está activo.\n" + lastStatus;
        }

        boolean foreground = isGeodeForeground();
        if (snapshot != null && !foreground) {
            restoreSnapshot("recuperación de estado pendiente");
        }

        monitoring = true;
        geodeWasForeground = false;
        monitorTask = scheduler.scheduleWithFixedDelay(this::monitorTick, 0, 2, TimeUnit.SECONDS);
        lastStatus = "Monitor iniciado. Se aplicará 144 Hz solo mientras Geode esté en primer plano.";
        return lastStatus;
    }

    @Override
    public synchronized String stopAndRestore() {
        monitoring = false;
        if (monitorTask != null) {
            monitorTask.cancel(false);
            monitorTask = null;
        }
        geodeWasForeground = false;
        return restoreSnapshot("detención manual");
    }

    @Override
    public String diagnose() {
        StringBuilder report = new StringBuilder();
        report.append("Geode 144 Shizuku v1.1.0\n")
                .append("UID del UserService: ").append(android.os.Process.myUid()).append('\n')
                .append("Monitor activo: ").append(monitoring).append('\n')
                .append("Geode en primer plano: ").append(isGeodeForeground()).append('\n')
                .append("Snapshot guardado: ").append(snapshot != null).append("\n\n");

        report.append("peak_refresh_rate: ")
                .append(valueOrNull(run("settings get system peak_refresh_rate").output)).append('\n');
        report.append("min_refresh_rate: ")
                .append(valueOrNull(run("settings get system min_refresh_rate").output)).append('\n');
        report.append("modo preferido:\n")
                .append(run("cmd display get-user-preferred-display-mode 2>&1").printable()).append('\n');
        report.append("game_overlay:\n")
                .append(run("device_config get game_overlay " + TARGET_PACKAGE + " 2>&1").printable()).append('\n');
        report.append("Game Mode:\n")
                .append(run("cmd game list-modes " + TARGET_PACKAGE + " 2>&1").printable()).append('\n');
        report.append("UID del paquete:\n")
                .append(run("cmd package list packages -U | grep 'package:" + TARGET_PACKAGE + "' || true").printable())
                .append("\n\n");

        String displayCommand = "dumpsys display | grep -E " + shellQuote(
                "DisplayDeviceInfo|mDesiredDisplayModeSpecs|mBaseDisplayInfo|supportedModes|" +
                        "frameRateOverride|mFrameRateOverrides|ignore_app_preferred_refresh_rate_request") +
                " | head -n 100";
        report.append("DisplayManager:\n").append(run(displayCommand).printable());

        lastStatus = report.toString();
        return lastStatus;
    }

    @Override
    public boolean isMonitoring() {
        return monitoring;
    }

    @Override
    public String getLastStatus() {
        return lastStatus;
    }

    @Override
    public synchronized void exit() {
        stopAndRestore();
        scheduler.shutdownNow();
        System.exit(0);
    }

    @Override
    public synchronized void destroy() {
        stopAndRestore();
        scheduler.shutdownNow();
        System.exit(0);
    }

    private void monitorTick() {
        try {
            boolean foreground = isGeodeForeground();
            if (foreground) {
                if (!geodeWasForeground) {
                    captureSnapshotIfNeeded();
                }
                apply144Hz();
            } else if (geodeWasForeground || snapshot != null) {
                restoreSnapshot("Geode dejó el primer plano");
            }
            geodeWasForeground = foreground;
        } catch (Throwable throwable) {
            lastStatus = "Error en monitor: " + throwable;
        }
    }

    private synchronized void captureSnapshotIfNeeded() {
        if (snapshot != null) return;

        StateSnapshot state = new StateSnapshot();
        state.peakRefreshRate = clean(run("settings get system peak_refresh_rate").output);
        state.minRefreshRate = clean(run("settings get system min_refresh_rate").output);
        state.gameOverlay = clean(run("device_config get game_overlay " + TARGET_PACKAGE).output);
        state.preferredDisplayMode = clean(run("cmd display get-user-preferred-display-mode 2>&1").output);
        state.gameModeOutput = clean(run("cmd game list-modes " + TARGET_PACKAGE + " 2>&1").output);
        state.gameMode = parseGameMode(state.gameModeOutput);
        snapshot = state;
        saveSnapshot();
        lastStatus = "Valores originales guardados antes de aplicar 144 Hz.";
    }

    private synchronized void apply144Hz() {
        List<String> results = new ArrayList<>();
        results.add(runCommand("settings put system peak_refresh_rate " + TARGET_RATE));
        results.add(runCommand("settings put system min_refresh_rate " + TARGET_RATE));

        CommandResult preferred = run("cmd display set-user-preferred-display-mode " +
                TARGET_WIDTH + " " + TARGET_HEIGHT + " " + TARGET_RATE + " 2>&1");
        if (!preferred.ok()) {
            preferred = run("cmd display set-user-preferred-display-mode " +
                    TARGET_WIDTH + " " + TARGET_HEIGHT + " 144 2>&1");
        }
        results.add("display: " + preferred.printable());

        CommandResult gameSet = run("cmd game set --mode 2 --fps disable --downscale disable " +
                TARGET_PACKAGE + " 2>&1");
        results.add("game set: " + gameSet.printable());
        results.add(runCommand("cmd game mode performance " + TARGET_PACKAGE));

        results.add(runCommand("device_config put game_overlay " + TARGET_PACKAGE + " " +
                shellQuote("mode=2,fps=0,downscaleFactor=1.0")));

        lastStatus = "144 Hz reaplicados mientras Geode está activo.\n" + String.join("\n", results);
    }

    private synchronized String restoreSnapshot(String reason) {
        if (snapshot == null) {
            deleteStateFile();
            lastStatus = "No había valores pendientes que restaurar (" + reason + ").";
            return lastStatus;
        }

        StateSnapshot state = snapshot;
        List<String> results = new ArrayList<>();
        results.add(restoreSetting("peak_refresh_rate", state.peakRefreshRate));
        results.add(restoreSetting("min_refresh_rate", state.minRefreshRate));

        if (isNullValue(state.gameOverlay)) {
            results.add(runCommand("device_config delete game_overlay " + TARGET_PACKAGE));
        } else {
            results.add(runCommand("device_config put game_overlay " + TARGET_PACKAGE + " " +
                    shellQuote(state.gameOverlay)));
        }

        results.add(runCommand("cmd game reset " + TARGET_PACKAGE));
        if (!isNullValue(state.gameMode)) {
            results.add(runCommand("cmd game mode " + shellQuote(state.gameMode) + " " + TARGET_PACKAGE));
        }

        DisplayMode originalMode = parseDisplayMode(state.preferredDisplayMode);
        if (originalMode == null) {
            results.add(runCommand("cmd display clear-user-preferred-display-mode"));
        } else {
            results.add(runCommand("cmd display set-user-preferred-display-mode " +
                    originalMode.width + " " + originalMode.height + " " + originalMode.refreshRate));
        }

        snapshot = null;
        deleteStateFile();
        lastStatus = "Valores restaurados (" + reason + ").\n" + String.join("\n", results);
        return lastStatus;
    }

    private String restoreSetting(String key, String value) {
        if (isNullValue(value)) {
            return runCommand("settings delete system " + key);
        }
        return runCommand("settings put system " + key + " " + shellQuote(value));
    }

    private boolean isGeodeForeground() {
        String activity = run("dumpsys activity activities | grep -E " +
                shellQuote("mResumedActivity|topResumedActivity|ResumedActivity") + " | head -n 8").output;
        if (activity.contains(TARGET_PACKAGE)) return true;

        String window = run("dumpsys window windows | grep -E " +
                shellQuote("mCurrentFocus|mFocusedApp") + " | head -n 8").output;
        return window.contains(TARGET_PACKAGE);
    }

    private String runCommand(String command) {
        CommandResult result = run(command + " 2>&1");
        return command + " -> " + result.printable();
    }

    private CommandResult run(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, "tiempo de espera agotado");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            }
            return new CommandResult(process.exitValue(), output.toString());
        } catch (Throwable throwable) {
            if (process != null) process.destroyForcibly();
            return new CommandResult(-1, throwable.toString());
        }
    }

    private void saveSnapshot() {
        if (snapshot == null) return;
        try {
            Properties properties = new Properties();
            properties.setProperty("peak", encode(snapshot.peakRefreshRate));
            properties.setProperty("min", encode(snapshot.minRefreshRate));
            properties.setProperty("overlay", encode(snapshot.gameOverlay));
            properties.setProperty("preferred", encode(snapshot.preferredDisplayMode));
            properties.setProperty("gameModeOutput", encode(snapshot.gameModeOutput));
            properties.setProperty("gameMode", encode(snapshot.gameMode));
            try (FileOutputStream output = new FileOutputStream(STATE_FILE)) {
                properties.store(output, "Geode144 Shizuku restoration state");
            }
            run("chmod 600 " + STATE_FILE.getAbsolutePath());
        } catch (Throwable throwable) {
            lastStatus = "No se pudo persistir el snapshot: " + throwable;
        }
    }

    private void loadSnapshot() {
        if (!STATE_FILE.isFile()) return;
        try {
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(STATE_FILE)) {
                properties.load(input);
            }
            StateSnapshot state = new StateSnapshot();
            state.peakRefreshRate = decode(properties.getProperty("peak"));
            state.minRefreshRate = decode(properties.getProperty("min"));
            state.gameOverlay = decode(properties.getProperty("overlay"));
            state.preferredDisplayMode = decode(properties.getProperty("preferred"));
            state.gameModeOutput = decode(properties.getProperty("gameModeOutput"));
            state.gameMode = decode(properties.getProperty("gameMode"));
            snapshot = state;
            lastStatus = "Se recuperó un snapshot pendiente de restauración.";
        } catch (Throwable throwable) {
            lastStatus = "No se pudo leer el snapshot: " + throwable;
            deleteStateFile();
        }
    }

    private void deleteStateFile() {
        try {
            if (STATE_FILE.exists() && !STATE_FILE.delete()) {
                run("rm -f " + STATE_FILE.getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String parseGameMode(String output) {
        if (output == null) return null;
        Matcher named = Pattern.compile(
                "(?i)(?:current\\s+game\\s+mode|game\\s+mode|current\\s+mode|mode)\\s*[:=]\\s*" +
                        "(standard|performance|battery|custom)").matcher(output);
        if (named.find()) return named.group(1).toLowerCase(Locale.ROOT);

        Matcher numeric = Pattern.compile(
                "(?i)(?:current\\s+game\\s+mode|game\\s+mode|current\\s+mode|mode)\\s*[:=]\\s*([1-4])")
                .matcher(output);
        if (!numeric.find()) return null;
        switch (numeric.group(1)) {
            case "1": return "standard";
            case "2": return "performance";
            case "3": return "battery";
            case "4": return "custom";
            default: return null;
        }
    }

    private static DisplayMode parseDisplayMode(String output) {
        if (isNullValue(output)) return null;
        Matcher matcher = Pattern.compile("(\\d{3,5})\\D+(\\d{3,5})\\D+(\\d+(?:\\.\\d+)?)")
                .matcher(output);
        if (!matcher.find()) return null;
        try {
            return new DisplayMode(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    matcher.group(3));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static String valueOrNull(String value) {
        String clean = clean(value);
        return isNullValue(clean) ? "<sin valor>" : clean;
    }

    private static boolean isNullValue(String value) {
        if (value == null) return true;
        String clean = value.trim();
        return clean.isEmpty() || "null".equalsIgnoreCase(clean) || "undefined".equalsIgnoreCase(clean);
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String encode(String value) {
        if (value == null) return "~";
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String decode(String value) {
        if (value == null || "~".equals(value)) return null;
        return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    private static final class StateSnapshot {
        String peakRefreshRate;
        String minRefreshRate;
        String gameOverlay;
        String preferredDisplayMode;
        String gameModeOutput;
        String gameMode;
    }

    private static final class DisplayMode {
        final int width;
        final int height;
        final String refreshRate;

        DisplayMode(int width, int height, String refreshRate) {
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output.trim();
        }

        boolean ok() {
            return exitCode == 0;
        }

        String printable() {
            String text = output.isEmpty() ? "sin salida" : output;
            return "[" + exitCode + "] " + text;
        }
    }
}
