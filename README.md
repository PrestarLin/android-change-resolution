# Android Change Resolution

Android display diagnostics app with an optional root-only action to set the next Qualcomm external-display mode.

Most of the app works without root. It uses Android public display APIs to list connected displays, display parameters, supported `Display.Mode` entries, and the currently active mode. Root is only requested when the user taps **Set selected mode** on an external display.

## Features

- List Android displays from `DisplayManager`.
- Show display id, name, state, rotation, flags, metrics, density, DPI, and active mode.
- Hide internal-panel resolution choices; internal displays are diagnostic-only.
- List supported modes for external displays.
- Optionally probe Qualcomm composer configs through a root-only native helper.
- Let the user choose an external-display mode and write Qualcomm's mode override property.
- Prompt the user to replug Type-C so Qualcomm composer applies the selected mode during hotplug initialization.

## Qualcomm Mode Override

The app uses this vendor property:

```sh
vendor.display.hdmi_cfg_idx=<width>:<height>:<refresh>:<selector>
```

Example:

```sh
su -c 'setprop vendor.display.hdmi_cfg_idx 1920:1080:120:10'
```

The fourth field is a Qualcomm SDM mode selector, not a config id. The app derives it from the matching connected DRM connector mode's low flag bits. On the tested monitor, `DP-1 1920x1080@120` reports `flags=0x80000a`, so the app writes `1920:1080:120:10`.

On the tested OnePlus 8T Qualcomm display stack, this property causes composer to initialize the external Type-C display at the selected mode after the cable is replugged. Android still sees the full supported mode list; this does not fake or filter EDID.

## Qualcomm Diagnostics

Normal display information comes from Android public APIs and does not require root.

When root is granted, the app also extracts and runs an arm64 native probe from the APK. The probe starts as a separate root process and attempts to `dlopen` Qualcomm displayconfig libraries:

- `libdisplayconfig.qti.so`
- `/vendor/lib64/libdisplayconfig.qti.so`
- `/system_ext/lib64/libdisplayconfig.system.qti.so`
- `/system_ext/lib64/libdisplayconfig.qti.so`

All Qualcomm calls are optional and tolerant of device differences. Missing libraries, missing mangled symbols, service errors, or unexpected ABI behavior are reported in the UI as diagnostics. They do not stop the non-root display listing from working.

The probe currently tries:

- `IsDisplayConnected(DISPLAY_EXTERNAL)`
- `GetConfigCount(DISPLAY_EXTERNAL)`
- `GetActiveConfig(DISPLAY_EXTERNAL)`
- `GetDisplayAttributes(config, DISPLAY_EXTERNAL)`
- `IsSupportedConfigSwitch(active, config)` when available
- DRM `/dev/dri/card0` connector and mode enumeration, including mode `flags`, `type`, and derived selector

The app also records root-side system diagnostics such as the current `vendor.display.hdmi_cfg_idx`, composer PID, and DRM DP/HDMI connector status/modes.

## Build

Requirements:

- Android SDK.
- Gradle.
- Android NDK with an arm64 clang toolchain.

Build the debug APK:

```sh
gradle :app:assembleDebug
```

The native probe is built from `app/src/main/cpp/qti_display_probe.cpp` and packaged as `assets/native/arm64-v8a/qti-display-probe`.

## Runtime Flow

Normal diagnostics:

1. App calls Android `DisplayManager`.
2. App renders display information and supported external-display modes.
3. No root is needed.

Set selected mode:

1. User selects a mode on an external display.
2. App asks `su` for root.
3. App runs the optional Qualcomm probe and records config diagnostics.
4. App writes `vendor.display.hdmi_cfg_idx`.
5. App verifies the property with `getprop`.
6. App asks the user to replug Type-C.

## Root Scope

Root is only used for Qualcomm diagnostics and the mode override action. The app does not disable SELinux, install Magisk modules, attach to composer, patch memory, inject code, or keep a background native process alive.

This is Qualcomm-specific. Unsupported devices still get the non-root diagnostics UI.
