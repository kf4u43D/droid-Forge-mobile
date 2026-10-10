package fr.androforge;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;

/** Keeps the exact edited field visible when the keyboard resizes circuit cards. */
public final class EditingListView extends ListView {
    private boolean suppressCardFocusSelection;

    public EditingListView(Context context) { super(context); }

    @Override public View getFocusedChild() {
        return suppressCardFocusSelection ? null : super.getFocusedChild();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        View focused = findFocus();
        View card = super.getFocusedChild();
        int position = card == null ? INVALID_POSITION : getPositionForView(card);
        boolean retainEditor = focused instanceof EditText && card != null && height > 0
                && position != INVALID_POSITION;
        int cardTop = 0;
        if (retainEditor) {
            Rect rectangle = new Rect();
            focused.getDrawingRect(rectangle);
            offsetDescendantRectToMyCoords(focused, rectangle);
            int viewportTop = getPaddingTop();
            int lastFieldTop = Math.max(viewportTop, height - getPaddingBottom() - rectangle.height());
            int fieldTop = Math.max(viewportTop, Math.min(rectangle.top, lastFieldTop));
            cardTop = card.getTop() + fieldTop - rectangle.top - getListPaddingTop();
        }
        // ListView's FocusSelector aligns the bottom of its direct child. For a
        // tall card that hides the edited field. Anchor its own rectangle instead.
        suppressCardFocusSelection = retainEditor;
        try { super.onSizeChanged(width, height, oldWidth, oldHeight); }
        finally { suppressCardFocusSelection = false; }
        if (retainEditor) {
            // Set LAYOUT_SPECIFIC before layoutChildren can recycle a lower card
            // that would otherwise fall outside the reduced viewport.
            setSelectionFromTop(position, cardTop);
            post(() -> {
                if (!isAttachedToWindow() || card.getParent() != this || !focused.isAttachedToWindow()
                        || !focused.hasFocus() || getPositionForView(focused) != position) return;
                Rect rectangle = new Rect();
                focused.getDrawingRect(rectangle);
                focused.requestRectangleOnScreen(rectangle, true);
            });
        }
    }
}
