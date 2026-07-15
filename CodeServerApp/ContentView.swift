import SwiftUI

struct ContentView: View {
    @AppStorage("codeServerURL") private var serverURL = ""
    @StateObject private var webViewStore = CodeServerWebViewStore()
    @State private var draftAddress = ""
    @State private var isShowingSettings = false

    var body: some View {
        Group {
            if serverURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                setupView
            } else {
                browserView
            }
        }
        .onAppear {
            if draftAddress.isEmpty {
                draftAddress = serverURL
            }
        }
        .sheet(isPresented: $isShowingSettings) {
            ServerSettingsView(serverURL: $serverURL)
        }
    }

    private var setupView: some View {
        VStack(spacing: 24) {
            Image(systemName: "chevron.left.forwardslash.chevron.right")
                .font(.system(size: 58, weight: .semibold))
                .foregroundColor(.accentColor)

            VStack(spacing: 8) {
                Text("CodeServerApp")
                    .font(.largeTitle.bold())
                Text("Enter the address of your code-server instance.")
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }

            TextField("http://192.168.1.10:8080", text: $draftAddress)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .textFieldStyle(.roundedBorder)
                .onSubmit(saveDraftAddress)

            Button("Connect", action: saveDraftAddress)
                .buttonStyle(.borderedProminent)
                .disabled(draftAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(28)
    }

    private var browserView: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Image(systemName: "network")
                    .foregroundColor(.secondary)

                Text(serverURL)
                    .font(.system(.caption, design: .monospaced))
                    .lineLimit(1)
                    .truncationMode(.middle)

                Spacer(minLength: 8)

                Button {
                    webViewStore.reload()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("Reload code-server")

                Button {
                    isShowingSettings = true
                } label: {
                    Image(systemName: "gearshape")
                }
                .accessibilityLabel("Server settings")
            }
            .padding(.horizontal, 12)
            .frame(height: 44)
            .background(Color(uiColor: .secondarySystemBackground))

            CodeServerWebView(address: serverURL, store: webViewStore)

            SpecialKeyBar { key in
                webViewStore.send(key)
            }
        }
    }

    private func saveDraftAddress() {
        serverURL = CodeServerWebViewStore.normalizedAddress(draftAddress)
    }
}

private struct ServerSettingsView: View {
    @Binding var serverURL: String
    @Environment(\.dismiss) private var dismiss
    @State private var draftAddress: String

    init(serverURL: Binding<String>) {
        _serverURL = serverURL
        _draftAddress = State(initialValue: serverURL.wrappedValue)
    }

    var body: some View {
        NavigationView {
            Form {
                Section("code-server address") {
                    TextField("http://192.168.1.10:8080", text: $draftAddress)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                }

                Section {
                    Text("HTTP is allowed for local-network servers. Use a valid HTTPS certificate when connecting over the internet.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Server")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        serverURL = CodeServerWebViewStore.normalizedAddress(draftAddress)
                        dismiss()
                    }
                    .disabled(draftAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
