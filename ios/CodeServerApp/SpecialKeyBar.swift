import SwiftUI

struct KeyboardStroke {
    let key: String
    let code: String
    let keyCode: Int
}

enum CodeServerKey: String, CaseIterable, Identifiable {
    case escape
    case tab
    case enter
    case backspace
    case arrowLeft
    case arrowUp
    case arrowDown
    case arrowRight

    var id: String { rawValue }

    var isArrow: Bool {
        switch self {
        case .arrowLeft, .arrowUp, .arrowDown, .arrowRight:
            return true
        default:
            return false
        }
    }

    var label: String {
        switch self {
        case .escape: return "Esc"
        case .tab: return "Tab"
        case .enter: return "Enter"
        case .backspace: return "Bksp"
        case .arrowLeft: return "←"
        case .arrowUp: return "↑"
        case .arrowDown: return "↓"
        case .arrowRight: return "→"
        }
    }

    var stroke: KeyboardStroke {
        switch self {
        case .escape:
            return KeyboardStroke(key: "Escape", code: "Escape", keyCode: 27)
        case .tab:
            return KeyboardStroke(key: "Tab", code: "Tab", keyCode: 9)
        case .enter:
            return KeyboardStroke(key: "Enter", code: "Enter", keyCode: 13)
        case .backspace:
            return KeyboardStroke(key: "Backspace", code: "Backspace", keyCode: 8)
        case .arrowLeft:
            return KeyboardStroke(key: "ArrowLeft", code: "ArrowLeft", keyCode: 37)
        case .arrowUp:
            return KeyboardStroke(key: "ArrowUp", code: "ArrowUp", keyCode: 38)
        case .arrowDown:
            return KeyboardStroke(key: "ArrowDown", code: "ArrowDown", keyCode: 40)
        case .arrowRight:
            return KeyboardStroke(key: "ArrowRight", code: "ArrowRight", keyCode: 39)
        }
    }
}

struct SpecialKeyBar: View {
    @Binding var controlLocked: Bool
    @Binding var shiftLocked: Bool
    @State private var repeatTask: Task<Void, Never>? = nil

    let onKeyboard: () -> Void
    let onKey: (CodeServerKey) -> Void
    let onCommand: (String) -> Void
    let onControlC: () -> Void
    let onModifiersChanged: (_ control: Bool, _ shift: Bool) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                keyButton(label: "KB", accessibilityLabel: "Force show keyboard") {
                    onKeyboard()
                }

                modifierButton(label: "Ctrl", isLocked: controlLocked) {
                    controlLocked.toggle()
                    onModifiersChanged(controlLocked, shiftLocked)
                }

                modifierButton(label: "Shift", isLocked: shiftLocked) {
                    shiftLocked.toggle()
                    onModifiersChanged(controlLocked, shiftLocked)
                }

                ForEach(CodeServerKey.allCases) { key in
                    if key.isArrow {
                        repeatingKeyButton(key)
                    } else {
                        keyButton(label: key.label, accessibilityLabel: key.label) {
                            onKey(key)
                        }
                    }
                }

                ForEach(["/context", "/rewind", "/cost"], id: \.self) { command in
                    keyButton(label: command, accessibilityLabel: "Send \(command) command") {
                        onCommand(command)
                    }
                }

                keyButton(label: "Ctrl+C", accessibilityLabel: "Send Control C") {
                    onControlC()
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 6)
        }
        .background(Color(uiColor: .secondarySystemBackground))
        .onDisappear(perform: stopRepeating)
    }

    private func keyButton(
        label: String,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                .frame(minWidth: label == "Enter" ? 52 : 38, minHeight: 34)
                .padding(.horizontal, 3)
                .background(Color(uiColor: .tertiarySystemFill))
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    private func repeatingKeyButton(_ key: CodeServerKey) -> some View {
        keyButton(label: key.label, accessibilityLabel: "Hold \(key.label) to repeat") {
            onKey(key)
        }
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    startRepeating(key)
                }
                .onEnded { _ in
                    stopRepeating()
                }
        )
    }

    private func startRepeating(_ key: CodeServerKey) {
        guard repeatTask == nil else { return }
        repeatTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            while !Task.isCancelled {
                onKey(key)
                try? await Task.sleep(nanoseconds: 70_000_000)
            }
        }
    }

    private func stopRepeating() {
        repeatTask?.cancel()
        repeatTask = nil
    }

    private func modifierButton(
        label: String,
        isLocked: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 3) {
                Text(label)
                Image(systemName: isLocked ? "lock.fill" : "lock.open")
                    .font(.caption2)
            }
            .font(.system(.subheadline, design: .monospaced).weight(.semibold))
            .frame(minWidth: 54, minHeight: 34)
            .padding(.horizontal, 3)
            .foregroundColor(isLocked ? .white : .primary)
            .background(isLocked ? Color.accentColor : Color(uiColor: .tertiarySystemFill))
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label) \(isLocked ? "locked" : "unlocked")")
    }
}
