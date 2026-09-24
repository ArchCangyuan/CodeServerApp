import UIKit

enum MouseAction: String {
    case move
    case down
    case up
}

/// Floating mouse controls drawn above the active web view. Only the joystick and
/// the L/R buttons receive touches; everything else passes through to the page.
/// Each control tracks its own touch, so the joystick can move the cursor while L
/// is held to drag or select.
final class MouseOverlayView: UIView {
    /// Reports an action at the cursor position, normalized to 0...1 of the view.
    var onMouse: ((MouseAction, CGPoint, Int) -> Void)?

    private let maximumSpeed: CGFloat = 700
    private let deadZone: CGFloat = 0.08
    private let dragLockDelay: TimeInterval = 0.5

    private let cursorView = MouseCursorView()
    private let joystick = MouseJoystickView()
    private let leftButton = MouseButtonView(title: "L", accessibilityName: "Left click")
    private let rightButton = MouseButtonView(title: "R", accessibilityName: "Right click")
    private var cursor = CGPoint(x: -1, y: -1)
    private var displayLink: CADisplayLink?
    private var lastTimestamp: CFTimeInterval = 0
    private var leftHeld = false
    private var leftLocked = false
    private var leftLockArmed = false
    private var leftMovedWhileHeld = false
    private var leftUnlockPending = false
    private var rightHeld = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isMultipleTouchEnabled = true
        isHidden = true
        cursorView.isUserInteractionEnabled = false
        addSubview(cursorView)
        addSubview(joystick)
        addSubview(leftButton)
        addSubview(rightButton)

        joystick.onActiveChanged = { [weak self] active in
            if active {
                self?.startTicking()
            } else {
                self?.stopTicking()
            }
        }
        leftButton.onPress = { [weak self] in self?.leftDown() }
        leftButton.onRelease = { [weak self] cancelled in self?.leftUp(cancelled: cancelled) }
        rightButton.onPress = { [weak self] in self?.rightDown() }
        rightButton.onRelease = { [weak self] _ in self?.rightUp() }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setEnabled(_ enabled: Bool) {
        guard enabled == isHidden else { return }
        if enabled {
            isHidden = false
            send(.move, button: 0)
        } else {
            releaseButtons()
            isHidden = true
        }
    }

    func releaseButtons() {
        cancelDragLockTimer()
        if leftHeld || leftLocked || leftUnlockPending {
            send(.up, button: 0)
        }
        if rightHeld {
            send(.up, button: 2)
        }
        leftHeld = false
        leftLocked = false
        leftLockArmed = false
        leftUnlockPending = false
        rightHeld = false
        joystick.reset()
        stopTicking()
        updateButtonStates()
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard !isHidden, isUserInteractionEnabled else { return nil }
        for control in [leftButton, rightButton, joystick] as [UIView] {
            if control.point(inside: convert(point, to: control), with: event) {
                return control
            }
        }
        return nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let margin: CGFloat = 16
        let joystickSize: CGFloat = 128
        joystick.frame = CGRect(
            x: margin,
            y: bounds.height - margin - joystickSize,
            width: joystickSize,
            height: joystickSize
        )
        rightButton.frame = CGRect(
            x: bounds.width - margin - 56,
            y: bounds.height - margin - 56,
            width: 56,
            height: 56
        )
        leftButton.frame = CGRect(
            x: bounds.width - 84 - 68,
            y: bounds.height - 40 - 68,
            width: 68,
            height: 68
        )

        guard bounds.width > 0, bounds.height > 0 else { return }
        if cursor.x < 0 || cursor.y < 0 {
            cursor = CGPoint(x: bounds.midX, y: bounds.midY)
        } else {
            cursor = clamped(cursor)
        }
        updateCursorView()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            stopTicking()
        }
    }

    private func clamped(_ point: CGPoint) -> CGPoint {
        CGPoint(
            x: min(max(point.x, 0), max(bounds.width - 1, 0)),
            y: min(max(point.y, 0), max(bounds.height - 1, 0))
        )
    }

    private func updateCursorView() {
        cursorView.frame = CGRect(x: cursor.x, y: cursor.y, width: 16, height: 24)
    }

    private func send(_ action: MouseAction, button: Int) {
        guard bounds.width > 0, bounds.height > 0, cursor.x >= 0 else { return }
        onMouse?(
            action,
            CGPoint(x: cursor.x / bounds.width, y: cursor.y / bounds.height),
            button
        )
    }

    private func startTicking() {
        guard displayLink == nil else { return }
        lastTimestamp = 0
        let link = CADisplayLink(target: self, selector: #selector(step(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopTicking() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func step(_ link: CADisplayLink) {
        let seconds = lastTimestamp == 0 ? 1.0 / 60.0 : min(0.05, link.timestamp - lastTimestamp)
        lastTimestamp = link.timestamp
        let vector = joystick.vector
        let magnitude = hypot(vector.dx, vector.dy)
        guard magnitude > deadZone, bounds.width > 0 else { return }

        let normalized = (min(1, magnitude) - deadZone) / (1 - deadZone)
        // Quadratic response: small deflections give precise, slow movement.
        let speed = maximumSpeed * normalized * normalized
        let next = clamped(CGPoint(
            x: cursor.x + vector.dx / magnitude * speed * CGFloat(seconds),
            y: cursor.y + vector.dy / magnitude * speed * CGFloat(seconds)
        ))
        guard abs(next.x - cursor.x) >= 0.01 || abs(next.y - cursor.y) >= 0.01 else { return }
        cursor = next
        if leftHeld {
            leftMovedWhileHeld = true
        }
        updateCursorView()
        send(.move, button: 0)
    }

    private func leftDown() {
        if leftLocked {
            // Tap while drag-locked: release the button when this tap ends.
            leftLocked = false
            leftUnlockPending = true
            updateButtonStates()
            return
        }
        leftHeld = true
        leftLockArmed = false
        leftMovedWhileHeld = false
        send(.down, button: 0)
        perform(#selector(armDragLock), with: nil, afterDelay: dragLockDelay)
        updateButtonStates()
    }

    private func leftUp(cancelled: Bool) {
        cancelDragLockTimer()
        if leftUnlockPending {
            leftUnlockPending = false
            leftHeld = false
            send(.up, button: 0)
            updateButtonStates()
            return
        }
        guard leftHeld else { return }
        if !cancelled && leftLockArmed && !leftMovedWhileHeld {
            // Long press without movement: keep the button down for one-finger drags.
            leftLockArmed = false
            leftLocked = true
            updateButtonStates()
            return
        }
        leftHeld = false
        leftLockArmed = false
        send(.up, button: 0)
        updateButtonStates()
    }

    @objc private func armDragLock() {
        guard leftHeld, !leftMovedWhileHeld else { return }
        leftLockArmed = true
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        updateButtonStates()
    }

    private func cancelDragLockTimer() {
        NSObject.cancelPreviousPerformRequests(
            withTarget: self,
            selector: #selector(armDragLock),
            object: nil
        )
    }

    private func rightDown() {
        rightHeld = true
        send(.down, button: 2)
        updateButtonStates()
    }

    private func rightUp() {
        guard rightHeld else { return }
        rightHeld = false
        send(.up, button: 2)
        updateButtonStates()
    }

    private func updateButtonStates() {
        leftButton.setState(
            pressed: leftHeld || leftUnlockPending,
            locked: leftLocked || leftLockArmed
        )
        rightButton.setState(pressed: rightHeld, locked: false)
    }
}

private final class MouseCursorView: UIView {
    override class var layerClass: AnyClass { CAShapeLayer.self }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isAccessibilityElement = false
        let arrow = UIBezierPath()
        arrow.move(to: .zero)
        arrow.addLine(to: CGPoint(x: 0, y: 18))
        arrow.addLine(to: CGPoint(x: 4.5, y: 13.8))
        arrow.addLine(to: CGPoint(x: 7.6, y: 20.5))
        arrow.addLine(to: CGPoint(x: 10.6, y: 19.2))
        arrow.addLine(to: CGPoint(x: 7.5, y: 12.6))
        arrow.addLine(to: CGPoint(x: 13, y: 12.6))
        arrow.close()
        guard let shape = layer as? CAShapeLayer else { return }
        shape.path = arrow.cgPath
        shape.fillColor = UIColor.white.cgColor
        shape.strokeColor = UIColor.black.cgColor
        shape.lineWidth = 1.4
        shape.lineJoin = .round
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

private final class MouseJoystickView: UIView {
    var onActiveChanged: ((Bool) -> Void)?
    private(set) var vector = CGVector.zero

    private let baseLayer = CAShapeLayer()
    private let knobLayer = CAShapeLayer()
    private weak var activeTouch: UITouch?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isMultipleTouchEnabled = false
        isAccessibilityElement = true
        accessibilityLabel = "Mouse cursor joystick"
        baseLayer.fillColor = UIColor.black.withAlphaComponent(0.24).cgColor
        baseLayer.strokeColor = UIColor.white.withAlphaComponent(0.6).cgColor
        baseLayer.lineWidth = 2
        layer.addSublayer(baseLayer)
        layer.addSublayer(knobLayer)
        updateKnob()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func reset() {
        let wasActive = activeTouch != nil
        activeTouch = nil
        vector = .zero
        updateKnob()
        if wasActive {
            onActiveChanged?(false)
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let radius = min(bounds.width, bounds.height) / 2 - 2
        baseLayer.path = UIBezierPath(
            arcCenter: CGPoint(x: bounds.midX, y: bounds.midY),
            radius: max(radius, 0),
            startAngle: 0,
            endAngle: .pi * 2,
            clockwise: true
        ).cgPath
        updateKnob()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard activeTouch == nil, let touch = touches.first else { return }
        activeTouch = touch
        updateVector(with: touch)
        onActiveChanged?(true)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let activeTouch, touches.contains(activeTouch) else { return }
        updateVector(with: activeTouch)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let activeTouch, touches.contains(activeTouch) else { return }
        reset()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let activeTouch, touches.contains(activeTouch) else { return }
        reset()
    }

    private var travelRadius: CGFloat {
        max(1, min(bounds.width, bounds.height) / 2 * 0.62)
    }

    private func updateVector(with touch: UITouch) {
        let location = touch.location(in: self)
        var dx = (location.x - bounds.midX) / travelRadius
        var dy = (location.y - bounds.midY) / travelRadius
        let length = hypot(dx, dy)
        if length > 1 {
            dx /= length
            dy /= length
        }
        vector = CGVector(dx: dx, dy: dy)
        updateKnob()
    }

    private func updateKnob() {
        let knobRadius = (min(bounds.width, bounds.height) / 2 - 2) * 0.38
        let center = CGPoint(
            x: bounds.midX + vector.dx * travelRadius,
            y: bounds.midY + vector.dy * travelRadius
        )
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        knobLayer.path = UIBezierPath(
            arcCenter: center,
            radius: max(knobRadius, 0),
            startAngle: 0,
            endAngle: .pi * 2,
            clockwise: true
        ).cgPath
        knobLayer.fillColor = UIColor.white
            .withAlphaComponent(activeTouch == nil ? 0.47 : 0.78)
            .cgColor
        CATransaction.commit()
    }
}

private final class MouseButtonView: UIView {
    var onPress: (() -> Void)?
    var onRelease: ((_ cancelled: Bool) -> Void)?

    private let title: String
    private let label = UILabel()
    private weak var activeTouch: UITouch?

    init(title: String, accessibilityName: String) {
        self.title = title
        super.init(frame: .zero)
        isMultipleTouchEnabled = false
        isAccessibilityElement = true
        accessibilityLabel = accessibilityName
        accessibilityTraits = .button
        layer.borderWidth = 2
        label.text = title
        label.textColor = .white
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 18, weight: .bold)
        addSubview(label)
        setState(pressed: false, locked: false)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setState(pressed: Bool, locked: Bool) {
        let accent = UIColor.tintColor
        if locked {
            backgroundColor = accent.withAlphaComponent(0.86)
        } else if pressed {
            backgroundColor = accent.withAlphaComponent(0.67)
        } else {
            backgroundColor = UIColor.black.withAlphaComponent(0.31)
        }
        layer.borderColor = UIColor.white.withAlphaComponent(locked ? 1 : 0.6).cgColor
        label.text = locked ? "\(title)🔒" : title
        accessibilityValue = locked ? "Locked" : nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = min(bounds.width, bounds.height) / 2
        label.frame = bounds
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard activeTouch == nil, let touch = touches.first else { return }
        activeTouch = touch
        onPress?()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let activeTouch, touches.contains(activeTouch) else { return }
        self.activeTouch = nil
        onRelease?(false)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let activeTouch, touches.contains(activeTouch) else { return }
        self.activeTouch = nil
        onRelease?(true)
    }
}
