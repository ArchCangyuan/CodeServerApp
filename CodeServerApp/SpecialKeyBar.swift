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
    case arrowLeft
    case arrowUp
    case arrowDown
    case arrowRight

    var id: String { rawValue }

    var label: String {
        switch self {
        case .escape: return "Esc"
        case .tab: return "Tab"
        case .enter: return "Enter"
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

    let onKey: (CodeServerKey) -> Void
    let onModifiersChanged: (_ control: Bool, _ shift: Bool) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                modifierButton(label: "Ctrl", isLocked: controlLocked) {
                    controlLocked.toggle()
                    onModifiersChanged(controlLocked, shiftLocked)
                }

                modifierButton(label: "Shift", isLocked: shiftLocked) {
                    shiftLocked.toggle()
                    onModifiersChanged(controlLocked, shiftLocked)
                }

                ForEach(CodeServerKey.allCases) { key in
                    Button {
                        onKey(key)
                    } label: {
                        Text(key.label)
                            .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                            .frame(minWidth: 44, minHeight: 36)
                            .padding(.horizontal, 5)
                            .background(Color(uiColor: .tertiarySystemFill))
                            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(key.label)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
        }
        .background(Color(uiColor: .secondarySystemBackground))
    }

    private func modifierButton(
        label: String,
        isLocked: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Text(label)
                Image(systemName: isLocked ? "lock.fill" : "lock.open")
                    .font(.caption2)
            }
            .font(.system(.subheadline, design: .monospaced).weight(.semibold))
            .frame(minWidth: 62, minHeight: 36)
            .padding(.horizontal, 5)
            .foregroundColor(isLocked ? .white : .primary)
            .background(isLocked ? Color.accentColor : Color(uiColor: .tertiarySystemFill))
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label) \(isLocked ? "locked" : "unlocked")")
    }
}
