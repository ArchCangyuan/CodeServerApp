# YourWorkspace

Native iOS and Android wrappers for [code-server](https://github.com/coder/code-server).

## Features

- Configurable code-server URL with persistent cookies and website data
- Local HTTP server support
- Reload and server-address controls
- Supplemental keyboard for `Esc`, `Tab`, `Enter`, `Backspace`, and arrow keys
- Lockable `Ctrl` and `Shift` modifiers for combinations entered with the
  on-screen keyboard, a hardware keyboard, or the supplemental keys
- Native SwiftUI/`WKWebView` iOS app and native Java/Android `WebView` app
- Safe-area handling with an optional immersive fullscreen toggle; pull down from the top edge to exit fullscreen
- Desktop-site mode and an IME-aware supplemental key bar on both platforms
- Persistent virtual-viewport zoom controls that automatically reload once to apply a full-width, non-cropped layout
- A force-keyboard button backed by native Android and iOS input capture, keeping
  focus on canvas-based web RDP clients while forwarding text and editing keys
- Cloudflare Access browser-RDP support that focuses IronRDP's Shadow DOM
  renderer and sends text, Backspace, Delete, and Enter through its key handler
- Saved project profiles with bold titles and up to six hot WebView sessions retained for 30 minutes on both platforms
- GitHub Actions workflows that produce a TrollStore-ready IPA and an Android APK

## Repository layout

```text
CodeServerApp/
├── ios/          # SwiftUI and WKWebView application
├── android/      # Java and Android WebView application
├── branding/     # Shared source artwork
├── tools/        # Shared asset-generation utilities
└── .github/      # Independent IPA and APK workflows
```

The two applications are peers in one repository. They share branding and release history, but each has its own native project and build workflow.

## Build the iOS IPA

1. Open the repository's **Actions** tab.
2. Select **Build YourWorkspace IPA**.
3. Choose **Run workflow**.
4. Download and extract the `YourWorkspace.ipa` artifact.
5. Send `YourWorkspace.ipa` to the iPhone and open it with TrollStore.

The workflow builds without an Apple provisioning profile and applies an ad-hoc signature before packaging.

## Build the Android APK

1. Open the repository's **Actions** tab.
2. Select **Build YourWorkspace Android APK**.
3. Choose **Run workflow**.
4. Download `YourWorkspace.apk` and open it on the Android device.
5. Allow installation from the browser or file manager when Android asks.

The Android workflow produces a debug-signed APK that needs no Play Store or signing secrets. Android 8.0 (API 26) or newer is supported. Because hosted runners create development signing keys, uninstall an older workflow build first if Android reports an incompatible update signature.

## Development

- Open `ios/CodeServerApp.xcodeproj` in Xcode.
- Deployment target: iOS 15.0.
- Bundle identifier: `net.archcangyuan.CodeServerApp`.
- Open `android/` in Android Studio for Android development.
- Android application ID: `net.archcangyuan.codeserverapp.debug`.
- The initial server address is saved with `AppStorage` on iOS and
  `SharedPreferences` on Android.

## Notes

The supplemental keys are dispatched to the currently focused DOM element as keyboard events. `Ctrl` and `Shift` stay locked until tapped again. While locked, native keyboard events are redispatched with the selected modifiers. code-server's editor and terminal normally handle these events, but browser-security restrictions can prevent synthetic events from performing privileged browser operations such as clipboard paste.

TrollStore only works on specific iOS versions. Check the current compatibility list in the [official TrollStore repository](https://github.com/opa334/TrollStore).
