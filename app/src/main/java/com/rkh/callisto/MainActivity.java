package com.rkh.callisto;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.rkh.callisto.core.NativeCore;
import com.rkh.callisto.log.DebugLog;
import com.rkh.callisto.model.ConnectionMode;
import com.rkh.callisto.model.ConnectionStateStore;
import com.rkh.callisto.service.CallistoVpnService;
import com.rkh.callisto.service.LocalProxyService;
import com.rkh.callisto.ui.CallistoOrbView;
import com.rkh.callisto.ui.SpaceBackgroundView;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.File;

public final class MainActivity extends Activity implements DebugLog.Listener,
        ConnectionStateStore.Listener {
    private static final int VPN_REQUEST = 407;
    private static final int NOTIFICATION_REQUEST = 408;
    private static final int CREATE_LOG_FILE = 409;
    private static final String PREFS = "callisto_preferences";
    private static final String PREF_PSIPHON_ENABLED = "psiphon_enabled";
    private static final String PREF_CONNECTION_SPEED = "connection_speed";

    private SharedPreferences prefs;
    private FrameLayout content;
    private TextView logsText;
    private CallistoOrbView connectionOrb;
    private TextView connectionAction;
    private TextView connectionHint;
    private View modeSelectorView;
    private View countrySelectorView;
    private ConnectionMode mode;
    private String exitCountry;
    private boolean persian;
    private String logFilter = "ALL";
    private int activeTab;
    private final Handler serviceWatchdog = new Handler(Looper.getMainLooper());
    private boolean appEventsRegistered;
    private final Runnable connectionStateSync = new Runnable() {
        @Override public void run() {
            reconcileWorkingConnectionFromLogs();
            if (ConnectionStateStore.get(MainActivity.this).status
                    == ConnectionStateStore.Status.CONNECTING) {
                serviceWatchdog.postDelayed(this, 1500L);
            }
        }
    };
    private final BroadcastReceiver appEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ConnectionStateStore.acceptBroadcast(intent)) return;
            DebugLog.acceptBroadcast(intent);
        }
    };

    private static final String[] COUNTRY_CODES = {
            "BEST", "DE", "NL", "FI", "SE", "CH", "FR", "GB",
            "US", "CA", "PL", "RO", "JP", "SG", "AU"
    };
    private static final String[] COUNTRY_NAMES_FA = {
            "Best Country", "آلمان", "هلند", "فنلاند", "سوئد", "سوئیس", "فرانسه", "بریتانیا",
            "آمریکا", "کانادا", "لهستان", "رومانی", "ژاپن", "سنگاپور", "استرالیا"
    };
    private static final String[] COUNTRY_NAMES_EN = {
            "Best Country", "Germany", "Netherlands", "Finland", "Sweden", "Switzerland", "France", "United Kingdom",
            "United States", "Canada", "Poland", "Romania", "Japan", "Singapore", "Australia"
    };
    private static final String[] COUNTRY_FLAGS = {
            "✦", "🇩🇪", "🇳🇱", "🇫🇮", "🇸🇪", "🇨🇭", "🇫🇷", "🇬🇧",
            "🇺🇸", "🇨🇦", "🇵🇱", "🇷🇴", "🇯🇵", "🇸🇬", "🇦🇺"
    };

    private final int background = Color.BLACK;
    private final int card = Color.argb(224, 16, 16, 16);
    private final int cardStroke = Color.argb(145, 112, 112, 112);
    private final int primary = Color.WHITE;
    private final int cyan = Color.rgb(218, 218, 218);
    private final int text = Color.WHITE;
    private final int muted = Color.rgb(170, 170, 170);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.initialize(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        trimIdleCache();
        mode = ConnectionMode.VPN;
        prefs.edit().putString("mode", ConnectionMode.VPN.name()).apply();
        exitCountry = prefs.getString("exit_country", "BEST");
        persian = prefs.getBoolean("persian", false);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        DebugLog.add("APP", "Callisto 0.7.11-alpha started; Snowflake route; ABI="
                + Build.SUPPORTED_ABIS[0]);
        buildShell();
        requestNotificationsIfNeeded();
    }

    private void trimIdleCache() {
        if (ConnectionStateStore.get(this).isActive()) return;
        File[] files = getCacheDir().listFiles();
        if (files == null) return;
        for (File file : files) deleteCacheEntry(file);
    }

    private void deleteCacheEntry(File file) {
        if (file == null) return;
        // The connection engines own this directory while the route is active.
        if ("callisto_psiphon_core".equals(file.getName())) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteCacheEntry(child);
            }
        }
        try { file.delete(); } catch (SecurityException ignored) {}
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerAppEvents();
        DebugLog.subscribe(this);
        ConnectionStateStore.subscribe(this, this);
        serviceWatchdog.postDelayed(connectionStateSync, 800L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DebugLog.refresh(this);
        ConnectionStateStore.refresh(this);
        reconcileDesiredActiveState();
        reconcileStoredState();
        reconcileWorkingConnectionFromLogs();
        if (logsText != null) logsText.setText(DebugLog.asDisplayText(logFilter));
    }

    @Override
    protected void onStop() {
        DebugLog.unsubscribe(this);
        ConnectionStateStore.unsubscribe(this);
        serviceWatchdog.removeCallbacks(connectionStateSync);
        unregisterAppEvents();
        super.onStop();
    }

    private void registerAppEvents() {
        if (appEventsRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectionStateStore.ACTION_STATE_CHANGED);
        filter.addAction(DebugLog.ACTION_ENTRY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(appEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(appEvents, filter);
        }
        appEventsRegistered = true;
    }

    private void unregisterAppEvents() {
        if (!appEventsRegistered) return;
        try { unregisterReceiver(appEvents); } catch (IllegalArgumentException ignored) {}
        appEventsRegistered = false;
    }

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(background);
        root.addView(new SpaceBackgroundView(this), match());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(persian ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        column.setPadding(dp(18), dp(8), dp(18), dp(8));
        root.addView(column, match());

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            int bottomInset = insets.getSystemWindowInsetBottom();
            column.setPadding(dp(18), topInset + dp(8), dp(18), Math.max(dp(8), bottomInset));
            return insets;
        });

        column.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(48)));
        content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        root.requestApplyInsets();
        showTab(activeTab);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(persian
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        LinearLayout brand = vertical();
        brand.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        brand.setGravity(persian ? Gravity.END : Gravity.START);
        TextView title = label("CALLISTO", 20, text, true);
        try {
            title.setTypeface(Typeface.createFromAsset(getAssets(),
                    "fonts/orbitron_extra_bold.ttf"), Typeface.BOLD);
        } catch (Exception ignored) {
            title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        }
        title.setTextSize(21);
        title.setLetterSpacing(.11f);
        title.setGravity(persian ? Gravity.RIGHT : Gravity.LEFT);
        TextView subtitle = label(tr("شبکهٔ مقاوم", "RESILIENT NETWORK"), 10, muted, false);
        subtitle.setLetterSpacing(.10f);
        subtitle.setGravity(persian ? Gravity.RIGHT : Gravity.LEFT);
        brand.addView(title);
        brand.addView(subtitle);
        brand.setClickable(true);
        brand.setFocusable(true);
        brand.setContentDescription(tr("خانه", "Home"));
        brand.setOnClickListener(view -> openTab(0));
        bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(topIcon("≡", tr("لاگ‌ها", "Logs"), 1),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        settingsParams.setMarginStart(dp(5));
        bar.addView(topIcon("⚙", tr("تنظیمات", "Settings"), 2), settingsParams);
        return bar;
    }

    private TextView topIcon(String glyph, String description, int tab) {
        TextView icon = label(glyph, tab == 2 ? 22 : 23,
                activeTab == tab ? text : muted, false);
        icon.setGravity(Gravity.CENTER);
        icon.setContentDescription(description);
        icon.setClickable(true);
        icon.setFocusable(true);
        icon.setBackground(shape(activeTab == tab
                        ? Color.argb(90, 255, 255, 255) : Color.argb(105, 12, 12, 12),
                activeTab == tab ? primary : Color.argb(100, 105, 105, 105), 14));
        icon.setOnClickListener(view -> openTab(tab));
        return icon;
    }

    private void openTab(int tab) {
        activeTab = tab;
        buildShell();
    }

    private void showTab(int tab) {
        content.removeAllViews();
        connectionOrb = null;
        connectionAction = null;
        connectionHint = null;
        modeSelectorView = null;
        countrySelectorView = null;
        logsText = null;
        if (tab == 0) content.addView(buildHome(), match());
        else if (tab == 1) content.addView(buildLogs(), match());
        else content.addView(buildSettings(), match());
    }

    private View buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = vertical();
        body.setPadding(0, dp(8), 0, dp(18));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout hero = vertical();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(14), dp(4), dp(14), dp(18));
        hero.setBackground(null);

        connectionOrb = new CallistoOrbView(this);
        connectionOrb.setClickable(true);
        connectionOrb.setFocusable(true);
        connectionOrb.setContentDescription(tr("دکمه اتصال", "Connection button"));
        connectionOrb.setOnClickListener(view -> toggleConnection());
        hero.addView(connectionOrb, new LinearLayout.LayoutParams(-1, dp(250)));

        connectionAction = label("", 18, text, true);
        connectionAction.setGravity(Gravity.CENTER);
        hero.addView(connectionAction, new LinearLayout.LayoutParams(-1, -2));

        connectionHint = label("", 11, muted, false);
        connectionHint.setGravity(Gravity.CENTER);
        connectionHint.setPadding(dp(10), dp(6), dp(10), 0);
        hero.addView(connectionHint, new LinearLayout.LayoutParams(-1, -2));
        body.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        if (isPsiphonEnabled()) {
            body.addView(sectionTitle(tr("کشور اتصال", "CONNECTION COUNTRY")), spaced());
            countrySelectorView = countrySelector();
            body.addView(countrySelectorView, new LinearLayout.LayoutParams(-1, dp(62)));
        }

        body.addView(footerBlock(), footerParams());

        updateConnectionUi(ConnectionStateStore.get(this));
        return scroll;
    }

    private View modeSelector() {
        LinearLayout row = segmentedBase();
        row.addView(segment(tr("وی‌پی‌ان", "VPN"), mode == ConnectionMode.VPN, () -> selectMode(ConnectionMode.VPN)),
                new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(segment(tr("پروکسی", "Proxy"), mode == ConnectionMode.PROXY, () -> selectMode(ConnectionMode.PROXY)),
                new LinearLayout.LayoutParams(0, -1, 1f));
        return row;
    }

    private void selectMode(ConnectionMode selected) {
        if (ConnectionStateStore.get(this).isActive()) {
            Toast.makeText(this, tr("ابتدا اتصال را قطع کن", "Disconnect first"), Toast.LENGTH_SHORT).show();
            return;
        }
        mode = selected;
        prefs.edit().putString("mode", mode.name()).apply();
        DebugLog.add("APP", "Connection mode changed to " + mode.name());
        showTab(0);
    }

    private View countrySelector() {
        LinearLayout row = cardRow();
        LinearLayout labels = vertical();
        labels.addView(label(tr("کشور", "Country"), 13, text, true));
        TextView current = label(countryName(exitCountry), 11, cyan, false);
        current.setPadding(0, dp(4), 0, 0);
        labels.addView(current);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = label(persian ? "‹" : "›", 24, primary, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(34)));
        row.setOnClickListener(view -> showCountryDialog());
        return row;
    }

    private void showCountryDialog() {
        if (ConnectionStateStore.get(this).isActive()) {
            Toast.makeText(this, tr("ابتدا اتصال را قطع کن", "Disconnect first"), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = countryDisplayNames();
        int selected = 0;
        for (int i = 0; i < COUNTRY_CODES.length; i++) {
            if (COUNTRY_CODES[i].equals(exitCountry)) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(tr("کشور", "Country"))
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    exitCountry = COUNTRY_CODES[which];
                    prefs.edit().putString("exit_country", exitCountry).apply();
                    DebugLog.add("APP", "Psiphon egress country changed to " + exitCountry);
                    dialog.dismiss();
                    showTab(0);
                })
                .setNegativeButton(tr("لغو", "Cancel"), null)
                .show();
    }

    private String countryName(String code) {
        for (int i = 0; i < COUNTRY_CODES.length; i++) {
            if (COUNTRY_CODES[i].equals(code)) {
                return COUNTRY_FLAGS[i] + "  " + (persian ? COUNTRY_NAMES_FA[i] : COUNTRY_NAMES_EN[i]);
            }
        }
        return COUNTRY_FLAGS[0] + "  " + (persian ? COUNTRY_NAMES_FA[0] : COUNTRY_NAMES_EN[0]);
    }

    private String[] countryDisplayNames() {
        String[] result = new String[COUNTRY_CODES.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = COUNTRY_FLAGS[i] + "  " + (persian ? COUNTRY_NAMES_FA[i] : COUNTRY_NAMES_EN[i]);
        }
        return result;
    }

    private View buildLogs() {
        LinearLayout page = vertical();
        page.setPadding(0, dp(8), 0, dp(8));
        page.addView(label(tr("لاگ دیباگ", "DEBUG LOGS"), 21, text, true));
        TextView description = label(tr("رویدادهای اتصال با حذف خودکار اطلاعات حساس",
                "Connection events with secret redaction"), 11, muted, false);
        description.setPadding(0, dp(4), 0, dp(11));
        page.addView(description);

        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (String filter : new String[]{"ALL", "APP", "VPN", "PROXY", "CORE"}) {
            TextView chip = label(filter, 10, logFilter.equals(filter) ? text : muted, logFilter.equals(filter));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setBackground(shape(logFilter.equals(filter) ? Color.argb(95, 255, 255, 255) : card,
                    logFilter.equals(filter) ? primary : cardStroke, 30));
            chip.setOnClickListener(view -> {
                logFilter = filter;
                showTab(1);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(36));
            params.setMargins(0, 0, dp(7), 0);
            filterRow.addView(chip, params);
        }
        filters.addView(filterRow);
        page.addView(filters, new LinearLayout.LayoutParams(-1, dp(42)));

        ScrollView logScroll = new ScrollView(this);
        logScroll.setBackground(shape(Color.argb(238, 5, 5, 5), cardStroke, 18));
        logsText = label(DebugLog.asDisplayText(logFilter), 11, Color.rgb(215, 215, 215), false);
        logsText.setTypeface(Typeface.MONOSPACE);
        logsText.setTextDirection(View.TEXT_DIRECTION_LTR);
        logsText.setGravity(Gravity.START);
        logsText.setPadding(dp(14), dp(14), dp(14), dp(14));
        logsText.setTextIsSelectable(true);
        logScroll.addView(logsText);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        logParams.topMargin = dp(8);
        page.addView(logScroll, logParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(persian ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        actions.setPadding(0, dp(9), 0, 0);
        actions.addView(actionButton(tr("کپی", "Copy"), view -> copyLogs()), actionParams(true));
        actions.addView(actionButton(tr("خروجی", "Export"), view -> exportLogs()), actionParams(true));
        actions.addView(actionButton(tr("پاک‌کردن", "Clear"), view -> DebugLog.clear()), actionParams(false));
        page.addView(actions);
        return page;
    }

    private View buildSettings() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = vertical();
        page.setPadding(0, dp(8), 0, dp(20));
        scroll.addView(page);
        page.addView(label(tr("تنظیمات", "SETTINGS"), 21, text, true));

        page.addView(sectionTitle(tr("مسیر اختیاری", "OPTIONAL ROUTE")), spaced());
        page.addView(settingSwitch(tr("فعال‌سازی Psiphon", "Enable Psiphon"),
                tr("ترافیک را از Psiphon عبور می‌دهد و انتخاب کشور را فعال می‌کند",
                        "Routes traffic through Psiphon and enables country selection"),
                PREF_PSIPHON_ENABLED, true, () -> showTab(2)));

        page.addView(sectionTitle(tr("سرعت اتصال", "CONNECTION SPEED")), spaced());
        page.addView(torSpeedSelector(), new LinearLayout.LayoutParams(-1, dp(58)));
        TextView speedHint = label(tr(
                        "سریع: اتصال زودتر  •  متعادل: مصرف و سرعت متوازن  •  پایدار: فرصت بیشتر برای شبکه‌های ضعیف",
                        "Fast: quicker startup  •  Balanced: moderate load  •  Stable: more time on weak networks"),
                10, muted, false);
        speedHint.setPadding(dp(6), dp(7), dp(6), 0);
        page.addView(speedHint);

        page.addView(sectionTitle(tr("تنظیمات VPN", "VPN SETTINGS")), spaced());
        page.addView(settingSwitch(tr("جلوگیری از نشت DNS", "Prevent DNS leaks"),
                tr("DNS فقط از داخل تونل عبور کند", "Resolve DNS only inside the tunnel"), "dns_leak", true));
        page.addView(settingSwitch(tr("کلید قطع اضطراری", "Kill switch"),
                tr("در زمان قطع تونل، ترافیک متوقف شود", "Block traffic while the tunnel is down"), "kill_switch", true));
        page.addView(settingSwitch(tr("دسترسی شبکه محلی", "LAN access"),
                tr("دستگاه‌های داخل شبکه محلی قابل دسترس باشند", "Allow access to local network devices"), "lan", false));

        page.addView(sectionTitle("SPLIT TUNNELING"), spaced());
        page.addView(settingSwitch(tr("فعال‌سازی اسپلیت تانلینگ", "Enable split tunneling"),
                tr("برنامه‌های انتخاب‌شده را خارج از VPN نگه دار یا فقط همان‌ها را VPN کن",
                        "Choose whether selected apps bypass or exclusively use the VPN"), "split_tunneling", false,
                () -> showTab(2)));
        boolean splitEnabled = prefs.getBoolean("split_tunneling", false);
        View splitModes = splitModeSelector();
        splitModes.setEnabled(splitEnabled);
        splitModes.setAlpha(splitEnabled ? 1f : .5f);
        LinearLayout.LayoutParams splitModeParams = new LinearLayout.LayoutParams(-1, dp(58));
        splitModeParams.topMargin = dp(8);
        page.addView(splitModes, splitModeParams);
        Set<String> excludedApps = prefs.getStringSet("split_apps", Collections.emptySet());
        Button manageApps = actionButton(tr("انتخاب برنامه‌ها", "Choose apps") + "  (" + excludedApps.size() + ")",
                view -> showSplitTunnelingDialog());
        manageApps.setEnabled(splitEnabled);
        manageApps.setAlpha(splitEnabled ? 1f : .5f);
        LinearLayout.LayoutParams manageParams = new LinearLayout.LayoutParams(-1, dp(48));
        manageParams.topMargin = dp(8);
        page.addView(manageApps, manageParams);

        LinearLayout language = cardRow();
        LinearLayout languageText = vertical();
        languageText.addView(label(tr("زبان رابط", "Interface language"), 14, text, true));
        languageText.addView(label(persian ? "فارسی" : "English", 11, muted, false));
        language.addView(languageText, new LinearLayout.LayoutParams(0, -2, 1f));
        language.addView(actionButton(persian ? "EN" : "FA", view -> {
            persian = !persian;
            prefs.edit().putBoolean("persian", persian).apply();
            buildShell();
        }), new LinearLayout.LayoutParams(dp(62), dp(40)));
        page.addView(language, rowParams());

        page.addView(footerBlock(), footerParams());
        return scroll;
    }

    private boolean isPsiphonEnabled() {
        return prefs.getBoolean(PREF_PSIPHON_ENABLED, true);
    }

    private View torSpeedSelector() {
        String selected = prefs.getString(PREF_CONNECTION_SPEED, "FAST");
        LinearLayout row = segmentedBase();
        row.addView(segment(tr("سریع", "Fast"), "FAST".equals(selected),
                        () -> selectConnectionSpeed("FAST")),
                new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(segment(tr("متعادل", "Balanced"), "BALANCED".equals(selected),
                        () -> selectConnectionSpeed("BALANCED")),
                new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(segment(tr("پایدار", "Stable"), "STABLE".equals(selected),
                        () -> selectConnectionSpeed("STABLE")),
                new LinearLayout.LayoutParams(0, -1, 1f));
        return row;
    }

    private void selectConnectionSpeed(String selected) {
        if (ConnectionStateStore.get(this).isActive()) {
            Toast.makeText(this, tr("ابتدا اتصال را قطع کن", "Disconnect first"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(PREF_CONNECTION_SPEED, selected).apply();
        DebugLog.add("APP", "Connection speed profile=" + selected);
        showTab(2);
    }

    private View splitModeSelector() {
        String selected = prefs.getString("split_mode", "BYPASS");
        LinearLayout row = segmentedBase();
        row.addView(segment(tr("عبور مستقیم", "Bypass selected"), "BYPASS".equals(selected),
                        () -> selectSplitMode("BYPASS")),
                new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(segment("VPN Only", "VPN_ONLY".equals(selected),
                        () -> selectSplitMode("VPN_ONLY")),
                new LinearLayout.LayoutParams(0, -1, 1f));
        return row;
    }

    private void selectSplitMode(String selected) {
        if (ConnectionStateStore.get(this).isActive()) {
            Toast.makeText(this, tr("ابتدا اتصال را قطع کن", "Disconnect first"), Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString("split_mode", selected).apply();
        DebugLog.add("APP", "Split tunneling mode=" + selected);
        showTab(2);
    }

    private View settingSwitch(String title, String description, String key, boolean defaultValue) {
        return settingSwitch(title, description, key, defaultValue, null);
    }

    private View settingSwitch(String title, String description, String key, boolean defaultValue,
                               Runnable afterChange) {
        LinearLayout row = cardRow();
        LinearLayout labels = vertical();
        labels.addView(label(title, 14, text, true));
        TextView desc = label(description, 10, muted, false);
        desc.setPadding(0, dp(4), 0, 0);
        labels.addView(desc);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (PREF_PSIPHON_ENABLED.equals(key)
                    && ConnectionStateStore.get(this).isActive()) {
                Toast.makeText(this, tr("ابتدا اتصال را قطع کن", "Disconnect first"),
                        Toast.LENGTH_SHORT).show();
                showTab(2);
                return;
            }
            prefs.edit().putBoolean(key, checked).apply();
            DebugLog.add("APP", key + "=" + checked);
            if (afterChange != null) afterChange.run();
        });
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        row.setLayoutParams(rowParams());
        return row;
    }

    private void showSplitTunnelingDialog() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcherIntent, 0);
        Map<String, String> labelsByPackage = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
            CharSequence label = info.loadLabel(getPackageManager());
            labelsByPackage.put(info.activityInfo.packageName,
                    label == null ? info.activityInfo.packageName : label.toString());
        }
        List<AppEntry> apps = new ArrayList<>();
        for (Map.Entry<String, String> entry : labelsByPackage.entrySet()) {
            apps.add(new AppEntry(entry.getKey(), entry.getValue()));
        }
        if (apps.isEmpty()) {
            Toast.makeText(this, tr("برنامه‌ای پیدا نشد", "No apps found"), Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> saved = new HashSet<>(prefs.getStringSet("split_apps", Collections.emptySet()));
        LinearLayout dialogBody = vertical();
        dialogBody.setPadding(dp(18), dp(6), dp(18), 0);
        dialogBody.setBackgroundColor(Color.rgb(10, 10, 10));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(tr("جست‌وجوی برنامه…", "Search apps…"));
        search.setTextColor(text);
        search.setHintTextColor(muted);
        search.setBackground(shape(Color.argb(240, 12, 12, 12), cardStroke, 14));
        search.setPadding(dp(14), 0, dp(14), 0);
        dialogBody.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView appScroll = new ScrollView(this);
        LinearLayout appList = vertical();
        appScroll.addView(appList, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, dp(390));
        scrollParams.topMargin = dp(8);
        dialogBody.addView(appScroll, scrollParams);
        renderAppChoices(appList, apps, saved, search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                renderAppChoices(appList, apps, saved, search);
            }
            @Override public void afterTextChanged(Editable value) {}
        });

        String modeTitle = "VPN_ONLY".equals(prefs.getString("split_mode", "BYPASS"))
                ? "VPN Only" : tr("برنامه‌های خارج از VPN", "Apps outside VPN");
        TextView dialogTitle = label(modeTitle, 18, text, true);
        dialogTitle.setPadding(dp(22), dp(20), dp(22), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle)
                .setView(dialogBody)
                .setPositiveButton(tr("ذخیره", "Save"), (dialogInterface, which) -> {
                    prefs.edit().putStringSet("split_apps", new HashSet<>(saved)).apply();
                    DebugLog.add("APP", "Split tunneling app count=" + saved.size());
                    showTab(2);
                })
                .setNegativeButton(tr("لغو", "Cancel"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        shape(Color.rgb(10, 10, 10), cardStroke, 18));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(cyan);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(muted);
        });
        dialog.show();
    }

    private void renderAppChoices(LinearLayout container, List<AppEntry> apps,
                                  Set<String> selected, EditText search) {
        String query = search.getText() == null ? "" : search.getText().toString()
                .trim().toLowerCase(Locale.ROOT);
        List<AppEntry> visible = new ArrayList<>();
        for (AppEntry app : apps) {
            if (query.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(query)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(app);
            }
        }
        visible.sort((left, right) -> {
            int selectedOrder = Boolean.compare(selected.contains(right.packageName),
                    selected.contains(left.packageName));
            if (selectedOrder != 0) return selectedOrder;
            return String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label);
        });
        container.removeAllViews();
        for (AppEntry app : visible) {
            CheckBox choice = new CheckBox(this);
            choice.setText(app.label + "\n" + app.packageName);
            choice.setTextColor(text);
            choice.setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{cyan, muted}));
            choice.setTextSize(12);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setPadding(dp(8), dp(7), dp(8), dp(7));
            choice.setChecked(selected.contains(app.packageName));
            choice.setBackground(shape(choice.isChecked()
                            ? Color.argb(90, 255, 255, 255)
                            : Color.argb(125, 20, 20, 20),
                    choice.isChecked() ? cyan : cardStroke, 12));
            choice.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(app.packageName);
                else selected.remove(app.packageName);
                // Rebuilding immediately keeps every selected app at the top.
                renderAppChoices(container, apps, selected, search);
            });
            container.addView(choice, new LinearLayout.LayoutParams(-1, dp(64)));
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;

        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private void toggleConnection() {
        ConnectionStateStore.Snapshot state = ConnectionStateStore.get(this);
        if (state.status == ConnectionStateStore.Status.CONNECTED
                || state.status == ConnectionStateStore.Status.CONNECTING) {
            disconnect(state.mode);
        } else if (state.status != ConnectionStateStore.Status.DISCONNECTING) {
            connect();
        }
    }

    private void connect() {
        boolean psiphonEnabled = isPsiphonEnabled();
        DebugLog.add("APP", "Connect requested: mode=" + mode
                + ", route=" + (psiphonEnabled ? "Snowflake+Psiphon" : "Snowflake only"));
        boolean available;
        try {
            available = NativeCore.isAvailable(this, psiphonEnabled);
        } catch (Throwable error) {
            available = false;
            DebugLog.add("APP", "Connection component check crashed safely: "
                    + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
        if (!available) {
            ConnectionStateStore.update(this, ConnectionStateStore.Status.ERROR, mode,
                    "A required connection component is missing");
            new AlertDialog.Builder(this)
                    .setTitle(tr("اتصال در دسترس نیست", "Connection unavailable"))
                    .setMessage(tr("یکی از اجزای لازم داخل APK پیدا نشد. جزئیات در بخش لاگ ثبت شد.",
                            "A required APK component is missing. Details were written to Logs."))
                    .setPositiveButton(tr("مشاهده لاگ", "Open Logs"), (dialog, which) -> {
                        activeTab = 1;
                        buildShell();
                    })
                    .setNegativeButton(tr("بستن", "Close"), null)
                    .show();
            return;
        }
        ConnectionStateStore.setDesiredActive(this, true);
        ConnectionStateStore.update(this, ConnectionStateStore.Status.CONNECTING, mode, "Preparing");
        serviceWatchdog.postDelayed(connectionStateSync, 800L);
        if (mode == ConnectionMode.VPN) requestVpn();
        else startProxy();
    }

    private void requestVpn() {
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, VPN_REQUEST);
        else startVpn();
    }

    private void startVpn() {
        Intent service = new Intent(this, CallistoVpnService.class)
                .putExtra(CallistoVpnService.EXTRA_EXIT_COUNTRY, exitCountry)
                .putExtra(CallistoVpnService.EXTRA_PSIPHON_ENABLED, isPsiphonEnabled());
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Throwable error) {
            handleServiceStartFailure(ConnectionMode.VPN, error);
        }
    }

    private void startProxy() {
        Intent service = new Intent(this, LocalProxyService.class)
                .putExtra(LocalProxyService.EXTRA_EXIT_COUNTRY, exitCountry)
                .putExtra(LocalProxyService.EXTRA_PSIPHON_ENABLED, isPsiphonEnabled());
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Throwable error) {
            handleServiceStartFailure(ConnectionMode.PROXY, error);
        }
    }

    private void handleServiceStartFailure(ConnectionMode requestedMode, Throwable error) {
        String message = error.getClass().getSimpleName() + ": "
                + String.valueOf(error.getMessage());
        DebugLog.add("APP", "Could not start tunnel service: " + message);
        ConnectionStateStore.setDesiredActive(this, false);
        ConnectionStateStore.update(this, ConnectionStateStore.Status.ERROR,
                requestedMode, message);
    }

    private void disconnect(ConnectionMode activeMode) {
        ConnectionStateStore.setDesiredActive(this, false);
        ConnectionStateStore.update(this, ConnectionStateStore.Status.DISCONNECTING,
                activeMode, "Disconnecting");
        Intent stop = activeMode == ConnectionMode.VPN
                ? new Intent(this, CallistoVpnService.class).setAction(CallistoVpnService.ACTION_STOP)
                : new Intent(this, LocalProxyService.class).setAction(LocalProxyService.ACTION_STOP);
        startService(stop);
        scheduleDisconnectWatchdog(activeMode, 2500L);
        scheduleDisconnectWatchdog(activeMode, 16000L);
    }

    private void scheduleDisconnectWatchdog(ConnectionMode activeMode, long delayMillis) {
        serviceWatchdog.postDelayed(() -> {
            Class<?> expected = activeMode == ConnectionMode.VPN
                    ? CallistoVpnService.class : LocalProxyService.class;
            ConnectionStateStore.Snapshot state = ConnectionStateStore.get(MainActivity.this);
            if (state.status == ConnectionStateStore.Status.DISCONNECTING
                    && !isServiceRunning(expected)) {
                DebugLog.add("APP", "Tunnel process stopped; interface kept alive");
                ConnectionStateStore.update(MainActivity.this,
                        ConnectionStateStore.Status.DISCONNECTED, activeMode, "Disconnected");
            }
        }, delayMillis);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startVpn();
            else {
                DebugLog.add("VPN", "User denied Android VPN permission");
                ConnectionStateStore.setDesiredActive(this, false);
                ConnectionStateStore.update(this, ConnectionStateStore.Status.DISCONNECTED,
                        ConnectionMode.VPN, "VPN permission denied");
            }
        } else if (requestCode == CREATE_LOG_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output != null) output.write(DebugLog.asText(logFilter)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Toast.makeText(this, tr("فایل ذخیره شد", "Log file saved"), Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void reconcileStoredState() {
        ConnectionStateStore.Snapshot snapshot = ConnectionStateStore.get(this);
        if (!snapshot.isActive()) return;
        Class<?> expected = snapshot.mode == ConnectionMode.VPN
                ? CallistoVpnService.class : LocalProxyService.class;
        if (!isServiceRunning(expected) && !ConnectionStateStore.isDesiredActive(this)) {
            ConnectionStateStore.update(this, ConnectionStateStore.Status.DISCONNECTED,
                    snapshot.mode, "Disconnected");
        }
    }

    private void reconcileDesiredActiveState() {
        ConnectionStateStore.Snapshot snapshot = ConnectionStateStore.get(this);
        if (!ConnectionStateStore.isDesiredActive(this) || snapshot.isActive()) return;
        ConnectionMode restoredMode = snapshot.mode == null ? mode : snapshot.mode;
        if (hasWorkingConnectionSignal()) {
            ConnectionStateStore.update(this, ConnectionStateStore.Status.CONNECTED,
                    restoredMode, "Connected");
        } else {
            ConnectionStateStore.update(this, ConnectionStateStore.Status.CONNECTING,
                    restoredMode, "Restoring connection state");
        }
    }

    private void reconcileWorkingConnectionFromLogs() {
        ConnectionStateStore.Snapshot snapshot = ConnectionStateStore.get(this);
        if (snapshot.status != ConnectionStateStore.Status.CONNECTING) return;
        if (!hasWorkingConnectionSignal()) return;
        DebugLog.add("APP", "Main screen state synced from working route signal");
        ConnectionStateStore.forceConnected(this, snapshot.mode, "Connected");
    }

    private boolean hasWorkingConnectionSignal() {
        List<DebugLog.Entry> entries = DebugLog.snapshot();
        int first = Math.max(0, entries.size() - 120);
        for (int i = entries.size() - 1; i >= first; i--) {
            String message = entries.get(i).message.toLowerCase(Locale.US);
            if (message.contains("startup failed")
                    || message.contains("stopped unexpectedly")
                    || message.contains("permission denied")) {
                return false;
            }
            if (message.contains("proxy port is ready")
                    || message.contains("traffic can flow")
                    || message.contains("socks5 127.0.0.1:1819")
                    || message.contains("packet tunnel data plane is running")
                    || message.contains("vpn is connected")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) return true;
        }
        return false;
    }

    @Override
    public void onConnectionStateChanged(ConnectionStateStore.Snapshot snapshot) {
        updateConnectionUi(snapshot);
        if (snapshot.status == ConnectionStateStore.Status.CONNECTING) {
            serviceWatchdog.removeCallbacks(connectionStateSync);
            serviceWatchdog.postDelayed(connectionStateSync, 1200L);
        }
    }

    private void updateConnectionUi(ConnectionStateStore.Snapshot state) {
        if (connectionOrb == null) return;
        connectionOrb.setConnectionStatus(state.status);
        boolean active = state.isActive();
        if (modeSelectorView != null) {
            modeSelectorView.setEnabled(!active);
            modeSelectorView.setAlpha(active ? .55f : 1f);
        }
        if (countrySelectorView != null) {
            countrySelectorView.setEnabled(!active);
            countrySelectorView.setAlpha(active ? .55f : 1f);
        }
        switch (state.status) {
            case CONNECTING:
                connectionAction.setText(tr("درحال اتصال…", "Connecting…"));
                connectionHint.setText(tr("برای لغو روی قمر بزن", "Tap the moon to cancel"));
                break;
            case CONNECTED:
                connectionAction.setText(tr("قطع اتصال", "Disconnect"));
                connectionHint.setText(tr("اتصال برقرار است؛ برای قطع روی قمر بزن",
                        "Connected — tap the moon to disconnect"));
                break;
            case DISCONNECTING:
                connectionAction.setText(tr("درحال قطع…", "Disconnecting…"));
                connectionHint.setText(tr("چند لحظه صبر کن", "Please wait"));
                break;
            case ERROR:
                connectionAction.setText(tr("تلاش دوباره", "Try again"));
                connectionHint.setText(TextUtils.isEmpty(state.detail)
                        ? tr("اتصال ناموفق بود", "Connection failed") : state.detail);
                break;
            case DISCONNECTED:
            default:
                connectionAction.setText(tr("اتصال", "Connect"));
                connectionHint.setText(tr("برای شروع روی قمر بزن", "Tap the moon to connect"));
                break;
        }
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private void copyLogs() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Callisto logs", DebugLog.asText(logFilter)));
        Toast.makeText(this, tr("لاگ کپی شد", "Logs copied"), Toast.LENGTH_SHORT).show();
    }

    private void exportLogs() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "callisto-debug-log.txt");
        startActivityForResult(intent, CREATE_LOG_FILE);
    }

    @Override
    public void onLogChanged() {
        if (logsText != null) logsText.setText(DebugLog.asDisplayText(logFilter));
        reconcileWorkingConnectionFromLogs();
    }

    @Override
    public void onBackPressed() {
        if (activeTab == 1 || activeTab == 2) {
            activeTab = 0;
            buildShell();
            return;
        }
        super.onBackPressed();
    }

    private LinearLayout segmentedBase() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.setBackground(shape(Color.argb(215, 14, 14, 14), cardStroke, 18));
        return row;
    }

    private TextView segment(String value, boolean selected, Runnable action) {
        TextView item = label(value, 13, selected ? text : muted, selected);
        item.setGravity(Gravity.CENTER);
        item.setBackground(shape(selected ? Color.argb(90, 255, 255, 255) : Color.TRANSPARENT,
                selected ? Color.argb(180, 255, 255, 255) : Color.TRANSPARENT, 15));
        item.setOnClickListener(view -> action.run());
        return item;
    }

    private TextView sectionTitle(String value) {
        TextView result = label(value.toUpperCase(Locale.ROOT), 11, muted, true);
        result.setLetterSpacing(.08f);
        return result;
    }

    private LinearLayout cardRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(14), dp(13), dp(14));
        row.setBackground(shape(card, cardStroke, 18));
        return row;
    }

    private LinearLayout vertical() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0, 1.08f);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return view;
    }

    private Button actionButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setStateListAnimator(null);
        button.setBackground(shape(card, cardStroke, 14));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView poweredBy() {
        TextView footer = label("Powered By ReZa Kh", 10, Color.argb(200, 190, 190, 190), false);
        footer.setTextDirection(View.TEXT_DIRECTION_LTR);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(.06f);
        return footer;
    }

    private View footerBlock() {
        LinearLayout footer = vertical();
        footer.setGravity(Gravity.CENTER);
        TextView telegram = label("Telegram: @pingplas_channel", 11, cyan, true);
        telegram.setTextDirection(View.TEXT_DIRECTION_LTR);
        telegram.setGravity(Gravity.CENTER);
        telegram.setClickable(true);
        telegram.setFocusable(true);
        telegram.setContentDescription("Open Telegram channel @pingplas_channel");
        telegram.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://t.me/pingplas_channel")));
            } catch (Exception error) {
                Toast.makeText(this, tr("امکان بازکردن لینک نیست", "Unable to open link"),
                        Toast.LENGTH_SHORT).show();
            }
        });
        footer.addView(telegram, new LinearLayout.LayoutParams(-1, dp(27)));
        footer.addView(poweredBy(), new LinearLayout.LayoutParams(-1, dp(25)));
        return footer;
    }

    private GradientDrawable shape(int fill, int stroke, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(18);
        params.bottomMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        return params;
    }

    private LinearLayout.LayoutParams actionParams(boolean addGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        if (addGap) params.setMarginEnd(dp(7));
        return params;
    }

    private LinearLayout.LayoutParams footerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58));
        params.topMargin = dp(16);
        return params;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private String tr(String fa, String en) {
        return persian ? fa : en;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
