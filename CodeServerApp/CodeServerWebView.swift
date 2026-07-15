import SwiftUI
import WebKit

@MainActor
final class CodeServerWebViewStore: ObservableObject {
    weak var webView: WKWebView?

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
              ctrlKey: \(stroke.control ? "true" : "false"),
              altKey: false,
              shiftKey: false,
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
        Coordinator()
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()

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
    }
}
