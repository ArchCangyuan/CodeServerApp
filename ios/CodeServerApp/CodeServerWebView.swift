import SwiftUI
import UIKit
import WebKit

private let desktopViewportWidth = 1280
private let minimumLayoutZoomSteps = -6
private let maximumLayoutZoomSteps = 12
private let layoutZoomFactor = 1.1
private let projectSessionTTL: TimeInterval = 30 * 60
private let maximumHotProjectSessions = 10
private let layoutZoomStepsKey = "layoutZoomSteps"

private let desktopUserAgent = """
Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 \
(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36
""".replacingOccurrences(of: "\n", with: "")

private let keyboardBridgeSource = #"""
(() => {
  const setViewportWidth = (requestedWidth) => {
    const numericWidth = Number(requestedWidth) || 1280;
    const width = Math.max(400, Math.min(2400, Math.round(numericWidth)));
    let viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) {
      viewport = document.createElement('meta');
      viewport.setAttribute('name', 'viewport');
      (document.head || document.documentElement).appendChild(viewport);
    }
    viewport.setAttribute(
      'content',
      `width=${width}, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes`
    );
    document.documentElement.style.zoom = '1';
    if (document.body) {
      document.body.style.zoom = '1';
      document.body.style.width = '';
      document.body.style.minWidth = '';
    }
    window.__codeServerAppViewportWidth = width;
    requestAnimationFrame(() => window.dispatchEvent(new Event('resize')));
    return width;
  };

  window.__codeServerAppSetViewportWidth = setViewportWidth;
  setViewportWidth(window.__codeServerAppViewportWidth || 1280);

  const findIronRdpCanvas = () => {
    const roots = [document];
    const visited = new Set();
    for (let index = 0; index < roots.length && index < 128; index += 1) {
      const root = roots[index];
      if (!root || visited.has(root) || !root.querySelectorAll) continue;
      visited.add(root);

      const directCanvas = root.querySelector('canvas#renderer');
      if (directCanvas) return directCanvas;

      const ironHosts = root.querySelectorAll(
        'iron-remote-desktop, iron-remote-gui'
      );
      for (const host of ironHosts) {
        const canvas = host.shadowRoot?.querySelector('canvas#renderer');
        if (canvas) return canvas;
      }

      for (const element of root.querySelectorAll('*')) {
        if (element.shadowRoot && !visited.has(element.shadowRoot)) {
          roots.push(element.shadowRoot);
        }
        if (element.tagName === 'IFRAME') {
          try {
            if (element.contentDocument) roots.push(element.contentDocument);
          } catch (_) {}
        }
      }
    }
    return null;
  };

  const existingBridge = window.__codeServerAppKeyboard;
  if (existingBridge && existingBridge.version >= 10) {
    window.__codeServerAppForceKeyboard = () => existingBridge.forceKeyboard();
    existingBridge.installRdpGestures?.();
    existingBridge.installDesktopGestures?.();
    return;
  }

  const state = {
    control: false,
    shift: false,
    target: null,
    ironRdpCanvas: null,
    gestureCanvas: null,
    desktopGestureInstalled: false
  };

  const pointFromTouch = (touch) => ({
    clientX: touch.clientX,
    clientY: touch.clientY,
    screenX: touch.screenX,
    screenY: touch.screenY
  });

  const dispatchMouse = (canvas, type, point, button, buttons) => {
    const eventWindow = canvas.ownerDocument?.defaultView || window;
    canvas.dispatchEvent(new eventWindow.MouseEvent(type, {
      bubbles: true,
      cancelable: true,
      composed: true,
      view: eventWindow,
      clientX: point.clientX,
      clientY: point.clientY,
      screenX: point.screenX,
      screenY: point.screenY,
      button,
      buttons
    }));
  };

  const installRdpGestures = () => {
    const canvas = findIronRdpCanvas();
    if (!canvas) return false;
    state.ironRdpCanvas = canvas;
    if (state.gestureCanvas === canvas) return true;
    state.gestureCanvas = canvas;

    // Preserve WebView panning and IronRDP's native coordinate mapping.
    canvas.style.touchAction = '';
    canvas.style.webkitTouchCallout = 'none';

    let gesture = null;

    const releaseGesture = () => {
      if (gesture?.timer) window.clearTimeout(gesture.timer);
    };

    const sendButton = (point, button) => {
      const downButtons = button === 2 ? 2 : 1;
      dispatchMouse(canvas, 'mousemove', point, 0, 0);
      dispatchMouse(canvas, 'mousedown', point, button, downButtons);
      dispatchMouse(canvas, 'mouseup', point, button, 0);
    };

    canvas.addEventListener('contextmenu', (event) => {
      if (!gesture) return;
      event.preventDefault();
      event.stopImmediatePropagation();
    }, true);

    canvas.addEventListener('touchstart', (event) => {
      if (event.touches.length !== 1) return;
      const start = pointFromTouch(event.touches[0]);
      gesture = {
        start,
        last: start,
        armed: false,
        dragging: false,
        cancelled: false,
        timer: window.setTimeout(() => {
          if (gesture && !gesture.cancelled) gesture.armed = true;
        }, 550)
      };
    }, { capture: true, passive: true });

    canvas.addEventListener('touchmove', (event) => {
      if (!gesture || event.touches.length !== 1) return;
      const point = pointFromTouch(event.touches[0]);
      gesture.last = point;
      const distance = Math.hypot(
        point.clientX - gesture.start.clientX,
        point.clientY - gesture.start.clientY
      );

      if (!gesture.armed) {
        if (distance >= 7) {
          releaseGesture();
          gesture.cancelled = true;
        }
        return;
      }

      if (!gesture.dragging && distance < 7) return;

      event.preventDefault();
      event.stopImmediatePropagation();
      if (!gesture.dragging) {
        gesture.dragging = true;
        dispatchMouse(canvas, 'mousemove', gesture.start, 0, 0);
        dispatchMouse(canvas, 'mousedown', gesture.start, 0, 1);
      }
      dispatchMouse(canvas, 'mousemove', point, 0, 1);
    }, { capture: true, passive: false });

    const finishGesture = (event, cancelled) => {
      if (!gesture) return;
      releaseGesture();
      const touch = event.changedTouches?.[0];
      const point = touch ? pointFromTouch(touch) : gesture.last;

      if (gesture.dragging) {
        event.preventDefault();
        event.stopImmediatePropagation();
        dispatchMouse(canvas, 'mousemove', point, 0, 1);
        dispatchMouse(canvas, 'mouseup', point, 0, 0);
      } else if (gesture.armed && !gesture.cancelled) {
        event.preventDefault();
        event.stopImmediatePropagation();
        sendButton(point, 2);
      } else if (cancelled) {
        gesture.cancelled = true;
      }
      gesture = null;
    };

    canvas.addEventListener('touchend', (event) => {
      finishGesture(event, false);
    }, { capture: true, passive: false });
    canvas.addEventListener('touchcancel', (event) => {
      finishGesture(event, true);
    }, { capture: true, passive: false });
    return true;
  };

  const installDesktopGestures = () => {
    if (state.desktopGestureInstalled) return true;
    state.desktopGestureInstalled = true;

    let gesture = null;

    const eventPath = (event) => typeof event.composedPath === 'function'
      ? event.composedPath()
      : [event.target];
    const isIronRdpEvent = (event) => {
      const canvas = state.ironRdpCanvas || findIronRdpCanvas();
      return Boolean(canvas && eventPath(event).includes(canvas));
    };
    const isNativeTextTarget = (target) => {
      if (!target || target.nodeType !== 1) return false;
      const tagName = target.tagName;
      return tagName === 'INPUT'
        || tagName === 'TEXTAREA'
        || tagName === 'SELECT'
        || Boolean(target.isContentEditable);
    };
    const targetAt = (point, fallback) => {
      try {
        return document.elementFromPoint(point.clientX, point.clientY)
          || fallback;
      } catch (_) {
        return fallback;
      }
    };
    const clearTimer = (activeGesture) => {
      if (activeGesture?.timer) window.clearTimeout(activeGesture.timer);
    };
    const sendContextMenu = (target, point) => {
      if (!target?.dispatchEvent) return;
      dispatchMouse(target, 'mousemove', point, 0, 0);
      dispatchMouse(target, 'mousedown', point, 2, 2);
      dispatchMouse(target, 'mouseup', point, 2, 0);
      dispatchMouse(target, 'contextmenu', point, 2, 0);
    };

    document.addEventListener('contextmenu', (event) => {
      if (!event.isTrusted || !gesture || isIronRdpEvent(event)) return;
      event.preventDefault();
      event.stopImmediatePropagation();
    }, true);

    document.addEventListener('touchstart', (event) => {
      if (event.touches.length !== 1 || isIronRdpEvent(event)) return;
      const path = eventPath(event);
      const startTarget = path.find((target) => target?.dispatchEvent)
        || event.target;
      if (!startTarget || isNativeTextTarget(startTarget)) return;
      if (startTarget.style) startTarget.style.webkitTouchCallout = 'none';
      const start = pointFromTouch(event.touches[0]);
      gesture = {
        start,
        last: start,
        startTarget,
        armed: false,
        dragging: false,
        cancelled: false,
        timer: window.setTimeout(() => {
          if (gesture && !gesture.cancelled) gesture.armed = true;
        }, 550)
      };
    }, { capture: true, passive: true });

    document.addEventListener('touchmove', (event) => {
      if (!gesture || event.touches.length !== 1) return;
      const point = pointFromTouch(event.touches[0]);
      gesture.last = point;
      const distance = Math.hypot(
        point.clientX - gesture.start.clientX,
        point.clientY - gesture.start.clientY
      );

      if (!gesture.armed) {
        if (distance >= 7) {
          clearTimer(gesture);
          gesture.cancelled = true;
        }
        return;
      }
      if (!gesture.dragging && distance < 7) return;

      event.preventDefault();
      event.stopImmediatePropagation();
      if (!gesture.dragging) {
        gesture.dragging = true;
        dispatchMouse(gesture.startTarget, 'mousemove', gesture.start, 0, 0);
        dispatchMouse(gesture.startTarget, 'mousedown', gesture.start, 0, 1);
      }
      const moveTarget = targetAt(point, gesture.startTarget);
      dispatchMouse(moveTarget, 'mousemove', point, 0, 1);
    }, { capture: true, passive: false });

    const finishGesture = (event, cancelled) => {
      if (!gesture) return;
      const activeGesture = gesture;
      gesture = null;
      clearTimer(activeGesture);
      const touch = event.changedTouches?.[0];
      const point = touch ? pointFromTouch(touch) : activeGesture.last;
      const endTarget = targetAt(point, activeGesture.startTarget);

      if (activeGesture.dragging) {
        event.preventDefault();
        event.stopImmediatePropagation();
        dispatchMouse(endTarget, 'mousemove', point, 0, 1);
        dispatchMouse(endTarget, 'mouseup', point, 0, 0);
      } else if (activeGesture.armed
          && !activeGesture.cancelled
          && !cancelled) {
        event.preventDefault();
        event.stopImmediatePropagation();
        sendContextMenu(endTarget, point);
      }
    };

    document.addEventListener('touchend', (event) => {
      finishGesture(event, false);
    }, { capture: true, passive: false });
    document.addEventListener('touchcancel', (event) => {
      finishGesture(event, true);
    }, { capture: true, passive: false });
    return true;
  };

  installRdpGestures();
  installDesktopGestures();
  window.setInterval(installRdpGestures, 1000);
  const deepestActiveElement = (rootDocument) => {
    let active = rootDocument.activeElement;
    for (let depth = 0; active && depth < 6; depth += 1) {
      if (active.tagName !== 'IFRAME') break;
      try {
        const childDocument = active.contentDocument;
        if (!childDocument || !childDocument.activeElement) break;
        active = childDocument.activeElement;
      } catch (_) {
        break;
      }
    }
    return active;
  };

  const rememberTarget = (candidate) => {
    if (!candidate) return;
    const ownerDocument = candidate.ownerDocument;
    const isGeneric = candidate.tagName === 'HTML'
      || Boolean(ownerDocument && candidate === ownerDocument.body);
    if (!isGeneric || !state.target) state.target = candidate;
  };

  const activeTarget = () => {
    if (state.ironRdpCanvas && state.ironRdpCanvas.isConnected) {
      return state.ironRdpCanvas;
    }
    const current = deepestActiveElement(document);
    if (current) {
      rememberTarget(current);
      const ownerDocument = current.ownerDocument;
      const isGeneric = current.tagName === 'HTML'
        || Boolean(ownerDocument && current === ownerDocument.body);
      if (!isGeneric) return current;
    }
    if (state.target && state.target.isConnected) return state.target;
    const fallback = document.querySelector(
      'canvas, [role="application"], [tabindex="0"]'
    ) || document.body || document.documentElement;
    rememberTarget(fallback);
    return fallback;
  };

  const defineLegacyKeyCodes = (event, keyCode, charCode = 0) => {
    try {
      Object.defineProperty(event, 'keyCode', { get: () => keyCode });
      Object.defineProperty(event, 'which', {
        get: () => charCode || keyCode
      });
      Object.defineProperty(event, 'charCode', { get: () => charCode });
    } catch (_) {}
  };

  const dispatchKeyPhase = (target, type, key, code, keyCode, source = null) => {
    if (!target) return false;
    const printable = typeof key === 'string' && Array.from(key).length === 1;
    const event = new KeyboardEvent(type, {
      key,
      code: code || '',
      location: source ? source.location : 0,
      repeat: source ? source.repeat : false,
      ctrlKey: state.control || Boolean(source && source.ctrlKey),
      shiftKey: state.shift || Boolean(source && source.shiftKey),
      altKey: Boolean(source && source.altKey),
      metaKey: Boolean(source && source.metaKey),
      bubbles: true,
      cancelable: true,
      composed: true
    });
    const charCode = type === 'keypress' && printable ? key.codePointAt(0) : 0;
    defineLegacyKeyCodes(event, keyCode || 0, charCode);
    return target.dispatchEvent(event);
  };

  const keyInfoForText = (key) => {
    if (/^[a-z]$/i.test(key)) {
      const upper = key.toUpperCase();
      return {
        code: `Key${upper}`,
        keyCode: upper.charCodeAt(0),
        shift: key === upper
      };
    }
    if (/^[0-9]$/.test(key)) {
      return { code: `Digit${key}`, keyCode: key.charCodeAt(0), shift: false };
    }
    if (key === ' ') return { code: 'Space', keyCode: 32, shift: false };
    const punctuation = {
      '`': ['Backquote', 192, false], '~': ['Backquote', 192, true],
      '-': ['Minus', 189, false], '_': ['Minus', 189, true],
      '=': ['Equal', 187, false], '+': ['Equal', 187, true],
      '[': ['BracketLeft', 219, false], '{': ['BracketLeft', 219, true],
      ']': ['BracketRight', 221, false], '}': ['BracketRight', 221, true],
      '\\': ['Backslash', 220, false], '|': ['Backslash', 220, true],
      ';': ['Semicolon', 186, false], ':': ['Semicolon', 186, true],
      "'": ['Quote', 222, false], '"': ['Quote', 222, true],
      ',': ['Comma', 188, false], '<': ['Comma', 188, true],
      '.': ['Period', 190, false], '>': ['Period', 190, true],
      '/': ['Slash', 191, false], '?': ['Slash', 191, true],
      '!': ['Digit1', 49, true], '@': ['Digit2', 50, true],
      '#': ['Digit3', 51, true], '$': ['Digit4', 52, true],
      '%': ['Digit5', 53, true], '^': ['Digit6', 54, true],
      '&': ['Digit7', 55, true], '*': ['Digit8', 56, true],
      '(': ['Digit9', 57, true], ')': ['Digit0', 48, true]
    };
    const mapped = punctuation[key];
    if (mapped) {
      return { code: mapped[0], keyCode: mapped[1], shift: mapped[2] };
    }
    return { code: '', keyCode: key.codePointAt(0) || 0, shift: false };
  };

  const dispatchCompleteKey = (key, code, keyCode, forceShift = false) => {
    const target = activeTarget();
    if (!target) return false;
    const needsSyntheticShift = forceShift && !state.shift;
    const source = forceShift ? { shiftKey: true } : null;
    if (needsSyntheticShift) {
      dispatchKeyPhase(
        target,
        'keydown',
        'Shift',
        'ShiftLeft',
        16,
        { shiftKey: true, location: 1 }
      );
    }
    dispatchKeyPhase(target, 'keydown', key, code, keyCode, source);
    if (Array.from(key).length === 1 && !state.control) {
      dispatchKeyPhase(target, 'keypress', key, code, keyCode, source);
    }
    dispatchKeyPhase(target, 'keyup', key, code, keyCode, source);
    if (needsSyntheticShift) {
      dispatchKeyPhase(target, 'keyup', 'Shift', 'ShiftLeft', 16);
    }
    return true;
  };

  const dispatchShortcut = (
    key,
    code,
    keyCode,
    useControl = false,
    useShift = false
  ) => {
    const target = activeTarget();
    if (!target) return false;
    const needsControl = useControl && !state.control;
    const needsShift = useShift && !state.shift;
    if (needsControl) {
      dispatchKeyPhase(
        target,
        'keydown',
        'Control',
        'ControlLeft',
        17,
        { ctrlKey: true, location: 1 }
      );
    }
    if (needsShift) {
      dispatchKeyPhase(
        target,
        'keydown',
        'Shift',
        'ShiftLeft',
        16,
        { ctrlKey: useControl, shiftKey: true, location: 1 }
      );
    }
    const source = {
      ctrlKey: state.control || useControl,
      shiftKey: state.shift || useShift
    };
    dispatchKeyPhase(target, 'keydown', key, code, keyCode, source);
    dispatchKeyPhase(target, 'keyup', key, code, keyCode, source);
    if (needsShift) {
      dispatchKeyPhase(
        target,
        'keyup',
        'Shift',
        'ShiftLeft',
        16,
        { ctrlKey: state.control || useControl }
      );
    }
    if (needsControl) {
      dispatchKeyPhase(target, 'keyup', 'Control', 'ControlLeft', 17);
    }
    return true;
  };

  const forwardText = (text) => {
    for (const key of Array.from(text || '')) {
      const info = keyInfoForText(key);
      dispatchCompleteKey(key, info.code, info.keyCode, info.shift);
    }
    return true;
  };

  const dispatchModifier = (key, code, keyCode, isDown) => {
    dispatchKeyPhase(
      activeTarget(),
      isDown ? 'keydown' : 'keyup',
      key,
      code,
      keyCode
    );
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
    dispatchKeyPhase(
      target,
      event.type,
      shiftedKey,
      event.code,
      event.keyCode || event.which || 0,
      event
    );
  };

  document.addEventListener('pointerdown', (event) => {
    const path = typeof event.composedPath === 'function'
      ? event.composedPath()
      : [];
    rememberTarget(path[0] || event.target);
  }, true);
  document.addEventListener('focusin', (event) => rememberTarget(event.target), true);
  document.addEventListener('keydown', redispatchWithLockedModifiers, true);
  document.addEventListener('keyup', redispatchWithLockedModifiers, true);

  // Real mice use pointerId 1 in Chromium, which would let pages capture a
  // pointer that never delivers the synthetic moves. Use a private id and
  // emulate setPointerCapture for it instead.
  const SYNTHETIC_POINTER_ID = 7331;

  const mouse = {
    buttons: 0,
    captureTarget: null,
    lastFx: 0.5,
    lastFy: 0.5,
    lastHit: null,
    hoverPath: [],
    downTargets: {},
    clickCount: 0,
    lastDownAt: 0,
    lastDownX: 0,
    lastDownY: 0
  };

  const installPointerCapture = (eventWindow) => {
    const prototype = eventWindow?.Element?.prototype;
    if (!prototype || prototype.__codeServerAppPointerCapture) return;
    const nativeSet = prototype.setPointerCapture;
    const nativeRelease = prototype.releasePointerCapture;
    const nativeHas = prototype.hasPointerCapture;
    prototype.setPointerCapture = function (pointerId) {
      if (pointerId !== SYNTHETIC_POINTER_ID) return nativeSet.call(this, pointerId);
      mouse.captureTarget = this;
    };
    prototype.releasePointerCapture = function (pointerId) {
      if (pointerId !== SYNTHETIC_POINTER_ID) return nativeRelease.call(this, pointerId);
      if (mouse.captureTarget === this) mouse.captureTarget = null;
    };
    prototype.hasPointerCapture = function (pointerId) {
      if (pointerId !== SYNTHETIC_POINTER_ID) return nativeHas.call(this, pointerId);
      return mouse.captureTarget === this;
    };
    Object.defineProperty(prototype, '__codeServerAppPointerCapture', { value: true });
  };
  installPointerCapture(window);

  const mouseButtonMask = (button) => {
    if (button === 2) return 2;
    if (button === 1) return 4;
    return 1;
  };

  const viewportPoint = (fx, fy) => {
    const viewport = window.visualViewport;
    const width = viewport ? viewport.width : window.innerWidth;
    const height = viewport ? viewport.height : window.innerHeight;
    const left = viewport ? viewport.offsetLeft : 0;
    const top = viewport ? viewport.offsetTop : 0;
    const clamp = (value) => Math.max(0, Math.min(1, Number(value) || 0));
    return {
      x: left + Math.min(width - 1, clamp(fx) * width),
      y: top + Math.min(height - 1, clamp(fy) * height)
    };
  };

  // Resolve the deepest element under the cursor, descending into open
  // shadow roots (IronRDP) and same-origin iframes.
  const mouseHitTest = (x, y) => {
    let root = document;
    let localX = x;
    let localY = y;
    let hit = null;
    for (let depth = 0; depth < 8; depth += 1) {
      let element = root.elementFromPoint(localX, localY);
      for (let level = 0; element?.shadowRoot && level < 16; level += 1) {
        const inner = element.shadowRoot.elementFromPoint(localX, localY);
        if (!inner || inner === element) break;
        element = inner;
      }
      if (!element) break;
      hit = {
        element,
        clientX: localX,
        clientY: localY,
        view: element.ownerDocument?.defaultView || window
      };
      if (element.tagName !== 'IFRAME') break;
      let childDocument = null;
      try {
        childDocument = element.contentDocument;
      } catch (_) {}
      if (!childDocument) break;
      const rect = element.getBoundingClientRect();
      localX -= rect.left + element.clientLeft;
      localY -= rect.top + element.clientTop;
      root = childDocument;
    }
    return hit;
  };

  const composedAncestors = (element) => {
    const chain = [];
    for (let node = element; node && chain.length < 128;) {
      if (node.nodeType === 1) chain.push(node);
      node = node.parentNode || node.host || null;
    }
    return chain;
  };

  const mouseEventInit = (hit, button, buttons, detail, bubbles) => ({
    bubbles,
    cancelable: bubbles,
    composed: true,
    view: hit.view,
    detail,
    clientX: hit.clientX,
    clientY: hit.clientY,
    screenX: hit.clientX,
    screenY: hit.clientY,
    button,
    buttons,
    ctrlKey: state.control,
    shiftKey: state.shift,
    altKey: false,
    metaKey: false
  });

  const fireMouse = (hit, element, type, button, buttons, detail = 0, bubbles = true) => {
    const eventWindow = element.ownerDocument?.defaultView || hit.view;
    return element.dispatchEvent(new eventWindow.MouseEvent(
      type,
      mouseEventInit(hit, button, buttons, detail, bubbles)
    ));
  };

  const firePointer = (hit, element, type, button, buttons, bubbles = true) => {
    const eventWindow = element.ownerDocument?.defaultView || hit.view;
    if (typeof eventWindow.PointerEvent !== 'function') return true;
    return element.dispatchEvent(new eventWindow.PointerEvent(type, {
      ...mouseEventInit(hit, button, buttons, 0, bubbles),
      pointerId: SYNTHETIC_POINTER_ID,
      pointerType: 'mouse',
      isPrimary: true,
      width: 1,
      height: 1,
      pressure: buttons ? 0.5 : 0
    }));
  };

  const updateMouseHover = (hit) => {
    const previousPath = mouse.hoverPath;
    const nextPath = composedAncestors(hit.element);
    const previous = previousPath[0] || null;
    const next = nextPath[0] || null;
    if (previous === next) return;
    const leaveHit = mouse.lastHit || hit;
    if (previous && previous.isConnected) {
      firePointer(leaveHit, previous, 'pointerout', 0, mouse.buttons);
      fireMouse(leaveHit, previous, 'mouseout', 0, mouse.buttons);
      for (const element of previousPath) {
        if (nextPath.includes(element)) break;
        firePointer(leaveHit, element, 'pointerleave', 0, mouse.buttons, false);
        fireMouse(leaveHit, element, 'mouseleave', 0, mouse.buttons, 0, false);
      }
    }
    if (next) {
      firePointer(hit, next, 'pointerover', 0, mouse.buttons);
      fireMouse(hit, next, 'mouseover', 0, mouse.buttons);
      const entering = [];
      for (const element of nextPath) {
        if (previousPath.includes(element)) break;
        entering.push(element);
      }
      for (const element of entering.reverse()) {
        firePointer(hit, element, 'pointerenter', 0, mouse.buttons, false);
        fireMouse(hit, element, 'mouseenter', 0, mouse.buttons, 0, false);
      }
    }
    mouse.hoverPath = nextPath;
  };

  const focusFromMouse = (target) => {
    const selector = 'input, textarea, select, button, a[href], [tabindex], '
      + '[contenteditable=""], [contenteditable="true"]';
    for (const element of composedAncestors(target)) {
      if (!element.matches?.(selector) || element.disabled) continue;
      try {
        element.focus({ preventScroll: true });
      } catch (_) {}
      return;
    }
  };

  const commonMouseTarget = (first, second) => {
    if (!first || !second || !first.isConnected) return null;
    for (const element of composedAncestors(first)) {
      if (element === second || element.contains(second)) return element;
    }
    return null;
  };

  const mouseAction = (action, fx, fy, button) => {
    if (action === 'release') {
      for (const held of [0, 2, 1]) {
        if (mouse.buttons & mouseButtonMask(held)) {
          mouseAction('up', mouse.lastFx, mouse.lastFy, held);
        }
      }
      return true;
    }

    mouse.lastFx = fx;
    mouse.lastFy = fy;
    const point = viewportPoint(fx, fy);
    const hit = mouseHitTest(point.x, point.y)
      || (mouse.lastHit?.element?.isConnected ? mouse.lastHit : null);
    if (!hit) {
      if (action === 'up') mouse.buttons &= ~mouseButtonMask(button);
      return false;
    }
    installPointerCapture(hit.view);
    const captured = mouse.captureTarget?.isConnected ? mouse.captureTarget : null;
    if (!captured) updateMouseHover(hit);
    mouse.lastHit = hit;
    const target = captured || hit.element;

    if (action === 'move') {
      firePointer(hit, target, 'pointermove', -1, mouse.buttons);
      fireMouse(hit, target, 'mousemove', 0, mouse.buttons);
      return true;
    }

    if (action === 'down') {
      if (button === 0) {
        const now = performance.now();
        const nearPrevious = Math.hypot(
          point.x - mouse.lastDownX,
          point.y - mouse.lastDownY
        ) < 8;
        mouse.clickCount = nearPrevious && now - mouse.lastDownAt < 500
          ? Math.min(mouse.clickCount + 1, 3)
          : 1;
        mouse.lastDownAt = now;
        mouse.lastDownX = point.x;
        mouse.lastDownY = point.y;
      }
      mouse.captureTarget = null;
      mouse.buttons |= mouseButtonMask(button);
      mouse.downTargets[button] = target;
      rememberTarget(target);
      const detail = button === 0 ? mouse.clickCount : 1;
      const pointerAllowed = firePointer(hit, target, 'pointerdown', button, mouse.buttons);
      const mouseAllowed = fireMouse(
        hit,
        target,
        'mousedown',
        button,
        mouse.buttons,
        detail
      );
      if (button === 0 && pointerAllowed && mouseAllowed) focusFromMouse(target);
      return true;
    }

    if (action === 'up') {
      if (!(mouse.buttons & mouseButtonMask(button))) return false;
      mouse.buttons &= ~mouseButtonMask(button);
      const detail = button === 0 ? mouse.clickCount : 1;
      firePointer(hit, target, 'pointerup', button, mouse.buttons);
      fireMouse(hit, target, 'mouseup', button, mouse.buttons, detail);
      if (!mouse.buttons) mouse.captureTarget = null;
      const downTarget = mouse.downTargets[button];
      delete mouse.downTargets[button];
      if (button === 0) {
        const clickTarget = commonMouseTarget(downTarget, target);
        if (clickTarget) {
          fireMouse(hit, clickTarget, 'click', 0, mouse.buttons, detail);
          if (detail === 2) {
            fireMouse(hit, clickTarget, 'dblclick', 0, mouse.buttons, 2);
          }
        }
      } else if (button === 2) {
        fireMouse(hit, target, 'contextmenu', 2, mouse.buttons, 1);
      }
      return true;
    }
    return false;
  };

  const bridge = {
    version: 10,
    forceKeyboard() {
      installRdpGestures();
      const canvas = findIronRdpCanvas();
      if (canvas) {
        state.ironRdpCanvas = canvas;
        rememberTarget(canvas);
        canvas.focus({ preventScroll: true });
        return 'ironrdp';
      }
      rememberTarget(deepestActiveElement(document));
      return activeTarget() ? 'generic' : 'missing';
    },
    installRdpGestures,
    installDesktopGestures,
    sendKey(key, code, keyCode) {
      return dispatchCompleteKey(key, code, keyCode);
    },
    sendText(text) {
      return forwardText(String(text || ''));
    },
    sendShortcut(key, code, keyCode, control, shift) {
      return dispatchShortcut(key, code, keyCode, control, shift);
    },
    mouse(action, fx, fy, button) {
      return mouseAction(String(action), Number(fx), Number(fy), Number(button) || 0);
    },
    setModifiers(control, shift) {
      const nextControl = Boolean(control);
      const nextShift = Boolean(shift);
      const controlChanged = state.control !== nextControl;
      const shiftChanged = state.shift !== nextShift;
      state.control = nextControl;
      state.shift = nextShift;
      if (controlChanged) dispatchModifier('Control', 'ControlLeft', 17, nextControl);
      if (shiftChanged) dispatchModifier('Shift', 'ShiftLeft', 16, nextShift);
    }
  };

  window.__codeServerAppKeyboard = bridge;
  window.__codeServerAppForceKeyboard = () => bridge.forceKeyboard();
})();
"""#

@MainActor
final class CodeServerWebViewStore: NSObject, ObservableObject, WKNavigationDelegate {
    @Published private(set) var zoomPercent: Int
    @Published private(set) var statusMessage: String?
    @Published private(set) var currentPageAddress = ""

    private final class ProjectSession {
        let key: String
        let webView: WKWebView
        var lastInactiveAt: TimeInterval?
        var lastFinishedURL: String?
        var appliedZoomSteps: Int?
        var zoomReloadInProgress = false
        var urlObservation: NSKeyValueObservation?

        init(key: String, webView: WKWebView) {
            self.key = key
            self.webView = webView
        }
    }

    private var sessions: [String: ProjectSession] = [:]
    private weak var hostView: WebViewSessionContainerView?
    private var requestedAddress = ""
    private var activeSessionKey: String?
    private var keepAliveEnabled = false
    private var controlLocked = false
    private var shiftLocked = false
    private var layoutZoomSteps: Int
    private var statusToken = UUID()

    override init() {
        let savedSteps = UserDefaults.standard.integer(forKey: layoutZoomStepsKey)
        layoutZoomSteps = min(max(savedSteps, minimumLayoutZoomSteps), maximumLayoutZoomSteps)
        zoomPercent = Int(round(pow(layoutZoomFactor, Double(layoutZoomSteps)) * 100))
        super.init()
    }

    static func normalizedAddress(_ address: String) -> String {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        if let scheme = URLComponents(string: trimmed)?.scheme?.lowercased(),
           scheme == "http" || scheme == "https" {
            return trimmed
        }
        return "http://\(trimmed)"
    }

    func attach(to view: WebViewSessionContainerView) {
        hostView = view
        view.onCommittedText = { [weak self] text in
            self?.sendText(text)
        }
        view.onDeleteBackward = { [weak self] in
            self?.send(.backspace)
        }
        view.onEnter = { [weak self] in
            self?.send(.enter)
        }
        view.onMouse = { [weak self] action, point, button in
            self?.sendMouse(action, at: point, button: button)
        }
        for session in sessions.values {
            view.install(session.webView)
        }
        if !requestedAddress.isEmpty {
            activate(address: requestedAddress)
        }
    }

    func activate(address: String, restoringSavedAddress: Bool = false) {
        let normalized = Self.normalizedAddress(address)
        guard !normalized.isEmpty else { return }
        requestedAddress = normalized
        guard let hostView else { return }

        let now = Date.timeIntervalSinceReferenceDate
        cleanupExpiredSessions(now: now)

        let target: ProjectSession
        let created: Bool
        if let existing = session(matching: normalized) {
            target = existing
            created = false
        } else {
            let webView = makeWebView()
            target = ProjectSession(key: normalized, webView: webView)
            sessions[normalized] = target
            observeURLChanges(for: target)
            hostView.install(webView)
            created = true
        }

        if let activeSessionKey,
           activeSessionKey != target.key,
           let current = sessions[activeSessionKey] {
            // Release held mouse buttons on the page that is being left.
            hostView.releaseMouseButtons()
            current.lastInactiveAt = now
            // Keep the inactive WKWebView visible behind the active one so WebKit
            // does not suspend its RDP/WebSocket session. It cannot receive input.
            current.webView.isHidden = false
            current.webView.isUserInteractionEnabled = false
        }

        let currentAddress = target.webView.url?.absoluteString ?? ""
        let restoreSavedAddress = restoringSavedAddress
            && !created
            && !Self.addressesEquivalent(currentAddress, normalized)

        activeSessionKey = target.key
        target.lastInactiveAt = nil
        target.webView.isHidden = false
        target.webView.isUserInteractionEnabled = true
        hostView.bringWebViewToFront(target.webView)

        if (created || restoreSavedAddress), let url = URL(string: normalized) {
            if currentPageAddress != normalized {
                currentPageAddress = normalized
            }
            target.webView.load(URLRequest(url: url))
        } else {
            publishAddress(for: target, fallback: normalized)
            if target.appliedZoomSteps != layoutZoomSteps {
                applyLayoutZoom(to: target, reloadAfterApply: target.webView.url != nil)
            }
        }
        syncModifiers(on: target.webView)
        evictExcessSessions()
    }

    func reload() {
        activeSession?.webView.reload()
    }

    func changeZoom(by direction: Int) {
        guard direction != 0 else { return }
        let nextSteps = min(
            max(layoutZoomSteps + direction, minimumLayoutZoomSteps),
            maximumLayoutZoomSteps
        )
        guard nextSteps != layoutZoomSteps else { return }

        layoutZoomSteps = nextSteps
        UserDefaults.standard.set(layoutZoomSteps, forKey: layoutZoomStepsKey)
        zoomPercent = Int(round(pow(layoutZoomFactor, Double(layoutZoomSteps)) * 100))
        if let activeSession {
            applyLayoutZoom(to: activeSession, reloadAfterApply: activeSession.webView.url != nil)
        }
        showStatus("UI zoom \(zoomPercent)% – reloading to fit")
    }

    func isSessionHot(_ address: String) -> Bool {
        let normalized = Self.normalizedAddress(address)
        let now = Date.timeIntervalSinceReferenceDate
        cleanupExpiredSessions(now: now)
        guard let session = session(matching: normalized) else { return false }
        if session.key == activeSessionKey { return true }
        guard let inactiveAt = session.lastInactiveAt else { return false }
        if keepAliveEnabled { return true }
        return now - inactiveAt < projectSessionTTL
    }

    func setKeepAliveEnabled(_ enabled: Bool) {
        keepAliveEnabled = enabled
        if !enabled {
            cleanupExpiredSessions(now: Date.timeIntervalSinceReferenceDate)
        }
    }

    func setModifiers(control: Bool, shift: Bool) {
        controlLocked = control
        shiftLocked = shift
        if let webView = activeSession?.webView {
            syncModifiers(on: webView)
        }
        if control || shift {
            hostView?.activateKeyboardCapture()
        }
    }

    func send(_ key: CodeServerKey) {
        guard let webView = activeSession?.webView else { return }
        let stroke = key.stroke
        let script = """
        window.__codeServerAppKeyboard?.sendKey(
          \(Self.javaScriptString(stroke.key)),
          \(Self.javaScriptString(stroke.code)),
          \(stroke.keyCode)
        ) ?? false;
        """
        webView.evaluateJavaScript(script)
    }

    func sendText(_ text: String) {
        guard !text.isEmpty, let webView = activeSession?.webView else { return }
        let script = """
        window.__codeServerAppKeyboard?.sendText(
          \(Self.javaScriptString(text))
        ) ?? false;
        """
        webView.evaluateJavaScript(script)
    }

    func sendControlC() {
        guard let webView = activeSession?.webView else { return }
        webView.evaluateJavaScript(
            "window.__codeServerAppKeyboard?.sendShortcut('c', 'KeyC', 67, true, false) ?? false;"
        )
    }

    func sendMouse(_ action: MouseAction, at point: CGPoint, button: Int) {
        guard let webView = activeSession?.webView else { return }
        let x = min(max(Double(point.x), 0), 1)
        let y = min(max(Double(point.y), 0), 1)
        webView.evaluateJavaScript(
            "window.__codeServerAppKeyboard?.mouse?.('\(action.rawValue)', \(x), \(y), \(button)) ?? false;"
        )
    }

    func announceMouseMode(_ enabled: Bool) {
        showStatus(
            enabled
                ? "Mouse mode: joystick moves, L/R click, hold L to lock drag"
                : "Mouse mode off"
        )
    }

    func forceKeyboard() {
        guard let session = activeSession else { return }
        session.webView.evaluateJavaScript(
            "window.__codeServerAppForceKeyboard ? window.__codeServerAppForceKeyboard() : false"
        ) { [weak self] value, _ in
            Task { @MainActor [weak self] in
                guard let self,
                      self.activeSessionKey == session.key else { return }
                self.hostView?.activateKeyboardCapture()
                if let mode = value as? String, mode == "ironrdp" {
                    self.showStatus("IronRDP focused – IME connected")
                } else {
                    self.showStatus("Keyboard connected")
                }
            }
        }
    }

    private var activeSession: ProjectSession? {
        guard let activeSessionKey else { return nil }
        return sessions[activeSessionKey]
    }

    private func session(matching normalizedAddress: String) -> ProjectSession? {
        sessions[normalizedAddress]
    }

    private func observeURLChanges(for session: ProjectSession) {
        session.urlObservation = session.webView.observe(\.url, options: [.initial, .new]) {
            [weak self, weak session] webView, _ in
            let address = webView.url?.absoluteString ?? ""
            Task { @MainActor [weak self, weak session] in
                guard let self,
                      let session,
                      self.sessions[session.key] === session else { return }
                self.publishAddress(for: session, fallback: address)
            }
        }
    }

    private func publishAddress(for session: ProjectSession, fallback: String = "") {
        guard session.key == activeSessionKey else { return }
        let address = session.webView.url?.absoluteString ?? fallback
        let normalized = Self.normalizedAddress(address)
        guard normalized.hasPrefix("http://") || normalized.hasPrefix("https://") else {
            return
        }
        if currentPageAddress != normalized {
            currentPageAddress = normalized
        }
    }

    private func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.defaultWebpagePreferences.preferredContentMode = .desktop
        configuration.userContentController.addUserScript(
            WKUserScript(
                source: keyboardBridgeSource,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            )
        )

        let webView = WKWebView(frame: .zero, configuration: configuration)
        disableDoubleTapZoom(in: webView)
        DispatchQueue.main.async { [weak self, weak webView] in
            guard let self, let webView else { return }
            self.disableDoubleTapZoom(in: webView)
        }
        webView.navigationDelegate = self
        webView.customUserAgent = desktopUserAgent
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsLinkPreview = false
        webView.scrollView.keyboardDismissMode = .interactive
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.isHidden = true
        return webView
    }

    private func disableDoubleTapZoom(in view: UIView) {
        for recognizer in view.gestureRecognizers ?? [] {
            guard let tap = recognizer as? UITapGestureRecognizer,
                  tap.numberOfTapsRequired == 2 else { continue }
            tap.isEnabled = false
        }
        for subview in view.subviews {
            disableDoubleTapZoom(in: subview)
        }
    }

    private func syncModifiers(on webView: WKWebView) {
        let script = """
        window.__codeServerAppKeyboard?.setModifiers(
          \(controlLocked ? "true" : "false"),
          \(shiftLocked ? "true" : "false")
        );
        """
        webView.evaluateJavaScript(script)
    }

    private func viewportWidth() -> Int {
        Int(round(Double(desktopViewportWidth) / pow(layoutZoomFactor, Double(layoutZoomSteps))))
    }

    private func applyLayoutZoom(to session: ProjectSession, reloadAfterApply: Bool) {
        let requestedSteps = layoutZoomSteps
        let requestedWidth = viewportWidth()
        let script = """
        (() => {
          const width = \(requestedWidth);
          if (window.__codeServerAppSetViewportWidth) {
            return window.__codeServerAppSetViewportWidth(width);
          }
          let viewport = document.querySelector('meta[name="viewport"]');
          if (!viewport) {
            viewport = document.createElement('meta');
            viewport.name = 'viewport';
            (document.head || document.documentElement).appendChild(viewport);
          }
          viewport.content = `width=${width}, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes`;
          window.__codeServerAppViewportWidth = width;
          window.dispatchEvent(new Event('resize'));
          return width;
        })();
        """
        session.webView.evaluateJavaScript(script) { [weak self, weak session] _, _ in
            Task { @MainActor [weak self, weak session] in
                guard let self,
                      let session,
                      self.sessions[session.key] === session,
                      requestedSteps == self.layoutZoomSteps else { return }
                session.appliedZoomSteps = requestedSteps
                if reloadAfterApply,
                   session.webView.url != nil,
                   !session.zoomReloadInProgress {
                    session.zoomReloadInProgress = true
                    session.webView.reload()
                }
            }
        }
    }

    private func cleanupExpiredSessions(now: TimeInterval) {
        guard !keepAliveEnabled else { return }
        let expiredKeys = sessions.compactMap { key, session -> String? in
            guard key != activeSessionKey,
                  let inactiveAt = session.lastInactiveAt,
                  now - inactiveAt >= projectSessionTTL else { return nil }
            return key
        }
        for key in expiredKeys {
            destroySession(key: key)
        }
    }

    private func evictExcessSessions() {
        while sessions.count > maximumHotProjectSessions {
            let candidate = sessions.values
                .filter { $0.key != activeSessionKey }
                .min { ($0.lastInactiveAt ?? 0) < ($1.lastInactiveAt ?? 0) }
            guard let candidate else { return }
            destroySession(key: candidate.key)
        }
    }

    private func destroySession(key: String) {
        guard let session = sessions.removeValue(forKey: key) else { return }
        session.urlObservation = nil
        session.webView.stopLoading()
        session.webView.navigationDelegate = nil
        session.webView.removeFromSuperview()
    }

    private func showStatus(_ message: String) {
        let token = UUID()
        statusToken = token
        statusMessage = message
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            guard let self, self.statusToken == token else { return }
            self.statusMessage = nil
        }
    }

    private static func javaScriptString(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [value]),
              let encoded = String(data: data, encoding: .utf8),
              encoded.count >= 2 else { return "\"\"" }
        return String(encoded.dropFirst().dropLast())
    }

    private static func addressesEquivalent(_ first: String, _ second: String) -> Bool {
        comparableAddress(first) == comparableAddress(second)
    }

    private static func comparableAddress(_ address: String) -> String {
        let normalized = normalizedAddress(address)
        guard var components = URLComponents(string: normalized) else {
            return normalized
        }
        if components.path == "/" {
            components.path = ""
        } else if components.path.hasSuffix("/") {
            components.path.removeLast()
        }
        return components.string ?? normalized
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        if let session = sessions.values.first(where: { $0.webView === webView }),
           !session.zoomReloadInProgress,
           session.lastFinishedURL != navigationAction.request.url?.absoluteString {
            session.appliedZoomSteps = nil
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        disableDoubleTapZoom(in: webView)
        guard let session = sessions.values.first(where: { $0.webView === webView }) else {
            return
        }
        session.lastFinishedURL = webView.url?.absoluteString
        publishAddress(for: session)
        let completedZoomReload = session.zoomReloadInProgress
        session.zoomReloadInProgress = false
        let needsReload = !completedZoomReload
            && ((session.appliedZoomSteps == nil && layoutZoomSteps != 0)
                || (session.appliedZoomSteps != nil
                    && session.appliedZoomSteps != layoutZoomSteps))
        applyLayoutZoom(to: session, reloadAfterApply: needsReload)
        syncModifiers(on: webView)
    }
}

struct CodeServerWebView: UIViewRepresentable {
    let address: String
    let store: CodeServerWebViewStore
    let mouseModeEnabled: Bool

    func makeUIView(context: Context) -> WebViewSessionContainerView {
        let view = WebViewSessionContainerView()
        store.attach(to: view)
        return view
    }

    func updateUIView(_ view: WebViewSessionContainerView, context: Context) {
        store.attach(to: view)
        store.activate(address: address)
        view.setMouseModeEnabled(mouseModeEnabled)
    }
}

final class WebViewSessionContainerView: UIView {
    var onCommittedText: ((String) -> Void)? {
        didSet { keyboardCapture.onCommittedText = onCommittedText }
    }
    var onDeleteBackward: (() -> Void)? {
        didSet { keyboardCapture.onDeleteBackward = onDeleteBackward }
    }
    var onEnter: (() -> Void)? {
        didSet { keyboardCapture.onEnter = onEnter }
    }
    var onMouse: ((MouseAction, CGPoint, Int) -> Void)? {
        didSet { mouseOverlay.onMouse = onMouse }
    }

    private let keyboardCapture = KeyboardCaptureTextView()
    private let mouseOverlay = MouseOverlayView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .systemBackground
        addSubview(mouseOverlay)
        addSubview(keyboardCapture)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func install(_ webView: WKWebView) {
        if webView.superview !== self {
            addSubview(webView)
        }
        webView.frame = bounds
        bringSubviewToFront(mouseOverlay)
        bringSubviewToFront(keyboardCapture)
    }

    func bringWebViewToFront(_ webView: WKWebView) {
        bringSubviewToFront(webView)
        bringSubviewToFront(mouseOverlay)
        bringSubviewToFront(keyboardCapture)
    }

    func setMouseModeEnabled(_ enabled: Bool) {
        mouseOverlay.setEnabled(enabled)
    }

    func releaseMouseButtons() {
        mouseOverlay.releaseButtons()
    }

    func activateKeyboardCapture() {
        bringSubviewToFront(keyboardCapture)
        keyboardCapture.activate()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        for subview in subviews where subview !== keyboardCapture {
            subview.frame = bounds
        }
        keyboardCapture.frame = CGRect(
            x: 1,
            y: max(bounds.height - 2, 1),
            width: 1,
            height: 1
        )
    }
}

private final class KeyboardCaptureTextView: UITextView, UITextViewDelegate {
    var onCommittedText: ((String) -> Void)?
    var onDeleteBackward: (() -> Void)?
    var onEnter: (() -> Void)?
    private var isFlushing = false

    init() {
        super.init(frame: .zero, textContainer: nil)
        delegate = self
        backgroundColor = .clear
        textColor = .clear
        tintColor = .clear
        alpha = 0.01
        isScrollEnabled = false
        autocorrectionType = .no
        autocapitalizationType = .none
        spellCheckingType = .no
        smartDashesType = .no
        smartQuotesType = .no
        smartInsertDeleteType = .no
        keyboardType = .default
        returnKeyType = .default
        accessibilityElementsHidden = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func activate() {
        text = ""
        becomeFirstResponder()
    }

    override func deleteBackward() {
        if markedTextRange == nil && text.isEmpty {
            onDeleteBackward?()
            return
        }
        super.deleteBackward()
    }

    func textViewDidChange(_ textView: UITextView) {
        guard !isFlushing,
              markedTextRange == nil,
              !text.isEmpty else { return }

        let committed = text ?? ""
        isFlushing = true
        text = ""
        isFlushing = false

        var buffer = ""
        for character in committed {
            if character == "\n" || character == "\r" {
                if !buffer.isEmpty {
                    onCommittedText?(buffer)
                    buffer = ""
                }
                onEnter?()
            } else {
                buffer.append(character)
            }
        }
        if !buffer.isEmpty {
            onCommittedText?(buffer)
        }
    }
}
