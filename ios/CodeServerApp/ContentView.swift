import SwiftUI

private let savedProjectsKey = "savedProjects"

struct ProjectProfile: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    var url: String

    static func loadSaved() -> [ProjectProfile] {
        guard let data = UserDefaults.standard.data(forKey: savedProjectsKey),
              let projects = try? JSONDecoder().decode([ProjectProfile].self, from: data) else {
            return []
        }
        return projects
    }

    static func persist(_ projects: [ProjectProfile]) {
        guard let data = try? JSONEncoder().encode(projects) else { return }
        UserDefaults.standard.set(data, forKey: savedProjectsKey)
    }
}

struct ContentView: View {
    @AppStorage("codeServerURL") private var serverURL = ""
    @StateObject private var webViewStore = CodeServerWebViewStore()
    @State private var draftAddress = ""
    @State private var activeSessionAddress = ""
    @State private var savedProjects = ProjectProfile.loadSaved()
    @State private var isShowingProjects = false
    @State private var controlLocked = false
    @State private var shiftLocked = false
    @State private var isFullscreen = false
    @FocusState private var addressFieldFocused: Bool

    var body: some View {
        Group {
            if serverURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                setupView
            } else {
                browserView
            }
        }
        .statusBarHidden(isFullscreen)
        .onAppear {
            if draftAddress.isEmpty {
                draftAddress = serverURL
            }
            if activeSessionAddress.isEmpty {
                activeSessionAddress = serverURL
            }
        }
        .onChange(of: serverURL) { newAddress in
            if !addressFieldFocused {
                draftAddress = newAddress
            }
        }
        .onChange(of: webViewStore.currentPageAddress) { newAddress in
            guard !newAddress.isEmpty else { return }
            serverURL = newAddress
            if !addressFieldFocused {
                draftAddress = newAddress
            }
        }
    }

    private var setupView: some View {
        VStack(spacing: 24) {
            Image("AppLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 92, height: 92)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

            VStack(spacing: 8) {
                Text("YourWorkspace")
                    .font(.largeTitle.bold())
                Text("Enter the address of your code-server instance.")
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }

            TextField("http://192.168.1.10:8080", text: $draftAddress)
                .focused($addressFieldFocused)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .textFieldStyle(.roundedBorder)
                .onSubmit(loadDraftAddress)

            Button("Connect", action: loadDraftAddress)
                .buttonStyle(.borderedProminent)
                .disabled(draftAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(28)
    }

    private var browserView: some View {
        VStack(spacing: 0) {
            if !isFullscreen {
                addressBar
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            CodeServerWebView(
                address: activeSessionAddress.isEmpty ? serverURL : activeSessionAddress,
                store: webViewStore
            )

            SpecialKeyBar(
                controlLocked: $controlLocked,
                shiftLocked: $shiftLocked,
                onKeyboard: {
                    webViewStore.forceKeyboard()
                },
                onKey: { key in
                    webViewStore.send(key)
                },
                onCommand: { command in
                    webViewStore.sendCommand(command)
                },
                onControlC: {
                    webViewStore.sendControlC()
                },
                onModifiersChanged: { control, shift in
                    webViewStore.setModifiers(control: control, shift: shift)
                }
            )
        }
        .ignoresSafeArea(.container, edges: isFullscreen ? .top : [])
        .animation(.easeInOut(duration: 0.18), value: isFullscreen)
        .overlay(alignment: .top) {
            if isFullscreen {
                topEdgeExitGesture
            }
        }
        .overlay(alignment: .top) {
            if let message = webViewStore.statusMessage {
                Text(message)
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.black.opacity(0.78))
                    .clipShape(Capsule())
                    .padding(.top, isFullscreen ? 18 : 8)
                    .transition(.opacity)
                    .allowsHitTesting(false)
            }
        }
        .sheet(isPresented: $isShowingProjects) {
            ProjectSwitcherView(
                projects: $savedProjects,
                currentAddress: CodeServerWebViewStore.normalizedAddress(draftAddress),
                isHot: { webViewStore.isSessionHot($0) },
                onOpen: openProject,
                onSave: saveProject,
                onDelete: deleteProjects
            )
        }
    }

    private var addressBar: some View {
        HStack(spacing: 7) {
            toolbarButton(systemName: "square.stack.3d.up", label: "Switch projects") {
                isShowingProjects = true
            }

            TextField("http://192.168.1.10:8080", text: $draftAddress)
                .focused($addressFieldFocused)
                .font(.system(.caption, design: .monospaced))
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .submitLabel(.go)
                .textFieldStyle(.roundedBorder)
                .layoutPriority(1)
                .onSubmit(loadDraftAddress)

            toolbarButton(systemName: "arrow.clockwise", label: "Reload code-server") {
                webViewStore.reload()
            }

            toolbarButton(systemName: "minus.magnifyingglass", label: "Zoom out") {
                webViewStore.changeZoom(by: -1)
            }

            toolbarButton(systemName: "plus.magnifyingglass", label: "Zoom in") {
                webViewStore.changeZoom(by: 1)
            }

            toolbarButton(
                systemName: "arrow.up.left.and.arrow.down.right",
                label: "Enter fullscreen"
            ) {
                isFullscreen = true
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(minHeight: 52)
        .background(Color(uiColor: .secondarySystemBackground))
    }

    private var topEdgeExitGesture: some View {
        VStack(spacing: 0) {
            Color.clear
                .frame(height: 42)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 8)
                        .onEnded { value in
                            if value.translation.height >= 48,
                               abs(value.translation.width) < 140 {
                                isFullscreen = false
                            }
                        }
                )
            Spacer(minLength: 0)
        }
        .ignoresSafeArea(.container, edges: .top)
    }

    private func toolbarButton(
        systemName: String,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .frame(width: 25, height: 30)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(label)
    }

    private func loadDraftAddress() {
        let normalized = CodeServerWebViewStore.normalizedAddress(draftAddress)
        guard !normalized.isEmpty else { return }
        draftAddress = normalized
        activeSessionAddress = normalized
        serverURL = normalized
        webViewStore.activate(address: normalized)
    }

    private func openProject(_ project: ProjectProfile) {
        draftAddress = project.url
        activeSessionAddress = project.url
        serverURL = project.url
        webViewStore.activate(address: project.url)
        isShowingProjects = false
    }

    private func saveProject(name: String, url: String) {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedURL = CodeServerWebViewStore.normalizedAddress(url)
        guard !normalizedURL.isEmpty else { return }
        savedProjects.append(
            ProjectProfile(
                name: trimmedName.isEmpty ? "Project \(savedProjects.count + 1)" : trimmedName,
                url: normalizedURL
            )
        )
        ProjectProfile.persist(savedProjects)
    }

    private func deleteProjects(at offsets: IndexSet) {
        savedProjects.remove(atOffsets: offsets)
        ProjectProfile.persist(savedProjects)
    }
}

private struct ProjectSwitcherView: View {
    @Binding var projects: [ProjectProfile]
    let currentAddress: String
    let isHot: (String) -> Bool
    let onOpen: (ProjectProfile) -> Void
    let onSave: (String, String) -> Void
    let onDelete: (IndexSet) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var isShowingSaveProject = false

    var body: some View {
        NavigationView {
            List {
                Section {
                    Button {
                        isShowingSaveProject = true
                    } label: {
                        Label("Save current address", systemImage: "plus.circle.fill")
                    }
                    .disabled(currentAddress.isEmpty)
                }

                Section("Saved projects") {
                    if projects.isEmpty {
                        Text("No saved projects")
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(projects) { project in
                            Button {
                                onOpen(project)
                            } label: {
                                HStack(spacing: 10) {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(project.name)
                                            .font(.body.bold())
                                            .foregroundColor(.primary)
                                        Text(project.url)
                                            .font(.caption.monospaced())
                                            .foregroundColor(.secondary)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 8)
                                    if isHot(project.url) {
                                        Text("HOT")
                                            .font(.caption2.bold())
                                            .foregroundColor(.green)
                                            .padding(.horizontal, 7)
                                            .padding(.vertical, 3)
                                            .background(Color.green.opacity(0.13))
                                            .clipShape(Capsule())
                                    }
                                }
                            }
                        }
                        .onDelete(perform: onDelete)
                    }
                }
            }
            .navigationTitle("Projects")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    EditButton()
                        .disabled(projects.isEmpty)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
        .sheet(isPresented: $isShowingSaveProject) {
            SaveProjectView(
                defaultName: "Project \(projects.count + 1)",
                address: currentAddress,
                onSave: onSave
            )
        }
    }
}

private struct SaveProjectView: View {
    let address: String
    let onSave: (String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String

    init(defaultName: String, address: String, onSave: @escaping (String, String) -> Void) {
        self.address = address
        self.onSave = onSave
        _name = State(initialValue: defaultName)
    }

    var body: some View {
        NavigationView {
            Form {
                Section("Project name") {
                    TextField("Project name", text: $name)
                }
                Section("Address") {
                    Text(address)
                        .font(.footnote.monospaced())
                        .textSelection(.enabled)
                }
            }
            .navigationTitle("Save project")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(name, address)
                        dismiss()
                    }
                    .disabled(address.isEmpty)
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}
