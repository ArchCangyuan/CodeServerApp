import SwiftUI
import WebKit

private let keyboardBridgeSource = """
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
"""

@MainActor
final class CodeServerWebViewStore: ObservableObject {
    weak var webView: WKWebView?
    private var controlLocked = false
    private var shiftLocked = false

    static func normalizedAddress(_ address: String) -> String {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        if let scheme = URLComponents(string: trimmed)?.scheme?.lowercased(),
           scheme == "http" || scheme == "https" {
            return trimmed
        }
        return "http://\(trimmed)"
    }

    func reload() {
        webView?.reload()
    }

    func setModifiers(control: Bool, shift: Bool) {
        controlLocked = control
        shiftLocked = shift
        syncModifiers()
    }

    func syncModifiers() {
        let script = """
        window.__codeServerAppKeyboard?.setModifiers(
          \(controlLocked ? "true" : "false"),
          \(shiftLocked ? "true" : "false")
        );
        """
        webView?.evaluateJavaScript(script)
    }

    func send(_ key: CodeServerKey) {
        guard let webView else { return }
        let stroke = key.stroke

        let script = """
        (() => {
          const target = document.activeElement || document.body;
          if (!target) return false;
          if (typeof target.focus === 'function') target.focus();

          const dispatch = (type) => {
            const event = new KeyboardEvent(type, {
              key: '\(stroke.key)',
              code: '\(stroke.code)',
              ctrlKey: \(controlLocked ? "true" : "false"),
              altKey: false,
              shiftKey: \(shiftLocked ? "true" : "false"),
              metaKey: false,
              bubbles: true,
              cancelable: true,
              composed: true
            });

            try {
              Object.defineProperty(event, 'keyCode', { get: () => \(stroke.keyCode) });
              Object.defineProperty(event, 'which', { get: () => \(stroke.keyCode) });
            } catch (_) {}

            target.dispatchEvent(event);
          };

          dispatch('keydown');
          dispatch('keyup');
          return true;
        })();
        """

        webView.evaluateJavaScript(script)
    }
}

struct CodeServerWebView: UIViewRepresentable {
    let address: String
    let store: CodeServerWebViewStore

    func makeCoordinator() -> Coordinator {
        Coordinator(store: store)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.userContentController.addUserScript(
            WKUserScript(
                source: keyboardBridgeSource,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            )
        )

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsLinkPreview = false
        webView.scrollView.keyboardDismissMode = .interactive
        store.webView = webView
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        store.webView = webView

        let normalized = CodeServerWebViewStore.normalizedAddress(address)
        guard context.coordinator.loadedAddress != normalized,
              let url = URL(string: normalized) else {
            return
        }

        context.coordinator.loadedAddress = normalized
        webView.load(URLRequest(url: url))
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var loadedAddress: String?
        weak var store: CodeServerWebViewStore?

        init(store: CodeServerWebViewStore) {
            self.store = store
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor [weak store] in
                store?.syncModifiers()
            }
        }
    }
}
