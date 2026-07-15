package net.archcangyuan.codeserverapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
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

import org.json.JSONObject;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFERENCES = "code_server_app";
    private static final String ADDRESS_KEY = "server_address";
    private static final int ACCENT = Color.rgb(103, 80, 164);
    private static final int KEY_BACKGROUND = Color.rgb(230, 230, 234);

    private static final String KEYBOARD_BRIDGE = """
        (() => {
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
    private EditText addressField;
    private WebView webView;
    private Button controlButton;
    private Button shiftButton;
    private boolean controlLocked;
    private boolean shiftLocked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        setContentView(createContentView());
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout addressBar = new LinearLayout(this);
        addressBar.setOrientation(LinearLayout.HORIZONTAL);
        addressBar.setGravity(Gravity.CENTER_VERTICAL);
        addressBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        addressBar.setBackgroundColor(Color.rgb(243, 243, 243));

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

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installKeyboardBridge();
            }
        });
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
        if (webView != null && webView.canGoBack()) {
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
}
