package net.archcangyuan.codeserverapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class MainActivity extends Activity {
    private static final String PREFERENCES = "code_server_app";
    private static final String ADDRESS_KEY = "server_address";
    private static final String PROJECTS_KEY = "saved_projects";
    private static final String LEGACY_NATIVE_ZOOM_PERCENT_KEY = "zoom_percent";
    private static final String LAYOUT_ZOOM_STEPS_KEY = "layout_zoom_steps";
    private static final String VIEWPORT_RELOAD_ZOOM_MIGRATED_KEY =
        "viewport_reload_zoom_migrated";
    private static final int DESKTOP_VIEWPORT_WIDTH = 1280;
    private static final int MIN_LAYOUT_ZOOM_STEPS = -6;
    private static final int MAX_LAYOUT_ZOOM_STEPS = 6;
    private static final double LAYOUT_ZOOM_FACTOR = 1.1;
    private static final long PROJECT_SESSION_TTL_MS = 30L * 60L * 1_000L;
    private static final int MAX_HOT_PROJECT_SESSIONS = 6;
    private static final int ACCENT = Color.rgb(103, 80, 164);
    private static final int KEY_BACKGROUND = Color.rgb(230, 230, 234);
    private static final String DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String KEYBOARD_BRIDGE = """
        (() => {
          const PROXY_ID = '__code_server_app_keyboard_proxy';
          const setViewportWidth = (requestedWidth) => {
            const numericWidth = Number(requestedWidth) || 1280;
            const width = Math.max(640, Math.min(2400, Math.round(numericWidth)));
            let viewport = document.querySelector('meta[name="viewport"]');
            if (!viewport) {
              viewport = document.createElement('meta');
              viewport.setAttribute('name', 'viewport');
              (document.head || document.documentElement).appendChild(viewport);
            }
            viewport.setAttribute(
              'content',
              `width=${width}, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes`
            );
            document.documentElement.style.zoom = '1';
            if (document.body) {
              document.body.style.zoom = '1';
              document.body.style.width = '';
              document.body.style.minWidth = '';
            }
            window.__codeServerAppViewportWidth = width;
            requestAnimationFrame(() => window.dispatchEvent(new Event('resize')));
            return width;
          };

          window.__codeServerAppSetViewportWidth = setViewportWidth;
          setViewportWidth(window.__codeServerAppViewportWidth || 1280);

          const findIronRdpCanvas = () => {
            const roots = [document];
            const visited = new Set();
            for (let index = 0; index < roots.length && index < 128; index += 1) {
              const root = roots[index];
              if (!root || visited.has(root) || !root.querySelectorAll) continue;
              visited.add(root);

              const directCanvas = root.querySelector('canvas#renderer');
              if (directCanvas) return directCanvas;

              const ironHosts = root.querySelectorAll(
                'iron-remote-desktop, iron-remote-gui'
              );
              for (const host of ironHosts) {
                const canvas = host.shadowRoot?.querySelector('canvas#renderer');
                if (canvas) return canvas;
              }

              for (const element of root.querySelectorAll('*')) {
                if (element.shadowRoot && !visited.has(element.shadowRoot)) {
                  roots.push(element.shadowRoot);
                }
                if (element.tagName === 'IFRAME') {
                  try {
                    if (element.contentDocument) roots.push(element.contentDocument);
                  } catch (_) {}
                }
              }
            }
            return null;
          };

          const existingBridge = window.__codeServerAppKeyboard;
          if (existingBridge && existingBridge.version >= 5) {
            window.__codeServerAppForceKeyboard = () => existingBridge.forceKeyboard();
            existingBridge.installRdpGestures?.();
            return;
          }

          const state = {
            control: false,
            shift: false,
            target: null,
            composing: false,
            lastForwardedKey: '',
            lastForwardedAt: 0,
            lastCompositionText: '',
            lastCompositionAt: 0,
            ironRdpCanvas: null,
            gestureCanvas: null
          };

          const pointFromTouch = (touch) => ({
            clientX: touch.clientX,
            clientY: touch.clientY,
            screenX: touch.screenX,
            screenY: touch.screenY
          });

          const dispatchPointer = (canvas, type, point, button, buttons, detail = 1) => {
            const eventWindow = canvas.ownerDocument?.defaultView || window;
            const event = new eventWindow.PointerEvent(type, {
              bubbles: true,
              cancelable: true,
              composed: true,
              pointerId: 1,
              pointerType: 'mouse',
              isPrimary: true,
              clientX: point.clientX,
              clientY: point.clientY,
              screenX: point.screenX,
              screenY: point.screenY,
              button,
              buttons,
              detail
            });
            canvas.dispatchEvent(event);
          };

          const dispatchMouse = (canvas, type, point, button, buttons, detail = 1) => {
            const eventWindow = canvas.ownerDocument?.defaultView || window;
            canvas.dispatchEvent(new eventWindow.MouseEvent(type, {
              bubbles: true,
              cancelable: true,
              composed: true,
              view: eventWindow,
              clientX: point.clientX,
              clientY: point.clientY,
              screenX: point.screenX,
              screenY: point.screenY,
              button,
              buttons,
              detail
            }));
          };

          const installRdpGestures = () => {
            const canvas = findIronRdpCanvas();
            if (!canvas) return false;
            state.ironRdpCanvas = canvas;
            if (state.gestureCanvas === canvas) return true;
            state.gestureCanvas = canvas;
            canvas.style.touchAction = 'none';
            canvas.style.webkitTouchCallout = 'none';

            let gesture = null;
            let lastTap = null;
            let suppressNativeClickUntil = 0;

            const stopNativeTouchPointer = (event) => {
              if (event.pointerType !== 'touch') return;
              event.preventDefault();
              event.stopImmediatePropagation();
            };
            canvas.addEventListener('pointerdown', stopNativeTouchPointer, true);
            canvas.addEventListener('pointermove', stopNativeTouchPointer, true);
            canvas.addEventListener('pointerup', stopNativeTouchPointer, true);
            canvas.addEventListener('pointercancel', stopNativeTouchPointer, true);
            canvas.addEventListener('click', (event) => {
              if (event.isTrusted && performance.now() < suppressNativeClickUntil) {
                event.preventDefault();
                event.stopImmediatePropagation();
              }
            }, true);
            canvas.addEventListener('contextmenu', (event) => {
              if (event.isTrusted) event.preventDefault();
            }, true);

            canvas.addEventListener('touchstart', (event) => {
              if (event.touches.length !== 1) return;
              event.preventDefault();
              event.stopImmediatePropagation();
              const start = pointFromTouch(event.touches[0]);
              gesture = {
                start,
                last: start,
                dragging: false,
                longPressed: false,
                timer: window.setTimeout(() => {
                  if (!gesture || gesture.dragging) return;
                  gesture.longPressed = true;
                  dispatchPointer(canvas, 'pointerdown', gesture.last, 2, 2);
                  dispatchPointer(canvas, 'pointerup', gesture.last, 2, 0);
                  dispatchMouse(canvas, 'contextmenu', gesture.last, 2, 0);
                  suppressNativeClickUntil = performance.now() + 800;
                }, 550)
              };
            }, { capture: true, passive: false });

            canvas.addEventListener('touchmove', (event) => {
              if (!gesture || event.touches.length !== 1) return;
              event.preventDefault();
              event.stopImmediatePropagation();
              const point = pointFromTouch(event.touches[0]);
              gesture.last = point;
              const distance = Math.hypot(
                point.clientX - gesture.start.clientX,
                point.clientY - gesture.start.clientY
              );
              if (!gesture.dragging && !gesture.longPressed && distance >= 7) {
                window.clearTimeout(gesture.timer);
                gesture.dragging = true;
                dispatchPointer(canvas, 'pointerdown', gesture.start, 0, 1);
              }
              if (gesture.dragging) {
                dispatchPointer(canvas, 'pointermove', point, -1, 1);
              }
            }, { capture: true, passive: false });

            const finishGesture = (event, cancelled) => {
              if (!gesture) return;
              event.preventDefault();
              event.stopImmediatePropagation();
              window.clearTimeout(gesture.timer);
              const touch = event.changedTouches?.[0];
              const point = touch ? pointFromTouch(touch) : gesture.last;
              if (gesture.dragging) {
                dispatchPointer(canvas, 'pointermove', point, -1, 1);
                dispatchPointer(canvas, 'pointerup', point, 0, 0);
              } else if (!gesture.longPressed && !cancelled) {
                const now = performance.now();
                const isDouble = lastTap
                  && now - lastTap.at < 350
                  && Math.hypot(
                    point.clientX - lastTap.point.clientX,
                    point.clientY - lastTap.point.clientY
                  ) < 24;
                const detail = isDouble ? 2 : 1;
                dispatchPointer(canvas, 'pointerdown', point, 0, 1, detail);
                dispatchPointer(canvas, 'pointerup', point, 0, 0, detail);
                dispatchMouse(canvas, 'click', point, 0, 0, detail);
                if (isDouble) {
                  dispatchMouse(canvas, 'dblclick', point, 0, 0, 2);
                  lastTap = null;
                } else {
                  lastTap = { at: now, point };
                }
                suppressNativeClickUntil = now + 800;
              }
              gesture = null;
            };
            canvas.addEventListener('touchend', (event) => {
              finishGesture(event, false);
            }, { capture: true, passive: false });
            canvas.addEventListener('touchcancel', (event) => {
              finishGesture(event, true);
            }, { capture: true, passive: false });
            return true;
          };

          installRdpGestures();
          window.setInterval(installRdpGestures, 1000);

          const isProxy = (element) => Boolean(element && element.id === PROXY_ID);

          const deepestActiveElement = (rootDocument) => {
            let active = rootDocument.activeElement;
            for (let depth = 0; active && depth < 6; depth += 1) {
              if (active.tagName !== 'IFRAME') break;
              try {
                const childDocument = active.contentDocument;
                if (!childDocument || !childDocument.activeElement) break;
                active = childDocument.activeElement;
              } catch (_) {
                break;
              }
            }
            return active;
          };

          const rememberTarget = (candidate) => {
            if (!candidate || isProxy(candidate)) return;
            const ownerDocument = candidate.ownerDocument;
            const isGeneric = candidate.tagName === 'HTML'
              || Boolean(ownerDocument && candidate === ownerDocument.body);
            if (!isGeneric || !state.target) state.target = candidate;
          };

          const activeTarget = () => {
            if (state.ironRdpCanvas && state.ironRdpCanvas.isConnected) {
              return state.ironRdpCanvas;
            }
            const current = deepestActiveElement(document);
            if (current && !isProxy(current)) {
              rememberTarget(current);
              const ownerDocument = current.ownerDocument;
              const isGeneric = current.tagName === 'HTML'
                || Boolean(ownerDocument && current === ownerDocument.body);
              if (!isGeneric) return current;
            }
            if (state.target && state.target.isConnected) return state.target;
            const fallback = document.querySelector(
              'canvas, [role="application"], [tabindex="0"]'
            ) || document.body || document.documentElement;
            rememberTarget(fallback);
            return fallback;
          };

          const defineLegacyKeyCodes = (event, keyCode, charCode = 0) => {
            try {
              Object.defineProperty(event, 'keyCode', { get: () => keyCode });
              Object.defineProperty(event, 'which', {
                get: () => charCode || keyCode
              });
              Object.defineProperty(event, 'charCode', { get: () => charCode });
            } catch (_) {}
          };

          const dispatchKeyPhase = (
            target,
            type,
            key,
            code,
            keyCode,
            source = null
          ) => {
            if (!target) return false;
            const printable = typeof key === 'string' && Array.from(key).length === 1;
            const event = new KeyboardEvent(type, {
              key,
              code: code || '',
              location: source ? source.location : 0,
              repeat: source ? source.repeat : false,
              ctrlKey: state.control || Boolean(source && source.ctrlKey),
              shiftKey: state.shift || Boolean(source && source.shiftKey),
              altKey: Boolean(source && source.altKey),
              metaKey: Boolean(source && source.metaKey),
              bubbles: true,
              cancelable: true,
              composed: true
            });
            const charCode = type === 'keypress' && printable
              ? key.codePointAt(0)
              : 0;
            defineLegacyKeyCodes(event, keyCode || 0, charCode);
            return target.dispatchEvent(event);
          };

          const keyInfoForText = (key) => {
            if (/^[a-z]$/i.test(key)) {
              const upper = key.toUpperCase();
              return {
                code: `Key${upper}`,
                keyCode: upper.charCodeAt(0),
                shift: key === upper
              };
            }
            if (/^[0-9]$/.test(key)) {
              return {
                code: `Digit${key}`,
                keyCode: key.charCodeAt(0),
                shift: false
              };
            }
            if (key === ' ') return { code: 'Space', keyCode: 32, shift: false };
            const punctuation = {
              '`': ['Backquote', 192, false], '~': ['Backquote', 192, true],
              '-': ['Minus', 189, false], '_': ['Minus', 189, true],
              '=': ['Equal', 187, false], '+': ['Equal', 187, true],
              '[': ['BracketLeft', 219, false], '{': ['BracketLeft', 219, true],
              ']': ['BracketRight', 221, false], '}': ['BracketRight', 221, true],
              '\\\\': ['Backslash', 220, false], '|': ['Backslash', 220, true],
              ';': ['Semicolon', 186, false], ':': ['Semicolon', 186, true],
              "'": ['Quote', 222, false], '"': ['Quote', 222, true],
              ',': ['Comma', 188, false], '<': ['Comma', 188, true],
              '.': ['Period', 190, false], '>': ['Period', 190, true],
              '/': ['Slash', 191, false], '?': ['Slash', 191, true],
              '!': ['Digit1', 49, true], '@': ['Digit2', 50, true],
              '#': ['Digit3', 51, true], '$': ['Digit4', 52, true],
              '%': ['Digit5', 53, true], '^': ['Digit6', 54, true],
              '&': ['Digit7', 55, true], '*': ['Digit8', 56, true],
              '(': ['Digit9', 57, true], ')': ['Digit0', 48, true]
            };
            const mapped = punctuation[key];
            if (mapped) {
              return { code: mapped[0], keyCode: mapped[1], shift: mapped[2] };
            }
            return {
              code: '',
              keyCode: key.codePointAt(0) || 0,
              shift: false
            };
          };

          const dispatchCompleteKey = (key, code, keyCode, forceShift = false) => {
            const target = activeTarget();
            if (!target) return;
            const source = forceShift ? { shiftKey: true } : null;
            dispatchKeyPhase(target, 'keydown', key, code, keyCode, source);
            if (Array.from(key).length === 1 && !state.control) {
              dispatchKeyPhase(target, 'keypress', key, code, keyCode, source);
            }
            dispatchKeyPhase(target, 'keyup', key, code, keyCode, source);
          };

          const forwardText = (text) => {
            for (const key of Array.from(text || '')) {
              const info = keyInfoForText(key);
              dispatchCompleteKey(key, info.code, info.keyCode, info.shift);
            }
          };

          const wasRecentlyForwarded = (key) =>
            state.lastForwardedKey === key
              && performance.now() - state.lastForwardedAt < 500;

          const handleProxyInput = (event) => {
            if (!isProxy(event.target)) return;
            event.stopImmediatePropagation();
            const input = event.target;
            if (event.isComposing || state.composing) return;

            const inputType = event.inputType || '';
            if (inputType.startsWith('deleteContentBackward')) {
              if (!wasRecentlyForwarded('Backspace')) {
                dispatchCompleteKey('Backspace', 'Backspace', 8);
              }
            } else if (inputType === 'insertLineBreak'
                || inputType === 'insertParagraph') {
              if (!wasRecentlyForwarded('Enter')) {
                dispatchCompleteKey('Enter', 'Enter', 13);
              }
            } else {
              const text = event.data != null ? event.data : input.value;
              const duplicateComposition = text
                && text === state.lastCompositionText
                && performance.now() - state.lastCompositionAt < 500;
              const duplicateKey = text
                && Array.from(text).length === 1
                && wasRecentlyForwarded(text);
              if (text && !duplicateComposition && !duplicateKey) forwardText(text);
            }
            input.value = '';
          };

          const ensureProxy = () => {
            let input = document.getElementById(PROXY_ID);
            if (input) return input;
            input = document.createElement('input');
            input.id = PROXY_ID;
            input.type = 'text';
            input.inputMode = 'text';
            input.autocomplete = 'off';
            input.autocapitalize = 'off';
            input.spellcheck = false;
            input.setAttribute('enterkeyhint', 'enter');
            input.setAttribute('aria-label', 'CodeServerApp keyboard proxy');
            Object.assign(input.style, {
              position: 'fixed',
              left: '1px',
              bottom: '1px',
              width: '1px',
              height: '1px',
              padding: '0',
              border: '0',
              opacity: '0.01',
              zIndex: '2147483647'
            });
            input.addEventListener('input', handleProxyInput);
            input.addEventListener('compositionstart', (event) => {
              event.stopImmediatePropagation();
              state.composing = true;
            });
            input.addEventListener('compositionend', (event) => {
              event.stopImmediatePropagation();
              state.composing = false;
              const text = event.data || input.value;
              if (text) {
                forwardText(text);
                state.lastCompositionText = text;
                state.lastCompositionAt = performance.now();
              }
              input.value = '';
            });
            (document.body || document.documentElement).appendChild(input);
            return input;
          };

          const forceKeyboard = () => {
            installRdpGestures();
            const ironRdpCanvas = findIronRdpCanvas();
            if (ironRdpCanvas) {
              state.ironRdpCanvas = ironRdpCanvas;
              rememberTarget(ironRdpCanvas);
              ironRdpCanvas.focus({ preventScroll: true });
              return 'ironrdp';
            }
            rememberTarget(deepestActiveElement(document));
            return activeTarget() ? 'generic' : 'missing';
          };

          const forwardProxyKey = (event) => {
            if (!event.isTrusted || !isProxy(event.target)) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            const keyCode = event.keyCode || event.which || 0;
            if (event.isComposing || keyCode === 229
                || event.key === 'Unidentified' || event.key === 'Process'
                || event.key === 'Dead') {
              return;
            }
            const target = activeTarget();
            dispatchKeyPhase(target, event.type, event.key, event.code, keyCode, event);
            state.lastForwardedKey = event.key;
            state.lastForwardedAt = performance.now();
          };

          const dispatchModifier = (key, code, keyCode, isDown) => {
            dispatchKeyPhase(
              activeTarget(),
              isDown ? 'keydown' : 'keyup',
              key,
              code,
              keyCode
            );
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
            dispatchKeyPhase(
              target,
              event.type,
              shiftedKey,
              event.code,
              event.keyCode || event.which || 0,
              event
            );
          };

          document.addEventListener('pointerdown', (event) => {
            const path = typeof event.composedPath === 'function'
              ? event.composedPath()
              : [];
            rememberTarget(path[0] || event.target);
          }, true);
          document.addEventListener('focusin', (event) => {
            rememberTarget(event.target);
          }, true);
          document.addEventListener('keydown', forwardProxyKey, true);
          document.addEventListener('keyup', forwardProxyKey, true);
          document.addEventListener('keydown', redispatchWithLockedModifiers, true);
          document.addEventListener('keyup', redispatchWithLockedModifiers, true);

          const bridge = {
            version: 5,
            forceKeyboard,
            installRdpGestures,
            sendKey(key, code, keyCode) {
              dispatchCompleteKey(key, code, keyCode);
              return true;
            },
            sendText(text) {
              forwardText(String(text || ''));
              return true;
            },
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
          window.__codeServerAppKeyboard = bridge;
          window.__codeServerAppForceKeyboard = () => bridge.forceKeyboard();
        })();
        """;

    private SharedPreferences preferences;
    private final List<ProjectProfile> projects = new ArrayList<>();
    private final Map<String, ProjectSession> projectSessions = new LinkedHashMap<>();
    private final Map<WebView, Integer> appliedLayoutZoomSteps = new WeakHashMap<>();
    private final Map<WebView, String> lastFinishedUrls = new WeakHashMap<>();
    private final Set<WebView> zoomReloadInProgress =
        Collections.newSetFromMap(new WeakHashMap<>());
    private LinearLayout rootContainer;
    private LinearLayout addressBar;
    private EditText addressField;
    private FrameLayout webContainer;
    private WebView webView;
    private String activeSessionKey;
    private Button controlButton;
    private Button shiftButton;
    private Button fullscreenButton;
    private boolean controlLocked;
    private boolean shiftLocked;
    private boolean fullscreen;
    private int layoutZoomSteps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        preferences.edit().remove(LEGACY_NATIVE_ZOOM_PERCENT_KEY).apply();
        if (!preferences.getBoolean(VIEWPORT_RELOAD_ZOOM_MIGRATED_KEY, false)) {
            preferences.edit()
                .putInt(LAYOUT_ZOOM_STEPS_KEY, 0)
                .putBoolean(VIEWPORT_RELOAD_ZOOM_MIGRATED_KEY, true)
                .apply();
        }
        layoutZoomSteps = Math.max(
            MIN_LAYOUT_ZOOM_STEPS,
            Math.min(
                MAX_LAYOUT_ZOOM_STEPS,
                preferences.getInt(LAYOUT_ZOOM_STEPS_KEY, 0)
            )
        );
        loadProjects();
        setContentView(createContentView());
        configureSystemUi();

        String savedAddress = preferences.getString(ADDRESS_KEY, "");
        addressField.setText(savedAddress);
        if (savedAddress == null || savedAddress.trim().isEmpty()) {
            showBlankWebView();
            addressField.requestFocus();
        } else {
            switchToProjectUrl(savedAddress);
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
        zoomOutButton.setFocusable(false);
        zoomOutButton.setFocusableInTouchMode(false);
        zoomOutButton.setOnClickListener(view -> changeZoom(-1));
        addressBar.addView(zoomOutButton);

        Button zoomInButton = createToolbarButton("+");
        zoomInButton.setContentDescription("Zoom in");
        zoomInButton.setFocusable(false);
        zoomInButton.setFocusableInTouchMode(false);
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

        webContainer = new FrameLayout(this);
        root.addView(
            webContainer,
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
        keyRow.setPadding(dp(6), dp(6), dp(6), dp(6));

        Button keyboardButton = createKeyButton("KB");
        keyboardButton.setContentDescription("Force show keyboard");
        keyboardButton.setOnClickListener(view -> forceShowKeyboard());
        keyRow.addView(keyboardButton, keyLayoutParams(dp(54)));

        controlButton = createKeyButton("Ctrl 🔓");
        controlButton.setOnClickListener(view -> {
            controlLocked = !controlLocked;
            updateModifierButtons();
            syncModifiers();
            syncModifierImeCapture();
        });
        keyRow.addView(controlButton, keyLayoutParams(dp(72)));

        shiftButton = createKeyButton("Shift 🔓");
        shiftButton.setOnClickListener(view -> {
            shiftLocked = !shiftLocked;
            updateModifierButtons();
            syncModifiers();
            syncModifierImeCapture();
        });
        keyRow.addView(shiftButton, keyLayoutParams(dp(76)));

        addKey(keyRow, "Esc", "Escape", "Escape", 27, dp(54));
        addKey(keyRow, "Tab", "Tab", "Tab", 9, dp(54));
        addKey(keyRow, "Enter", "Enter", "Enter", 13, dp(64));
        addKey(keyRow, "Bksp", "Backspace", "Backspace", 8, dp(64));
        addKey(keyRow, "←", "ArrowLeft", "ArrowLeft", 37, dp(50));
        addKey(keyRow, "↑", "ArrowUp", "ArrowUp", 38, dp(50));
        addKey(keyRow, "↓", "ArrowDown", "ArrowDown", 40, dp(50));
        addKey(keyRow, "→", "ArrowRight", "ArrowRight", 39, dp(50));

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
        boolean imeVisible;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets safeArea = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            imeVisible = insets.isVisible(WindowInsets.Type.ime()) || ime.bottom > 0;

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
            imeVisible = keyboardInset > 0;
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
        if (webView instanceof RdpInputWebView) {
            ((RdpInputWebView) webView).setImeVisible(imeVisible);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView target) {
        WebSettings settings = target.getSettings();
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
        target.setInitialScale(0);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(target, true);

        target.setWebChromeClient(new WebChromeClient());
        target.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                String lastFinishedUrl = lastFinishedUrls.get(view);
                if (!zoomReloadInProgress.contains(view)
                    && (lastFinishedUrl == null || !lastFinishedUrl.equals(url))) {
                    appliedLayoutZoomSteps.remove(view);
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateAddressFromWebView(view, url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                installKeyboardBridge(view, false, false);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                lastFinishedUrls.put(view, url);
                updateAddressFromWebView(view, url);
                boolean completedZoomReload = zoomReloadInProgress.remove(view);
                installKeyboardBridge(view, true, !completedZoomReload);
            }

        });
    }

    private WebView createProjectWebView() {
        WebView target = new RdpInputWebView(this);
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
        target.setVisibility(View.GONE);
        configureWebView(target);
        webContainer.addView(
            target,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );
        return target;
    }

    private void showBlankWebView() {
        webView = createProjectWebView();
        webView.setVisibility(View.VISIBLE);
        webView.onResume();
        activeSessionKey = null;
    }

    private void changeZoom(int direction) {
        if (webView == null || direction == 0) {
            return;
        }

        int nextSteps = Math.max(
            MIN_LAYOUT_ZOOM_STEPS,
            Math.min(MAX_LAYOUT_ZOOM_STEPS, layoutZoomSteps + direction)
        );
        if (nextSteps == layoutZoomSteps) {
            return;
        }

        layoutZoomSteps = nextSteps;
        preferences.edit().putInt(LAYOUT_ZOOM_STEPS_KEY, layoutZoomSteps).apply();
        applyLayoutZoom(webView, true);

        int zoomPercent = (int) Math.round(
            Math.pow(LAYOUT_ZOOM_FACTOR, layoutZoomSteps) * 100.0
        );
        Toast.makeText(
            this,
            "UI zoom " + zoomPercent + "% - reloading to fit",
            Toast.LENGTH_SHORT
        ).show();
    }

    private int calculateLayoutViewportWidth() {
        return (int) Math.round(
            DESKTOP_VIEWPORT_WIDTH / Math.pow(LAYOUT_ZOOM_FACTOR, layoutZoomSteps)
        );
    }

    private void applyLayoutZoom(WebView target, boolean reloadAfterApply) {
        if (target == null) {
            return;
        }
        int requestedSteps = layoutZoomSteps;
        int viewportWidth = calculateLayoutViewportWidth();
        String script = "(() => {"
            + "const width=" + viewportWidth + ";"
            + "if(window.__codeServerAppSetViewportWidth){"
            + "return window.__codeServerAppSetViewportWidth(width);}"
            + "let viewport=document.querySelector('meta[name=viewport]');"
            + "if(!viewport){viewport=document.createElement('meta');"
            + "viewport.name='viewport';"
            + "(document.head||document.documentElement).appendChild(viewport);}"
            + "viewport.content='width='+width+','"
            + "+' minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes';"
            + "document.documentElement.style.zoom='1';"
            + "if(document.body){document.body.style.zoom='1';"
            + "document.body.style.width='';document.body.style.minWidth='';}"
            + "window.__codeServerAppViewportWidth=width;"
            + "window.dispatchEvent(new Event('resize'));"
            + "return width;"
            + "})()";
        target.evaluateJavascript(script, value -> {
            if (requestedSteps != layoutZoomSteps) {
                return;
            }
            appliedLayoutZoomSteps.put(target, requestedSteps);
            target.requestLayout();
            target.invalidate();
            if (reloadAfterApply && target.getUrl() != null) {
                zoomReloadInProgress.add(target);
                target.reload();
            }
        });
    }

    private void forceShowKeyboard() {
        if (webView == null) {
            return;
        }
        WebView target = webView;
        target.requestFocus();
        String script = "window.__codeServerAppForceKeyboard"
            + " ? window.__codeServerAppForceKeyboard() : false";
        target.evaluateJavascript(script, value -> target.postDelayed(() -> {
            if (target != webView) {
                return;
            }
            Toast.makeText(
                this,
                "\"ironrdp\"".equals(value)
                    ? "IronRDP focused"
                    : "RDP canvas not found",
                Toast.LENGTH_SHORT
            ).show();
            if (target instanceof RdpInputWebView) {
                ((RdpInputWebView) target).showForcedIme(
                    "\"ironrdp\"".equals(value)
                );
            }
        }, 100L));
    }

    private void switchToProjectUrl(String address) {
        String normalized = normalizeAddress(address);
        if (normalized.isEmpty()) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        cleanupExpiredProjectSessions(now);

        Map.Entry<String, ProjectSession> existingEntry = findProjectSession(normalized);
        ProjectSession targetSession = existingEntry == null ? null : existingEntry.getValue();
        String targetSessionKey = existingEntry == null ? normalized : existingEntry.getKey();
        boolean created = targetSession == null;
        if (created) {
            targetSession = new ProjectSession(createProjectWebView());
            projectSessions.put(normalized, targetSession);
            targetSessionKey = normalized;
        }

        activateProjectSession(targetSessionKey, targetSession, now);
        if (created) {
            targetSession.webView.loadUrl(normalized);
        }
        evictExcessProjectSessions();

        String currentUrl = targetSession.webView.getUrl();
        String displayedAddress = currentUrl == null || currentUrl.trim().isEmpty()
            ? normalized
            : currentUrl;
        addressField.setText(displayedAddress);
        preferences.edit().putString(ADDRESS_KEY, displayedAddress).apply();
        addressField.clearFocus();
        targetSession.webView.requestFocus();
    }

    private Map.Entry<String, ProjectSession> findProjectSession(String address) {
        String normalized = normalizeAddress(address);
        ProjectSession exact = projectSessions.get(normalized);
        if (exact != null) {
            return new AbstractMap.SimpleImmutableEntry<>(normalized, exact);
        }
        for (Map.Entry<String, ProjectSession> entry : projectSessions.entrySet()) {
            String currentUrl = entry.getValue().webView.getUrl();
            if (currentUrl != null && normalizeAddress(currentUrl).equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    private void updateAddressFromWebView(WebView source, String url) {
        if (source != webView || url == null) {
            return;
        }
        String normalized = normalizeAddress(url);
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return;
        }
        if (addressField != null && !addressField.hasFocus()) {
            addressField.setText(normalized);
        }
        preferences.edit().putString(ADDRESS_KEY, normalized).apply();
    }

    private void activateProjectSession(
        String sessionKey,
        ProjectSession targetSession,
        long now
    ) {
        if (webView == targetSession.webView) {
            activeSessionKey = sessionKey;
            targetSession.webView.requestFocus();
            return;
        }

        if (webView != null) {
            if (activeSessionKey == null) {
                destroyWebView(webView);
            } else {
                ProjectSession currentSession = projectSessions.get(activeSessionKey);
                if (currentSession != null) {
                    currentSession.lastInactiveAt = now;
                }
                webView.onPause();
                webView.setVisibility(View.GONE);
            }
        }

        webView = targetSession.webView;
        activeSessionKey = sessionKey;
        targetSession.lastInactiveAt = 0L;
        webView.setVisibility(View.VISIBLE);
        webView.bringToFront();
        webView.onResume();
        Integer appliedSteps = appliedLayoutZoomSteps.get(webView);
        boolean needsReload = appliedSteps != null && appliedSteps != layoutZoomSteps;
        applyLayoutZoom(webView, needsReload);
        syncModifiers(webView);
    }

    private void cleanupExpiredProjectSessions(long now) {
        Iterator<Map.Entry<String, ProjectSession>> iterator =
            projectSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ProjectSession> entry = iterator.next();
            if (entry.getKey().equals(activeSessionKey)) {
                continue;
            }
            ProjectSession session = entry.getValue();
            if (session.lastInactiveAt > 0L
                && now - session.lastInactiveAt >= PROJECT_SESSION_TTL_MS) {
                iterator.remove();
                destroyWebView(session.webView);
            }
        }
    }

    private void evictExcessProjectSessions() {
        while (projectSessions.size() > MAX_HOT_PROJECT_SESSIONS) {
            String oldestKey = null;
            long oldestInactiveAt = Long.MAX_VALUE;
            for (Map.Entry<String, ProjectSession> entry : projectSessions.entrySet()) {
                if (entry.getKey().equals(activeSessionKey)) {
                    continue;
                }
                if (entry.getValue().lastInactiveAt < oldestInactiveAt) {
                    oldestKey = entry.getKey();
                    oldestInactiveAt = entry.getValue().lastInactiveAt;
                }
            }
            if (oldestKey == null) {
                return;
            }
            ProjectSession removed = projectSessions.remove(oldestKey);
            if (removed != null) {
                destroyWebView(removed.webView);
            }
        }
    }

    private boolean isProjectSessionHot(String address, long now) {
        Map.Entry<String, ProjectSession> entry = findProjectSession(address);
        if (entry == null) {
            return false;
        }
        ProjectSession session = entry.getValue();
        return entry.getKey().equals(activeSessionKey)
            || (session.lastInactiveAt > 0L
                && now - session.lastInactiveAt < PROJECT_SESSION_TTL_MS);
    }

    private void destroyWebView(WebView target) {
        appliedLayoutZoomSteps.remove(target);
        lastFinishedUrls.remove(target);
        zoomReloadInProgress.remove(target);
        if (webContainer != null) {
            webContainer.removeView(target);
        }
        target.stopLoading();
        target.destroy();
    }

    private void loadEnteredAddress() {
        String normalized = normalizeAddress(addressField.getText().toString());
        if (normalized.isEmpty()) {
            return;
        }

        switchToProjectUrl(normalized);
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
        long now = SystemClock.elapsedRealtime();
        cleanupExpiredProjectSessions(now);

        List<CharSequence> choices = new ArrayList<>();
        choices.add("+ Save current address");
        for (ProjectProfile project : projects) {
            choices.add(styledProjectLabel(project, false, isProjectSessionHot(project.url, now)));
        }
        choices.add("Manage saved projects");

        new AlertDialog.Builder(this)
            .setTitle(boldText("Projects"))
            .setItems(choices.toArray(new CharSequence[0]), (dialog, which) -> {
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
            .setTitle(boldText("Save project"))
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
        switchToProjectUrl(project.url);
        if (fullscreen) {
            hideAddressBar();
        }
    }

    private void showProjectManager() {
        if (projects.isEmpty()) {
            Toast.makeText(this, "No saved projects", Toast.LENGTH_SHORT).show();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        cleanupExpiredProjectSessions(now);
        CharSequence[] labels = new CharSequence[projects.size()];
        for (int index = 0; index < projects.size(); index++) {
            ProjectProfile project = projects.get(index);
            labels[index] = styledProjectLabel(
                project,
                true,
                isProjectSessionHot(project.url, now)
            );
        }

        new AlertDialog.Builder(this)
            .setTitle(boldText("Tap a project to delete"))
            .setItems(labels, (dialog, which) -> confirmProjectDeletion(which))
            .setNegativeButton("Done", null)
            .show();
    }

    private void confirmProjectDeletion(int index) {
        ProjectProfile project = projects.get(index);
        new AlertDialog.Builder(this)
            .setTitle(boldText("Delete " + project.name + "?"))
            .setMessage(project.url)
            .setPositiveButton("Delete", (dialog, which) -> {
                projects.remove(index);
                persistProjects();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static CharSequence boldText(String text) {
        SpannableString styled = new SpannableString(text);
        styled.setSpan(
            new StyleSpan(Typeface.BOLD),
            0,
            text.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return styled;
    }

    private static CharSequence styledProjectLabel(
        ProjectProfile project,
        boolean includeUrl,
        boolean hot
    ) {
        String suffix = hot ? "  • HOT" : "";
        String text = project.name + suffix + (includeUrl ? "\n" + project.url : "");
        SpannableString styled = new SpannableString(text);
        styled.setSpan(
            new StyleSpan(Typeface.BOLD),
            0,
            project.name.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return styled;
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

    private void installKeyboardBridge(
        WebView target,
        boolean applyZoom,
        boolean allowZoomReload
    ) {
        target.evaluateJavascript(KEYBOARD_BRIDGE, value -> {
            if (applyZoom) {
                Integer appliedSteps = appliedLayoutZoomSteps.get(target);
                boolean needsReload = allowZoomReload
                    && ((appliedSteps == null && layoutZoomSteps != 0)
                        || (appliedSteps != null && appliedSteps != layoutZoomSteps));
                applyLayoutZoom(target, needsReload);
            }
            syncModifiers(target);
        });
    }

    private void syncModifiers() {
        if (webView != null) {
            syncModifiers(webView);
        }
    }

    private void syncModifierImeCapture() {
        if (webView instanceof RdpInputWebView) {
            ((RdpInputWebView) webView).syncModifierCapture(
                controlLocked || shiftLocked
            );
        }
    }

    private void syncModifiers(WebView target) {
        String script = "if (window.__codeServerAppKeyboard) { "
            + "window.__codeServerAppKeyboard.setModifiers("
            + controlLocked + "," + shiftLocked + "); }";
        target.evaluateJavascript(script, null);
        if (target == webView) {
            target.requestFocus();
        }
    }

    private void sendKey(String key, String code, int keyCode) {
        String script = String.format(Locale.US, """
            (() => {
              if (window.__codeServerAppKeyboard
                  && typeof window.__codeServerAppKeyboard.sendKey === 'function') {
                return window.__codeServerAppKeyboard.sendKey(%1$s, %2$s, %5$d);
              }
              const target = document.activeElement || document.body;
              if (!target) return false;
              if (typeof target.focus === 'function') target.focus();
              const dispatch = (type) => {
                const event = new KeyboardEvent(type, {
                  key: %1$s,
                  code: %2$s,
                  ctrlKey: %3$s,
                  shiftKey: %4$s,
                  altKey: false,
                  metaKey: false,
                  bubbles: true,
                  cancelable: true,
                  composed: true
                });
                try {
                  Object.defineProperty(event, 'keyCode', { get: () => %5$d });
                  Object.defineProperty(event, 'which', { get: () => %5$d });
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
        button.setPadding(dp(6), 0, dp(6), 0);
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
        params.setMarginEnd(dp(4));
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
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        boolean activeViewIsCached = activeSessionKey != null;
        for (ProjectSession session : new ArrayList<>(projectSessions.values())) {
            destroyWebView(session.webView);
        }
        projectSessions.clear();
        if (!activeViewIsCached && webView != null) {
            destroyWebView(webView);
        }
        webView = null;
        super.onDestroy();
    }

    private final class RdpInputWebView extends WebView {
        private final KeyCharacterMap virtualKeyboard =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        private boolean forcedImeEnabled;
        private boolean ironRdpMode;
        private boolean imeVisible;
        private boolean imeInputConfirmed;
        private long forcedImeRequestedAt;

        RdpInputWebView(Context context) {
            super(context);
        }

        void showForcedIme(boolean useIronRdp) {
            forcedImeEnabled = true;
            ironRdpMode = useIronRdp;
            imeInputConfirmed = false;
            forcedImeRequestedAt = SystemClock.elapsedRealtime();
            requestFocus();
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.restartInput(this);
                inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        }

        void setImeVisible(boolean visible) {
            boolean wasVisible = imeVisible;
            imeVisible = visible;
            if (wasVisible && !visible && forcedImeEnabled) {
                disableForcedIme();
            }
        }

        void syncModifierCapture(boolean enabled) {
            if (!enabled) {
                if (forcedImeEnabled && !ironRdpMode) {
                    disableForcedIme();
                }
                return;
            }
            if (!imeVisible && !forcedImeEnabled) {
                return;
            }
            forcedImeEnabled = true;
            forcedImeRequestedAt = SystemClock.elapsedRealtime();
            requestFocus();
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.restartInput(this);
                inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        }

        private void disableForcedIme() {
            if (!forcedImeEnabled) {
                return;
            }
            forcedImeEnabled = false;
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.restartInput(this);
            }
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return forcedImeEnabled || super.onCheckIsTextEditor();
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (!forcedImeEnabled) {
                return super.onCreateInputConnection(outAttrs);
            }
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            outAttrs.initialSelStart = 0;
            outAttrs.initialSelEnd = 0;
            return new ForcedImeInputConnection(this);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && forcedImeEnabled
                && !imeVisible
                && SystemClock.elapsedRealtime() - forcedImeRequestedAt > 500L) {
                disableForcedIme();
            }
            return super.onTouchEvent(event);
        }

        private void confirmImeInput() {
            if (imeInputConfirmed) {
                return;
            }
            imeInputConfirmed = true;
            Toast.makeText(
                MainActivity.this,
                "IME connected",
                Toast.LENGTH_SHORT
            ).show();
        }

        private void dispatchImeKeyEvent(KeyEvent event) {
            int metaState = event.getMetaState();
            if (controlLocked) {
                metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            }
            if (shiftLocked) {
                metaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            }
            KeyEvent copiedEvent = new KeyEvent(
                event.getDownTime(),
                event.getEventTime(),
                event.getAction(),
                event.getKeyCode(),
                event.getRepeatCount(),
                metaState,
                event.getDeviceId(),
                event.getScanCode(),
                event.getFlags(),
                event.getSource()
            );
            post(() -> {
                confirmImeInput();
                RdpInputWebView.this.dispatchKeyEvent(copiedEvent);
            });
        }

        private void dispatchNativeKey(int keyCode) {
            if (ironRdpMode) {
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    dispatchBridgeKey("Backspace", "Backspace", 8);
                    return;
                }
                if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                    dispatchBridgeKey("Delete", "Delete", 46);
                    return;
                }
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    dispatchBridgeKey("Enter", "Enter", 13);
                    return;
                }
            }
            long now = SystemClock.uptimeMillis();
            dispatchImeKeyEvent(new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0
            ));
            dispatchImeKeyEvent(new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_UP,
                keyCode,
                0
            ));
        }

        private void dispatchBridgeKey(String key, String code, int keyCode) {
            String script = "window.__codeServerAppKeyboard"
                + " ? window.__codeServerAppKeyboard.sendKey("
                + JSONObject.quote(key) + ","
                + JSONObject.quote(code) + ","
                + keyCode + ") : false";
            post(() -> {
                confirmImeInput();
                evaluateJavascript(script, null);
            });
        }

        private void dispatchBridgeText(String text) {
            String script = "window.__codeServerAppKeyboard"
                + " ? window.__codeServerAppKeyboard.sendText("
                + JSONObject.quote(text) + ") : false";
            post(() -> {
                confirmImeInput();
                evaluateJavascript(script, null);
            });
        }

        private void dispatchCommittedText(CharSequence text) {
            if (text == null || text.length() == 0) {
                return;
            }
            String value = text.toString();
            if (ironRdpMode) {
                dispatchBridgeText(value);
                return;
            }
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                KeyEvent[] events = virtualKeyboard.getEvents(character.toCharArray());
                if (events != null && events.length > 0) {
                    for (KeyEvent event : events) {
                        dispatchImeKeyEvent(event);
                    }
                } else {
                    dispatchBridgeText(character);
                }
                offset += Character.charCount(codePoint);
            }
        }

        private final class ForcedImeInputConnection extends BaseInputConnection {
            private final Editable editable = new SpannableStringBuilder();
            private String mirroredComposition = "";

            ForcedImeInputConnection(View targetView) {
                super(targetView, true);
            }

            @Override
            public Editable getEditable() {
                return editable;
            }

            private int commonPrefixLength(String left, String right) {
                int offset = 0;
                int limit = Math.min(left.length(), right.length());
                while (offset < limit) {
                    int leftCodePoint = left.codePointAt(offset);
                    int rightCodePoint = right.codePointAt(offset);
                    if (leftCodePoint != rightCodePoint) {
                        break;
                    }
                    offset += Character.charCount(leftCodePoint);
                }
                return offset;
            }

            private void syncComposingText(String nextText) {
                int commonLength = commonPrefixLength(mirroredComposition, nextText);
                int deleteCount = mirroredComposition.codePointCount(
                    commonLength,
                    mirroredComposition.length()
                );
                for (int index = 0; index < deleteCount; index += 1) {
                    dispatchNativeKey(KeyEvent.KEYCODE_DEL);
                }
                if (commonLength < nextText.length()) {
                    dispatchCommittedText(nextText.substring(commonLength));
                }
                mirroredComposition = nextText;
            }

            private void trimMirroredComposition(int count) {
                for (int index = 0;
                    index < count && !mirroredComposition.isEmpty();
                    index += 1) {
                    int end = mirroredComposition.offsetByCodePoints(
                        mirroredComposition.length(),
                        -1
                    );
                    mirroredComposition = mirroredComposition.substring(0, end);
                }
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                super.setComposingText(text, newCursorPosition);
                syncComposingText(text == null ? "" : text.toString());
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                super.commitText(text, newCursorPosition);
                String committed = text == null ? "" : text.toString();
                if (mirroredComposition.isEmpty()) {
                    dispatchCommittedText(committed);
                } else {
                    syncComposingText(committed);
                    mirroredComposition = "";
                }
                return true;
            }

            @Override
            public boolean finishComposingText() {
                super.finishComposingText();
                mirroredComposition = "";
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                super.deleteSurroundingText(beforeLength, afterLength);
                if (beforeLength > 0) {
                    for (int index = 0; index < beforeLength; index += 1) {
                        dispatchNativeKey(KeyEvent.KEYCODE_DEL);
                    }
                    trimMirroredComposition(beforeLength);
                } else if (afterLength > 0) {
                    for (int index = 0; index < afterLength; index += 1) {
                        dispatchNativeKey(KeyEvent.KEYCODE_FORWARD_DEL);
                    }
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(
                int beforeLength,
                int afterLength
            ) {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
                if (beforeLength > 0) {
                    for (int index = 0; index < beforeLength; index += 1) {
                        dispatchNativeKey(KeyEvent.KEYCODE_DEL);
                    }
                    trimMirroredComposition(beforeLength);
                } else if (afterLength > 0) {
                    for (int index = 0; index < afterLength; index += 1) {
                        dispatchNativeKey(KeyEvent.KEYCODE_FORWARD_DEL);
                    }
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (ironRdpMode) {
                    int keyCode = event.getKeyCode();
                    boolean supportedKey = keyCode == KeyEvent.KEYCODE_DEL
                        || keyCode == KeyEvent.KEYCODE_FORWARD_DEL
                        || keyCode == KeyEvent.KEYCODE_ENTER;
                    if (supportedKey) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            dispatchNativeKey(keyCode);
                        }
                        return true;
                    }
                }
                dispatchImeKeyEvent(event);
                return true;
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                dispatchNativeKey(KeyEvent.KEYCODE_ENTER);
                return true;
            }
        }
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

    private static final class ProjectSession {
        final WebView webView;
        long lastInactiveAt;

        ProjectSession(WebView webView) {
            this.webView = webView;
        }
    }
}
