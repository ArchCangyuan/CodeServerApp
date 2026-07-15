import SwiftUI

struct KeyboardStroke {
    let key: String
    let code: String
    let keyCode: Int
    let control: Bool
}

enum CodeServerKey: String, CaseIterable, Identifiable {
    case escape
    case tab
    case controlC
    case controlX
    case controlD
    case controlZ
    case controlA
    case controlL
    case controlR
    case arrowLeft
    case arrowUp
    case arrowDown
    case arrowRight

    var id: String { rawValue }

    var label: String {
        switch self {
        case .escape: return "Esc"
        case .tab: return "Tab"
        case .controlC: return "Ctrl+C"
        case .controlX: return "Ctrl+X"
        case .controlD: return "Ctrl+D"
        case .controlZ: return "Ctrl+Z"
        case .controlA: return "Ctrl+A"
        case .controlL: return "Ctrl+L"
        case .controlR: return "Ctrl+R"
        case .arrowLeft: return "←"
        case .arrowUp: return "↑"
        case .arrowDown: return "↓"
        case .arrowRight: return "→"
        }
    }

    var stroke: KeyboardStroke {
        switch self {
        case .escape:
            return KeyboardStroke(key: "Escape", code: "Escape", keyCode: 27, control: false)
        case .tab:
            return KeyboardStroke(key: "Tab", code: "Tab", keyCode: 9, control: false)
        case .controlC:
            return controlStroke("c", keyCode: 67)
        case .controlX:
            return controlStroke("x", keyCode: 88)
        case .controlD:
            return controlStroke("d", keyCode: 68)
        case .controlZ:
            return controlStroke("z", keyCode: 90)
        case .controlA:
            return controlStroke("a", keyCode: 65)
        case .controlL:
            return controlStroke("l", keyCode: 76)
        case .controlR:
            return controlStroke("r", keyCode: 82)
        case .arrowLeft:
            return KeyboardStroke(key: "ArrowLeft", code: "ArrowLeft", keyCode: 37, control: false)
        case .arrowUp:
            return KeyboardStroke(key: "ArrowUp", code: "ArrowUp", keyCode: 38, control: false)
        case .arrowDown:
            return KeyboardStroke(key: "ArrowDown", code: "ArrowDown", keyCode: 40, control: false)
        case .arrowRight:
            return KeyboardStroke(key: "ArrowRight", code: "ArrowRight", keyCode: 39, control: false)
        }
    }

    private func controlStroke(_ character: String, keyCode: Int) -> KeyboardStroke {
        KeyboardStroke(
            key: character,
            code: "Key\(character.uppercased())",
            keyCode: keyCode,
            control: true
        )
    }
}

struct SpecialKeyBar: View {
    let onKey: (CodeServerKey) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
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
}
