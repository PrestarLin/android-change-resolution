package com.taowen.androidchangeresolution;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements DisplayManager.DisplayListener {
    private static final String QUALCOMM_MODE_OVERRIDE_PROP = "vendor.display.hdmi_cfg_idx";
    private static final String QTI_PROBE_ASSET = "native/arm64-v8a/qti-display-probe";
    private static final String QTI_PROBE_FILE_NAME = "qti-display-probe";
    private static final double REFRESH_MATCH_TOLERANCE_HZ = 0.5d;
    private static final int ROOT_TIMEOUT_SECONDS = 30;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<Integer, Display.Mode> selectedModes = new HashMap<>();

    private DisplayManager displayManager;
    private LinearLayout displayContainer;
    private TextView statusView;
    private QualcommDiagnostics qualcommDiagnostics = QualcommDiagnostics.notProbed();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        setContentView(createContentView());
        refreshDisplays("Ready");
    }

    @Override
    protected void onStart() {
        super.onStart();
        displayManager.registerDisplayListener(this, mainHandler);
    }

    @Override
    protected void onStop() {
        displayManager.unregisterDisplayListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onDisplayAdded(int displayId) {
        refreshDisplays("Display added: " + displayId);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        selectedModes.remove(displayId);
        refreshDisplays("Display removed: " + displayId);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        refreshDisplays("Display changed: " + displayId);
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(color(R.color.app_background));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(20));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Change Resolution", 22, color(R.color.text_primary), true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refreshButton = compactButton("Refresh");
        refreshButton.setOnClickListener(v -> refreshDisplays("Refreshed"));
        header.addView(refreshButton);

        statusView = text("", 13, color(R.color.text_secondary), false);
        statusView.setPadding(0, dp(8), 0, dp(12));
        root.addView(statusView);

        displayContainer = new LinearLayout(this);
        displayContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(displayContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return scrollView;
    }

    private void refreshDisplays(String status) {
        Display[] displays = displayManager.getDisplays();
        statusView.setText(status + " · displays=" + displays.length);
        displayContainer.removeAllViews();

        for (Display display : displays) {
            ensureSelectedMode(display);
            displayContainer.addView(createDisplayPanel(display));
        }
    }

    private void ensureSelectedMode(Display display) {
        Display.Mode selected = selectedModes.get(display.getDisplayId());
        if (selected == null || !containsMode(display.getSupportedModes(), selected)) {
            selectedModes.put(display.getDisplayId(), display.getMode());
        }
    }

    private View createDisplayPanel(Display display) {
        boolean external = display.getDisplayId() != Display.DEFAULT_DISPLAY;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        panel.setBackground(panelBackground());

        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.setMargins(0, 0, 0, dp(12));
        panel.setLayoutParams(panelParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(titleRow);

        TextView name = text(display.getName(), 18, color(R.color.text_primary), true);
        titleRow.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView type = pill(external ? "External" : "Default");
        titleRow.addView(type);

        addKeyValue(panel, "Display ID", String.valueOf(display.getDisplayId()));
        addOptionalDisplayValue(panel, "Unique ID", display, "getUniqueId");
        addOptionalDisplayValue(panel, "Address", display, "getAddress");
        addKeyValue(panel, "State", stateName(display.getState()));
        addKeyValue(panel, "Rotation", rotationName(display.getRotation()));
        addKeyValue(panel, "Flags", flagsName(display.getFlags()));
        addOptionalDisplayValue(panel, "Owner package", display, "getOwnerPackageName");
        addOptionalDisplayValue(panel, "Owner uid", display, "getOwnerUid");
        addKeyValue(panel, "Valid", String.valueOf(display.isValid()));

        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        if (external) {
            addKeyValue(panel, "Metrics",
                    metrics.widthPixels + "x" + metrics.heightPixels +
                            " px, densityDpi=" + metrics.densityDpi +
                            ", density=" + formatFloat(metrics.density));
        } else {
            addKeyValue(panel, "Density",
                    "densityDpi=" + metrics.densityDpi +
                            ", density=" + formatFloat(metrics.density));
        }
        addKeyValue(panel, "DPI",
                "xdpi=" + formatFloat(metrics.xdpi) + ", ydpi=" + formatFloat(metrics.ydpi));

        if (!external) {
            return panel;
        }

        Display.Mode current = display.getMode();
        addSectionLabel(panel, "Current mode");
        addKeyValue(panel, "Mode", formatMode(current));

        addQualcommDiagnostics(panel, current);

        addSectionLabel(panel, "Supported modes");
        RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        modeGroup.setPadding(0, dp(2), 0, dp(8));
        panel.addView(modeGroup);

        Map<Integer, Display.Mode> radioModes = new HashMap<>();
        Display.Mode selected = selectedModes.get(display.getDisplayId());
        List<Display.Mode> modes = sortedModes(display.getSupportedModes());
        for (Display.Mode mode : modes) {
            RadioButton radio = new RadioButton(this);
            int radioId = View.generateViewId();
            radio.setId(radioId);
            radio.setText(formatMode(mode) + (sameModeId(mode, current) ? "  current" : ""));
            radio.setTextColor(color(R.color.text_primary));
            radio.setTextSize(14);
            radio.setPadding(0, dp(2), 0, dp(2));
            radioModes.put(radioId, mode);
            modeGroup.addView(radio);
            if (sameModeId(mode, selected)) {
                modeGroup.check(radioId);
            }
        }

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            Display.Mode mode = radioModes.get(checkedId);
            if (mode != null) {
                selectedModes.put(display.getDisplayId(), mode);
            }
        });

        Button applyButton = primaryButton("Set selected mode");
        applyButton.setOnClickListener(v -> {
            Display.Mode mode = selectedModes.get(display.getDisplayId());
            if (mode == null) {
                showMessage("No mode selected", "Select one display mode first.");
                return;
            }
            beginForceMode(display, mode);
        });
        panel.addView(applyButton);

        return panel;
    }

    private void addQualcommDiagnostics(LinearLayout panel, Display.Mode current) {
        addSectionLabel(panel, "Qualcomm composer");
        addKeyValue(panel, "Probe", qualcommDiagnostics.statusLine());
        if (qualcommDiagnostics.probed) {
            addKeyValue(panel, "DRM", qualcommDiagnostics.drmLine());
            for (DrmConnector connector : qualcommDiagnostics.drmConnectors) {
                addKeyValue(panel, "DRM connector", connector.format());
                if (connector.connectedExternal()) {
                    for (DrmMode mode : connector.modes) {
                        addKeyValue(panel, "DRM mode", mode.format(current));
                    }
                }
            }
        }
        if (qualcommDiagnostics.available) {
            addKeyValue(panel, "Library", emptyFallback(qualcommDiagnostics.library, "unknown"));
            addKeyValue(panel, "External connected", qualcommDiagnostics.connectedLine());
            addKeyValue(panel, "Active config", qualcommDiagnostics.activeConfigLine());
            addKeyValue(panel, "Config count", qualcommDiagnostics.configCountLine());
            addKeyValue(panel, "Symbols", qualcommDiagnostics.symbolLine());
            if (!qualcommDiagnostics.missingSymbols.isEmpty()) {
                addKeyValue(panel, "Missing symbols", joinLimited(qualcommDiagnostics.missingSymbols, 3));
            }
            if (!qualcommDiagnostics.extraDiagnostics.isEmpty()) {
                addKeyValue(panel, "System",
                        trimForDisplay(qualcommDiagnostics.extraDiagnostics.replace("\n", " | "), 700));
            }
            for (QualcommConfig config : qualcommDiagnostics.configs) {
                addKeyValue(panel, "QTI config", config.format(current));
            }
        } else if (!qualcommDiagnostics.rawError().isEmpty()) {
            addKeyValue(panel, "Unavailable", qualcommDiagnostics.rawError());
        }

        Button probeButton = primaryButton("Probe Qualcomm configs");
        probeButton.setOnClickListener(v -> beginQualcommProbe());
        panel.addView(probeButton);
    }

    private void beginQualcommProbe() {
        setBusy("Requesting root for Qualcomm diagnostics...");
        executor.execute(() -> {
            try {
                ensureRootAvailable();
                QualcommDiagnostics diagnostics = collectQualcommDiagnostics();
                mainHandler.post(() -> {
                    qualcommDiagnostics = diagnostics;
                    refreshDisplays("Qualcomm diagnostics updated");
                });
            } catch (Exception e) {
                QualcommDiagnostics diagnostics = QualcommDiagnostics.failed(e.getMessage());
                mainHandler.post(() -> {
                    qualcommDiagnostics = diagnostics;
                    refreshDisplays("Qualcomm diagnostics failed");
                    showError("Qualcomm diagnostics failed", e);
                });
            }
        });
    }

    private void beginForceMode(Display display, Display.Mode mode) {
        if (display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            showMessage("Unsupported target", "Only external displays are handled by this build.");
            return;
        }

        setBusy("Requesting root...");
        executor.execute(() -> {
            try {
                ensureRootAvailable();
                applyQualcommModeOverride(display, mode);
            } catch (Exception e) {
                mainHandler.post(() -> showError("Mode override failed", e));
            }
        });
    }

    private void applyQualcommModeOverride(Display display, Display.Mode mode) throws Exception {
        QualcommDiagnostics diagnostics = collectQualcommDiagnostics();
        qualcommDiagnostics = diagnostics;

        ForceModePlan plan = createForceModePlan(mode, diagnostics);
        String propertyValue = plan.propertyValue;
        String command = "setprop " + shellQuote(QUALCOMM_MODE_OVERRIDE_PROP)
                + " " + shellQuote(propertyValue)
                + " && actual=$(getprop " + shellQuote(QUALCOMM_MODE_OVERRIDE_PROP) + ")"
                + " && [ \"$actual\" = " + shellQuote(propertyValue) + " ]"
                + " || { echo \"Failed to set " + QUALCOMM_MODE_OVERRIDE_PROP
                + ", current value: $actual\"; exit 5; }";
        CommandResult result = runRootCommand(command);
        if (result.exitCode != 0) {
            throw new IllegalStateException(result.output.trim());
        }

        mainHandler.post(() -> {
            statusView.setText("Configured " + modeSpec(mode));
            refreshDisplays("Configured " + modeSpec(mode));
            showMessage("Mode override configured",
                    "Set " + QUALCOMM_MODE_OVERRIDE_PROP + "=" + propertyValue
                            + " for display " + display.getDisplayId() + ".\n\n"
                            + plan.diagnosticsSummary + "\n\n"
                            + "Replug Type-C to make Qualcomm composer initialize the external display with this mode.");
        });
    }

    private ForceModePlan createForceModePlan(Display.Mode mode, QualcommDiagnostics diagnostics) {
        List<QualcommConfig> matches = diagnostics.matchingConfigs(mode);
        DrmMode drmMode = diagnostics.bestMatchingDrmMode(mode);
        if (drmMode == null || drmMode.selector <= 0) {
            throw new IllegalStateException(
                    "Could not derive Qualcomm mode selector from DRM connector modes.\n\n"
                            + "Probe status: " + diagnostics.statusLine() + "\n"
                            + "DRM: " + diagnostics.drmLine() + "\n"
                            + "Selected Android mode: " + formatMode(mode));
        }

        int width = drmMode.width;
        int height = drmMode.height;
        int refresh = drmMode.refresh;
        int selector = drmMode.selector;
        if (!matches.isEmpty()) {
            QualcommConfig config = matches.get(0);
            if (config.width == width && config.height == height) {
                refresh = config.roundedRefresh;
            }
        }

        String propertyValue = width + ":" + height + ":" + refresh + ":"
                + selector;
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
                    summary.append(" (multiple configs share this size/refresh)");
                }
            }
        } else {
            summary.append("\nQTI probe unavailable: ")
                    .append(emptyFallback(diagnostics.rawError(), "unknown error"));
        }
        return new ForceModePlan(propertyValue, summary.toString());
    }

    private void ensureRootAvailable() throws Exception {
        CommandResult rootCheck = runRootCommand("id");
        if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
            throw new IllegalStateException(
                    "Root was not granted to this app.\n\n"
                            + "Magisk's standard flow is: the app executes su, "
                            + "then Magisk asks whether to grant root to this package.\n\n"
                            + "Open Magisk > Superuser and allow Change Resolution. "
                            + "Also check that Superuser access is enabled for Apps.\n\n"
                            + rootCheck.output.trim());
        }
    }

    private CommandResult runRootCommand(String command) throws Exception {
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
            return new CommandResult(124, output.toString(StandardCharsets.UTF_8.name()) + "\nTimed out");
        }
        reader.join(1000);
        return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8.name()));
    }

    private QualcommDiagnostics collectQualcommDiagnostics() throws Exception {
        String probePath = ensureQtiProbeExecutable();
        String command = "chmod 700 " + shellQuote(probePath)
                + " && LD_LIBRARY_PATH=/vendor/lib64:/system_ext/lib64 "
                + shellQuote(probePath) + " diag external";
        CommandResult result = runRootCommand(command);

        String json = extractJsonObject(result.output);
        QualcommDiagnostics diagnostics;
        if (json.isEmpty()) {
            diagnostics = QualcommDiagnostics.failed(
                    "qti-display-probe did not return JSON. exit=" + result.exitCode
                            + " output=" + trimForDisplay(result.output, 600));
        } else {
            diagnostics = QualcommDiagnostics.fromJson(json, result.output, result.exitCode);
        }
        diagnostics.extraDiagnostics = collectRootDisplayDiagnostics();
        return diagnostics;
    }

    private String ensureQtiProbeExecutable() throws Exception {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                arm64 = true;
                break;
            }
        }
        if (!arm64) {
            throw new IllegalStateException("This build only includes the arm64-v8a Qualcomm probe.");
        }

        File dir = new File(getCodeCacheDir(), "native");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Failed to create " + dir);
        }
        File out = new File(dir, QTI_PROBE_FILE_NAME);
        try (InputStream in = getAssets().open(QTI_PROBE_ASSET);
             FileOutputStream fileOut = new FileOutputStream(out, false)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                fileOut.write(buffer, 0, n);
            }
        }
        if (!out.setExecutable(true, true)) {
            throw new IllegalStateException("Failed to mark qti-display-probe executable");
        }
        out.setReadable(true, true);
        return out.getAbsolutePath();
    }

    private String collectRootDisplayDiagnostics() {
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
            CommandResult result = runRootCommand(command);
            String value = result.output.trim();
            if (result.exitCode != 0) {
                value = "exit=" + result.exitCode + " " + value;
            }
            return trimForDisplay(value, 1600);
        } catch (Exception e) {
            return "extra diagnostics failed: " + e.getMessage();
        }
    }

    private String extractJsonObject(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) {
            return "";
        }
        return output.substring(start, end + 1);
    }

    private List<Display.Mode> sortedModes(Display.Mode[] modes) {
        List<Display.Mode> out = new ArrayList<>();
        Collections.addAll(out, modes);
        out.sort(Comparator
                .comparingInt(Display.Mode::getPhysicalWidth).reversed()
                .thenComparing(Comparator.comparingInt(Display.Mode::getPhysicalHeight).reversed())
                .thenComparing(Comparator.comparingDouble(Display.Mode::getRefreshRate).reversed())
                .thenComparingInt(Display.Mode::getModeId));
        return out;
    }

    private boolean containsMode(Display.Mode[] modes, Display.Mode target) {
        for (Display.Mode mode : modes) {
            if (sameModeId(mode, target)) return true;
        }
        return false;
    }

    private boolean sameModeId(Display.Mode a, Display.Mode b) {
        return a != null && b != null && a.getModeId() == b.getModeId();
    }

    private String formatMode(Display.Mode mode) {
        return "#" + mode.getModeId()
                + "  " + mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
                + " @ " + formatRefresh(mode.getRefreshRate()) + " Hz";
    }

    private String modeSpec(Display.Mode mode) {
        return mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
                + "@" + Math.round(mode.getRefreshRate());
    }

    private String formatRefresh(float refresh) {
        int rounded = Math.round(refresh);
        if (Math.abs(refresh - rounded) < 0.01f) {
            return String.valueOf(rounded);
        }
        return String.format(Locale.US, "%.2f", refresh);
    }

    private String formatFloat(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String emptyFallback(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }

    private String joinLimited(List<String> values, int maxCount) {
        if (values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int count = Math.min(values.size(), maxCount);
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append("; ");
            out.append(values.get(i));
        }
        if (values.size() > count) {
            out.append("; +").append(values.size() - count).append(" more");
        }
        return trimForDisplay(out.toString(), 600);
    }

    private String trimForDisplay(String value, int maxChars) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() <= maxChars) return clean;
        return clean.substring(0, Math.max(0, maxChars - 16)) + " ... truncated";
    }

    private String stateName(int state) {
        switch (state) {
            case Display.STATE_OFF:
                return "OFF";
            case Display.STATE_ON:
                return "ON";
            case Display.STATE_DOZE:
                return "DOZE";
            case Display.STATE_DOZE_SUSPEND:
                return "DOZE_SUSPEND";
            case Display.STATE_VR:
                return "VR";
            case Display.STATE_ON_SUSPEND:
                return "ON_SUSPEND";
            case Display.STATE_UNKNOWN:
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    private String rotationName(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0:
                return "0";
            case Surface.ROTATION_90:
                return "90";
            case Surface.ROTATION_180:
                return "180";
            case Surface.ROTATION_270:
                return "270";
            default:
                return String.valueOf(rotation);
        }
    }

    private String flagsName(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) names.add("PROTECTED");
        if ((flags & Display.FLAG_SECURE) != 0) names.add("SECURE");
        if ((flags & Display.FLAG_PRIVATE) != 0) names.add("PRIVATE");
        if ((flags & Display.FLAG_PRESENTATION) != 0) names.add("PRESENTATION");
        if ((flags & Display.FLAG_ROUND) != 0) names.add("ROUND");
        if (names.isEmpty()) names.add("none");
        return String.join(", ", names) + " (0x" + Integer.toHexString(flags) + ")";
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView pill(String value) {
        TextView view = text(value, 12, color(R.color.accent), true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setStroke(dp(1), color(R.color.accent));
        bg.setCornerRadius(dp(8));
        view.setBackground(bg);
        return view;
    }

    private Button compactButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setMinHeight(dp(36));
        button.setMinimumHeight(dp(36));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private Button primaryButton(String value) {
        Button button = compactButton(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void addSectionLabel(LinearLayout parent, String value) {
        TextView label = text(value, 14, color(R.color.text_primary), true);
        label.setPadding(0, dp(12), 0, dp(4));
        parent.addView(label);
    }

    private void addKeyValue(LinearLayout parent, String key, String value) {
        TextView row = text(key + ": " + value, 13, color(R.color.text_secondary), false);
        row.setPadding(0, dp(2), 0, dp(2));
        parent.addView(row);
    }

    private void addOptionalDisplayValue(LinearLayout parent, String key, Display display, String methodName) {
        try {
            Method method = Display.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(display);
            if (value != null) {
                addKeyValue(parent, key, String.valueOf(value));
            }
        } catch (Exception ignored) {
        }
    }

    private GradientDrawable panelBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.panel_background));
        bg.setStroke(dp(1), color(R.color.panel_border));
        bg.setCornerRadius(dp(8));
        return bg;
    }

    private void setBusy(String message) {
        statusView.setText(message);
    }

    private void showMessage(String title, String message) {
        statusView.setText(title);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showError(String title, Exception e) {
        showError(title, e, "");
    }

    private void showError(String title, Exception e, String suffix) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        showMessage(title, message + suffix);
    }

    private int color(int resId) {
        return getResources().getColor(resId, getTheme());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static final class ForceModePlan {
        final String propertyValue;
        final String diagnosticsSummary;

        ForceModePlan(String propertyValue, String diagnosticsSummary) {
            this.propertyValue = propertyValue;
            this.diagnosticsSummary = diagnosticsSummary;
        }
    }

    private static final class QualcommDiagnostics {
        boolean probed;
        boolean available;
        String library = "";
        String error = "";
        String rawOutput = "";
        String extraDiagnostics = "";
        int commandExitCode = 0;

        final List<String> loadErrors = new ArrayList<>();
        final List<String> missingSymbols = new ArrayList<>();
        final Map<String, Boolean> symbols = new HashMap<>();
        final List<QualcommConfig> configs = new ArrayList<>();
        final List<DrmConnector> drmConnectors = new ArrayList<>();

        Boolean connected;
        int connectedErr = Integer.MIN_VALUE;
        Integer configCount;
        int configCountErr = Integer.MIN_VALUE;
        Integer activeConfig;
        int activeConfigErr = Integer.MIN_VALUE;
        boolean drmAvailable;
        String drmPath = "";
        String drmError = "";

        static QualcommDiagnostics notProbed() {
            QualcommDiagnostics diagnostics = new QualcommDiagnostics();
            diagnostics.probed = false;
            diagnostics.error = "not probed";
            return diagnostics;
        }

        static QualcommDiagnostics failed(String error) {
            QualcommDiagnostics diagnostics = new QualcommDiagnostics();
            diagnostics.probed = true;
            diagnostics.available = false;
            diagnostics.error = error == null ? "unknown error" : error;
            return diagnostics;
        }

        static QualcommDiagnostics fromJson(String json, String rawOutput, int exitCode) throws Exception {
            JSONObject root = new JSONObject(json);
            QualcommDiagnostics diagnostics = new QualcommDiagnostics();
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
                        diagnostics.configs.add(QualcommConfig.fromJson(configObject));
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

        List<QualcommConfig> matchingConfigs(Display.Mode mode) {
            List<QualcommConfig> matches = new ArrayList<>();
            if (mode == null) return matches;
            int roundedRefresh = Math.round(mode.getRefreshRate());
            for (QualcommConfig config : configs) {
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

        DrmMode bestMatchingDrmMode(Display.Mode mode) {
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

        String statusLine() {
            if (!probed) return "not probed";
            if (available) {
                return "available"
                        + (commandExitCode == 0 ? "" : " (exit=" + commandExitCode + ")");
            }
            String reason = rawError();
            if (reason.isEmpty()) reason = "unavailable";
            return reason;
        }

        String connectedLine() {
            if (connected == null) return "unknown";
            return connected + errSuffix(connectedErr);
        }

        String configCountLine() {
            if (configCount == null) return "unknown";
            return configCount + errSuffix(configCountErr);
        }

        String activeConfigLine() {
            if (activeConfig == null) return "unknown";
            return "#" + activeConfig + errSuffix(activeConfigErr);
        }

        String symbolLine() {
            if (symbols.isEmpty()) return "unknown";
            int ok = 0;
            for (Boolean value : symbols.values()) {
                if (Boolean.TRUE.equals(value)) ok++;
            }
            return ok + "/" + symbols.size() + " present";
        }

        String drmLine() {
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

        String rawError() {
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

    private static final class QualcommConfig {
        int index;
        int err;
        int width;
        int height;
        double refresh;
        int roundedRefresh;
        long vsyncPeriodNs;
        double xdpi;
        double ydpi;
        int panelType;
        boolean yuv;
        boolean active;
        Integer switchErr;
        Boolean switchSupported;

        static QualcommConfig fromJson(JSONObject object) {
            QualcommConfig config = new QualcommConfig();
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

        String format(Display.Mode current) {
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

    private static final class DrmConnector {
        int id;
        String name = "";
        int type;
        int typeId;
        String connection = "";
        boolean external;
        int encoder;
        String error = "";
        final List<DrmMode> modes = new ArrayList<>();

        static DrmConnector fromJson(JSONObject object) {
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

        boolean connected() {
            return "connected".equals(connection);
        }

        boolean connectedExternal() {
            return connected() && external;
        }

        String format() {
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

    private static final class DrmMode {
        int index;
        String connectorName = "";
        String name = "";
        int width;
        int height;
        int refresh;
        int clock;
        int flags;
        int type;
        int selector;

        static DrmMode fromJson(JSONObject object) {
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

        boolean matches(Display.Mode mode) {
            if (mode == null) return false;
            return width == mode.getPhysicalWidth()
                    && height == mode.getPhysicalHeight()
                    && (Math.abs(refresh - mode.getRefreshRate()) <= REFRESH_MATCH_TOLERANCE_HZ
                    || refresh == Math.round(mode.getRefreshRate()));
        }

        String format(Display.Mode current) {
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

        String diagnosticLabel() {
            return connectorName + "[" + index + "]"
                    + " type=0x" + Integer.toHexString(type)
                    + " flags=0x" + Integer.toHexString(flags)
                    + " selector=" + selector;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

}
