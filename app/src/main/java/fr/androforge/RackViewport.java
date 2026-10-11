package fr.androforge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Bounded rack drawing, with a viewport independent of patch edits and circuit focus. */
final class RackViewport extends View {
    private static final LruCache<String, List<ModuleGeometry.Port>> PORTS = new LruCache<>(128);
    static final class Module {
        final String name;
        final int block, controller, g8, rgbOffset;
        Bitmap image;
        Module(String name, int block, int controller, int g8, int rgbOffset, Bitmap image) {
            this.name = name; this.block = block; this.controller = controller;
            this.g8 = g8; this.rgbOffset = rgbOffset; this.image = image;
        }
        List<ModuleGeometry.Port> ports() {
            return geometry(false);
        }
        List<ModuleGeometry.Port> renderingPorts() { return geometry(true); }
        private List<ModuleGeometry.Port> geometry(boolean painting) {
            String key = name + ":" + controller + ":" + g8 + ":" + rgbOffset + ":" + painting;
            List<ModuleGeometry.Port> ports = PORTS.get(key);
            if (ports == null) {
                ports = painting ? ModuleGeometry.renderingPorts(name, controller, g8, rgbOffset) : ModuleGeometry.ports(name, controller, g8, rgbOffset);
                PORTS.put(key, ports);
            }
            return ports;
        }
    }

    interface Listener {
        void addModule();
        void openModule(Module module);
        void editLabel(Module module, String register);
        void viewportChanged();
    }

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scales;
    private final float density;
    private final int touchSlopSquared;
    private List<Module> modules = Collections.emptyList();
    private Map<String, String> labels = Collections.emptyMap();
    private float[] starts = new float[0];
    private float widthHp, zoom = 1, leftHp, topHp;
    private boolean gestureBlocked, doubleTapCandidate, doubleTapPanning;
    private float doubleTapX, doubleTapY, lastDoubleTapX, lastDoubleTapY;
    private Module pendingTapModule;
    private String pendingTapRegister;
    private int pendingTapIndex = -1;
    private Module pressedModule;
    private String pressedRegister;
    private int pressedIndex = -1, accessibilityFocus = -1, hovered = -1;
    private Listener listener;

    RackViewport(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        touchSlopSquared = touchSlop * touchSlop;
        setBackgroundColor(0xff1c1c1c);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(context.getString(R.string.mobile_rack_description));
        labelPaint.setColor(0xffffffff);
        textPaint.setTextAlign(Paint.Align.CENTER);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                capturePoint(event.getX(), event.getY()); return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                if (gestureBlocked) return true;
                capturePoint(event.getX(), event.getY());
                pendingTapModule = pressedModule; pendingTapRegister = pressedRegister; pendingTapIndex = pressedIndex;
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                Module module = pendingTapModule;
                if (module != null && modules.contains(module)) {
                    pressedModule = module; pressedRegister = pendingTapRegister; pressedIndex = pendingTapIndex;
                    clearPendingTap(); performClick();
                } else clearPendingTap();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                clearPendingTap(); return true;
            }
            @Override public boolean onDoubleTapEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    doubleTapCandidate = !gestureBlocked;
                    doubleTapX = lastDoubleTapX = event.getX(); doubleTapY = lastDoubleTapY = event.getY();
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (doubleTapCandidate && movedFromDoubleTap(event)) {
                        doubleTapCandidate = false; gestureBlocked = true; doubleTapPanning = true;
                    }
                    // GestureDetector sends double-tap moves here rather than to onScroll.
                    if (doubleTapPanning && !scales.isInProgress()) {
                        leftHp += (lastDoubleTapX - event.getX()) / unit();
                        topHp += (lastDoubleTapY - event.getY()) / unit(); changed();
                    }
                    lastDoubleTapX = event.getX(); lastDoubleTapY = event.getY();
                } else if (action == MotionEvent.ACTION_UP) {
                    boolean add = doubleTapCandidate && !gestureBlocked && !movedFromDoubleTap(event);
                    doubleTapCandidate = false; doubleTapPanning = false;
                    if (add) addModule();
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    doubleTapCandidate = false; doubleTapPanning = false;
                }
                return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                doubleTapCandidate = false; clearPendingTap();
                if (!gestureBlocked) { capturePoint(event.getX(), event.getY()); performLongClick(); }
            }
            @Override public boolean onContextClick(MotionEvent event) {
                doubleTapCandidate = false; clearPendingTap();
                capturePoint(event.getX(), event.getY()); return performLongClick();
            }
            @Override public boolean onScroll(MotionEvent from, MotionEvent to, float dx, float dy) {
                gestureBlocked = true; doubleTapCandidate = false; clearPendingTap();
                if (scales.isInProgress()) return true;
                leftHp += dx / unit(); topHp += dy / unit();
                changed(); return true;
            }
        });
        scales = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                gestureBlocked = true; doubleTapCandidate = false; doubleTapPanning = false; clearPendingTap(); return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                scaleTo(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true;
            }
        });
        // Double tap belongs to the module picker, while two-finger scaling keeps zooming.
        scales.setQuickScaleEnabled(false);
    }

    void setListener(Listener listener) { this.listener = listener; }

    void setModules(List<Module> modules, Map<String, String> labels) {
        cancelGestures(android.os.SystemClock.uptimeMillis());
        if (accessibilityFocus >= 0) sendVirtualEvent(accessibilityFocus, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        accessibilityFocus = -1; hovered = -1; pressedModule = null; pressedRegister = null; pressedIndex = -1;
        this.modules = new ArrayList<>(modules); this.labels = labels;
        starts = new float[modules.size()]; widthHp = 0;
        for (int index = 0; index < modules.size(); index++) {
            starts[index] = widthHp;
            widthHp += ModuleGeometry.width(modules.get(index).name) + .08f;
        }
        if (modules.size() > 0) widthHp -= .08f;
        clamp(); invalidate();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    List<Module> modules() { return Collections.unmodifiableList(modules); }
    float zoom() { return zoom; }
    float left() { return leftHp; }
    float top() { return topHp; }

    void restore(float zoom, float left, float top) {
        this.zoom = finite(zoom) ? Math.max(.5f, Math.min(4, zoom)) : 1;
        leftHp = finite(left) ? Math.max(0, left) : 0;
        topHp = finite(top) ? Math.max(0, top) : 0;
        clamp(); invalidate();
    }

    void zoomBy(float factor) { scaleTo(zoom * factor, getWidth() / 2f, getHeight() / 2f); }
    void resetZoom() { zoom = 1; leftHp = 0; topHp = 0; changed(); }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private float unit() { return Math.max(1, getHeight() - 12 * density) / ModuleGeometry.HEIGHT * zoom; }
    private float verticalOrigin() { return Math.max(0, (getHeight() - ModuleGeometry.HEIGHT * unit()) / 2); }
    private float horizontalOrigin() { return 6 * density - leftHp * unit(); }

    private void scaleTo(float value, float x, float y) {
        if (!finite(value)) return;
        float oldUnit = unit();
        float anchorX = (x - 6 * density) / oldUnit + leftHp;
        float anchorY = (y - verticalOrigin()) / oldUnit + topHp;
        zoom = Math.max(.5f, Math.min(4, value));
        leftHp = anchorX - (x - 6 * density) / unit();
        topHp = anchorY - (y - verticalOrigin()) / unit();
        changed();
    }

    private void clamp() {
        if (getHeight() <= 0 || getWidth() <= 0 || modules.isEmpty()) return;
        leftHp = Math.max(0, Math.min(Math.max(0, widthHp - (getWidth() - 12 * density) / unit()), leftHp));
        topHp = Math.max(0, Math.min(Math.max(0, ModuleGeometry.HEIGHT - getHeight() / unit()), topHp));
    }

    private void changed() {
        clamp(); invalidate();
        if (listener != null) listener.viewportChanged();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        clamp(); invalidate();
    }

    private int firstVisible() {
        int low = 0, high = starts.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (starts[mid] + ModuleGeometry.width(modules.get(mid).name) < leftHp) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = unit(), originX = horizontalOrigin(), originY = verticalOrigin() - topHp * unit;
        for (int index = firstVisible(); index < modules.size(); index++) {
            Module module = modules.get(index);
            float x = originX + starts[index] * unit;
            float width = ModuleGeometry.width(module.name) * unit;
            if (x >= getWidth()) break;
            rect.set(x, originY, x + width, originY + ModuleGeometry.HEIGHT * unit);
            if (module.image != null) canvas.drawBitmap(module.image, null, rect, imagePaint);
            else {
                imagePaint.setColor(0xff373737); canvas.drawRect(rect, imagePaint);
                textPaint.setColor(0xffeeeeee); textPaint.setTextSize(12 * density);
                canvas.drawText(module.name.toUpperCase(java.util.Locale.ROOT), x + width / 2, getHeight() / 2f, textPaint);
            }
            for (ModuleGeometry.Port port : module.renderingPorts()) {
                String text = labels.get(port.register);
                if (text == null || text.isEmpty()) continue;
                rect.set(x + port.labelX * unit, originY + port.labelY * unit,
                        x + (port.labelX + port.labelWidth) * unit, originY + (port.labelY + .7f) * unit);
                if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= getWidth() || rect.top >= getHeight()) continue;
                canvas.drawRoundRect(rect, .1f * unit, .1f * unit, labelPaint);
                textPaint.setColor(0xff000000); textPaint.setTextSize(.5f * unit);
                float length = textPaint.measureText(text);
                if (length > rect.width() - .1f * unit)
                    textPaint.setTextSize(textPaint.getTextSize() * Math.max(.1f, (rect.width() - .1f * unit) / length));
                canvas.drawText(text, rect.centerX(), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
            }
        }
    }

    private void capturePoint(float x, float y) {
        pressedModule = null; pressedRegister = null;
        pressedIndex = -1;
        float hpX = (x - horizontalOrigin()) / unit();
        float hpY = (y - verticalOrigin()) / unit() + topHp;
        if (hpY < 0 || hpY > ModuleGeometry.HEIGHT) return;
        for (int index = firstVisible(); index < modules.size(); index++) {
            Module module = modules.get(index);
            if (starts[index] > hpX) return;
            if (hpX > starts[index] + ModuleGeometry.width(module.name)) continue;
            pressedModule = module;
            pressedIndex = index;
            float localX = hpX - starts[index], bestDistance = Float.MAX_VALUE;
            for (ModuleGeometry.Port port : module.ports()) {
                float dx = localX - port.x, dy = hpY - port.y;
                float distance = dx * dx + dy * dy;
                boolean inLabel = localX >= port.labelX && localX <= port.labelX + port.labelWidth
                        && hpY >= port.labelY && hpY <= port.labelY + .7f;
                if ((inLabel || Math.abs(dx) <= port.width / 2 && Math.abs(dy) <= port.height / 2) && distance < bestDistance) {
                    bestDistance = distance; pressedRegister = port.register;
                }
            }
            return;
        }
    }

    private void clearPendingTap() {
        pendingTapModule = null; pendingTapRegister = null; pendingTapIndex = -1;
    }

    private boolean movedFromDoubleTap(MotionEvent event) {
        for (int index = 0; index < event.getHistorySize(); index++)
            if (outsideTouchSlop(event.getHistoricalX(index), event.getHistoricalY(index))) return true;
        return outsideTouchSlop(event.getX(), event.getY());
    }

    private boolean outsideTouchSlop(float x, float y) {
        float dx = x - doubleTapX, dy = y - doubleTapY;
        return dx * dx + dy * dy > touchSlopSquared;
    }

    private boolean addModule() {
        if (!isEnabled() || listener == null) return false;
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        listener.addModule(); return true;
    }

    private void cancelGestures(long now) {
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        gestures.onTouchEvent(cancel); scales.onTouchEvent(cancel); cancel.recycle();
        gestureBlocked = true; doubleTapCandidate = false; doubleTapPanning = false; clearPendingTap();
    }

    // Confirmed single taps select modules; completed double taps add a module.
    // Movement, extra pointers and cancellation must not activate either action.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) { cancelGestures(event.getEventTime()); return false; }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            gestureBlocked = false; doubleTapCandidate = false; doubleTapPanning = false;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            gestureBlocked = true; doubleTapCandidate = false; doubleTapPanning = false; clearPendingTap();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            gestureBlocked = true; doubleTapCandidate = false; doubleTapPanning = false; clearPendingTap();
        }
        scales.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            if (listener != null) listener.viewportChanged();
        return true;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return gestures.onGenericMotionEvent(event) || super.onGenericMotionEvent(event);
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        AccessibilityManager manager = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isEnabled() || !manager.isTouchExplorationEnabled()) return super.dispatchHoverEvent(event);
        int target = -1;
        if (event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT) {
            capturePoint(event.getX(), event.getY()); target = pressedIndex < 0 ? -1 : pressedIndex + 1;
        }
        if (hovered != target) {
            if (target >= 0) sendVirtualEvent(target, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            if (hovered >= 0) sendVirtualEvent(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
            hovered = target;
        }
        return target >= 0;
    }

    @Override public boolean performClick() {
        if (!isEnabled()) return false;
        super.performClick();
        if (pressedModule == null && !modules.isEmpty()) pressedModule = modules.get(Math.min(firstVisible(), modules.size() - 1));
        if (pressedModule != null && listener != null) listener.openModule(pressedModule);
        return true;
    }

    @Override public boolean performLongClick() {
        if (!isEnabled()) return false;
        super.performLongClick();
        if (pressedModule != null && listener != null) listener.editLabel(pressedModule, pressedRegister);
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        cancelGestures(android.os.SystemClock.uptimeMillis());
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) cancelGestures(android.os.SystemClock.uptimeMillis());
    }

    @Override public void onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents();
        cancelGestures(android.os.SystemClock.uptimeMillis());
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo node) {
        super.onInitializeAccessibilityNodeInfo(node);
        if (isEnabled()) node.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                R.id.mobile_rack_add_module_action, getContext().getString(R.string.mobile_rack_add_module)));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!isEnabled()) return false;
        if (action == R.id.mobile_rack_add_module_action) return addModule();
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            leftHp += (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1) * getWidth() * .8f / unit();
            changed(); sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED); return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return accessibility; }

    private void sendVirtualEvent(int id, int type) {
        if (getParent() == null) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setSource(this, id); event.setPackageName(getContext().getPackageName()); event.setClassName("android.widget.Button");
        int index = id - 1;
        if (index >= 0 && index < modules.size()) {
            Module module = modules.get(index);
            event.setContentDescription(getContext().getString(R.string.mobile_rack_module,
                    module.name.toUpperCase(java.util.Locale.ROOT), module.controller > 0 ? " · " + module.controller : module.g8 > 0 ? " · G" + module.g8 : ""));
        }
        getParent().requestSendAccessibilityEvent(this, event);
    }

    private final AccessibilityNodeProvider accessibility = new AccessibilityNodeProvider() {
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if (id != HOST_VIEW_ID && (id <= 0 || id > modules.size())) return null;
            AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
            if (id == HOST_VIEW_ID) {
                onInitializeAccessibilityNodeInfo(node);
                node.setSource(RackViewport.this);
                node.setClassName("android.widget.HorizontalScrollView"); node.setScrollable(true);
                node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                for (int index = firstVisible(); index < modules.size(); index++) {
                    if (horizontalOrigin() + starts[index] * unit() >= getWidth()) break;
                    node.addChild(RackViewport.this, index + 1);
                }
                return node;
            }
            int index = id - 1;
            if (index < 0 || index >= modules.size()) return null;
            Module module = modules.get(index);
            node.setSource(RackViewport.this, id); node.setParent(RackViewport.this);
            node.setPackageName(getContext().getPackageName()); node.setClassName("android.widget.Button");
            node.setEnabled(isEnabled()); node.setClickable(true); node.setLongClickable(true); node.setFocusable(true);
            node.setContentDescription(getContext().getString(R.string.mobile_rack_module,
                    module.name.toUpperCase(java.util.Locale.ROOT), module.controller > 0 ? " · " + module.controller : module.g8 > 0 ? " · G" + module.g8 : ""));
            node.addAction(AccessibilityNodeInfo.ACTION_CLICK); node.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            node.setAccessibilityFocused(accessibilityFocus == id);
            node.addAction(accessibilityFocus == id ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            float x = horizontalOrigin() + starts[index] * unit();
            Rect bounds = new Rect((int) x, (int) (verticalOrigin() - topHp * unit()),
                    (int) (x + ModuleGeometry.width(module.name) * unit()), (int) (verticalOrigin() + (ModuleGeometry.HEIGHT - topHp) * unit()));
            boolean visible = bounds.intersect(0, 0, getWidth(), getHeight()) && isShown();
            node.setVisibleToUser(visible); node.setBoundsInParent(bounds);
            int[] location = new int[2]; getLocationOnScreen(location); bounds.offset(location[0], location[1]);
            node.setBoundsInScreen(bounds); return node;
        }

        @Override public boolean performAction(int id, int action, Bundle arguments) {
            if (id == HOST_VIEW_ID) return RackViewport.this.performAccessibilityAction(action, arguments);
            int index = id - 1;
            if (index < 0 || index >= modules.size() || !isEnabled()) return false;
            Module module = modules.get(index);
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocus == id) return false;
                if (accessibilityFocus >= 0) sendVirtualEvent(accessibilityFocus, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                accessibilityFocus = id; sendVirtualEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED); invalidate(); return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocus != id) return false;
                accessibilityFocus = -1; sendVirtualEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED); invalidate(); return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLICK && listener != null) { listener.openModule(module); return true; }
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && listener != null) { listener.editLabel(module, null); return true; }
            return false;
        }

        @Override public AccessibilityNodeInfo findFocus(int focus) {
            return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocus >= 0 ? createAccessibilityNodeInfo(accessibilityFocus) : null;
        }
    };
}
