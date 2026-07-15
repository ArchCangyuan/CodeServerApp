# CodeServerApp

An iOS wrapper for [code-server](https://github.com/coder/code-server), built with SwiftUI and `WKWebView`.

## Features

- Configurable code-server URL with persistent cookies and website data
- Local HTTP server support
- Reload and server-address controls
- Supplemental keyboard for `Esc`, `Tab`, `Enter`, and arrow keys
- Lockable `Ctrl` and `Shift` modifiers for combinations entered with the
  on-screen keyboard, a hardware keyboard, or the supplemental keys
- GitHub Actions workflow that produces a TrollStore-ready IPA

## Build the IPA

1. Open the repository's **Actions** tab.
2. Select **Build CodeServerApp IPA**.
3. Choose **Run workflow**.
4. Download and extract the `CodeServerApp-*` artifact.
5. Send `CodeServerApp.ipa` to the iPhone and open it with TrollStore.

The workflow builds without an Apple provisioning profile and applies an ad-hoc signature before packaging.

## Development

- Open `CodeServerApp.xcodeproj` in Xcode.
- Deployment target: iOS 15.0.
- Bundle identifier: `net.archcangyuan.CodeServerApp`.
- The initial server address is entered inside the app and saved with `AppStorage`.

## Notes

The supplemental keys are dispatched to the currently focused DOM element as keyboard events. `Ctrl` and `Shift` stay locked until tapped again. While locked, native keyboard events are redispatched with the selected modifiers. code-server's editor and terminal normally handle these events, but browser-security restrictions can prevent synthetic events from performing privileged browser operations such as clipboard paste.

TrollStore only works on specific iOS versions. Check the current compatibility list in the [official TrollStore repository](https://github.com/opa334/TrollStore).
