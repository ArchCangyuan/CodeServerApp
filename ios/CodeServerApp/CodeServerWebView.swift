import SwiftUI
import UIKit
import WebKit

private let desktopViewportWidth = 1280
private let minimumLayoutZoomSteps = -6
private let maximumLayoutZoomSteps = 6
private let layoutZoomFactor = 1.1
private let projectSessionTTL: TimeInterval = 30 * 60
private let maximumHotProjectSessions = 6
private let layoutZoomStepsKey = "layoutZoomSteps"

private let desktopUserAgent = """
Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 \
(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36
""".replacingOccurrences(of: "\n", with: "")

private let keyboardBridgeSource = #"""
(() => {
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
  if (existingBridge && existingBridge.version >= 7) {
    window.__codeServerAppForceKeyboard = () => existingBridge.forceKeyboard();
    existingBridge.installRdpGestures?.();
    return;
  }

  const state = {
    control: false,
    shift: false,
    target: null,
    ironRdpCanvas: null,
    gestureCanvas: null
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

  installRdpGestures();
  window.setInterval(installRdpGestures, 1000);
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
    if (!candidate) return;
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
    if (current) {
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

  const dispatchKeyPhase = (target, type, key, code, keyCode, source = null) => {
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
    const charCode = type === 'keypress' && printable ? key.codePointAt(0) : 0;
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
      return { code: `Digit${key}`, keyCode: key.charCodeAt(0), shift: false };
    }
    if (key === ' ') return { code: 'Space', keyCode: 32, shift: false };
    const punctuation = {
      '`': ['Backquote', 192, false], '~': ['Backquote', 192, true],
      '-': ['Minus', 189, false], '_': ['Minus', 189, true],
      '=': ['Equal', 187, false], '+': ['Equal', 187, true],
      '[': ['BracketLeft', 219, false], '{': ['BracketLeft', 219, true],
      ']': ['BracketRight', 221, false], '}': ['BracketRight', 221, true],
      '\\': ['Backslash', 220, false], '|': ['Backslash', 220, true],
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
    return { code: '', keyCode: key.codePointAt(0) || 0, shift: false };
  };

  const dispatchCompleteKey = (key, code, keyCode, forceShift = false) => {
    const target = activeTarget();
    if (!target) return false;
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
    return true;
  };

  const forwardText = (text) => {
    for (const key of Array.from(text || '')) {
      const info = keyInfoForText(key);
      dispatchCompleteKey(key, info.code, info.keyCode, info.shift);
    }
    return true;
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
  document.addEventListener('focusin', (event) => rememberTarget(event.target), true);
  document.addEventListener('keydown', redispatchWithLockedModifiers, true);
  document.addEventListener('keyup', redispatchWithLockedModifiers, true);

  const bridge = {
    version: 7,
    forceKeyboard() {
      installRdpGestures();
      const canvas = findIronRdpCanvas();
      if (canvas) {
        state.ironRdpCanvas = canvas;
        rememberTarget(canvas);
        canvas.focus({ preventScroll: true });
        return 'ironrdp';
      }
      rememberTarget(deepestActiveElement(document));
      return activeTarget() ? 'generic' : 'missing';
    },
    installRdpGestures,
    sendKey(key, code, keyCode) {
      return dispatchCompleteKey(key, code, keyCode);
    },
    sendText(text) {
      return forwardText(String(text || ''));
    },
    setModifiers(control, shift) {
      const nextControl = Boolean(control);
      const nextShift = Boolean(shift);
      const controlChanged = state.control !== nextControl;
      const shiftChanged = state.shift !== nextShift;
      state.control = nextControl;
      state.shift = nextShift;
      if (controlChanged) dispatchModifier('Control', 'ControlLeft', 17, nextControl);
      if (shiftChanged) dispatchModifier('Shift', 'ShiftLeft', 16, nextShift);
    }
  };

  window.__codeServerAppKeyboard = bridge;
  window.__codeServerAppForceKeyboard = () => bridge.forceKeyboard();
})();
"""#

@MainActor
final class CodeServerWebViewStore: NSObject, ObservableObject, WKNavigationDelegate {
    @Published private(set) var zoomPercent: Int
    @Published private(set) var statusMessage: String?
    @Published private(set) var currentPageAddress = ""

    private final class ProjectSession {
        let key: String
        let webView: WKWebView
        var lastInactiveAt: TimeInterval?
        var lastFinishedURL: String?
        var appliedZoomSteps: Int?
        var zoomReloadInProgress = false
        var urlObservation: NSKeyValueObservation?

        init(key: String, webView: WKWebView) {
            self.key = key
            self.webView = webView
        }
    }

    private var sessions: [String: ProjectSession] = [:]
    private weak var hostView: WebViewSessionContainerView?
    private var requestedAddress = ""
    private var activeSessionKey: String?
    private var controlLocked = false
    private var shiftLocked = false
    private var layoutZoomSteps: Int
    private var statusToken = UUID()

    override init() {
        let savedSteps = UserDefaults.standard.integer(forKey: layoutZoomStepsKey)
        layoutZoomSteps = min(max(savedSteps, minimumLayoutZoomSteps), maximumLayoutZoomSteps)
        zoomPercent = Int(round(pow(layoutZoomFactor, Double(layoutZoomSteps)) * 100))
        super.init()
    }

    static func normalizedAddress(_ address: String) -> String {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        if let scheme = URLComponents(string: trimmed)?.scheme?.lowercased(),
           scheme == "http" || scheme == "https" {
            return trimmed
        }
        return "http://\(trimmed)"
    }

    func attach(to view: WebViewSessionContainerView) {
        hostView = view
        view.onCommittedText = { [weak self] text in
            self?.sendText(text)
        }
        view.onDeleteBackward = { [weak self] in
            self?.send(.backspace)
        }
        view.onEnter = { [weak self] in
            self?.send(.enter)
        }
        for session in sessions.values {
            view.install(session.webView)
        }
        if !requestedAddress.isEmpty {
            activate(address: requestedAddress)
        }
    }

    func activate(address: String) {
        let normalized = Self.normalizedAddress(address)
        guard !normalized.isEmpty else { return }
        requestedAddress = normalized
        guard let hostView else { return }

        let now = Date.timeIntervalSinceReferenceDate
        cleanupExpiredSessions(now: now)

        let target: ProjectSession
        let created: Bool
        if let existing = session(matching: normalized) {
            target = existing
            created = false
        } else {
            let webView = makeWebView()
            target = ProjectSession(key: normalized, webView: webView)
            sessions[normalized] = target
            observeURLChanges(for: target)
            hostView.install(webView)
            created = true
        }

        if let activeSessionKey,
           activeSessionKey != target.key,
           let current = sessions[activeSessionKey] {
            current.lastInactiveAt = now
            current.webView.isHidden = true
        }

        activeSessionKey = target.key
        target.lastInactiveAt = nil
        target.webView.isHidden = false
        hostView.bringWebViewToFront(target.webView)
        publishAddress(for: target, fallback: normalized)

        if created, let url = URL(string: normalized) {
            target.webView.load(URLRequest(url: url))
        } else if target.appliedZoomSteps != layoutZoomSteps {
            applyLayoutZoom(to: target, reloadAfterApply: target.webView.url != nil)
        }
        syncModifiers(on: target.webView)
        evictExcessSessions()
    }

    func reload() {
        activeSession?.webView.reload()
    }

    func changeZoom(by direction: Int) {
        guard direction != 0 else { return }
        let nextSteps = min(
            max(layoutZoomSteps + direction, minimumLayoutZoomSteps),
            maximumLayoutZoomSteps
        )
        guard nextSteps != layoutZoomSteps else { return }

        layoutZoomSteps = nextSteps
        UserDefaults.standard.set(layoutZoomSteps, forKey: layoutZoomStepsKey)
        zoomPercent = Int(round(pow(layoutZoomFactor, Double(layoutZoomSteps)) * 100))
        if let activeSession {
            applyLayoutZoom(to: activeSession, reloadAfterApply: activeSession.webView.url != nil)
        }
        showStatus("UI zoom \(zoomPercent)% – reloading to fit")
    }

    func isSessionHot(_ address: String) -> Bool {
        let normalized = Self.normalizedAddress(address)
        let now = Date.timeIntervalSinceReferenceDate
        cleanupExpiredSessions(now: now)
        guard let session = session(matching: normalized) else { return false }
        if session.key == activeSessionKey { return true }
        guard let inactiveAt = session.lastInactiveAt else { return false }
        return now - inactiveAt < projectSessionTTL
    }

    func setModifiers(control: Bool, shift: Bool) {
        controlLocked = control
        shiftLocked = shift
        if let webView = activeSession?.webView {
            syncModifiers(on: webView)
        }
        if control || shift {
            hostView?.activateKeyboardCapture()
        }
    }

    func send(_ key: CodeServerKey) {
        guard let webView = activeSession?.webView else { return }
        let stroke = key.stroke
        let script = """
        window.__codeServerAppKeyboard?.sendKey(
          \(Self.javaScriptString(stroke.key)),
          \(Self.javaScriptString(stroke.code)),
          \(stroke.keyCode)
        ) ?? false;
        """
        webView.evaluateJavaScript(script)
    }

    func sendText(_ text: String) {
        guard !text.isEmpty, let webView = activeSession?.webView else { return }
        let script = """
        window.__codeServerAppKeyboard?.sendText(
          \(Self.javaScriptString(text))
        ) ?? false;
        """
        webView.evaluateJavaScript(script)
    }

    func forceKeyboard() {
        guard let session = activeSession else { return }
        session.webView.evaluateJavaScript(
            "window.__codeServerAppForceKeyboard ? window.__codeServerAppForceKeyboard() : false"
        ) { [weak self] value, _ in
            Task { @MainActor [weak self] in
                guard let self,
                      self.activeSessionKey == session.key else { return }
                self.hostView?.activateKeyboardCapture()
                if let mode = value as? String, mode == "ironrdp" {
                    self.showStatus("IronRDP focused – IME connected")
                } else {
                    self.showStatus("Keyboard connected")
                }
            }
        }
    }

    private var activeSession: ProjectSession? {
        guard let activeSessionKey else { return nil }
        return sessions[activeSessionKey]
    }

    private func session(matching normalizedAddress: String) -> ProjectSession? {
        if let exact = sessions[normalizedAddress] {
            return exact
        }
        return sessions.values.first { session in
            guard let currentURL = session.webView.url?.absoluteString else { return false }
            return Self.normalizedAddress(currentURL) == normalizedAddress
        }
    }

    private func observeURLChanges(for session: ProjectSession) {
        session.urlObservation = session.webView.observe(\.url, options: [.initial, .new]) {
            [weak self, weak session] webView, _ in
            let address = webView.url?.absoluteString ?? ""
            Task { @MainActor [weak self, weak session] in
                guard let self,
                      let session,
                      self.sessions[session.key] === session else { return }
                self.publishAddress(for: session, fallback: address)
            }
        }
    }

    private func publishAddress(for session: ProjectSession, fallback: String = "") {
        guard session.key == activeSessionKey else { return }
        let address = session.webView.url?.absoluteString ?? fallback
        let normalized = Self.normalizedAddress(address)
        guard normalized.hasPrefix("http://") || normalized.hasPrefix("https://") else {
            return
        }
        if currentPageAddress != normalized {
            currentPageAddress = normalized
        }
    }

    private func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.defaultWebpagePreferences.preferredContentMode = .desktop
        configuration.userContentController.addUserScript(
            WKUserScript(
                source: keyboardBridgeSource,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            )
        )

        let webView = WKWebView(frame: .zero, configuration: configuration)
        disableDoubleTapZoom(in: webView)
        DispatchQueue.main.async { [weak self, weak webView] in
            guard let self, let webView else { return }
            self.disableDoubleTapZoom(in: webView)
        }
        webView.navigationDelegate = self
        webView.customUserAgent = desktopUserAgent
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsLinkPreview = false
        webView.scrollView.keyboardDismissMode = .interactive
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.isHidden = true
        return webView
    }

    private func disableDoubleTapZoom(in view: UIView) {
        for recognizer in view.gestureRecognizers ?? [] {
            guard let tap = recognizer as? UITapGestureRecognizer,
                  tap.numberOfTapsRequired == 2 else { continue }
            tap.isEnabled = false
        }
        for subview in view.subviews {
            disableDoubleTapZoom(in: subview)
        }
    }

    private func syncModifiers(on webView: WKWebView) {
        let script = """
        window.__codeServerAppKeyboard?.setModifiers(
          \(controlLocked ? "true" : "false"),
          \(shiftLocked ? "true" : "false")
        );
        """
        webView.evaluateJavaScript(script)
    }

    private func viewportWidth() -> Int {
        Int(round(Double(desktopViewportWidth) / pow(layoutZoomFactor, Double(layoutZoomSteps))))
    }

    private func applyLayoutZoom(to session: ProjectSession, reloadAfterApply: Bool) {
        let requestedSteps = layoutZoomSteps
        let requestedWidth = viewportWidth()
        let script = """
        (() => {
          const width = \(requestedWidth);
          if (window.__codeServerAppSetViewportWidth) {
            return window.__codeServerAppSetViewportWidth(width);
          }
          let viewport = document.querySelector('meta[name="viewport"]');
          if (!viewport) {
            viewport = document.createElement('meta');
            viewport.name = 'viewport';
            (document.head || document.documentElement).appendChild(viewport);
          }
          viewport.content = `width=${width}, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes`;
          window.__codeServerAppViewportWidth = width;
          window.dispatchEvent(new Event('resize'));
          return width;
        })();
        """
        session.webView.evaluateJavaScript(script) { [weak self, weak session] _, _ in
            Task { @MainActor [weak self, weak session] in
                guard let self,
                      let session,
                      self.sessions[session.key] === session,
                      requestedSteps == self.layoutZoomSteps else { return }
                session.appliedZoomSteps = requestedSteps
                if reloadAfterApply,
                   session.webView.url != nil,
                   !session.zoomReloadInProgress {
                    session.zoomReloadInProgress = true
                    session.webView.reload()
                }
            }
        }
    }

    private func cleanupExpiredSessions(now: TimeInterval) {
        let expiredKeys = sessions.compactMap { key, session -> String? in
            guard key != activeSessionKey,
                  let inactiveAt = session.lastInactiveAt,
                  now - inactiveAt >= projectSessionTTL else { return nil }
            return key
        }
        for key in expiredKeys {
            destroySession(key: key)
        }
    }

    private func evictExcessSessions() {
        while sessions.count > maximumHotProjectSessions {
            let candidate = sessions.values
                .filter { $0.key != activeSessionKey }
                .min { ($0.lastInactiveAt ?? 0) < ($1.lastInactiveAt ?? 0) }
            guard let candidate else { return }
            destroySession(key: candidate.key)
        }
    }

    private func destroySession(key: String) {
        guard let session = sessions.removeValue(forKey: key) else { return }
        session.urlObservation = nil
        session.webView.stopLoading()
        session.webView.navigationDelegate = nil
        session.webView.removeFromSuperview()
    }

    private func showStatus(_ message: String) {
        let token = UUID()
        statusToken = token
        statusMessage = message
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            guard let self, self.statusToken == token else { return }
            self.statusMessage = nil
        }
    }

    private static func javaScriptString(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [value]),
              let encoded = String(data: data, encoding: .utf8),
              encoded.count >= 2 else { return "\"\"" }
        return String(encoded.dropFirst().dropLast())
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        if let session = sessions.values.first(where: { $0.webView === webView }),
           !session.zoomReloadInProgress,
           session.lastFinishedURL != navigationAction.request.url?.absoluteString {
            session.appliedZoomSteps = nil
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        disableDoubleTapZoom(in: webView)
        guard let session = sessions.values.first(where: { $0.webView === webView }) else {
            return
        }
        session.lastFinishedURL = webView.url?.absoluteString
        publishAddress(for: session)
        let completedZoomReload = session.zoomReloadInProgress
        session.zoomReloadInProgress = false
        let needsReload = !completedZoomReload
            && ((session.appliedZoomSteps == nil && layoutZoomSteps != 0)
                || (session.appliedZoomSteps != nil
                    && session.appliedZoomSteps != layoutZoomSteps))
        applyLayoutZoom(to: session, reloadAfterApply: needsReload)
        syncModifiers(on: webView)
    }
}

struct CodeServerWebView: UIViewRepresentable {
    let address: String
    let store: CodeServerWebViewStore

    func makeUIView(context: Context) -> WebViewSessionContainerView {
        let view = WebViewSessionContainerView()
        store.attach(to: view)
        return view
    }

    func updateUIView(_ view: WebViewSessionContainerView, context: Context) {
        store.attach(to: view)
        store.activate(address: address)
    }
}

final class WebViewSessionContainerView: UIView {
    var onCommittedText: ((String) -> Void)? {
        didSet { keyboardCapture.onCommittedText = onCommittedText }
    }
    var onDeleteBackward: (() -> Void)? {
        didSet { keyboardCapture.onDeleteBackward = onDeleteBackward }
    }
    var onEnter: (() -> Void)? {
        didSet { keyboardCapture.onEnter = onEnter }
    }

    private let keyboardCapture = KeyboardCaptureTextView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .systemBackground
        addSubview(keyboardCapture)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func install(_ webView: WKWebView) {
        if webView.superview !== self {
            addSubview(webView)
        }
        webView.frame = bounds
        bringSubviewToFront(keyboardCapture)
    }

    func bringWebViewToFront(_ webView: WKWebView) {
        bringSubviewToFront(webView)
        bringSubviewToFront(keyboardCapture)
    }

    func activateKeyboardCapture() {
        bringSubviewToFront(keyboardCapture)
        keyboardCapture.activate()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        for subview in subviews where subview !== keyboardCapture {
            subview.frame = bounds
        }
        keyboardCapture.frame = CGRect(
            x: 1,
            y: max(bounds.height - 2, 1),
            width: 1,
            height: 1
        )
    }
}

private final class KeyboardCaptureTextView: UITextView, UITextViewDelegate {
    var onCommittedText: ((String) -> Void)?
    var onDeleteBackward: (() -> Void)?
    var onEnter: (() -> Void)?
    private var isFlushing = false

    init() {
        super.init(frame: .zero, textContainer: nil)
        delegate = self
        backgroundColor = .clear
        textColor = .clear
        tintColor = .clear
        alpha = 0.01
        isScrollEnabled = false
        autocorrectionType = .no
        autocapitalizationType = .none
        spellCheckingType = .no
        smartDashesType = .no
        smartQuotesType = .no
        smartInsertDeleteType = .no
        keyboardType = .default
        returnKeyType = .default
        accessibilityElementsHidden = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func activate() {
        text = ""
        becomeFirstResponder()
    }

    override func deleteBackward() {
        if markedTextRange == nil && text.isEmpty {
            onDeleteBackward?()
            return
        }
        super.deleteBackward()
    }

    func textViewDidChange(_ textView: UITextView) {
        guard !isFlushing,
              markedTextRange == nil,
              !text.isEmpty else { return }

        let committed = text ?? ""
        isFlushing = true
        text = ""
        isFlushing = false

        var buffer = ""
        for character in committed {
            if character == "\n" || character == "\r" {
                if !buffer.isEmpty {
                    onCommittedText?(buffer)
                    buffer = ""
                }
                onEnter?()
            } else {
                buffer.append(character)
            }
        }
        if !buffer.isEmpty {
            onCommittedText?(buffer)
        }
    }
}
