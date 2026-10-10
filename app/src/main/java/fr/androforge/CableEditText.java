package fr.androforge;

import android.content.Context;
import android.annotation.SuppressLint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.EditText;
import java.util.function.BooleanSupplier;

/** Keeps long-press and accessibility clicks available while a cable is being connected. */
final class CableEditText extends EditText {
    private BooleanSupplier connectionMode;
    private GestureDetector gestures;

    CableEditText(Context context) { super(context); }

    void setConnectionMode(BooleanSupplier mode) {
        connectionMode = mode;
        gestures = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) { performClick(); return true; }
            @Override public void onLongPress(MotionEvent event) { performLongClick(); }
            @Override public boolean onContextClick(MotionEvent event) { return performLongClick(); }
        });
    }

    // GestureDetector calls performClick only for a completed tap and performLongClick
    // for long-press/context gestures. Calling it here would also activate drags.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (gestures != null && connectionMode != null && connectionMode.getAsBoolean()) {
            gestures.onTouchEvent(event);
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return gestures != null && gestures.onGenericMotionEvent(event) || super.onGenericMotionEvent(event);
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void onDetachedFromWindow() {
        if (gestures != null) {
            MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            gestures.onTouchEvent(cancel);
            cancel.recycle();
        }
        super.onDetachedFromWindow();
    }
}
