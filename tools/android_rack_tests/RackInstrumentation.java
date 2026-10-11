package fr.androforge;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual Android gesture detectors, compiled with the exact production View source. */
public final class RackInstrumentation extends Instrumentation {
    private RackViewport rack;
    private Counts counts;
    private int checks;
    private long downTime;

    private static final class Counts implements RackViewport.Listener {
        int adds, opens, labels, changes;
        String register;
        Runnable afterAdd, afterOpen;
        @Override public void addModule() { adds++; if (afterAdd != null) afterAdd.run(); }
        @Override public void openModule(RackViewport.Module module) { opens++; if (afterOpen != null) afterOpen.run(); }
        @Override public void editLabel(RackViewport.Module module, String register) { labels++; this.register = register; }
        @Override public void viewportChanged() { changes++; }
    }

    private interface Task { void run() throws Throwable; }

    private void main(Task task) throws Throwable {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { task.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw failure[0];
    }

    private void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }

    private void fixture(int modules) throws Throwable {
        main(() -> {
            if (rack != null) rack.onDetachedFromWindow();
            rack = new RackViewport(getTargetContext()); counts = new Counts(); rack.setListener(counts);
            List<RackViewport.Module> list = new ArrayList<>();
            for (int index = 0; index < modules; index++)
                list.add(new RackViewport.Module("master", index, 0, 0, 0, (Bitmap) null));
            rack.setModules(list, Collections.emptyMap());
            // Ordinary parent behavior is needed by View.performLongClick's
            // context-menu fallback, even though this fixture opens no Activity.
            FrameLayout parent = new FrameLayout(getTargetContext());
            parent.addView(rack, new FrameLayout.LayoutParams(1000, 400));
            parent.layout(0, 0, 1000, 400); rack.layout(0, 0, 1000, 400);
        });
    }

    private void event(int action, float x, float y) throws Throwable {
        main(() -> {
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) downTime = now;
            MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { rack.onTouchEvent(event); } finally { event.recycle(); }
        });
    }

    private void pointers(int action, float firstX, float secondX) throws Throwable {
        main(() -> {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
            for (int index = 0; index < 2; index++) {
                properties[index] = new MotionEvent.PointerProperties(); properties[index].id = index;
                properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[index] = new MotionEvent.PointerCoords(); coords[index].x = index == 0 ? firstX : secondX;
                coords[index].y = 200; coords[index].pressure = 1; coords[index].size = 1;
            }
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    2, properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { rack.onTouchEvent(event); } finally { event.recycle(); }
        });
    }

    private void tap(float x, float y) throws Throwable {
        event(MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(35);
        event(MotionEvent.ACTION_UP, x, y);
    }

    private void gap() { SystemClock.sleep(65); }
    private void confirmed() { SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 80L); }

    private void untouched(String reason) throws Throwable {
        main(() -> { check(counts.adds == 0, reason + ": no add"); check(counts.opens == 0, reason + ": no selection"); });
    }

    private void runTests() throws Throwable {
        fixture(1); tap(50, 100); untouched("Single tap waits for confirmation"); confirmed();
        main(() -> { check(counts.opens == 1, "Single module tap selects once"); check(counts.adds == 0, "Single module tap never adds"); });

        fixture(1); tap(50, 100); gap(); event(MotionEvent.ACTION_DOWN, 50, 100);
        untouched("Double tap waits for second release"); SystemClock.sleep(35); event(MotionEvent.ACTION_UP, 50, 100); confirmed();
        main(() -> { check(counts.adds == 1, "Double module tap adds once"); check(counts.opens == 0, "Double module tap does not select"); });

        fixture(1); tap(950, 200); gap(); tap(950, 200); confirmed();
        main(() -> { check(counts.adds == 1, "Double blank-rack tap adds"); check(counts.opens == 0, "Blank rack does not select first module"); });
        fixture(0); tap(500, 200); gap(); tap(500, 200); confirmed();
        main(() -> check(counts.adds == 1, "Double empty-rack tap adds"));
        fixture(1); tap(950, 200); confirmed(); untouched("Single blank-rack tap");

        fixture(1); tap(50, 100); gap(); event(MotionEvent.ACTION_DOWN, 50, 100);
        event(MotionEvent.ACTION_CANCEL, 50, 100); confirmed(); untouched("Canceled second tap");

        fixture(24); tap(250, 200); gap(); event(MotionEvent.ACTION_DOWN, 250, 200);
        event(MotionEvent.ACTION_MOVE, 80, 200); event(MotionEvent.ACTION_UP, 80, 200); confirmed();
        untouched("Second tap becomes pan"); main(() -> check(rack.left() > 0, "Second-tap drag still pans"));

        fixture(24); event(MotionEvent.ACTION_DOWN, 250, 200); event(MotionEvent.ACTION_MOVE, 80, 200);
        event(MotionEvent.ACTION_UP, 80, 200); confirmed(); untouched("Ordinary pan");
        main(() -> check(rack.left() > 0, "Ordinary drag still pans"));

        fixture(24); tap(50, 200); gap(); event(MotionEvent.ACTION_DOWN, 50, 200);
        pointers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, 250);
        pointers(MotionEvent.ACTION_MOVE, 20, 650);
        // Android may use the first expanded span to begin scaling, then applies
        // a factor on the following move. A real pinch produces both stages.
        pointers(MotionEvent.ACTION_MOVE, 0, 950);
        pointers(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0, 950);
        event(MotionEvent.ACTION_UP, 0, 200); confirmed(); untouched("Second tap becomes pinch");
        main(() -> check(rack.zoom() > 1, "Two-finger pinch still zooms"));

        fixture(1); tap(50, 100); gap(); event(MotionEvent.ACTION_DOWN, 50, 100);
        pointers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, 70);
        pointers(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, 70);
        event(MotionEvent.ACTION_UP, 50, 100); confirmed(); untouched("Extra pointer without recognized scale");

        fixture(1);
        main(() -> {
            float density = rack.getResources().getDisplayMetrics().density;
            float unit = Math.max(1, 400 - 12 * density) / ModuleGeometry.HEIGHT;
            long now = SystemClock.uptimeMillis(); downTime = now;
            MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                    6 * density + .98f * unit, 6 * density + 14.47f * unit, 0);
            try { rack.onTouchEvent(event); } finally { event.recycle(); }
        });
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + ViewConfiguration.getTapTimeout() + 100L);
        event(MotionEvent.ACTION_UP, 50, 200); confirmed();
        main(() -> { check(counts.labels == 1, "Long press edits label once"); check("I1".equals(counts.register), "Long press retains exact port"); });
        untouched("Long press must not add/select");

        fixture(1); tap(50, 100); gap(); event(MotionEvent.ACTION_DOWN, 50, 100);
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + ViewConfiguration.getTapTimeout() + 100L);
        event(MotionEvent.ACTION_UP, 50, 100); confirmed(); untouched("Long second press cancels addition");
        main(() -> check(counts.labels == 1, "Long second press keeps label action"));

        fixture(1); tap(50, 100); gap(); event(MotionEvent.ACTION_DOWN, 50, 100);
        main(() -> {
            float jitter = ViewConfiguration.get(getTargetContext()).getScaledTouchSlop() / 2f;
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 50 + jitter, 100, 0);
            try { rack.onTouchEvent(event); } finally { event.recycle(); }
        });
        event(MotionEvent.ACTION_UP, 50, 100); confirmed(); main(() -> check(counts.adds == 1, "Small jitter still adds"));

        fixture(1); tap(50, 100); gap(); event(MotionEvent.ACTION_DOWN, 50, 100);
        event(MotionEvent.ACTION_UP, 300, 100); confirmed(); untouched("Displaced release without MOVE cancels addition");

        fixture(1); tap(50, 100); main(() -> rack.onDetachedFromWindow()); confirmed(); untouched("Detached pending tap");
        fixture(1); tap(50, 100); main(() -> rack.setModules(Collections.emptyList(), Collections.emptyMap()));
        confirmed(); untouched("Changed rack cancels pending tap");

        fixture(1); tap(50, 100); main(() -> rack.onWindowFocusChanged(false)); confirmed();
        untouched("Focus loss cancels pending single tap");
        main(() -> rack.onWindowFocusChanged(true)); tap(50, 100); confirmed();
        main(() -> { check(counts.opens == 1, "Single tap works after focus returns"); check(counts.adds == 0, "Focus return does not add"); });

        fixture(1); tap(50, 100); gap(); event(MotionEvent.ACTION_DOWN, 50, 100);
        main(() -> rack.onWindowFocusChanged(false)); event(MotionEvent.ACTION_UP, 50, 100); confirmed();
        untouched("Focus loss cancels second tap candidate");
        main(() -> rack.onWindowFocusChanged(true)); tap(50, 100); gap(); tap(50, 100); confirmed();
        main(() -> { check(counts.adds == 1, "Double tap works after focus returns"); check(counts.opens == 0, "Recovered double tap does not select"); });

        fixture(1); tap(50, 100); main(() -> rack.cancelPendingInputEvents()); confirmed();
        untouched("Input cancellation cancels pending single tap");
        fixture(1); tap(50, 100); gap(); event(MotionEvent.ACTION_DOWN, 50, 100);
        main(() -> rack.cancelPendingInputEvents()); event(MotionEvent.ACTION_UP, 50, 100); confirmed();
        untouched("Input cancellation cancels second tap candidate");

        fixture(1); main(() -> counts.afterAdd = () -> rack.onWindowFocusChanged(false));
        tap(50, 100); gap(); tap(50, 100); confirmed();
        main(() -> { check(counts.adds == 1, "Reentrant picker focus loss adds once"); check(counts.opens == 0, "Reentrant picker focus loss does not select"); });
        main(() -> rack.onWindowFocusChanged(true)); tap(50, 100); confirmed();
        main(() -> check(counts.opens == 1, "Normal tap works after picker callback focus loss"));

        fixture(1); main(() -> counts.afterOpen = () -> rack.cancelPendingInputEvents());
        tap(50, 100); confirmed();
        main(() -> { check(counts.opens == 1, "Reentrant module callback input cancellation selects once"); check(counts.adds == 0, "Reentrant selection does not add"); });

        fixture(1); main(() -> {
            AccessibilityNodeInfo host = rack.getAccessibilityNodeProvider().createAccessibilityNodeInfo(-1);
            boolean offered = false;
            for (AccessibilityNodeInfo.AccessibilityAction action : host.getActionList())
                if (action.getId() == R.id.mobile_rack_add_module_action && "Add module".contentEquals(action.getLabel())) offered = true;
            host.recycle(); check(offered, "Host offers English Add module accessibility action");
            check(rack.performAccessibilityAction(R.id.mobile_rack_add_module_action, null), "Host accessibility action handled");
            check(counts.adds == 1, "Host accessibility action adds once");
            check(rack.getAccessibilityNodeProvider().performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null), "Virtual module click handled");
            check(counts.opens == 1, "TalkBack virtual click retains module selection");
            check(rack.getAccessibilityNodeProvider().performAction(1, AccessibilityNodeInfo.ACTION_LONG_CLICK, null), "Virtual module long click handled");
            check(counts.labels == 1, "Virtual long click retains label action");
            rack.setEnabled(false);
            check(!rack.performAccessibilityAction(R.id.mobile_rack_add_module_action, null), "Disabled rack rejects Add module");
            check(!rack.getAccessibilityNodeProvider().performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null), "Disabled module rejects selection");
        });

        fixture(1); tap(50, 100); confirmed(); tap(50, 100); confirmed();
        main(() -> { check(counts.opens == 2, "Slow taps remain separate selections"); check(counts.adds == 0, "Slow taps never add"); });
    }

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runTests(); main(() -> { if (rack != null) rack.onDetachedFromWindow(); });
            JSONObject report = new JSONObject(); report.put("passed", true); report.put("checks", checks);
            report.put("scope", "Production rack source with Android gesture detectors and lifecycle cancellation in isolated standalone package");
            report.put("application_user_data_accessed", false);
            result.putBoolean("passed", true); result.putString("report", report.toString()); finish(-1, result);
        } catch (Throwable failure) {
            try { main(() -> { if (rack != null) rack.onDetachedFromWindow(); }); } catch (Throwable ignored) { }
            result.putBoolean("passed", false); result.putInt("checks", checks);
            result.putString("error", failure.toString()); finish(0, result);
        }
    }
}
