package com.winlator.star.xserver;

import android.util.SparseArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public class CursorManager extends XResourceManager {
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final DrawableManager drawableManager;

    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(IDGenerator.generate(), sourcePixmap.drawable.width, sourcePixmap.drawable.height, sourcePixmap.drawable.visual);
        Cursor cursor = new Cursor(id, x, y, drawable, sourcePixmap.drawable, maskPixmap != null ? maskPixmap.drawable : null);
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    /** A full-colour cursor (RENDER CreateCursor): {@code argb} is width*height premultiplied
     *  ARGB32 pixels in X byte order, copied into the cursor's own image. A cursor with no opaque
     *  pixel is hidden, the same as a core cursor with an empty mask. */
    public Cursor createArgbCursor(int id, int hotX, int hotY, short width, short height, ByteBuffer argb) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(IDGenerator.generate(), width, height, (byte)32);
        argb.rewind();
        drawable.drawImage((short)0, (short)0, (short)0, (short)0, width, height, (byte)32, argb, width, height);
        Cursor cursor = new Cursor(id, Math.max(0, Math.min(hotX, width - 1)), Math.max(0, Math.min(hotY, height - 1)), drawable, null, null);
        cursor.setVisible(hasOpaquePixel(argb));
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    /** A plain white arrow with a black outline, used when a colour cursor can't be built so the
     *  client still gets a valid, visible cursor instead of an error. */
    public Cursor createFallbackArrowCursor(int id) {
        final String[] rows = {
            "X          ", "XX         ", "X.X        ", "X..X       ", "X...X      ", "X....X     ",
            "X.....X    ", "X......X   ", "X.......X  ", "X........X ", "X.....XXXXX", "X..X..X    ",
            "X.X X..X   ", "XX  X..X   ", "X    X..X  ", "     X..X  ", "      XX   "
        };
        short w = 11, h = (short)rows.length;
        ByteBuffer argb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (String row : rows) {
            for (int x = 0; x < w; x++) {
                char c = x < row.length() ? row.charAt(x) : ' ';
                argb.putInt(c == 'X' ? 0xff000000 : c == '.' ? 0xffffffff : 0);
            }
        }
        return createArgbCursor(id, 0, 0, w, h, argb);
    }

    private static boolean hasOpaquePixel(ByteBuffer argb) {
        IntBuffer pixels = argb.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for (int i = 0; i < pixels.capacity(); i++) {
            if ((pixels.get(i) & 0xff000000) != 0) return true;
        }
        return false;
    }

    public void freeCursor(int id) {
        Cursor cursor = cursors.get(id);
        if (cursor != null && cursor.cursorImage != null) {
            drawableManager.removeDrawable(cursor.cursorImage.id);
        }
        triggerOnFreeResourceListener(cursor);
        cursors.remove(id);
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.getData().asIntBuffer();
        boolean result = true;
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0x000000) {
                result = false;
                break;
            }
        }
        return result;
    }

    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue) {
        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) cursor.cursorImage.drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, cursor.sourceImage, cursor.maskImage);
        }
    }
}