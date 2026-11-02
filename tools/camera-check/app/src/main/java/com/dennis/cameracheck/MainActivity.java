package com.dennis.cameracheck;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Camera Check: reports what this phone lets an ordinary app use — every camera and
 * lens it exposes, manual ISO/shutter/focus ranges, RAW and full-resolution capture,
 * 10-bit/Ultra HDR, and the maker's own processing modes (Camera Extensions: Auto,
 * Bokeh, Face Retouch, HDR, Night). Read-only: it never takes a photo.
 */
public final class MainActivity extends Activity {
    private TextView text;
    private String report = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        LinearLayout buttons = new LinearLayout(this);
        Button share = new Button(this);
        share.setText("Share report");
        share.setOnClickListener(v -> share());
        Button save = new Button(this);
        save.setText("Save to Downloads");
        save.setOnClickListener(v -> save());
        buttons.addView(share);
        buttons.addView(save);
        root.addView(buttons);
        ScrollView scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11);
        text.setTextIsSelectable(true);
        scroll.addView(text);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            text.setText("Allow camera access so the full details can be read (no photo is taken).");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            run();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        run();
    }

    private void run() {
        text.setText("Reading cameras…");
        new Thread(() -> {
            String out;
            try { out = build(); } catch (Throwable t) { out = "Camera Check stopped early: " + t + "\n\n" + sb; }
            final String done = out;
            report = done;
            runOnUiThread(() -> text.setText(done));
        }).start();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Camera Check — " + Build.MANUFACTURER + " " + Build.MODEL);
        i.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(i, "Share Camera Check report"));
    }

    private void save() {
        try {
            ContentValues v = new ContentValues();
            String name = "CameraCheck_" + Build.MODEL.replaceAll("[^A-Za-z0-9]+", "_") + ".txt";
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("could not create the file");
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                o.write(report.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Saved to Downloads/" + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ report

    private final StringBuilder sb = new StringBuilder();
    private final List<String> summary = new ArrayList<>();

    private void line(String s) { sb.append(s).append('\n'); }

    private String build() throws Exception {
        CameraManager cm = getSystemService(CameraManager.class);
        line("=== Camera Check 1.0 ===");
        line("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        line("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), build " + Build.DISPLAY);
        if (Build.VERSION.SDK_INT >= 31) line("Chip: " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
        line("Camera permission: " + (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED));
        String[] listed = cm.getCameraIdList();
        line("Listed camera IDs: " + Arrays.toString(listed));

        // Physical cameras behind logical ones, and IDs that open but are not listed.
        Set<String> all = new LinkedHashSet<>(Arrays.asList(listed));
        for (String id : listed) {
            try {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                all.addAll(c.getPhysicalCameraIds());
            } catch (Exception ignored) { }
        }
        List<String> hidden = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String id = Integer.toString(i);
            if (all.contains(id)) continue;
            try { cm.getCameraCharacteristics(id); hidden.add(id); all.add(id); } catch (Exception ignored) { }
        }
        line("Physical/hidden IDs readable by apps: " + (all.size() > listed.length ? all : "none extra"));
        line("  (not in the list but readable: " + hidden + ")");
        line("");

        boolean anyRaw = false, anyManual = false, anyMaxRes = false;
        List<String> lenses = new ArrayList<>(), ext = new ArrayList<>();
        for (String id : all) {
            CameraCharacteristics c;
            try { c = cm.getCameraCharacteristics(id); } catch (Exception e) { line("Camera " + id + ": unreadable (" + e.getMessage() + ")"); continue; }
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean raw = has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW);
            boolean manual = has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
            boolean maxRes = Build.VERSION.SDK_INT >= 31 && has(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR);
            anyRaw |= raw; anyManual |= manual; anyMaxRes |= maxRes;
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            float[] ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            SizeF phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            Size px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            String eq = "";
            if (f != null && f.length > 0 && phys != null) {
                double diag = Math.hypot(phys.getWidth(), phys.getHeight());
                eq = String.format(Locale.US, " ≈ %.0f mm full-frame equivalent", f[0] * 43.27 / diag);
            }
            line("--- Camera " + id + (Arrays.asList(listed).contains(id) ? "" : " (physical/hidden)") + " — "
                    + facingName(facing) + " ---");
            line("Hardware level: " + levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
            line("Capabilities: " + capNames(caps));
            if (!c.getPhysicalCameraIds().isEmpty()) line("Logical camera made of: " + c.getPhysicalCameraIds());
            line("Focal length: " + Arrays.toString(f) + " mm" + eq + "; aperture f/" + Arrays.toString(ap));
            if (phys != null) line(String.format(Locale.US, "Sensor: %.2f × %.2f mm, %s px", phys.getWidth(), phys.getHeight(), px));
            if (Build.VERSION.SDK_INT >= 31) {
                Size mr = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
                if (mr != null) line("Maximum-resolution sensor mode: " + mr + " px");
            }
            line("ISO range: " + c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    + "  (analog up to " + c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) + ")");
            Range<Long> exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (exp != null) line("Shutter range: " + shutter(exp.getLower()) + " to " + shutter(exp.getUpper())
                    + "  (longest frame " + shutter(nz(c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION))) + ")");
            Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            line("Manual focus: " + (minFocus == null || minFocus == 0 ? "fixed/none" :
                    String.format(Locale.US, "yes, closest %.1f cm (%s calibration)", 100 / minFocus,
                            calibName(c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)))));
            line("Optical stabilisation modes: " + Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)));
            line("Exposure compensation: " + c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    + " steps of " + c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP));
            if (Build.VERSION.SDK_INT >= 30) line("Zoom ratio range: " + c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE));
            line("Colour filter: " + cfaName(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
                    + ", white level " + c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                line("JPEG max: " + max(map, ImageFormat.JPEG) + "; YUV max: " + max(map, ImageFormat.YUV_420_888)
                        + "; HEIC max: " + max(map, ImageFormat.HEIC));
                line("RAW (DNG) sizes: " + sizes(map, ImageFormat.RAW_SENSOR));
                if (Build.VERSION.SDK_INT >= 34) line("Ultra HDR JPEG (JPEG_R) max: " + max(map, ImageFormat.JPEG_R));
                Size[] hs = map.getHighSpeedVideoSizes();
                if (hs != null && hs.length > 0) line("High-speed video sizes: " + Arrays.toString(hs));
            }
            if (Build.VERSION.SDK_INT >= 31) {
                StreamConfigurationMap mrMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                if (mrMap != null) line("Maximum-resolution mode: RAW " + sizes(mrMap, ImageFormat.RAW_SENSOR)
                        + "; JPEG " + max(mrMap, ImageFormat.JPEG) + "; YUV " + max(mrMap, ImageFormat.YUV_420_888));
            }
            if (Build.VERSION.SDK_INT >= 33) {
                DynamicRangeProfiles d = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
                if (d != null) line("10-bit/HDR video profiles: " + rangeNames(d.getSupportedProfiles()));
            }
            line("Noise reduction modes: " + Arrays.toString(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES))
                    + "; sharpening (edge) modes: " + Arrays.toString(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)));

            // The maker's own processing: Camera Extensions.
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    CameraExtensionCharacteristics e = cm.getCameraExtensionCharacteristics(id);
                    List<Integer> supported = e.getSupportedExtensions();
                    if (supported.isEmpty()) line("Maker processing modes (Camera Extensions): none");
                    for (int x : supported) {
                        List<Size> js = e.getExtensionSupportedSizes(x, ImageFormat.JPEG);
                        String keys = "";
                        if (Build.VERSION.SDK_INT >= 33) {
                            List<String> k = new ArrayList<>();
                            for (Object key : e.getAvailableCaptureRequestKeys(x)) k.add(((CaptureRequest.Key<?>) key).getName().replace("android.", ""));
                            keys = " • controls allowed: " + (k.isEmpty() ? "none" : k);
                        }
                        String post = Build.VERSION.SDK_INT >= 34 ? " • postview " + e.isPostviewAvailable(x) : "";
                        line("Maker mode " + extName(x) + ": JPEG up to " + (js.isEmpty() ? "?" : js.get(0)) + post + keys);
                        ext.add(extName(x) + " on camera " + id);
                    }
                } catch (Exception ex) {
                    line("Maker processing modes: not readable (" + ex.getMessage() + ")");
                }
            }
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && f != null && f.length > 0)
                lenses.add("camera " + id + " " + String.format(Locale.US, "%.1f mm", f[0]) + eq.replace(" full-frame equivalent", " eq"));
            line("");
        }

        // Plain-English summary first.
        summary.add("Manual ISO/shutter (Pro mode): " + (anyManual ? "YES" : "no"));
        summary.add("RAW (DNG) capture: " + (anyRaw ? "YES" : "no"));
        summary.add("Full-resolution (50/200 MP-style) mode for apps: " + (anyMaxRes ? "YES" : "no"));
        summary.add("Rear cameras an app can address: " + (lenses.isEmpty() ? "none found" : lenses));
        summary.add("Maker processing modes (Night/HDR/Bokeh…): " + (ext.isEmpty() ? "none exposed" : ext));
        StringBuilder head = new StringBuilder("SUMMARY\n");
        for (String s : summary) head.append("• ").append(s).append('\n');
        return head + "\n" + sb;
    }

    // ------------------------------------------------------------------ names

    private static boolean has(int[] a, int v) { if (a != null) for (int x : a) if (x == v) return true; return false; }
    private static long nz(Long v) { return v == null ? 0 : v; }

    private static String shutter(long ns) {
        if (ns <= 0) return "?";
        double s = ns / 1e9;
        return s >= 1 ? String.format(Locale.US, "%.1f s", s) : "1/" + Math.round(1 / s) + " s";
    }

    private static String max(StreamConfigurationMap m, int format) {
        try {
            Size[] s = m.getOutputSizes(format);
            if (s == null || s.length == 0) return "none";
            Size best = s[0];
            for (Size z : s) if ((long) z.getWidth() * z.getHeight() > (long) best.getWidth() * best.getHeight()) best = z;
            return best + String.format(Locale.US, " (%.1f MP)", best.getWidth() * (double) best.getHeight() / 1e6);
        } catch (Exception e) { return "none"; }
    }

    private static String sizes(StreamConfigurationMap m, int format) {
        try {
            Size[] s = m.getOutputSizes(format);
            return s == null || s.length == 0 ? "none" : Arrays.toString(s);
        } catch (Exception e) { return "none"; }
    }

    private static String facingName(Integer f) {
        if (f == null) return "unknown";
        switch (f) {
            case CameraCharacteristics.LENS_FACING_BACK: return "rear";
            case CameraCharacteristics.LENS_FACING_FRONT: return "front";
            default: return "external";
        }
    }

    private static String levelName(Integer l) {
        if (l == null) return "?";
        switch (l) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3 (best)";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return Integer.toString(l);
        }
    }

    private static String capNames(int[] caps) {
        if (caps == null) return "?";
        String[] n = {"BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW", "PRIVATE_REPROCESSING",
                "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING", "DEPTH_OUTPUT", "CONSTRAINED_HIGH_SPEED_VIDEO",
                "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA", "MONOCHROME", "SECURE_IMAGE_DATA", "SYSTEM_CAMERA",
                "OFFLINE_PROCESSING", "ULTRA_HIGH_RESOLUTION_SENSOR", "REMOSAIC_REPROCESSING", "DYNAMIC_RANGE_TEN_BIT",
                "STREAM_USE_CASE", "COLOR_SPACE_PROFILES"};
        List<String> out = new ArrayList<>();
        for (int c : caps) out.add(c >= 0 && c < n.length ? n[c] : Integer.toString(c));
        return out.toString();
    }

    private static String calibName(Integer c) {
        if (c == null) return "?";
        switch (c) {
            case 0: return "uncalibrated";
            case 1: return "approximate";
            case 2: return "calibrated";
            default: return Integer.toString(c);
        }
    }

    private static String cfaName(Integer c) {
        if (c == null) return "?";
        String[] n = {"RGGB", "GRBG", "GBRG", "BGGR", "RGB", "MONO", "NIR"};
        return c >= 0 && c < n.length ? n[c] : Integer.toString(c);
    }

    private static String extName(int x) {
        switch (x) {
            case 0: return "Auto";
            case 1: return "Face Retouch";
            case 2: return "Bokeh";
            case 3: return "HDR";
            case 4: return "Night";
            default: return "extension " + x;
        }
    }

    private static String rangeNames(Set<Long> p) {
        List<String> out = new ArrayList<>();
        for (long v : p) {
            if (v == DynamicRangeProfiles.STANDARD) out.add("SDR");
            else if (v == DynamicRangeProfiles.HLG10) out.add("HLG10");
            else if (v == DynamicRangeProfiles.HDR10) out.add("HDR10");
            else if (v == DynamicRangeProfiles.HDR10_PLUS) out.add("HDR10+");
            else out.add("profile " + v);
        }
        return out.toString();
    }
}
