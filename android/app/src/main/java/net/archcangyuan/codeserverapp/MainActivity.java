package net.archcangyuan.codeserverapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFERENCES = "code_server_app";
    private static final String ADDRESS_KEY = "server_address";
    private static final String PROJECTS_KEY = "saved_projects";
    private static final String ZOOM_PERCENT_KEY = "zoom_percent";
    private static final int DESKTOP_VIEWPORT_WIDTH = 1280;
    private static final int ACCENT = Color.rgb(103, 80, 164);
    private static final int KEY_BACKGROUND = Color.rgb(230, 230, 234);
    private static final String DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String KEYBOARD_BRIDGE = """
        (() => {
          let viewport = document.querySelector('meta[name="viewport"]');
          if (!viewport) {
            viewport = document.createElement('meta');
            viewport.setAttribute('name', 'viewport');
            (document.head || document.documentElement).appendChild(viewport);
          }
          viewport.setAttribute(
            'content',
            'width=1280, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes'
          );

          if (window.__codeServerAppKeyboard) return;

          const state = { control: false, shift: false };
          const activeTarget = () => document.activeElement || document.body;

          const defineLegacyKeyCodes = (event, keyCode) => {
            try {
              Object.defineProperty(event, 'keyCode', { get: () => keyCode });
              Object.defineProperty(event, 'which', { get: () => keyCode });
            } catch (_) {}
          };

          const dispatchModifier = (key, code, keyCode, isDown) => {
            const target = activeTarget();
            if (!target) return;
            const event = new KeyboardEvent(isDown ? 'keydown' : 'keyup', {
              key,
              code,
              ctrlKey: state.control,
              shiftKey: state.shift,
              altKey: false,
              metaKey: false,
              bubbles: true,
              cancelable: true,
              composed: true
            });
            defineLegacyKeyCodes(event, keyCode);
            target.dispatchEvent(event);
          };

          const redispatchWithLockedModifiers = (event) => {
            if (!event.isTrusted || (!state.control && !state.shift)) return;
            if (event.key === 'Control' || event.key === 'Shift') return;

            const target = event.target || activeTarget();
            if (!target) return;

            event.preventDefault();
            event.stopImmediatePropagation();

            const shiftedKey = state.shift && event.key.length === 1
              ? event.key.toUpperCase()
              : event.key;
            const replacement = new KeyboardEvent(event.type, {
              key: shiftedKey,
              code: event.code,
              location: event.location,
              repeat: event.repeat,
              ctrlKey: state.control || event.ctrlKey,
              shiftKey: state.shift || event.shiftKey,
              altKey: event.altKey,
              metaKey: event.metaKey,
              bubbles: true,
              cancelable: true,
              composed: true
            });
            defineLegacyKeyCodes(replacement, event.keyCode || event.which || 0);
            target.dispatchEvent(replacement);
          };

          document.addEventListener('keydown', redispatchWithLockedModifiers, true);
          document.addEventListener('keyup', redispatchWithLockedModifiers, true);

          window.__codeServerAppKeyboard = {
            setModifiers(control, shift) {
              const nextControl = Boolean(control);
              const nextShift = Boolean(shift);
              const controlChanged = state.control !== nextControl;
              const shiftChanged = state.shift !== nextShift;
              state.control = nextControl;
              state.shift = nextShift;

              if (controlChanged) {
                dispatchModifier('Control', 'ControlLeft', 17, nextControl);
              }
              if (shiftChanged) {
                dispatchModifier('Shift', 'ShiftLeft', 16, nextShift);
              }
            }
          };
        })();
        """;

    private SharedPreferences preferences;
    private final List<ProjectProfile> projects = new ArrayList<>();
    private LinearLayout rootContainer;
    private LinearLayout addressBar;
    private EditText addressField;
    private WebView webView;
    private Button controlButton;
    private Button shiftButton;
    private Button fullscreenButton;
    private boolean controlLocked;
    private boolean shiftLocked;
    private boolean fullscreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        loadProjects();
        setContentView(createContentView());
        configureSystemUi();
        configureWebView();

        String savedAddress = preferences.getString(ADDRESS_KEY, "");
        addressField.setText(savedAddress);
        if (savedAddress == null || savedAddress.trim().isEmpty()) {
            addressField.requestFocus();
        } else {
            webView.loadUrl(savedAddress);
        }
    }

    private View createContentView() {
        EdgeGestureLayout root = new EdgeGestureLayout(this);
        rootContainer = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applySafeAreaInsets(view, insets);
            return insets;
        });

        addressBar = new LinearLayout(this);
        addressBar.setOrientation(LinearLayout.HORIZONTAL);
        addressBar.setGravity(Gravity.CENTER_VERTICAL);
        addressBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        addressBar.setBackgroundColor(Color.rgb(243, 243, 243));

        Button projectsButton = createToolbarButton("☰");
        projectsButton.setContentDescription("Switch code-server project");
        projectsButton.setOnClickListener(view -> showProjectSwitcher());
        addressBar.addView(projectsButton);

        addressField = new EditText(this);
        addressField.setSingleLine(true);
        addressField.setTextSize(14);
        addressField.setHint("http://192.168.1.10:8080");
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressField.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterPressed = event != null
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadEnteredAddress();
                return true;
            }
            return false;
        });
        addressBar.addView(
            addressField,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        );

        Button goButton = createToolbarButton("Go");
        goButton.setOnClickListener(view -> loadEnteredAddress());
        addressBar.addView(goButton);

        Button reloadButton = createToolbarButton("↻");
        reloadButton.setContentDescription("Reload code-server");
        reloadButton.setOnClickListener(view -> webView.reload());
        addressBar.addView(reloadButton);

        Button zoomOutButton = createToolbarButton("−");
        zoomOutButton.setContentDescription("Zoom out");
        zoomOutButton.setOnClickListener(view -> changeZoom(-1));
        addressBar.addView(zoomOutButton);

        Button zoomInButton = createToolbarButton("+");
        zoomInButton.setContentDescription("Zoom in");
        zoomInButton.setOnClickListener(view -> changeZoom(1));
        addressBar.addView(zoomInButton);

        fullscreenButton = createToolbarButton("⛶");
        fullscreenButton.setContentDescription("Enter fullscreen");
        fullscreenButton.setOnClickListener(view -> toggleFullscreen());
        addressBar.addView(fullscreenButton);

        root.addView(
            addressBar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        );

        webView = new WebView(this);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        root.addView(
            webView,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        HorizontalScrollView keyboardScroll = new HorizontalScrollView(this);
        keyboardScroll.setHorizontalScrollBarEnabled(false);
        keyboardScroll.setFillViewport(false);
        keyboardScroll.setBackgroundColor(Color.rgb(243, 243, 243));

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        keyRow.setPadding(dp(8), dp(6), dp(8), dp(6));

        controlButton = createKeyButton("Ctrl 🔓");
        controlButton.setOnClickListener(view -> {
            controlLocked = !controlLocked;
            updateModifierButtons();
            syncModifiers();
        });
        keyRow.addView(controlButton, keyLayoutParams(dp(82)));

        shiftButton = createKeyButton("Shift 🔓");
        shiftButton.setOnClickListener(view -> {
            shiftLocked = !shiftLocked;
            updateModifierButtons();
            syncModifiers();
        });
        keyRow.addView(shiftButton, keyLayoutParams(dp(88)));

        addKey(keyRow, "Esc", "Escape", "Escape", 27, dp(62));
        addKey(keyRow, "Tab", "Tab", "Tab", 9, dp(62));
        addKey(keyRow, "Enter", "Enter", "Enter", 13, dp(76));
        addKey(keyRow, "←", "ArrowLeft", "ArrowLeft", 37, dp(58));
        addKey(keyRow, "↑", "ArrowUp", "ArrowUp", 38, dp(58));
        addKey(keyRow, "↓", "ArrowDown", "ArrowDown", 40, dp(58));
        addKey(keyRow, "→", "ArrowRight", "ArrowRight", 39, dp(58));

        keyboardScroll.addView(keyRow);
        root.addView(
            keyboardScroll,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        );

        updateModifierButtons();
        return root;
    }

    private void configureSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        applyFullscreenState();
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        applyFullscreenState();
    }

    private void applyFullscreenState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (fullscreen) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (fullscreen) {
                flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        if (fullscreenButton != null) {
            fullscreenButton.setContentDescription(
                fullscreen ? "Exit fullscreen" : "Enter fullscreen"
            );
            fullscreenButton.setTextColor(fullscreen ? Color.WHITE : Color.BLACK);
            fullscreenButton.setBackgroundTintList(
                ColorStateList.valueOf(fullscreen ? ACCENT : KEY_BACKGROUND)
            );
        }

        if (rootContainer != null) {
            if (fullscreen) {
                rootContainer.setPadding(0, 0, 0, 0);
            }
            rootContainer.requestApplyInsets();
        }

        if (addressBar != null) {
            if (fullscreen) {
                hideAddressBar();
            } else {
                addressBar.setVisibility(View.VISIBLE);
            }
        }
    }

    private void applySafeAreaInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets safeArea = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            Insets ime = insets.getInsets(WindowInsets.Type.ime());

            if (fullscreen) {
                view.setPadding(0, 0, 0, ime.bottom);
            } else {
                view.setPadding(
                    safeArea.left,
                    safeArea.top,
                    safeArea.right,
                    Math.max(safeArea.bottom, ime.bottom)
                );
            }
        } else {
            int bottomInset = insets.getSystemWindowInsetBottom();
            int keyboardInset = bottomInset > dp(120) ? bottomInset : 0;
            if (fullscreen) {
                view.setPadding(0, 0, 0, keyboardInset);
            } else {
                view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    bottomInset
                );
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(90);
        int savedZoomPercent = preferences.getInt(ZOOM_PERCENT_KEY, 0);
        webView.setInitialScale(
            savedZoomPercent > 0 ? savedZoomPercent : calculateDesktopScale()
        );

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                installKeyboardBridge();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installKeyboardBridge();
            }

            @Override
            public void onScaleChanged(WebView view, float oldScale, float newScale) {
                super.onScaleChanged(view, oldScale, newScale);
                int zoomPercent = Math.round(newScale * 100f);
                if (zoomPercent > 0) {
                    preferences.edit().putInt(ZOOM_PERCENT_KEY, zoomPercent).apply();
                }
            }
        });
    }

    private void changeZoom(int direction) {
        if (webView == null || direction == 0) {
            return;
        }

        if (direction > 0) {
            webView.zoomIn();
        } else {
            webView.zoomOut();
        }
    }

    private void loadEnteredAddress() {
        String normalized = normalizeAddress(addressField.getText().toString());
        if (normalized.isEmpty()) {
            return;
        }

        addressField.setText(normalized);
        preferences.edit().putString(ADDRESS_KEY, normalized).apply();
        webView.loadUrl(normalized);
        addressField.clearFocus();
        webView.requestFocus();
    }

    private int calculateDesktopScale() {
        float density = getResources().getDisplayMetrics().density;
        float availableCssWidth = getResources().getDisplayMetrics().widthPixels / density;
        int scale = Math.round(availableCssWidth * 100f / DESKTOP_VIEWPORT_WIDTH);
        return Math.max(20, Math.min(60, scale));
    }

    private void hideAddressBar() {
        if (addressBar != null) {
            addressBar.setVisibility(View.GONE);
        }
    }

    private void loadProjects() {
        projects.clear();
        String serialized = preferences.getString(PROJECTS_KEY, "[]");
        try {
            JSONArray array = new JSONArray(serialized == null ? "[]" : serialized);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String name = item.optString("name", "").trim();
                String url = item.optString("url", "").trim();
                if (!name.isEmpty() && !url.isEmpty()) {
                    projects.add(new ProjectProfile(name, url));
                }
            }
        } catch (Exception ignored) {
            projects.clear();
        }
    }

    private void persistProjects() {
        JSONArray array = new JSONArray();
        for (ProjectProfile project : projects) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", project.name);
                item.put("url", project.url);
                array.put(item);
            } catch (Exception ignored) {}
        }
        preferences.edit().putString(PROJECTS_KEY, array.toString()).apply();
    }

    private void showProjectSwitcher() {
        List<String> choices = new ArrayList<>();
        choices.add("+ Save current address");
        for (ProjectProfile project : projects) {
            choices.add(project.name);
        }
        choices.add("Manage saved projects");

        new AlertDialog.Builder(this)
            .setTitle("Projects")
            .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                if (which == 0) {
                    saveCurrentAsProject();
                } else if (which <= projects.size()) {
                    openProject(projects.get(which - 1));
                } else {
                    showProjectManager();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveCurrentAsProject() {
        String url = normalizeAddress(addressField.getText().toString());
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a code-server address first", Toast.LENGTH_SHORT).show();
            return;
        }

        EditText nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setHint("Project name");
        nameField.setText("Project " + (projects.size() + 1));
        nameField.selectAll();

        new AlertDialog.Builder(this)
            .setTitle("Save project")
            .setMessage(url)
            .setView(nameField)
            .setPositiveButton("Save", (dialog, which) -> {
                String name = nameField.getText().toString().trim();
                if (name.isEmpty()) {
                    name = "Project " + (projects.size() + 1);
                }
                projects.add(new ProjectProfile(name, url));
                persistProjects();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void openProject(ProjectProfile project) {
        addressField.setText(project.url);
        preferences.edit().putString(ADDRESS_KEY, project.url).apply();
        webView.loadUrl(project.url);
        addressField.clearFocus();
        webView.requestFocus();
        if (fullscreen) {
            hideAddressBar();
        }
    }

    private void showProjectManager() {
        if (projects.isEmpty()) {
            Toast.makeText(this, "No saved projects", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[projects.size()];
        for (int index = 0; index < projects.size(); index++) {
            ProjectProfile project = projects.get(index);
            labels[index] = project.name + "\n" + project.url;
        }

        new AlertDialog.Builder(this)
            .setTitle("Tap a project to delete")
            .setItems(labels, (dialog, which) -> confirmProjectDeletion(which))
            .setNegativeButton("Done", null)
            .show();
    }

    private void confirmProjectDeletion(int index) {
        ProjectProfile project = projects.get(index);
        new AlertDialog.Builder(this)
            .setTitle("Delete " + project.name + "?")
            .setMessage(project.url)
            .setPositiveButton("Delete", (dialog, which) -> {
                projects.remove(index);
                persistProjects();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static String normalizeAddress(String address) {
        String trimmed = address == null ? "" : address.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "http://" + trimmed;
    }

    private void installKeyboardBridge() {
        webView.evaluateJavascript(KEYBOARD_BRIDGE, value -> syncModifiers());
    }

    private void syncModifiers() {
        String script = "if (window.__codeServerAppKeyboard) { "
            + "window.__codeServerAppKeyboard.setModifiers("
            + controlLocked + "," + shiftLocked + "); }";
        webView.evaluateJavascript(script, null);
        webView.requestFocus();
    }

    private void sendKey(String key, String code, int keyCode) {
        String script = String.format(Locale.US, """
            (() => {
              const target = document.activeElement || document.body;
              if (!target) return false;
              if (typeof target.focus === 'function') target.focus();
              const dispatch = (type) => {
                const event = new KeyboardEvent(type, {
                  key: %s,
                  code: %s,
                  ctrlKey: %s,
                  shiftKey: %s,
                  altKey: false,
                  metaKey: false,
                  bubbles: true,
                  cancelable: true,
                  composed: true
                });
                try {
                  Object.defineProperty(event, 'keyCode', { get: () => %d });
                  Object.defineProperty(event, 'which', { get: () => %d });
                } catch (_) {}
                target.dispatchEvent(event);
              };
              dispatch('keydown');
              dispatch('keyup');
              return true;
            })();
            """,
                JSONObject.quote(key),
                JSONObject.quote(code),
                controlLocked,
                shiftLocked,
                keyCode,
                keyCode
            );
        webView.evaluateJavascript(script, null);
        webView.requestFocus();
    }

    private void addKey(
        LinearLayout row,
        String label,
        String key,
        String code,
        int keyCode,
        int width
    ) {
        Button button = createKeyButton(label);
        button.setOnClickListener(view -> sendKey(key, code, keyCode));
        row.addView(button, keyLayoutParams(width));
    }

    private Button createToolbarButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private Button createKeyButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setTextColor(Color.BLACK);
        button.setBackgroundTintList(ColorStateList.valueOf(KEY_BACKGROUND));
        return button;
    }

    private void updateModifierButtons() {
        styleModifierButton(controlButton, "Ctrl", controlLocked);
        styleModifierButton(shiftButton, "Shift", shiftLocked);
    }

    private void styleModifierButton(Button button, String label, boolean locked) {
        if (button == null) {
            return;
        }
        button.setText(label + (locked ? " 🔒" : " 🔓"));
        button.setContentDescription(label + (locked ? " locked" : " unlocked"));
        button.setTextColor(locked ? Color.WHITE : Color.BLACK);
        button.setBackgroundTintList(ColorStateList.valueOf(locked ? ACCENT : KEY_BACKGROUND));
    }

    private LinearLayout.LayoutParams keyLayoutParams(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(46));
        params.setMarginEnd(dp(6));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            fullscreen = false;
            applyFullscreenState();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class EdgeGestureLayout extends LinearLayout {
        private float edgePullStartY;
        private boolean trackingEdgePull;

        EdgeGestureLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (fullscreen && addressBar != null && addressBar.getVisibility() != View.VISIBLE) {
                switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (event.getY() <= dp(32)) {
                        edgePullStartY = event.getY();
                        trackingEdgePull = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (trackingEdgePull) {
                        if (event.getY() - edgePullStartY >= dp(48)) {
                            trackingEdgePull = false;
                            fullscreen = false;
                            applyFullscreenState();
                        }
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (trackingEdgePull) {
                        trackingEdgePull = false;
                        return true;
                    }
                    break;
                default:
                    break;
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                trackingEdgePull = false;
            }
            return super.dispatchTouchEvent(event);
        }
    }

    private static final class ProjectProfile {
        final String name;
        final String url;

        ProjectProfile(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
