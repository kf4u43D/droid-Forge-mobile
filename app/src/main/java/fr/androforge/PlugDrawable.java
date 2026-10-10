package fr.androforge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Official Forge plug image, mirrored on outputs as in the desktop editor. */
final class PlugDrawable extends Drawable {
    private final Bitmap bitmap;
    private final boolean mirrored;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    PlugDrawable(Bitmap bitmap, boolean mirrored) { this.bitmap = bitmap; this.mirrored = mirrored; }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int save = canvas.save();
        if (mirrored) canvas.scale(-1, 1, bounds.exactCenterX(), bounds.exactCenterY());
        canvas.drawBitmap(bitmap, null, bounds, paint);
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
