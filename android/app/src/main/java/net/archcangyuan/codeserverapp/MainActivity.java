package net.archcangyuan.codeserverapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
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
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class MainActivity extends Activity {
    private static final String PREFERENCES = "code_server_app";
    private static final String ADDRESS_KEY = "server_address";
    private static final String PROJECTS_KEY = "saved_projects";
    private static final String KEEP_ALIVE_KEY = "keep_alive_enabled";
    private static final String MOUSE_MODE_KEY = "mouse_mode_enabled";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2001;
    private static final int OVERLAY_PERMISSION_REQUEST = 2002;
    private static final long SESSION_KEEP_ALIVE_PULSE_MS = 10_000L;
    private static final String LEGACY_NATIVE_ZOOM_PERCENT_KEY = "zoom_percent";
    private static final String LAYOUT_ZOOM_STEPS_KEY = "layout_zoom_steps";
    private static final String VIEWPORT_RELOAD_ZOOM_MIGRATED_KEY =
        "viewport_reload_zoom_migrated";
    private static final int DESKTOP_VIEWPORT_WIDTH = 1280;
    private static final int MIN_LAYOUT_ZOOM_STEPS = -10;
    private static final int MAX_LAYOUT_ZOOM_STEPS = 16;
    private static final double LAYOUT_ZOOM_FACTOR = 1.1;
    private static final long PROJECT_SESSION_TTL_MS = 30L * 60L * 1_000L;
    private static final int MAX_HOT_PROJECT_SESSIONS = 10;
    private static final long ADDRESS_BAR_AUTO_HIDE_MS = 5_000L;
    private static final int ACCENT = Color.rgb(103, 80, 164);
    private static final int KEY_BACKGROUND = Color.rgb(230, 230, 234);
    private static final String DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String KEEP_ALIVE_PULSE_SCRIPT =
        "(() => { window.dispatchEvent(new Event('__your_workspace_keep_alive')); "
            + "return document.visibilityState; })()";

    private static final String KEYBOARD_BRIDGE = """
        (() => {
          const PROXY_ID = '__code_server_app_keyboard_proxy';
          // fitWidth is the native view width in points. When given, the page scale is
          // pinned to fit the new layout width for a moment, because web views keep
          // their old scale on live viewport changes and would crop the page. If the
          // page still does not fit, allowReload lets it fall back to one reload.
          const setViewportWidth = (requestedWidth, fitWidth = 0, allowReload = false) => {
            const numericWidth = Number(requestedWidth) || 1280;
            const width = Math.max(200, Math.min(4000, Math.round(numericWidth)));
            let viewport = document.querySelector('meta[name="viewport"]');
            if (!viewport) {
              viewport = document.createElement('meta');
              viewport.setAttribute('name', 'viewport');
              (document.head || document.documentElement).appendChild(viewport);
            }
            const relaxedContent =
              `width=${width}, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes`;
            const token = (window.__codeServerAppViewportToken || 0) + 1;
            window.__codeServerAppViewportToken = token;
            // On a web RDP page (IronRDP) a layout resize makes the remote desktop resize
            // or reconnect the session, and a reload always reconnects; both ask for the
            // credentials again. There, zoom only changes the page scale: the layout width
            // the session started with is kept and the page never reloads.
            if (Number(fitWidth) > 0 && window.__codeServerAppIsRdpPage?.()) {
              const layoutWidth = Math.max(
                200,
                Math.round(
                  Number(window.__codeServerAppViewportWidth)
                    || document.documentElement.clientWidth
                    || width
                )
              );
              const rdpScale = Math.max(
                0.1,
                Math.min(5, Math.max(Number(fitWidth) / layoutWidth, Number(fitWidth) / width))
              ).toFixed(4);
              viewport.setAttribute(
                'content',
                `width=${layoutWidth}, initial-scale=${rdpScale}, minimum-scale=${rdpScale}, `
                  + `maximum-scale=${rdpScale}, user-scalable=yes`
              );
              window.setTimeout(() => {
                if (window.__codeServerAppViewportToken !== token) return;
                viewport.setAttribute(
                  'content',
                  `width=${layoutWidth}, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes`
                );
              }, 180);
              return layoutWidth;
            }
            if (Number(fitWidth) > 0) {
              const scale = Math.max(0.1, Math.min(5, Number(fitWidth) / width)).toFixed(4);
              viewport.setAttribute(
                'content',
                `width=${width}, initial-scale=${scale}, minimum-scale=${scale}, `
                  + `maximum-scale=${scale}, user-scalable=yes`
              );
              window.setTimeout(() => {
                if (window.__codeServerAppViewportToken !== token) return;
                viewport.setAttribute('content', relaxedContent);
                window.dispatchEvent(new Event('resize'));
                if (!allowReload) return;
                window.setTimeout(() => {
                  if (window.__codeServerAppViewportToken !== token) return;
                  // innerWidth follows the visual viewport in Chromium, so compare the
                  // visible width against the layout width instead.
                  const layout = document.documentElement.clientWidth || width;
                  const visible = window.visualViewport ? window.visualViewport.width : layout;
                  if (Math.abs(visible - layout) <= layout * 0.03) return;
                  const reloadKey = '__codeServerAppViewportReloadAt';
                  try {
                    const lastReload = Number(window.sessionStorage.getItem(reloadKey)) || 0;
                    if (Date.now() - lastReload < 15000) return;
                    window.sessionStorage.setItem(reloadKey, String(Date.now()));
                  } catch (_) {
                    return;
                  }
                  window.location.reload();
                }, 250);
              }, 180);
            } else {
              viewport.setAttribute('content', relaxedContent);
            }
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

          window.__codeServerAppIsRdpPage = () => Boolean(findIronRdpCanvas());

          const existingBridge = window.__codeServerAppKeyboard;
          if (existingBridge && existingBridge.version >= 11) {
            window.__codeServerAppForceKeyboard = () => existingBridge.forceKeyboard();
            existingBridge.installRdpGestures?.();
            existingBridge.installDesktopGestures?.();
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
            gestureCanvas: null,
            desktopGestureInstalled: false
          };

          const pointFromTouch = (touch) => ({
            clientX: touch.clientX,
            clientY: touch.clientY,
            screenX: touch.screenX,
            screenY: touch.screenY
          });

          const dispatchMouse = (canvas, type, point, button, buttons) => {
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
              buttons
            }));
          };

          const installRdpGestures = () => {
            const canvas = findIronRdpCanvas();
            if (!canvas) return false;
            state.ironRdpCanvas = canvas;
            if (state.gestureCanvas === canvas) return true;
            state.gestureCanvas = canvas;

            // Preserve WebView panning and IronRDP's native coordinate mapping.
            canvas.style.touchAction = '';
            canvas.style.webkitTouchCallout = 'none';

            let gesture = null;

            const releaseGesture = () => {
              if (gesture?.timer) window.clearTimeout(gesture.timer);
            };

            const sendButton = (point, button) => {
              const downButtons = button === 2 ? 2 : 1;
              dispatchMouse(canvas, 'mousemove', point, 0, 0);
              dispatchMouse(canvas, 'mousedown', point, button, downButtons);
              dispatchMouse(canvas, 'mouseup', point, button, 0);
            };

            canvas.addEventListener('contextmenu', (event) => {
              if (!gesture) return;
              event.preventDefault();
              event.stopImmediatePropagation();
            }, true);

            canvas.addEventListener('touchstart', (event) => {
              if (event.touches.length !== 1) return;
              const start = pointFromTouch(event.touches[0]);
              gesture = {
                start,
                last: start,
                armed: false,
                dragging: false,
                cancelled: false,
                timer: window.setTimeout(() => {
                  if (gesture && !gesture.cancelled) gesture.armed = true;
                }, 550)
              };
            }, { capture: true, passive: true });

            canvas.addEventListener('touchmove', (event) => {
              if (!gesture || event.touches.length !== 1) return;
              const point = pointFromTouch(event.touches[0]);
              gesture.last = point;
              const distance = Math.hypot(
                point.clientX - gesture.start.clientX,
                point.clientY - gesture.start.clientY
              );

              if (!gesture.armed) {
                if (distance >= 7) {
                  releaseGesture();
                  gesture.cancelled = true;
                }
                return;
              }

              if (!gesture.dragging && distance < 7) return;

              event.preventDefault();
              event.stopImmediatePropagation();
              if (!gesture.dragging) {
                gesture.dragging = true;
                dispatchMouse(canvas, 'mousemove', gesture.start, 0, 0);
                dispatchMouse(canvas, 'mousedown', gesture.start, 0, 1);
              }
              dispatchMouse(canvas, 'mousemove', point, 0, 1);
            }, { capture: true, passive: false });

            const finishGesture = (event, cancelled) => {
              if (!gesture) return;
              releaseGesture();
              const touch = event.changedTouches?.[0];
              const point = touch ? pointFromTouch(touch) : gesture.last;

              if (gesture.dragging) {
                event.preventDefault();
                event.stopImmediatePropagation();
                dispatchMouse(canvas, 'mousemove', point, 0, 1);
                dispatchMouse(canvas, 'mouseup', point, 0, 0);
              } else if (gesture.armed && !gesture.cancelled) {
                event.preventDefault();
                event.stopImmediatePropagation();
                sendButton(point, 2);
              } else if (cancelled) {
                gesture.cancelled = true;
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

          const installDesktopGestures = () => {
            if (state.desktopGestureInstalled) return true;
            state.desktopGestureInstalled = true;

            let gesture = null;

            const eventPath = (event) => typeof event.composedPath === 'function'
              ? event.composedPath()
              : [event.target];
            const isIronRdpEvent = (event) => {
              const canvas = state.ironRdpCanvas || findIronRdpCanvas();
              return Boolean(canvas && eventPath(event).includes(canvas));
            };
            const isNativeTextTarget = (target) => {
              if (!target || target.nodeType !== 1) return false;
              const tagName = target.tagName;
              return tagName === 'INPUT'
                || tagName === 'TEXTAREA'
                || tagName === 'SELECT'
                || Boolean(target.isContentEditable);
            };
            const targetAt = (point, fallback) => {
              try {
                return document.elementFromPoint(point.clientX, point.clientY)
                  || fallback;
              } catch (_) {
                return fallback;
              }
            };
            const clearTimer = (activeGesture) => {
              if (activeGesture?.timer) window.clearTimeout(activeGesture.timer);
            };
            const sendContextMenu = (target, point) => {
              if (!target?.dispatchEvent) return;
              dispatchMouse(target, 'mousemove', point, 0, 0);
              dispatchMouse(target, 'mousedown', point, 2, 2);
              dispatchMouse(target, 'mouseup', point, 2, 0);
              dispatchMouse(target, 'contextmenu', point, 2, 0);
            };

            document.addEventListener('contextmenu', (event) => {
              if (!event.isTrusted || !gesture || isIronRdpEvent(event)) return;
              event.preventDefault();
              event.stopImmediatePropagation();
            }, true);

            document.addEventListener('touchstart', (event) => {
              if (event.touches.length !== 1 || isIronRdpEvent(event)) return;
              const path = eventPath(event);
              const startTarget = path.find((target) => target?.dispatchEvent)
                || event.target;
              if (!startTarget || isNativeTextTarget(startTarget)) return;
              if (startTarget.style) startTarget.style.webkitTouchCallout = 'none';
              const start = pointFromTouch(event.touches[0]);
              gesture = {
                start,
                last: start,
                startTarget,
                armed: false,
                dragging: false,
                cancelled: false,
                timer: window.setTimeout(() => {
                  if (gesture && !gesture.cancelled) gesture.armed = true;
                }, 550)
              };
            }, { capture: true, passive: true });

            document.addEventListener('touchmove', (event) => {
              if (!gesture || event.touches.length !== 1) return;
              const point = pointFromTouch(event.touches[0]);
              gesture.last = point;
              const distance = Math.hypot(
                point.clientX - gesture.start.clientX,
                point.clientY - gesture.start.clientY
              );

              if (!gesture.armed) {
                if (distance >= 7) {
                  clearTimer(gesture);
                  gesture.cancelled = true;
                }
                return;
              }
              if (!gesture.dragging && distance < 7) return;

              event.preventDefault();
              event.stopImmediatePropagation();
              if (!gesture.dragging) {
                gesture.dragging = true;
                dispatchMouse(gesture.startTarget, 'mousemove', gesture.start, 0, 0);
                dispatchMouse(gesture.startTarget, 'mousedown', gesture.start, 0, 1);
              }
              const moveTarget = targetAt(point, gesture.startTarget);
              dispatchMouse(moveTarget, 'mousemove', point, 0, 1);
            }, { capture: true, passive: false });

            const finishGesture = (event, cancelled) => {
              if (!gesture) return;
              const activeGesture = gesture;
              gesture = null;
              clearTimer(activeGesture);
              const touch = event.changedTouches?.[0];
              const point = touch ? pointFromTouch(touch) : activeGesture.last;
              const endTarget = targetAt(point, activeGesture.startTarget);

              if (activeGesture.dragging) {
                event.preventDefault();
                event.stopImmediatePropagation();
                dispatchMouse(endTarget, 'mousemove', point, 0, 1);
                dispatchMouse(endTarget, 'mouseup', point, 0, 0);
              } else if (activeGesture.armed
                  && !activeGesture.cancelled
                  && !cancelled) {
                event.preventDefault();
                event.stopImmediatePropagation();
                sendContextMenu(endTarget, point);
              }
            };

            document.addEventListener('touchend', (event) => {
              finishGesture(event, false);
            }, { capture: true, passive: false });
            document.addEventListener('touchcancel', (event) => {
              finishGesture(event, true);
            }, { capture: true, passive: false });
            return true;
          };

          installRdpGestures();
          installDesktopGestures();
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
            const needsSyntheticShift = forceShift && !state.shift;
            const source = forceShift ? { shiftKey: true } : null;
            if (needsSyntheticShift) {
              dispatchKeyPhase(
                target,
                'keydown',
                'Shift',
                'ShiftLeft',
                16,
                { shiftKey: true, location: 1 }
              );
            }
            dispatchKeyPhase(target, 'keydown', key, code, keyCode, source);
            if (Array.from(key).length === 1 && !state.control) {
              dispatchKeyPhase(target, 'keypress', key, code, keyCode, source);
            }
            dispatchKeyPhase(target, 'keyup', key, code, keyCode, source);
            if (needsSyntheticShift) {
              dispatchKeyPhase(target, 'keyup', 'Shift', 'ShiftLeft', 16);
            }
          };

          const dispatchShortcut = (
            key,
            code,
            keyCode,
            useControl = false,
            useShift = false
          ) => {
            const target = activeTarget();
            if (!target) return false;
            const needsControl = useControl && !state.control;
            const needsShift = useShift && !state.shift;
            if (needsControl) {
              dispatchKeyPhase(
                target,
                'keydown',
                'Control',
                'ControlLeft',
                17,
                { ctrlKey: true, location: 1 }
              );
            }
            if (needsShift) {
              dispatchKeyPhase(
                target,
                'keydown',
                'Shift',
                'ShiftLeft',
                16,
                { ctrlKey: useControl, shiftKey: true, location: 1 }
              );
            }
            const source = {
              ctrlKey: state.control || useControl,
              shiftKey: state.shift || useShift
            };
            dispatchKeyPhase(target, 'keydown', key, code, keyCode, source);
            dispatchKeyPhase(target, 'keyup', key, code, keyCode, source);
            if (needsShift) {
              dispatchKeyPhase(
                target,
                'keyup',
                'Shift',
                'ShiftLeft',
                16,
                { ctrlKey: state.control || useControl }
              );
            }
            if (needsControl) {
              dispatchKeyPhase(target, 'keyup', 'Control', 'ControlLeft', 17);
            }
            return true;
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
            input.setAttribute('aria-label', 'YourWorkspace keyboard proxy');
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

          // Real mice use pointerId 1 in Chromium, which would let pages capture a
          // pointer that never delivers the synthetic moves. Use a private id and
          // emulate setPointerCapture for it instead.
          const SYNTHETIC_POINTER_ID = 7331;

          const mouse = {
            buttons: 0,
            captureTarget: null,
            lastX: 0,
            lastY: 0,
            lastHit: null,
            hoverPath: [],
            downTargets: {},
            clickCount: 0,
            lastDownAt: 0,
            lastDownX: 0,
            lastDownY: 0
          };

          // Mouse mode: the finger positions the cursor and in-page L/R buttons click.
          // Everything runs inside real touch handlers, so clicks carry user
          // activation (clipboard writes, window.open) like a physical mouse.
          const mouseMode = {
            enabled: false,
            viewWidth: 0,
            scale: 1,
            host: null,
            cursor: null,
            left: null,
            right: null,
            touches: new Map(),
            cursorX: -1,
            cursorY: -1,
            leftHeld: false,
            leftLocked: false,
            leftLockArmed: false,
            leftMoved: false,
            leftUnlockPending: false,
            lockTimer: 0,
            rightHeld: false,
            inputModes: new Map()
          };

          const installPointerCapture = (eventWindow) => {
            const prototype = eventWindow?.Element?.prototype;
            if (!prototype || prototype.__codeServerAppPointerCapture) return;
            const nativeSet = prototype.setPointerCapture;
            const nativeRelease = prototype.releasePointerCapture;
            const nativeHas = prototype.hasPointerCapture;
            prototype.setPointerCapture = function (pointerId) {
              if (pointerId !== SYNTHETIC_POINTER_ID) return nativeSet.call(this, pointerId);
              mouse.captureTarget = this;
            };
            prototype.releasePointerCapture = function (pointerId) {
              if (pointerId !== SYNTHETIC_POINTER_ID) return nativeRelease.call(this, pointerId);
              if (mouse.captureTarget === this) mouse.captureTarget = null;
            };
            prototype.hasPointerCapture = function (pointerId) {
              if (pointerId !== SYNTHETIC_POINTER_ID) return nativeHas.call(this, pointerId);
              return mouse.captureTarget === this;
            };
            Object.defineProperty(prototype, '__codeServerAppPointerCapture', { value: true });
          };
          installPointerCapture(window);

          const mouseButtonMask = (button) => {
            if (button === 2) return 2;
            if (button === 1) return 4;
            return 1;
          };

          // Resolve the deepest element under the cursor, descending into open
          // shadow roots (IronRDP) and same-origin iframes.
          const mouseHitTest = (x, y) => {
            let root = document;
            let localX = x;
            let localY = y;
            let hit = null;
            for (let depth = 0; depth < 8; depth += 1) {
              let element = root.elementFromPoint(localX, localY);
              if (element && element === mouseMode.host) {
                element = root.elementsFromPoint(localX, localY)
                  .find((candidate) => candidate !== mouseMode.host) || null;
              }
              for (let level = 0; element?.shadowRoot && level < 16; level += 1) {
                const inner = element.shadowRoot.elementFromPoint(localX, localY);
                if (!inner || inner === element) break;
                element = inner;
              }
              if (!element) break;
              hit = {
                element,
                clientX: localX,
                clientY: localY,
                view: element.ownerDocument?.defaultView || window
              };
              if (element.tagName !== 'IFRAME') break;
              let childDocument = null;
              try {
                childDocument = element.contentDocument;
              } catch (_) {}
              if (!childDocument) break;
              const rect = element.getBoundingClientRect();
              localX -= rect.left + element.clientLeft;
              localY -= rect.top + element.clientTop;
              root = childDocument;
            }
            return hit;
          };

          const composedAncestors = (element) => {
            const chain = [];
            for (let node = element; node && chain.length < 128;) {
              if (node.nodeType === 1) chain.push(node);
              node = node.parentNode || node.host || null;
            }
            return chain;
          };

          const mouseEventInit = (hit, button, buttons, detail, bubbles) => ({
            bubbles,
            cancelable: bubbles,
            composed: true,
            view: hit.view,
            detail,
            clientX: hit.clientX,
            clientY: hit.clientY,
            screenX: hit.clientX,
            screenY: hit.clientY,
            button,
            buttons,
            ctrlKey: state.control,
            shiftKey: state.shift,
            altKey: false,
            metaKey: false
          });

          const fireMouse = (hit, element, type, button, buttons, detail = 0, bubbles = true) => {
            const eventWindow = element.ownerDocument?.defaultView || hit.view;
            return element.dispatchEvent(new eventWindow.MouseEvent(
              type,
              mouseEventInit(hit, button, buttons, detail, bubbles)
            ));
          };

          const firePointer = (hit, element, type, button, buttons, bubbles = true) => {
            const eventWindow = element.ownerDocument?.defaultView || hit.view;
            if (typeof eventWindow.PointerEvent !== 'function') return true;
            return element.dispatchEvent(new eventWindow.PointerEvent(type, {
              ...mouseEventInit(hit, button, buttons, 0, bubbles),
              pointerId: SYNTHETIC_POINTER_ID,
              pointerType: 'mouse',
              isPrimary: true,
              width: 1,
              height: 1,
              pressure: buttons ? 0.5 : 0
            }));
          };

          const updateMouseHover = (hit) => {
            const previousPath = mouse.hoverPath;
            const nextPath = composedAncestors(hit.element);
            const previous = previousPath[0] || null;
            const next = nextPath[0] || null;
            if (previous === next) return;
            const leaveHit = mouse.lastHit || hit;
            if (previous && previous.isConnected) {
              firePointer(leaveHit, previous, 'pointerout', 0, mouse.buttons);
              fireMouse(leaveHit, previous, 'mouseout', 0, mouse.buttons);
              for (const element of previousPath) {
                if (nextPath.includes(element)) break;
                firePointer(leaveHit, element, 'pointerleave', 0, mouse.buttons, false);
                fireMouse(leaveHit, element, 'mouseleave', 0, mouse.buttons, 0, false);
              }
            }
            if (next) {
              firePointer(hit, next, 'pointerover', 0, mouse.buttons);
              fireMouse(hit, next, 'mouseover', 0, mouse.buttons);
              const entering = [];
              for (const element of nextPath) {
                if (previousPath.includes(element)) break;
                entering.push(element);
              }
              for (const element of entering.reverse()) {
                firePointer(hit, element, 'pointerenter', 0, mouse.buttons, false);
                fireMouse(hit, element, 'mouseenter', 0, mouse.buttons, 0, false);
              }
            }
            mouse.hoverPath = nextPath;
          };

          const isEditableElement = (element) => {
            if (!element || element.nodeType !== 1) return false;
            if (element.tagName === 'TEXTAREA' || element.isContentEditable) return true;
            if (element.tagName !== 'INPUT') return false;
            const nonText = [
              'button', 'checkbox', 'color', 'file', 'hidden', 'image',
              'radio', 'range', 'reset', 'submit'
            ];
            return !nonText.includes(String(element.type || '').toLowerCase());
          };

          // inputmode="none" keeps the system keyboard hidden while the element
          // still takes focus and receives forwarded keys.
          const suppressKeyboardFor = (element) => {
            if (!mouseMode.enabled || !isEditableElement(element)) return;
            if (!mouseMode.inputModes.has(element)) {
              mouseMode.inputModes.set(element, element.getAttribute('inputmode'));
            }
            if (element.getAttribute('inputmode') !== 'none') {
              element.setAttribute('inputmode', 'none');
            }
          };

          const suppressKeyboardInDocument = () => {
            if (!mouseMode.enabled) return;
            for (const element of document.querySelectorAll(
              'textarea, input, [contenteditable]'
            )) {
              suppressKeyboardFor(element);
            }
            suppressKeyboardFor(deepestActiveElement(document));
          };

          const restoreKeyboardInputModes = () => {
            for (const [element, previous] of mouseMode.inputModes) {
              if (previous === null) {
                element.removeAttribute('inputmode');
              } else {
                element.setAttribute('inputmode', previous);
              }
            }
            mouseMode.inputModes.clear();
          };

          const focusFromMouse = (target) => {
            const selector = 'input, textarea, select, button, a[href], [tabindex], '
              + '[contenteditable=""], [contenteditable="true"]';
            for (const element of composedAncestors(target)) {
              if (!element.matches?.(selector) || element.disabled) continue;
              suppressKeyboardFor(element);
              try {
                element.focus({ preventScroll: true });
              } catch (_) {}
              return;
            }
          };

          const commonMouseTarget = (first, second) => {
            if (!first || !second || !first.isConnected) return null;
            for (const element of composedAncestors(first)) {
              if (element === second || element.contains(second)) return element;
            }
            return null;
          };

          const mouseAction = (action, x, y, button = 0) => {
            mouse.lastX = x;
            mouse.lastY = y;
            const hit = mouseHitTest(x, y)
              || (mouse.lastHit?.element?.isConnected ? mouse.lastHit : null);
            if (!hit) {
              if (action === 'up') mouse.buttons &= ~mouseButtonMask(button);
              return false;
            }
            installPointerCapture(hit.view);
            const captured = mouse.captureTarget?.isConnected ? mouse.captureTarget : null;
            if (!captured) updateMouseHover(hit);
            mouse.lastHit = hit;
            const target = captured || hit.element;

            if (action === 'move') {
              firePointer(hit, target, 'pointermove', -1, mouse.buttons);
              fireMouse(hit, target, 'mousemove', 0, mouse.buttons);
              return true;
            }

            if (action === 'down') {
              if (button === 0) {
                const now = performance.now();
                const nearPrevious = Math.hypot(x - mouse.lastDownX, y - mouse.lastDownY) < 8;
                mouse.clickCount = nearPrevious && now - mouse.lastDownAt < 500
                  ? Math.min(mouse.clickCount + 1, 3)
                  : 1;
                mouse.lastDownAt = now;
                mouse.lastDownX = x;
                mouse.lastDownY = y;
              }
              suppressKeyboardInDocument();
              mouse.captureTarget = null;
              mouse.buttons |= mouseButtonMask(button);
              mouse.downTargets[button] = target;
              rememberTarget(target);
              const detail = button === 0 ? mouse.clickCount : 1;
              const pointerAllowed = firePointer(hit, target, 'pointerdown', button, mouse.buttons);
              const mouseAllowed = fireMouse(
                hit,
                target,
                'mousedown',
                button,
                mouse.buttons,
                detail
              );
              if (button === 0 && pointerAllowed && mouseAllowed) focusFromMouse(target);
              return true;
            }

            if (action === 'up') {
              if (!(mouse.buttons & mouseButtonMask(button))) return false;
              mouse.buttons &= ~mouseButtonMask(button);
              const detail = button === 0 ? mouse.clickCount : 1;
              firePointer(hit, target, 'pointerup', button, mouse.buttons);
              fireMouse(hit, target, 'mouseup', button, mouse.buttons, detail);
              if (!mouse.buttons) mouse.captureTarget = null;
              const downTarget = mouse.downTargets[button];
              delete mouse.downTargets[button];
              if (button === 0) {
                const clickTarget = commonMouseTarget(downTarget, target);
                if (clickTarget) {
                  fireMouse(hit, clickTarget, 'click', 0, mouse.buttons, detail);
                  if (detail === 2) {
                    fireMouse(hit, clickTarget, 'dblclick', 0, mouse.buttons, 2);
                  }
                }
              } else if (button === 2) {
                fireMouse(hit, target, 'contextmenu', 2, mouse.buttons, 1);
              }
              return true;
            }
            return false;
          };

          const scrollableAncestor = (element, deltaX, deltaY) => {
            for (const candidate of composedAncestors(element)) {
              const style = candidate.ownerDocument.defaultView.getComputedStyle(candidate);
              const scrollY = deltaY
                && /(auto|scroll|overlay)/.test(style.overflowY)
                && candidate.scrollHeight > candidate.clientHeight;
              const scrollX = deltaX
                && /(auto|scroll|overlay)/.test(style.overflowX)
                && candidate.scrollWidth > candidate.clientWidth;
              if (scrollY || scrollX) return candidate;
            }
            return element.ownerDocument.scrollingElement;
          };

          const mouseWheel = (x, y, deltaX, deltaY) => {
            const hit = mouseHitTest(x, y);
            if (!hit) return;
            const eventWindow = hit.view;
            const allowed = hit.element.dispatchEvent(new eventWindow.WheelEvent('wheel', {
              ...mouseEventInit(hit, 0, mouse.buttons, 0, true),
              deltaX,
              deltaY,
              deltaMode: 0,
              // Chromium leaves the legacy wheelDelta at 0 for synthetic events and
              // Monaco prefers it; 2.4x maps one finger pixel to one editor pixel.
              wheelDeltaX: -deltaX * 2.4,
              wheelDeltaY: -deltaY * 2.4
            }));
            // Untrusted wheel events never scroll natively, so scroll plain pages here.
            if (allowed) scrollableAncestor(hit.element, deltaX, deltaY)?.scrollBy(deltaX, deltaY);
          };

          const visualViewportRect = () => {
            const viewport = window.visualViewport;
            return {
              left: viewport ? viewport.offsetLeft : 0,
              top: viewport ? viewport.offsetTop : 0,
              width: viewport ? viewport.width : window.innerWidth,
              height: viewport ? viewport.height : window.innerHeight
            };
          };

          const ensureMouseOverlay = () => {
            if (mouseMode.host) {
              if (!mouseMode.host.isConnected) document.documentElement.appendChild(mouseMode.host);
              return;
            }
            const host = document.createElement('div');
            host.setAttribute('data-code-server-app-mouse', '');
            Object.assign(host.style, {
              position: 'fixed',
              left: '0',
              top: '0',
              width: '0',
              height: '0',
              margin: '0',
              padding: '0',
              border: '0',
              zIndex: '2147483647',
              pointerEvents: 'none',
              display: 'none'
            });
            const root = host.attachShadow({ mode: 'open' });
            root.innerHTML = `<style>
              .cursor {
                position: absolute; left: 0; top: 0; width: 14px; height: 22px;
                transform-origin: 0 0; pointer-events: none; overflow: visible;
              }
              .button {
                position: absolute; box-sizing: border-box; border-radius: 50%;
                display: flex; align-items: center; justify-content: center;
                font-family: system-ui, sans-serif; font-weight: 700; color: #fff;
                background: rgba(0, 0, 0, 0.31); border: 2px solid rgba(255, 255, 255, 0.6);
                pointer-events: auto; touch-action: none;
                user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;
              }
              .button.pressed { background: rgba(103, 80, 164, 0.67); }
              .button.locked { background: rgba(103, 80, 164, 0.86); border-color: #fff; }
            </style>
            <svg class="cursor" viewBox="0 0 14 22">
              <path d="M0.7 0.7 L0.7 18.5 L5.2 14.3 L8.3 21 L11.3 19.7 L8.2 13.1 L13.7 13.1 Z"
                fill="#fff" stroke="#000" stroke-width="1.4" stroke-linejoin="round"/>
            </svg>
            <div class="button left">L</div>
            <div class="button right">R</div>`;
            mouseMode.host = host;
            mouseMode.cursor = root.querySelector('.cursor');
            mouseMode.left = root.querySelector('.left');
            mouseMode.right = root.querySelector('.right');
            document.documentElement.appendChild(host);
          };

          const updateMouseCursor = () => {
            if (!mouseMode.cursor) return;
            const rect = visualViewportRect();
            mouseMode.cursor.style.display = mouseMode.cursorX < 0 ? 'none' : 'block';
            mouseMode.cursor.style.transform = `translate(${mouseMode.cursorX - rect.left}px, `
              + `${mouseMode.cursorY - rect.top}px) scale(${mouseMode.scale})`;
          };

          const updateMouseButtons = () => {
            if (!mouseMode.left) return;
            const leftLocked = mouseMode.leftLocked || mouseMode.leftLockArmed;
            mouseMode.left.classList.toggle(
              'pressed',
              mouseMode.leftHeld || mouseMode.leftUnlockPending
            );
            mouseMode.left.classList.toggle('locked', leftLocked);
            mouseMode.left.textContent = leftLocked ? 'L🔒' : 'L';
            mouseMode.right.classList.toggle('pressed', mouseMode.rightHeld);
          };

          const layoutMouseOverlay = () => {
            if (!mouseMode.enabled || !mouseMode.host) return;
            ensureMouseOverlay();
            const rect = visualViewportRect();
            // Size controls in native points so they stay finger-sized at any page zoom.
            const scale = mouseMode.viewWidth > 0 ? rect.width / mouseMode.viewWidth : 1;
            mouseMode.scale = scale;
            Object.assign(mouseMode.host.style, {
              display: 'block',
              left: `${rect.left}px`,
              top: `${rect.top}px`,
              width: `${rect.width}px`,
              height: `${rect.height}px`
            });
            const place = (element, size, right, bottom) => Object.assign(element.style, {
              width: `${size * scale}px`,
              height: `${size * scale}px`,
              right: `${right * scale}px`,
              bottom: `${bottom * scale}px`,
              fontSize: `${18 * scale}px`,
              borderWidth: `${2 * scale}px`
            });
            place(mouseMode.left, 68, 84, 40);
            place(mouseMode.right, 56, 16, 16);
            if (mouseMode.cursorX < 0) {
              mouseMode.cursorX = rect.left + rect.width / 2;
              mouseMode.cursorY = rect.top + rect.height / 2;
            }
            updateMouseCursor();
          };

          const moveMouseCursor = (x, y) => {
            mouseMode.cursorX = x;
            mouseMode.cursorY = y;
            if (mouseMode.leftHeld) mouseMode.leftMoved = true;
            updateMouseCursor();
            mouseAction('move', x, y);
          };

          const clearLeftLockTimer = () => {
            if (mouseMode.lockTimer) window.clearTimeout(mouseMode.lockTimer);
            mouseMode.lockTimer = 0;
          };

          const mouseLeftDown = () => {
            if (mouseMode.leftLocked) {
              // Tap while drag-locked: release the button when this tap ends.
              mouseMode.leftLocked = false;
              mouseMode.leftUnlockPending = true;
              updateMouseButtons();
              return;
            }
            mouseMode.leftHeld = true;
            mouseMode.leftLockArmed = false;
            mouseMode.leftMoved = false;
            mouseAction('down', mouseMode.cursorX, mouseMode.cursorY, 0);
            clearLeftLockTimer();
            mouseMode.lockTimer = window.setTimeout(() => {
              mouseMode.lockTimer = 0;
              if (!mouseMode.leftHeld || mouseMode.leftMoved) return;
              mouseMode.leftLockArmed = true;
              navigator.vibrate?.(15);
              updateMouseButtons();
            }, 500);
            updateMouseButtons();
          };

          const mouseLeftUp = (cancelled) => {
            clearLeftLockTimer();
            if (mouseMode.leftUnlockPending) {
              mouseMode.leftUnlockPending = false;
              mouseMode.leftHeld = false;
              mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, 0);
              updateMouseButtons();
              return;
            }
            if (!mouseMode.leftHeld) return;
            if (!cancelled && mouseMode.leftLockArmed && !mouseMode.leftMoved) {
              // Long press without movement: keep the button down for one-finger drags.
              mouseMode.leftLockArmed = false;
              mouseMode.leftLocked = true;
              updateMouseButtons();
              return;
            }
            mouseMode.leftHeld = false;
            mouseMode.leftLockArmed = false;
            mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, 0);
            updateMouseButtons();
          };

          const releaseMouseButtons = () => {
            clearLeftLockTimer();
            for (const held of [0, 2, 1]) {
              if (mouse.buttons & mouseButtonMask(held)) {
                mouseAction('up', mouse.lastX, mouse.lastY, held);
              }
            }
            mouseMode.leftHeld = false;
            mouseMode.leftLocked = false;
            mouseMode.leftLockArmed = false;
            mouseMode.leftUnlockPending = false;
            mouseMode.rightHeld = false;
            mouseMode.touches.clear();
            updateMouseButtons();
          };

          const pointInElement = (element, x, y) => {
            if (!element) return false;
            const rect = element.getBoundingClientRect();
            return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
          };

          const handleMouseModeTouch = (event) => {
            if (!mouseMode.enabled) return;
            if (event.cancelable) event.preventDefault();
            event.stopImmediatePropagation();
            const type = event.type;
            for (const touch of Array.from(event.changedTouches || [])) {
              const x = touch.clientX;
              const y = touch.clientY;

              if (type === 'touchstart') {
                const pageTouches = Array.from(mouseMode.touches.values())
                  .filter((info) => info.role === 'cursor' || info.role === 'anchor');
                let role = 'cursor';
                if (pointInElement(mouseMode.left, x, y)) {
                  role = 'left';
                } else if (pointInElement(mouseMode.right, x, y)) {
                  role = 'right';
                } else if (pageTouches.length) {
                  // A second finger on the page scrolls; the first one stops steering.
                  role = 'scroll';
                  for (const info of pageTouches) {
                    info.role = 'anchor';
                  }
                }
                mouseMode.touches.set(touch.identifier, {
                  role,
                  startX: x,
                  startY: y,
                  lastX: x,
                  lastY: y,
                  startedAt: performance.now(),
                  moved: false
                });
                if (role === 'left') {
                  mouseLeftDown();
                } else if (role === 'right') {
                  mouseMode.rightHeld = true;
                  mouseAction('down', mouseMode.cursorX, mouseMode.cursorY, 2);
                  updateMouseButtons();
                } else if (role === 'cursor') {
                  moveMouseCursor(x, y);
                }
                continue;
              }

              const info = mouseMode.touches.get(touch.identifier);
              if (!info) continue;

              if (type === 'touchmove') {
                if (Math.hypot(x - info.startX, y - info.startY) > 10 * mouseMode.scale) {
                  info.moved = true;
                }
                if (info.role === 'cursor') {
                  moveMouseCursor(x, y);
                } else if (info.role === 'scroll') {
                  mouseWheel(mouseMode.cursorX, mouseMode.cursorY, info.lastX - x, info.lastY - y);
                }
                info.lastX = x;
                info.lastY = y;
                continue;
              }

              mouseMode.touches.delete(touch.identifier);
              const cancelled = type === 'touchcancel';
              if (info.role === 'left') {
                mouseLeftUp(cancelled);
              } else if (info.role === 'right') {
                if (mouseMode.rightHeld) {
                  mouseMode.rightHeld = false;
                  mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, 2);
                  updateMouseButtons();
                }
              } else if (info.role === 'cursor'
                  && !cancelled
                  && !info.moved
                  && !mouse.buttons
                  && performance.now() - info.startedAt < 350) {
                // A quick tap is a left click at the finger.
                mouseAction('down', x, y, 0);
                mouseAction('up', x, y, 0);
              }
            }
          };

          // Keep real touch-derived pointer and mouse events away from the page so
          // only the emulated mouse reaches it.
          const blockNativePointerEvents = (event) => {
            if (!mouseMode.enabled || !event.isTrusted) return;
            if (event.pointerType === 'mouse' || event.pointerType === 'pen') return;
            event.stopImmediatePropagation();
            if (event.cancelable) event.preventDefault();
          };

          for (const type of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) {
            window.addEventListener(type, handleMouseModeTouch, { capture: true, passive: false });
          }
          for (const type of [
            'pointerdown', 'pointermove', 'pointerup', 'pointercancel',
            'mousedown', 'mousemove', 'mouseup', 'click', 'dblclick', 'contextmenu'
          ]) {
            window.addEventListener(type, blockNativePointerEvents, true);
          }
          document.addEventListener('focusin', (event) => {
            const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
            suppressKeyboardFor(path[0] || event.target);
          }, true);
          window.visualViewport?.addEventListener('resize', layoutMouseOverlay);
          window.visualViewport?.addEventListener('scroll', layoutMouseOverlay);

          const setMouseMode = (enabled, viewWidth) => {
            if (viewWidth > 0) mouseMode.viewWidth = viewWidth;
            if (enabled === mouseMode.enabled) {
              layoutMouseOverlay();
              return true;
            }
            mouseMode.enabled = enabled;
            if (enabled) {
              ensureMouseOverlay();
              layoutMouseOverlay();
              updateMouseButtons();
              suppressKeyboardInDocument();
            } else {
              releaseMouseButtons();
              if (mouseMode.host) mouseMode.host.style.display = 'none';
              restoreKeyboardInputModes();
            }
            return true;
          };

          const bridge = {
            version: 11,
            forceKeyboard,
            installRdpGestures,
            installDesktopGestures,
            sendKey(key, code, keyCode) {
              dispatchCompleteKey(key, code, keyCode);
              return true;
            },
            sendText(text) {
              forwardText(String(text || ''));
              return true;
            },
            sendShortcut(key, code, keyCode, control, shift) {
              return dispatchShortcut(key, code, keyCode, control, shift);
            },
            setMouseMode(enabled, viewWidth) {
              return setMouseMode(Boolean(enabled), Number(viewWidth) || 0);
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
    private LinearLayout rootContainer;
    private LinearLayout addressBar;
    private EditText addressField;
    private FrameLayout webContainer;
    private LinearLayout zoomOverlay;
    private TextView zoomPercentLabel;
    private boolean zoomSliderTracking;
    private WebView webView;
    private String activeSessionKey;
    private Button controlButton;
    private Button shiftButton;
    private Button mouseModeButton;
    private boolean controlLocked;
    private boolean shiftLocked;
    private boolean keepAliveEnabled;
    private boolean mouseModeEnabled;
    private int layoutZoomSteps;
    private final Handler keepAliveHandler = new Handler(Looper.getMainLooper());
    private final Handler addressBarHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideAddressBar = () -> {
        if (addressBar == null || addressBar.getVisibility() != View.VISIBLE) {
            return;
        }
        if ((addressField != null && addressField.hasFocus()) || zoomSliderTracking) {
            // Keep the bar while an address is typed or zoom is dragged; both reschedule.
            return;
        }
        if (webView == null || webView.getUrl() == null) {
            return;
        }
        hideAddressBar();
    };
    private final Runnable sessionKeepAlivePulse = new Runnable() {
        @Override
        public void run() {
            if (!keepAliveEnabled || isDestroyed()) {
                return;
            }
            boolean activeViewIsCached = activeSessionKey != null;
            for (ProjectSession session : projectSessions.values()) {
                pulseWebView(session.webView);
            }
            if (!activeViewIsCached) {
                pulseWebView(webView);
            }
            keepAliveHandler.postDelayed(this, SESSION_KEEP_ALIVE_PULSE_MS);
        }
    };

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
        keepAliveEnabled = preferences.getBoolean(KEEP_ALIVE_KEY, false);
        mouseModeEnabled = preferences.getBoolean(MOUSE_MODE_KEY, false);
        loadProjects();
        setContentView(createContentView());
        configureSystemUi();
        applyKeepAliveMode();

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
        addressField.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                addressBarHandler.removeCallbacks(autoHideAddressBar);
            } else {
                scheduleAddressBarAutoHide();
            }
        });
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

        Button reloadButton = createToolbarButton("↻");
        reloadButton.setContentDescription("Reload code-server");
        reloadButton.setOnClickListener(view -> webView.reload());
        addressBar.addView(reloadButton);

        Button settingsButton = createToolbarButton("⚙");
        settingsButton.setContentDescription("Settings");
        settingsButton.setOnClickListener(view -> showSettings());
        addressBar.addView(settingsButton);

        root.addView(
            addressBar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        );

        // The zoom slider floats above the web views in a separate frame, so
        // bringing a session's WebView to the front never covers it.
        FrameLayout contentFrame = new FrameLayout(this);
        root.addView(
            contentFrame,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );
        webContainer = new FrameLayout(this);
        contentFrame.addView(
            webContainer,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );
        zoomOverlay = createZoomOverlay();
        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        zoomParams.topMargin = dp(8);
        contentFrame.addView(zoomOverlay, zoomParams);

        webContainer.addOnLayoutChangeListener(
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (mouseModeEnabled && right - left != oldRight - oldLeft) {
                    view.post(this::syncMouseModeAll);
                }
            }
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

        mouseModeButton = createKeyButton("🖱");
        mouseModeButton.setOnClickListener(view -> setMouseModeEnabled(!mouseModeEnabled));
        keyRow.addView(mouseModeButton, keyLayoutParams(dp(54)));

        controlButton = createKeyButton("Ctrl 🔓");
        controlButton.setOnClickListener(view -> {
            controlLocked = !controlLocked;
            updateModifierButtons();
            syncModifiers();
            if (!mouseModeEnabled) {
                syncModifierImeCapture();
            }
        });
        keyRow.addView(controlButton, keyLayoutParams(dp(72)));

        shiftButton = createKeyButton("Shift 🔓");
        shiftButton.setOnClickListener(view -> {
            shiftLocked = !shiftLocked;
            updateModifierButtons();
            syncModifiers();
            if (!mouseModeEnabled) {
                syncModifierImeCapture();
            }
        });
        keyRow.addView(shiftButton, keyLayoutParams(dp(76)));

        addKey(keyRow, "Esc", "Escape", "Escape", 27, dp(54));
        addKey(keyRow, "Tab", "Tab", "Tab", 9, dp(54));
        addKey(keyRow, "Enter", "Enter", "Enter", 13, dp(64));
        addKey(keyRow, "Bksp", "Backspace", "Backspace", 8, dp(64));
        addRepeatingKey(keyRow, "←", "ArrowLeft", "ArrowLeft", 37, dp(50));
        addRepeatingKey(keyRow, "↑", "ArrowUp", "ArrowUp", 38, dp(50));
        addRepeatingKey(keyRow, "↓", "ArrowDown", "ArrowDown", 40, dp(50));
        addRepeatingKey(keyRow, "→", "ArrowRight", "ArrowRight", 39, dp(50));
        addKey(keyRow, "PgUp", "PageUp", "PageUp", 33, dp(64));
        addKey(keyRow, "PgDn", "PageDown", "PageDown", 34, dp(64));

        Button controlCButton = createKeyButton("Ctrl+C");
        controlCButton.setContentDescription("Send Control C");
        controlCButton.setOnClickListener(view -> sendControlC());
        keyRow.addView(controlCButton, keyLayoutParams(dp(74)));

        keyboardScroll.addView(keyRow);
        root.addView(
            keyboardScroll,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        );

        updateModifierButtons();
        applyMouseMode();
        return root;
    }

    private void configureSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Let the page extend into the status-bar strip beside a camera cutout.
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        hideSystemBars();
    }

    private void showSettings() {
        CheckBox keepAliveCheckBox = new CheckBox(this);
        keepAliveCheckBox.setText(
            "Keep sessions alive\n"
                + "Uses a foreground service, persistent notification, native WebView pulses, "
                + "and an optional 1-pixel overlay process anchor. "
                + "Keeps up to 10 open sessions connected without the 30-minute expiry. "
                + "May increase battery usage."
        );
        keepAliveCheckBox.setChecked(keepAliveEnabled);
        int padding = dp(20);
        keepAliveCheckBox.setPadding(padding, dp(8), padding, dp(8));

        new AlertDialog.Builder(this)
            .setTitle(boldText("Settings"))
            .setView(keepAliveCheckBox)
            .setPositiveButton("Done", (dialog, which) -> {
                setKeepAliveEnabled(keepAliveCheckBox.isChecked());
            })
            .setNeutralButton("System permissions", (dialog, which) -> {
                requestKeepAlivePermissions();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void setKeepAliveEnabled(boolean enabled) {
        if (keepAliveEnabled == enabled) {
            return;
        }
        keepAliveEnabled = enabled;
        preferences.edit().putBoolean(KEEP_ALIVE_KEY, enabled).apply();
        applyKeepAliveMode();
        updateWebViewRendererPriority();
        updateSessionKeepAlivePulse();
        if (enabled) {
            requestKeepAlivePermissions();
        }
        if (!enabled) {
            cleanupExpiredProjectSessions(SystemClock.elapsedRealtime());
        }
        Toast.makeText(
            this,
            enabled ? "Session keep-alive enabled" : "Session keep-alive disabled",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void applyKeepAliveMode() {
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (keepAliveEnabled) {
            startForegroundService(serviceIntent);
        } else {
            stopService(serviceIntent);
        }
    }

    private void requestKeepAlivePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] { Manifest.permission.POST_NOTIFICATIONS },
                NOTIFICATION_PERMISSION_REQUEST
            );
            return;
        }
        requestAggressiveKeepAlivePermissions();
    }

    private void requestAggressiveKeepAlivePermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Intent overlayIntent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(overlayIntent, OVERLAY_PERMISSION_REQUEST);
            return;
        }
        requestBatteryOptimizationExemption();
    }

    private void requestBatteryOptimizationExemption() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null
            || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            Intent batteryIntent = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())
            );
            startActivity(batteryIntent);
        } catch (RuntimeException exception) {
            Toast.makeText(
                this,
                "Open system battery settings and allow unrestricted background use",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateSessionKeepAlivePulse() {
        keepAliveHandler.removeCallbacks(sessionKeepAlivePulse);
        if (keepAliveEnabled) {
            keepAliveHandler.post(sessionKeepAlivePulse);
        }
    }

    private void updateWebViewRendererPriority() {
        boolean activeViewIsCached = activeSessionKey != null;
        for (ProjectSession session : projectSessions.values()) {
            applyWebViewRendererPriority(session.webView);
        }
        if (!activeViewIsCached) {
            applyWebViewRendererPriority(webView);
        }
    }

    private void applyWebViewRendererPriority(WebView target) {
        if (target == null) {
            return;
        }
        target.setRendererPriorityPolicy(
            keepAliveEnabled
                ? WebView.RENDERER_PRIORITY_IMPORTANT
                : WebView.RENDERER_PRIORITY_BOUND,
            !keepAliveEnabled
        );
    }

    private void pulseWebView(WebView target) {
        if (target == null) {
            return;
        }
        try {
            target.evaluateJavascript(KEEP_ALIVE_PULSE_SCRIPT, null);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            requestAggressiveKeepAlivePermissions();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            applyKeepAliveMode();
            requestBatteryOptimizationExemption();
        }
    }

    /**
     * The app always runs fullscreen in sticky immersive mode. An edge swipe then
     * only shows translucent, temporary system bars (never the notification shade)
     * and is still delivered to the app, which answers the first swipe with its own
     * address bar and dismisses the system bars again; see EdgeGestureLayout.
     */
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Dialogs and other windows can bring the system bars back.
            hideSystemBars();
        }
    }

    private void applySafeAreaInsets(View view, WindowInsets insets) {
        boolean imeVisible;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            imeVisible = insets.isVisible(WindowInsets.Type.ime()) || ime.bottom > 0;

            // System bars are hidden and the page fills the top edge, including the
            // cutout strip. Only the address bar steps below a top cutout, and side
            // cutouts in landscape keep their padding.
            Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            view.setPadding(
                cutout.left,
                0,
                cutout.right,
                Math.max(cutout.bottom, ime.bottom)
            );
            applyAddressBarTopInset(cutout.top);
        } else {
            int bottomInset = insets.getSystemWindowInsetBottom();
            int keyboardInset = bottomInset > dp(120) ? bottomInset : 0;
            imeVisible = keyboardInset > 0;
            view.setPadding(0, 0, 0, keyboardInset);
        }
        if (webView instanceof RdpInputWebView) {
            ((RdpInputWebView) webView).setImeVisible(imeVisible);
        }
    }

    private void applyAddressBarTopInset(int topInset) {
        if (addressBar == null) {
            return;
        }
        addressBar.setPadding(dp(8), dp(5) + topInset, dp(8), dp(5));
        ViewGroup.LayoutParams params = addressBar.getLayoutParams();
        if (params != null && params.height != dp(56) + topInset) {
            params.height = dp(56) + topInset;
            addressBar.setLayoutParams(params);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(90);
        target.setInitialScale(0);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(target, true);

        target.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(
                WebView view,
                boolean isDialog,
                boolean isUserGesture,
                Message resultMsg
            ) {
                // Links opened with window.open (e.g. terminal and editor links) go to
                // the system browser instead of replacing the code-server page.
                WebView popup = new WebView(view.getContext());
                popup.setWebViewClient(new WebViewClient() {
                    private boolean handled;

                    private void openOnce(WebView popupView, Uri uri) {
                        if (handled) {
                            return;
                        }
                        handled = true;
                        openExternalUrl(uri);
                        popupView.post(() -> {
                            popupView.stopLoading();
                            popupView.destroy();
                        });
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(
                        WebView popupView,
                        WebResourceRequest request
                    ) {
                        openOnce(popupView, request.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView popupView, String url, Bitmap favicon) {
                        if (url != null && !url.startsWith("about:")) {
                            openOnce(popupView, Uri.parse(url));
                        }
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
        target.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                String lastFinishedUrl = lastFinishedUrls.get(view);
                if (lastFinishedUrl == null || !lastFinishedUrl.equals(url)) {
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
                installKeyboardBridge(view, false);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                lastFinishedUrls.put(view, url);
                updateAddressFromWebView(view, url);
                installKeyboardBridge(view, true);
                if (view == webView) {
                    showAddressBarTemporarily();
                }
            }

        });
    }

    private WebView createProjectWebView() {
        WebView target = new RdpInputWebView(this);
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
        target.setVisibility(View.GONE);
        configureWebView(target);
        applyWebViewRendererPriority(target);
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

    private LinearLayout createZoomOverlay() {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(14), dp(4), dp(12), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(190, 243, 243, 243));
        background.setCornerRadius(dp(20));
        background.setStroke(Math.max(1, dp(1) / 2), Color.argb(40, 0, 0, 0));
        overlay.setBackground(background);
        overlay.setElevation(dp(2));

        TextView smaller = new TextView(this);
        smaller.setText("A");
        smaller.setTextSize(11);
        smaller.setTextColor(Color.argb(170, 0, 0, 0));
        smaller.setTypeface(Typeface.DEFAULT_BOLD);
        overlay.addView(smaller);

        SeekBar slider = new SeekBar(this);
        slider.setMax(MAX_LAYOUT_ZOOM_STEPS - MIN_LAYOUT_ZOOM_STEPS);
        slider.setProgress(layoutZoomSteps - MIN_LAYOUT_ZOOM_STEPS);
        slider.setContentDescription("UI zoom");
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(90, 0, 0, 0)));
        slider.setPadding(dp(12), 0, dp(12), 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int steps = progress + MIN_LAYOUT_ZOOM_STEPS;
                updateZoomPercentLabel(steps);
                if (fromUser && !zoomSliderTracking) {
                    // Keyboard or accessibility adjustments apply immediately.
                    setLayoutZoomSteps(steps);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                zoomSliderTracking = true;
                addressBarHandler.removeCallbacks(autoHideAddressBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                zoomSliderTracking = false;
                // Relayout once on release instead of on every step while dragging.
                setLayoutZoomSteps(seekBar.getProgress() + MIN_LAYOUT_ZOOM_STEPS);
                scheduleAddressBarAutoHide();
            }
        });
        overlay.addView(slider, new LinearLayout.LayoutParams(dp(180), dp(36)));

        TextView larger = new TextView(this);
        larger.setText("A");
        larger.setTextSize(17);
        larger.setTextColor(Color.argb(170, 0, 0, 0));
        larger.setTypeface(Typeface.DEFAULT_BOLD);
        overlay.addView(larger);

        zoomPercentLabel = new TextView(this);
        zoomPercentLabel.setTextSize(12);
        zoomPercentLabel.setTextColor(Color.BLACK);
        zoomPercentLabel.setTypeface(Typeface.MONOSPACE);
        zoomPercentLabel.setGravity(Gravity.END);
        zoomPercentLabel.setMinWidth(dp(44));
        overlay.addView(zoomPercentLabel);
        updateZoomPercentLabel(layoutZoomSteps);
        return overlay;
    }

    private void updateZoomPercentLabel(int steps) {
        if (zoomPercentLabel == null) {
            return;
        }
        int zoomPercent = (int) Math.round(Math.pow(LAYOUT_ZOOM_FACTOR, steps) * 100.0);
        zoomPercentLabel.setText(zoomPercent + "%");
    }

    private void setLayoutZoomSteps(int steps) {
        int nextSteps = Math.max(MIN_LAYOUT_ZOOM_STEPS, Math.min(MAX_LAYOUT_ZOOM_STEPS, steps));
        if (webView == null || nextSteps == layoutZoomSteps) {
            return;
        }
        layoutZoomSteps = nextSteps;
        preferences.edit().putInt(LAYOUT_ZOOM_STEPS_KEY, layoutZoomSteps).apply();
        applyLayoutZoom(webView, true);
    }

    private int calculateLayoutViewportWidth() {
        return (int) Math.round(
            DESKTOP_VIEWPORT_WIDTH / Math.pow(LAYOUT_ZOOM_FACTOR, layoutZoomSteps)
        );
    }

    /**
     * Applies the layout zoom in place by changing the virtual viewport width and
     * pinning the page scale to fit it. The page reloads only when the web view
     * still does not fit afterwards and a fallback reload is allowed.
     */
    private void applyLayoutZoom(WebView target, boolean allowFallbackReload) {
        if (target == null) {
            return;
        }
        int requestedSteps = layoutZoomSteps;
        int viewportWidth = calculateLayoutViewportWidth();
        int viewWidthPx = target.getWidth() > 0
            ? target.getWidth()
            : (webContainer == null ? 0 : webContainer.getWidth());
        float fitWidthDp = viewWidthPx / getResources().getDisplayMetrics().density;
        String script = "(() => {"
            + "const width=" + viewportWidth + ";"
            + "if(window.__codeServerAppSetViewportWidth){"
            + "return window.__codeServerAppSetViewportWidth(width,"
            + String.format(Locale.US, "%.2f", fitWidthDp) + ","
            + allowFallbackReload + ");}"
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

        ProjectSession targetSession = findProjectSession(normalized);
        boolean created = targetSession == null;
        if (created) {
            targetSession = new ProjectSession(createProjectWebView());
            projectSessions.put(normalized, targetSession);
        }

        String currentUrl = targetSession.webView.getUrl();
        boolean restoreSavedAddress = !created
            && !addressesEquivalent(currentUrl, normalized);

        activateProjectSession(normalized, targetSession, now);
        if (created || restoreSavedAddress) {
            targetSession.webView.loadUrl(normalized);
        }
        evictExcessProjectSessions();

        String displayedAddress = created || restoreSavedAddress
            ? normalized
            : currentUrl;
        addressField.setText(displayedAddress);
        preferences.edit().putString(ADDRESS_KEY, displayedAddress).apply();
        addressField.clearFocus();
        targetSession.webView.requestFocus();
        showAddressBarTemporarily();
    }

    private ProjectSession findProjectSession(String address) {
        return projectSessions.get(normalizeAddress(address));
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
                // Keep hot sessions attached, visible behind the active WebView, and
                // resumed so RDP/WebSocket connections are not suspended on switch.
                webView.clearFocus();
            }
        }

        webView = targetSession.webView;
        activeSessionKey = sessionKey;
        targetSession.lastInactiveAt = 0L;
        webView.setVisibility(View.VISIBLE);
        webView.bringToFront();
        webView.onResume();
        Integer appliedSteps = appliedLayoutZoomSteps.get(webView);
        boolean zoomChanged = appliedSteps != null && appliedSteps != layoutZoomSteps;
        applyLayoutZoom(webView, zoomChanged);
        syncModifiers(webView);
        syncMouseMode(webView);
    }

    private void cleanupExpiredProjectSessions(long now) {
        if (keepAliveEnabled) {
            return;
        }
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
        String normalized = normalizeAddress(address);
        ProjectSession session = findProjectSession(normalized);
        if (session == null) {
            return false;
        }
        return normalized.equals(activeSessionKey)
            || (keepAliveEnabled && session.lastInactiveAt > 0L)
            || (session.lastInactiveAt > 0L
                && now - session.lastInactiveAt < PROJECT_SESSION_TTL_MS);
    }

    private void destroyWebView(WebView target) {
        appliedLayoutZoomSteps.remove(target);
        lastFinishedUrls.remove(target);
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

    /** Shows the address bar and hides it again after five seconds. */
    private void showAddressBarTemporarily() {
        if (addressBar == null) {
            return;
        }
        addressBar.setVisibility(View.VISIBLE);
        if (zoomOverlay != null) {
            zoomOverlay.setVisibility(View.VISIBLE);
        }
        scheduleAddressBarAutoHide();
    }

    private void scheduleAddressBarAutoHide() {
        addressBarHandler.removeCallbacks(autoHideAddressBar);
        addressBarHandler.postDelayed(autoHideAddressBar, ADDRESS_BAR_AUTO_HIDE_MS);
    }

    private void hideAddressBar() {
        addressBarHandler.removeCallbacks(autoHideAddressBar);
        if (addressBar != null) {
            addressBar.setVisibility(View.GONE);
        }
        if (zoomOverlay != null) {
            zoomOverlay.setVisibility(View.GONE);
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

    private static boolean addressesEquivalent(String first, String second) {
        return comparableAddress(first).equals(comparableAddress(second));
    }

    private static String comparableAddress(String address) {
        String normalized = normalizeAddress(address);
        int queryIndex = normalized.indexOf('?');
        int fragmentIndex = normalized.indexOf('#');
        int suffixIndex;
        if (queryIndex < 0) {
            suffixIndex = fragmentIndex;
        } else if (fragmentIndex < 0) {
            suffixIndex = queryIndex;
        } else {
            suffixIndex = Math.min(queryIndex, fragmentIndex);
        }
        String base = suffixIndex < 0 ? normalized : normalized.substring(0, suffixIndex);
        String suffix = suffixIndex < 0 ? "" : normalized.substring(suffixIndex);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + suffix;
    }

    private void installKeyboardBridge(WebView target, boolean applyZoom) {
        // Seed the zoomed viewport width so the first layout already uses it.
        String script = "window.__codeServerAppViewportWidth="
            + calculateLayoutViewportWidth() + ";" + KEYBOARD_BRIDGE;
        target.evaluateJavascript(script, value -> {
            if (applyZoom) {
                Integer appliedSteps = appliedLayoutZoomSteps.get(target);
                boolean zoomChanged = (appliedSteps == null && layoutZoomSteps != 0)
                    || (appliedSteps != null && appliedSteps != layoutZoomSteps);
                applyLayoutZoom(target, zoomChanged);
            }
            syncModifiers(target);
            syncMouseMode(target);
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

    private void sendControlC() {
        String script = "window.__codeServerAppKeyboard"
            + " && typeof window.__codeServerAppKeyboard.sendShortcut === 'function'"
            + " ? window.__codeServerAppKeyboard.sendShortcut("
            + "'c','KeyC',67,true,false) : false";
        webView.evaluateJavascript(script, null);
        webView.requestFocus();
    }

    private void setMouseModeEnabled(boolean enabled) {
        if (mouseModeEnabled == enabled) {
            return;
        }
        mouseModeEnabled = enabled;
        preferences.edit().putBoolean(MOUSE_MODE_KEY, enabled).apply();
        applyMouseMode();
        Toast.makeText(
            this,
            enabled
                ? "Mouse mode: finger moves the cursor, tap or L/R to click"
                : "Mouse mode off",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void applyMouseMode() {
        if (mouseModeEnabled) {
            hideSystemKeyboard();
        }
        syncMouseModeAll();
        if (mouseModeButton != null) {
            mouseModeButton.setContentDescription(
                mouseModeEnabled ? "Disable mouse mode" : "Enable mouse mode"
            );
            mouseModeButton.setTextColor(mouseModeEnabled ? Color.WHITE : Color.BLACK);
            mouseModeButton.setBackgroundTintList(
                ColorStateList.valueOf(mouseModeEnabled ? ACCENT : KEY_BACKGROUND)
            );
        }
    }

    private void syncMouseModeAll() {
        boolean activeViewIsCached = activeSessionKey != null;
        for (ProjectSession session : projectSessions.values()) {
            syncMouseMode(session.webView);
        }
        if (!activeViewIsCached) {
            syncMouseMode(webView);
        }
    }

    private void syncMouseMode(WebView target) {
        if (target == null) {
            return;
        }
        int widthPx = target.getWidth() > 0
            ? target.getWidth()
            : (webContainer == null ? 0 : webContainer.getWidth());
        float widthDp = widthPx / getResources().getDisplayMetrics().density;
        String script = String.format(
            Locale.US,
            "window.__codeServerAppKeyboard"
                + " && typeof window.__codeServerAppKeyboard.setMouseMode === 'function'"
                + " ? window.__codeServerAppKeyboard.setMouseMode(%b, %.2f) : false",
            mouseModeEnabled,
            widthDp
        );
        target.evaluateJavascript(script, null);
    }

    private void hideSystemKeyboard() {
        if (webView instanceof RdpInputWebView) {
            ((RdpInputWebView) webView).disableForcedIme();
        }
        InputMethodManager inputMethodManager =
            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null && webView != null) {
            inputMethodManager.hideSoftInputFromWindow(webView.getWindowToken(), 0);
        }
    }

    private void openExternalUrl(Uri uri) {
        if (uri == null) {
            return;
        }
        String scheme = uri.getScheme() == null
            ? ""
            : uri.getScheme().toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "No app can open " + uri, Toast.LENGTH_SHORT).show();
        }
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

    private void addRepeatingKey(
        LinearLayout row,
        String label,
        String key,
        String code,
        int keyCode,
        int width
    ) {
        Button button = createKeyButton(label);
        button.setOnClickListener(view -> sendKey(key, code, keyCode));
        final Runnable[] repeatAction = new Runnable[1];
        repeatAction[0] = () -> {
            sendKey(key, code, keyCode);
            button.postDelayed(repeatAction[0], 70L);
        };
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                button.removeCallbacks(repeatAction[0]);
                button.postDelayed(repeatAction[0], 350L);
                break;
            case MotionEvent.ACTION_UP:
                button.removeCallbacks(repeatAction[0]);
                view.performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                button.removeCallbacks(repeatAction[0]);
                break;
            default:
                break;
            }
            return true;
        });
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        // Persist cookies (e.g. the Cloudflare Access session) right away, so the
        // login survives if the system kills the app while it is in the background.
        CookieManager.getInstance().flush();
        if (!keepAliveEnabled) {
            boolean activeViewIsCached = activeSessionKey != null;
            for (ProjectSession session : projectSessions.values()) {
                session.webView.onPause();
            }
            if (!activeViewIsCached && webView != null) {
                webView.onPause();
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (keepAliveEnabled) {
            applyKeepAliveMode();
        }
        updateSessionKeepAlivePulse();
        boolean activeViewIsCached = activeSessionKey != null;
        for (ProjectSession session : projectSessions.values()) {
            session.webView.onResume();
        }
        if (!activeViewIsCached && webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        keepAliveHandler.removeCallbacks(sessionKeepAlivePulse);
        addressBarHandler.removeCallbacks(autoHideAddressBar);
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
            if (forcedImeEnabled) {
                return true;
            }
            // Mouse mode never lets page focus changes raise the system keyboard.
            return !mouseModeEnabled && super.onCheckIsTextEditor();
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (!forcedImeEnabled) {
                return mouseModeEnabled ? null : super.onCreateInputConnection(outAttrs);
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

    /**
     * Watches for a downward pull from the top edge of the content while the address
     * bar is hidden. Touches still reach the page until the pull is recognized, so
     * taps near the top edge keep working; the page then receives a cancel.
     */
    private final class EdgeGestureLayout extends LinearLayout {
        private float edgePullStartX;
        private float edgePullStartY;
        private boolean trackingEdgePull;
        private boolean consumingEdgePull;

        EdgeGestureLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                trackingEdgePull = false;
                consumingEdgePull = false;
                boolean barHidden = addressBar != null
                    && addressBar.getVisibility() != View.VISIBLE;
                float contentY = event.getY() - getPaddingTop();
                if (barHidden && contentY >= 0f && contentY <= dp(40)) {
                    edgePullStartX = event.getX();
                    edgePullStartY = event.getY();
                    trackingEdgePull = true;
                }
            }
            if (consumingEdgePull) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    consumingEdgePull = false;
                }
                return true;
            }
            if (trackingEdgePull && action == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(event.getX() - edgePullStartX);
                float dy = event.getY() - edgePullStartY;
                if (dy >= dp(24) && dx < dy) {
                    trackingEdgePull = false;
                    consumingEdgePull = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    showAddressBarTemporarily();
                    // The same edge swipe also brought up the transient system bars;
                    // put them away so the first swipe belongs to the app. Once the
                    // address bar shows, a further swipe keeps them.
                    hideSystemBars();
                    postDelayed(MainActivity.this::hideSystemBars, 250L);
                    return true;
                }
                if (dy < -dp(8) || dx > dp(48)) {
                    trackingEdgePull = false;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
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
