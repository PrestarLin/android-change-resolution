package com.taowen.androidchangeresolution;

import android.content.Context;
import android.os.Build;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 自包含的可复用工具：运行 Qualcomm 原生探针并设置 vendor.display.hdmi_cfg_idx。
 *
 * 该文件同时被 connect-screen-axi 通过 git submodule 引用，修改本文件后
 * 更新子模块指针即可让融合应用引用新逻辑。
 *
 * 命令执行是可插拔的：ROOT 走 su；其它应用可传入自己的 Runner（例如 Shizuku）。
 */
public final class QtiModeOverride {

    public static final String QUALCOMM_MODE_OVERRIDE_PROP = "vendor.display.hdmi_cfg_idx";
    public static final String QTI_PROBE_ASSET = "native/arm64-v8a/qti-display-probe";
    public static final String QTI_PROBE_FILE_NAME = "qti-display-probe";
    public static final double REFRESH_MATCH_TOLERANCE_HZ = 0.5d;
    public static final int ROOT_TIMEOUT_SECONDS = 30;

    private QtiModeOverride() {
    }

    /** 命令执行器：由调用方决定用 Root(su) 还是 Shizuku 等。 */
    public interface Runner {
        Result run(String command) throws Exception;
    }

    /** su 方式的 Root 执行器。 */
    public static final Runner ROOT = command -> {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    output.write(buffer, 0, n);
                }
            } catch (Exception ignored) {
            }
        }, "root-output-reader");
        reader.start();
        boolean finished = process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1000);
            return new Result(124,
                    output.toString(StandardCharsets.UTF_8.name()) + "\nTimed out");
        }
        reader.join(1000);
        return new Result(process.exitValue(),
                output.toString(StandardCharsets.UTF_8.name()));
    };

    /** 执行任意命令。 */
    public static Result run(String command, Runner runner) throws Exception {
        if (runner == null) {
            runner = ROOT;
        }
        return runner.run(command);
    }

    /** 校验 root/shizuku 可用（id 输出含 uid=0）。 */
    public static void ensureRoot(Runner runner) throws Exception {
        Result check = run("id", runner);
        if (check.exitCode != 0 || !check.output.contains("uid=0")) {
            throw new IllegalStateException(
                    "Root 未授予本应用。\n\n"
                            + "Magisk 标准流程：应用执行 su 时 Magisk 会询问是否授权。\n"
                            + "请在 Magisk > 超级用户 中允许本应用，并确认已为应用启用超级用户。\n\n"
                            + check.output.trim());
        }
    }

    /** 从 assets 解出探针并设为可执行，返回文件绝对路径。 */
    public static String ensureProbeExecutable(Context context) throws Exception {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                arm64 = true;
                break;
            }
        }
        if (!arm64) {
            throw new IllegalStateException("此构建仅包含 arm64-v8a 的 Qualcomm 探针。");
        }

        File dir = new File(context.getCodeCacheDir(), "native");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 " + dir);
        }
        File out = new File(dir, QTI_PROBE_FILE_NAME);
        try (InputStream in = context.getAssets().open(QTI_PROBE_ASSET);
             FileOutputStream fileOut = new FileOutputStream(out, false)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                fileOut.write(buffer, 0, n);
            }
        }
        if (!out.setExecutable(true, true)) {
            throw new IllegalStateException("无法将 qti-display-probe 标记为可执行");
        }
        out.setReadable(true, true);
        return out.getAbsolutePath();
    }

    /** 运行探针 diag，解析为 Diagnostics。 */
    public static Diagnostics diag(Context context, Runner runner) throws Exception {
        String probePath = ensureProbeExecutable(context);
        String command = "chmod 700 " + shellQuote(probePath)
                + " && LD_LIBRARY_PATH=/vendor/lib64:/system_ext/lib64 "
                + shellQuote(probePath) + " diag external";
        Result result = run(command, runner);

        String json = extractJsonObject(result.output);
        Diagnostics diagnostics;
        if (json.isEmpty()) {
            diagnostics = Diagnostics.failed(
                    "qti-display-probe 未返回 JSON。exit=" + result.exitCode
                            + " output=" + trimForDisplay(result.output, 600));
        } else {
            diagnostics = Diagnostics.fromJson(json, result.output, result.exitCode);
        }
        diagnostics.extraDiagnostics = rootDisplayDiagnostics(runner);
        return diagnostics;
    }

    /** 额外系统诊断：属性、composer pid、DRM DP/HDMI 状态。 */
    public static String rootDisplayDiagnostics(Runner runner) {
        String command = "printf 'property=" + QUALCOMM_MODE_OVERRIDE_PROP + "='"
                + "; getprop " + shellQuote(QUALCOMM_MODE_OVERRIDE_PROP)
                + "; printf 'composerPid='"
                + "; pidof vendor.qti.hardware.display.composer-service 2>/dev/null"
                + " || pidof android.hardware.graphics.composer 2>/dev/null || true"
                + "; for d in /sys/class/drm/card*-DP-* /sys/class/drm/card*-HDMI-*; do"
                + " [ -e \"$d/status\" ] || continue;"
                + " name=${d##*/};"
                + " status=$(cat \"$d/status\" 2>/dev/null || true);"
                + " enabled=$(cat \"$d/enabled\" 2>/dev/null || true);"
                + " modes=$(tr '\\n' ',' < \"$d/modes\" 2>/dev/null | sed 's/,$//' || true);"
                + " printf '\\n%s status=%s enabled=%s modes=%s' \"$name\" \"$status\" \"$enabled\" \"$modes\";"
                + " done";
        try {
            Result result = run(command, runner);
            String value = result.output.trim();
            if (result.exitCode != 0) {
                value = "exit=" + result.exitCode + " " + value;
            }
            return trimForDisplay(value, 1600);
        } catch (Exception e) {
            return "extra diagnostics failed: " + e.getMessage();
        }
    }

    /** 读取当前 override 属性的值。 */
    public static String currentOverride(Context context, Runner runner) {
        try {
            Result result = run("getprop " + shellQuote(QUALCOMM_MODE_OVERRIDE_PROP), runner);
            return result.output.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 用 set-active 切换配置。 */
    public static boolean setActive(Context context, int configIndex, Runner runner) throws Exception {
        String probePath = ensureProbeExecutable(context);
        String command = "chmod 700 " + shellQuote(probePath)
                + " && LD_LIBRARY_PATH=/vendor/lib64:/system_ext/lib64 "
                + shellQuote(probePath) + " set-active " + configIndex + " external";
        Result result = run(command, runner);
        String json = extractJsonObject(result.output);
        if (!json.isEmpty()) {
            JSONObject root = new JSONObject(json);
            return root.optBoolean("ok", false);
        }
        return false;
    }

    /** 由诊断数据派生 override 属性值。 */
    public static OverridePlan buildPlan(Display.Mode mode, Diagnostics diagnostics) {
        List<QtiConfig> matches = diagnostics.matchingConfigs(mode);
        DrmMode drmMode = diagnostics.bestMatchingDrmMode(mode);
        if (drmMode == null || drmMode.selector <= 0) {
            throw new IllegalStateException(
                    "无法从 DRM 连接器模式推导 Qualcomm selector。\n\n"
                            + "探针状态: " + diagnostics.statusLine() + "\n"
                            + "DRM: " + diagnostics.drmLine() + "\n"
                            + "所选 Android 模式: " + formatMode(mode));
        }

        int width = drmMode.width;
        int height = drmMode.height;
        int refresh = drmMode.refresh;
        int selector = drmMode.selector;
        if (!matches.isEmpty()) {
            QtiConfig config = matches.get(0);
            if (config.width == width && config.height == height) {
                refresh = config.roundedRefresh;
            }
        }

        String propertyValue = width + ":" + height + ":" + refresh + ":" + selector;
        StringBuilder summary = new StringBuilder();
        summary.append("Selector: derived from DRM mode flags. ")
                .append(drmMode.diagnosticLabel());
        if (diagnostics.available) {
            summary.append("\nQTI: ").append(diagnostics.configCountLine())
                    .append(", ").append(diagnostics.activeConfigLine());
            if (matches.isEmpty()) {
                summary.append("\nQTI match: none for ").append(modeSpec(mode))
                        .append("; using Android mode dimensions.");
            } else {
                summary.append("\nQTI match: ");
                for (int i = 0; i < matches.size(); i++) {
                    if (i > 0) summary.append(", ");
                    summary.append("#").append(matches.get(i).index);
                }
                if (matches.size() > 1) {
                    summary.append(" (多个配置共享该尺寸/刷新率)");
                }
            }
        } else {
            summary.append("\nQTI probe unavailable: ")
                    .append(emptyFallback(diagnostics.rawError(), "unknown error"));
        }
        Integer qtiConfigIndex = matches.isEmpty() ? null : matches.get(0).index;
        return new OverridePlan(propertyValue, summary.toString(), qtiConfigIndex);
    }

    /**
     * 应用高通模式覆盖：设置属性 -> 校验 -> 尝试 set-active。
     * 返回 ApplyResult（propertyValue / setActiveOk / summary）。
     */
    public static ApplyResult apply(Context context, Display display, Display.Mode mode,
                                    Runner runner) throws Exception {
        Diagnostics diagnostics = diag(context, runner);
        OverridePlan plan = buildPlan(mode, diagnostics);
        String propertyValue = plan.propertyValue;

        String command = "setprop " + shellQuote(QUALCOMM_MODE_OVERRIDE_PROP)
                + " " + shellQuote(propertyValue)
                + " && actual=$(getprop " + shellQuote(QUALCOMM_MODE_OVERRIDE_PROP) + ")"
                + " && [ \"$actual\" = " + shellQuote(propertyValue) + " ]"
                + " || { echo \"Failed to set " + QUALCOMM_MODE_OVERRIDE_PROP
                + ", current value: $actual\"; exit 5; }";
        Result result = run(command, runner);
        if (result.exitCode != 0) {
            throw new IllegalStateException(result.output.trim());
        }

        boolean setActiveOk = false;
        if (plan.qtiConfigIndex != null
                && diagnostics.symbols.getOrDefault("setActiveConfig", false)) {
            try {
                setActiveOk = setActive(context, plan.qtiConfigIndex, runner);
            } catch (Exception ignored) {
            }
        }
        return new ApplyResult(propertyValue, setActiveOk, plan.diagnosticsSummary);
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static String extractJsonObject(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) {
            return "";
        }
        return output.substring(start, end + 1);
    }

    public static String formatMode(Display.Mode mode) {
        return "#" + mode.getModeId()
                + "  " + mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
                + " @ " + formatRefresh(mode.getRefreshRate()) + " Hz";
    }

    public static String modeSpec(Display.Mode mode) {
        return mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
                + "@" + Math.round(mode.getRefreshRate());
    }

    private static String formatRefresh(float refresh) {
        int rounded = Math.round(refresh);
        if (Math.abs(refresh - rounded) < 0.01f) {
            return String.valueOf(rounded);
        }
        return String.format(Locale.US, "%.2f", refresh);
    }

    private static String emptyFallback(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }

    public static String trimForDisplay(String value, int maxChars) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() <= maxChars) return clean;
        return clean.substring(0, Math.max(0, maxChars - 16)) + " ... truncated";
    }

    /** 命令执行结果。 */
    public static final class Result {
        public final int exitCode;
        public final String output;

        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    /** 计划：属性值 / 摘要 / 可选的 QTI 配置索引。 */
    public static final class OverridePlan {
        public final String propertyValue;
        public final String diagnosticsSummary;
        public final Integer qtiConfigIndex;

        public OverridePlan(String propertyValue, String diagnosticsSummary, Integer qtiConfigIndex) {
            this.propertyValue = propertyValue;
            this.diagnosticsSummary = diagnosticsSummary;
            this.qtiConfigIndex = qtiConfigIndex;
        }
    }

    /** apply 的结果。 */
    public static final class ApplyResult {
        public final String propertyValue;
        public final boolean setActiveOk;
        public final String diagnosticsSummary;

        public ApplyResult(String propertyValue, boolean setActiveOk, String diagnosticsSummary) {
            this.propertyValue = propertyValue;
            this.setActiveOk = setActiveOk;
            this.diagnosticsSummary = diagnosticsSummary;
        }
    }

    /** 探针诊断模型。 */
    public static final class Diagnostics {
        public boolean probed;
        public boolean available;
        public String library = "";
        public String error = "";
        public String rawOutput = "";
        public String extraDiagnostics = "";
        public int commandExitCode = 0;

        public final List<String> loadErrors = new ArrayList<>();
        public final List<String> missingSymbols = new ArrayList<>();
        public final Map<String, Boolean> symbols = new HashMap<>();
        public final List<QtiConfig> configs = new ArrayList<>();
        public final List<DrmConnector> drmConnectors = new ArrayList<>();

        public Boolean connected;
        public int connectedErr = Integer.MIN_VALUE;
        public Integer configCount;
        public int configCountErr = Integer.MIN_VALUE;
        public Integer activeConfig;
        public int activeConfigErr = Integer.MIN_VALUE;
        public boolean drmAvailable;
        public String drmPath = "";
        public String drmError = "";

        public static Diagnostics notProbed() {
            Diagnostics diagnostics = new Diagnostics();
            diagnostics.probed = false;
            diagnostics.error = "not probed";
            return diagnostics;
        }

        public static Diagnostics failed(String error) {
            Diagnostics diagnostics = new Diagnostics();
            diagnostics.probed = true;
            diagnostics.available = false;
            diagnostics.error = error == null ? "unknown error" : error;
            return diagnostics;
        }

        public static Diagnostics fromJson(String json, String rawOutput, int exitCode) throws Exception {
            JSONObject root = new JSONObject(json);
            Diagnostics diagnostics = new Diagnostics();
            diagnostics.probed = true;
            diagnostics.available = root.optBoolean("ok", false);
            diagnostics.library = root.optString("library", "");
            diagnostics.error = root.optString("error", "");
            diagnostics.rawOutput = rawOutput == null ? "" : rawOutput;
            diagnostics.commandExitCode = exitCode;

            readStringArray(root.optJSONArray("loadErrors"), diagnostics.loadErrors);
            readStringArray(root.optJSONArray("missingSymbols"), diagnostics.missingSymbols);

            JSONObject symbolsObject = root.optJSONObject("symbols");
            if (symbolsObject != null) {
                JSONArray names = symbolsObject.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String name = names.optString(i, "");
                        if (!name.isEmpty()) {
                            diagnostics.symbols.put(name, symbolsObject.optBoolean(name, false));
                        }
                    }
                }
            }

            JSONObject connectedObject = root.optJSONObject("connected");
            if (connectedObject != null) {
                diagnostics.connectedErr = connectedObject.optInt("err", Integer.MIN_VALUE);
                diagnostics.connected = connectedObject.optBoolean("value", false);
            }

            JSONObject countObject = root.optJSONObject("configCount");
            if (countObject != null) {
                diagnostics.configCountErr = countObject.optInt("err", Integer.MIN_VALUE);
                diagnostics.configCount = countObject.optInt("value", 0);
            }

            JSONObject activeObject = root.optJSONObject("activeConfig");
            if (activeObject != null) {
                diagnostics.activeConfigErr = activeObject.optInt("err", Integer.MIN_VALUE);
                diagnostics.activeConfig = activeObject.optInt("value", -1);
            }

            JSONArray configsArray = root.optJSONArray("configs");
            if (configsArray != null) {
                for (int i = 0; i < configsArray.length(); i++) {
                    JSONObject configObject = configsArray.optJSONObject(i);
                    if (configObject != null) {
                        diagnostics.configs.add(QtiConfig.fromJson(configObject));
                    }
                }
            }

            JSONObject drmObject = root.optJSONObject("drm");
            if (drmObject != null) {
                diagnostics.drmAvailable = drmObject.optBoolean("ok", false);
                diagnostics.drmPath = drmObject.optString("path", "");
                diagnostics.drmError = drmObject.optString("error", "");
                JSONArray connectorsArray = drmObject.optJSONArray("connectors");
                if (connectorsArray != null) {
                    for (int i = 0; i < connectorsArray.length(); i++) {
                        JSONObject connectorObject = connectorsArray.optJSONObject(i);
                        if (connectorObject != null) {
                            diagnostics.drmConnectors.add(DrmConnector.fromJson(connectorObject));
                        }
                    }
                }
            }
            return diagnostics;
        }

        private static void readStringArray(JSONArray array, List<String> out) {
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) out.add(value);
            }
        }

        public List<QtiConfig> matchingConfigs(Display.Mode mode) {
            List<QtiConfig> matches = new ArrayList<>();
            if (mode == null) return matches;
            int roundedRefresh = Math.round(mode.getRefreshRate());
            for (QtiConfig config : configs) {
                if (config.err == 0
                        && config.width == mode.getPhysicalWidth()
                        && config.height == mode.getPhysicalHeight()
                        && (Math.abs(config.refresh - mode.getRefreshRate()) <= REFRESH_MATCH_TOLERANCE_HZ
                        || config.roundedRefresh == roundedRefresh)) {
                    matches.add(config);
                }
            }
            return matches;
        }

        public DrmMode bestMatchingDrmMode(Display.Mode mode) {
            if (mode == null) return null;
            List<DrmMode> candidates = new ArrayList<>();
            for (DrmConnector connector : drmConnectors) {
                if (!connector.connectedExternal()) continue;
                for (DrmMode drmMode : connector.modes) {
                    if (drmMode.matches(mode)) {
                        candidates.add(drmMode);
                    }
                }
            }
            if (candidates.isEmpty()) {
                for (DrmConnector connector : drmConnectors) {
                    if (!connector.connected()) continue;
                    for (DrmMode drmMode : connector.modes) {
                        if (drmMode.matches(mode)) {
                            candidates.add(drmMode);
                        }
                    }
                }
            }
            if (candidates.isEmpty()) return null;
            candidates.sort(Comparator
                    .comparingInt((DrmMode drmMode) -> drmMode.selector > 0 ? 0 : 1)
                    .thenComparingInt(drmMode -> Math.abs(drmMode.refresh - Math.round(mode.getRefreshRate())))
                    .thenComparingInt(drmMode -> drmMode.index));
            return candidates.get(0);
        }

        public String statusLine() {
            if (!probed) return "not probed";
            if (available) {
                return "available"
                        + (commandExitCode == 0 ? "" : " (exit=" + commandExitCode + ")");
            }
            String reason = rawError();
            if (reason.isEmpty()) reason = "unavailable";
            return reason;
        }

        public String connectedLine() {
            if (connected == null) return "unknown";
            return connected + errSuffix(connectedErr);
        }

        public String configCountLine() {
            if (configCount == null) return "unknown";
            return configCount + errSuffix(configCountErr);
        }

        public String activeConfigLine() {
            if (activeConfig == null) return "unknown";
            return "#" + activeConfig + errSuffix(activeConfigErr);
        }

        public String symbolLine() {
            if (symbols.isEmpty()) return "unknown";
            int ok = 0;
            for (Boolean value : symbols.values()) {
                if (Boolean.TRUE.equals(value)) ok++;
            }
            return ok + "/" + symbols.size() + " present";
        }

        public String drmLine() {
            if (!drmAvailable) {
                return emptyStatic(drmError, "unavailable");
            }
            int connectedExternal = 0;
            int modeCount = 0;
            for (DrmConnector connector : drmConnectors) {
                if (connector.connectedExternal()) {
                    connectedExternal++;
                    modeCount += connector.modes.size();
                }
            }
            return emptyStatic(drmPath, "/dev/dri/card0")
                    + " connectedExternal=" + connectedExternal
                    + " modes=" + modeCount;
        }

        public String rawError() {
            if (error != null && !error.trim().isEmpty()) return error.trim();
            if (!missingSymbols.isEmpty()) return missingSymbols.get(0);
            if (!loadErrors.isEmpty()) return loadErrors.get(0);
            return "";
        }

        private String errSuffix(int err) {
            if (err == Integer.MIN_VALUE || err == 0) return "";
            return " err=" + err;
        }

        private static String emptyStatic(String value, String fallback) {
            if (value == null || value.trim().isEmpty()) return fallback;
            return value;
        }
    }

    /** 单个 QTI 配置。 */
    public static final class QtiConfig {
        public int index;
        public int err;
        public int width;
        public int height;
        public double refresh;
        public int roundedRefresh;
        public long vsyncPeriodNs;
        public double xdpi;
        public double ydpi;
        public int panelType;
        public boolean yuv;
        public boolean active;
        public Integer switchErr;
        public Boolean switchSupported;

        public static QtiConfig fromJson(JSONObject object) {
            QtiConfig config = new QtiConfig();
            config.index = object.optInt("index", -1);
            config.err = object.optInt("err", Integer.MIN_VALUE);
            config.width = object.optInt("width", 0);
            config.height = object.optInt("height", 0);
            config.refresh = object.optDouble("refresh", 0.0d);
            config.roundedRefresh = object.optInt("roundedRefresh", (int) Math.round(config.refresh));
            config.vsyncPeriodNs = object.optLong("vsyncPeriodNs", 0L);
            config.xdpi = object.optDouble("xdpi", 0.0d);
            config.ydpi = object.optDouble("ydpi", 0.0d);
            config.panelType = object.optInt("panelType", 0);
            config.yuv = object.optBoolean("isYuv", false);
            config.active = object.optBoolean("active", false);
            JSONObject switchObject = object.optJSONObject("switchFromActive");
            if (switchObject != null) {
                config.switchErr = switchObject.optInt("err", Integer.MIN_VALUE);
                config.switchSupported = switchObject.optBoolean("supported", false);
            }
            return config;
        }

        public String format(Display.Mode current) {
            StringBuilder out = new StringBuilder();
            out.append("#").append(index);
            if (err != 0) {
                out.append(" err=").append(err);
                return out.toString();
            }
            out.append("  ")
                    .append(width).append("x").append(height)
                    .append(" @ ").append(String.format(Locale.US, "%.2f", refresh)).append(" Hz");
            if (active) out.append(" active");
            if (current != null
                    && width == current.getPhysicalWidth()
                    && height == current.getPhysicalHeight()
                    && Math.abs(refresh - current.getRefreshRate()) <= REFRESH_MATCH_TOLERANCE_HZ) {
                out.append(" Android-current");
            }
            if (switchSupported != null) {
                out.append(" switch=").append(switchSupported);
                if (switchErr != null && switchErr != 0) out.append(" err=").append(switchErr);
            }
            out.append(" vsync=").append(vsyncPeriodNs).append("ns")
                    .append(" dpi=").append(String.format(Locale.US, "%.1f", xdpi))
                    .append("x").append(String.format(Locale.US, "%.1f", ydpi))
                    .append(" panel=").append(panelType)
                    .append(" yuv=").append(yuv);
            return out.toString();
        }
    }

    /** DRM 连接器。 */
    public static final class DrmConnector {
        public int id;
        public String name = "";
        public int type;
        public int typeId;
        public String connection = "";
        public boolean external;
        public int encoder;
        public String error = "";
        public final List<DrmMode> modes = new ArrayList<>();

        public static DrmConnector fromJson(JSONObject object) {
            DrmConnector connector = new DrmConnector();
            connector.id = object.optInt("id", -1);
            connector.name = object.optString("name", "");
            connector.type = object.optInt("type", 0);
            connector.typeId = object.optInt("typeId", 0);
            connector.connection = object.optString("connection", "");
            connector.external = object.optBoolean("external", false);
            connector.encoder = object.optInt("encoder", 0);
            connector.error = object.optString("err", "");
            JSONArray modesArray = object.optJSONArray("modes");
            if (modesArray != null) {
                for (int i = 0; i < modesArray.length(); i++) {
                    JSONObject modeObject = modesArray.optJSONObject(i);
                    if (modeObject != null) {
                        DrmMode mode = DrmMode.fromJson(modeObject);
                        mode.connectorName = connector.name;
                        connector.modes.add(mode);
                    }
                }
            }
            return connector;
        }

        public boolean connected() {
            return "connected".equals(connection);
        }

        public boolean connectedExternal() {
            return connected() && external;
        }

        public String format() {
            StringBuilder out = new StringBuilder();
            out.append(empty(name, "connector#" + id))
                    .append(" ").append(connection)
                    .append(" type=").append(type)
                    .append(":").append(typeId)
                    .append(" encoder=").append(encoder)
                    .append(" modes=").append(modes.size());
            if (!error.isEmpty()) out.append(" err=").append(error);
            return out.toString();
        }

        private static String empty(String value, String fallback) {
            if (value == null || value.trim().isEmpty()) return fallback;
            return value;
        }
    }

    /** DRM 模式。 */
    public static final class DrmMode {
        public int index;
        public String connectorName = "";
        public String name = "";
        public int width;
        public int height;
        public int refresh;
        public int clock;
        public int flags;
        public int type;
        public int selector;

        public static DrmMode fromJson(JSONObject object) {
            DrmMode mode = new DrmMode();
            mode.index = object.optInt("index", -1);
            mode.name = object.optString("name", "");
            mode.width = object.optInt("width", 0);
            mode.height = object.optInt("height", 0);
            mode.refresh = object.optInt("refresh", 0);
            mode.clock = object.optInt("clock", 0);
            mode.flags = object.optInt("flags", 0);
            mode.type = object.optInt("type", 0);
            mode.selector = object.optInt("selector", mode.type);
            return mode;
        }

        public boolean matches(Display.Mode mode) {
            if (mode == null) return false;
            return width == mode.getPhysicalWidth()
                    && height == mode.getPhysicalHeight()
                    && (Math.abs(refresh - mode.getRefreshRate()) <= REFRESH_MATCH_TOLERANCE_HZ
                    || refresh == Math.round(mode.getRefreshRate()));
        }

        public String format(Display.Mode current) {
            StringBuilder out = new StringBuilder();
            out.append(connectorName)
                    .append("[")
                    .append(index)
                    .append("] ")
                    .append(width)
                    .append("x")
                    .append(height)
                    .append(" @ ")
                    .append(refresh)
                    .append(" Hz");
            if (current != null && matches(current)) out.append(" Android-current");
            out.append(" flags=0x")
                    .append(Integer.toHexString(flags))
                    .append(" type=0x")
                    .append(Integer.toHexString(type))
                    .append(" selector=")
                    .append(selector)
                    .append(" clock=")
                    .append(clock);
            if (!name.isEmpty()) {
                out.append(" name=").append(name);
            }
            return out.toString();
        }

        public String diagnosticLabel() {
            return connectorName + "[" + index + "]"
                    + " type=0x" + Integer.toHexString(type)
                    + " flags=0x" + Integer.toHexString(flags)
                    + " selector=" + selector;
        }
    }
}
