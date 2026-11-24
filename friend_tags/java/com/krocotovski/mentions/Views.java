package com.krocotovski.mentions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Hand-drawn views: the plugin has no resources, so avatars, covers and hearts are painted. */
final class Views {
    private Views() {}

    private static final int[] AVATAR_BG = {0xFF5B6CFF, 0xFF00B3A1, 0xFFFF7A45, 0xFFB15CFF, 0xFF3BA7F5, 0xFFE9446A};

    /** Round avatar; letter on a coloured circle until the picture arrives. */
    static final class Avatar extends View implements Img.Target {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bmp;
        private String letter = "?";
        private int bg = AVATAR_BG[0];
        private int ring;

        Avatar(Context c) {
            super(c);
            tp.setColor(0xFFFFFFFF);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void bind(String name, String url, int px, int ringColor) {
            ring = ringColor;
            bmp = null;
            letter = name == null || name.isEmpty() ? "?" : name.replace("@", "").substring(0, 1).toUpperCase();
            bg = AVATAR_BG[Math.abs((name == null ? 0 : name.hashCode()) % AVATAR_BG.length)];
            invalidate();
            Img.load(url, this, px);
        }

        @Override public void set(Bitmap b) { bmp = b; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            float inset = ring != 0 ? r * 0.09f : 0;
            if (ring != 0) {
                p.setShader(null);
                p.setColor(ring);
                c.drawCircle(w / 2, h / 2, r, p);
            }
            float rr = r - inset;
            if (bmp != null) {
                BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                float sc = Math.max(2 * rr / bmp.getWidth(), 2 * rr / bmp.getHeight());
                m.setScale(sc, sc);
                m.postTranslate(w / 2 - bmp.getWidth() * sc / 2, h / 2 - bmp.getHeight() * sc / 2);
                sh.setLocalMatrix(m);
                p.setShader(sh);
                c.drawCircle(w / 2, h / 2, rr, p);
                p.setShader(null);
            } else {
                p.setColor(bg);
                c.drawCircle(w / 2, h / 2, rr, p);
                tp.setTextSize(rr * 0.95f);
                c.drawText(letter, w / 2, h / 2 - (tp.descent() + tp.ascent()) / 2, tp);
            }
        }
    }

    /** Video cover thumbnail with rounded corners and a play glyph placeholder. */
    static final class Cover extends View implements Img.Target {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path play = new Path();
        private Bitmap bmp;
        private final int placeholder;

        Cover(Context c, int placeholder) {
            super(c);
            this.placeholder = placeholder;
        }

        void bind(String url, int px) {
            bmp = null;
            invalidate();
            Img.load(url, this, px);
        }

        @Override public void set(Bitmap b) { bmp = b; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = Math.min(w, h) * 0.08f;
            rect.set(0, 0, w, h);
            if (bmp != null) {
                BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                float sc = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                m.setScale(sc, sc);
                m.postTranslate((w - bmp.getWidth() * sc) / 2, (h - bmp.getHeight() * sc) / 2);
                sh.setLocalMatrix(m);
                p.setShader(sh);
                c.drawRoundRect(rect, rad, rad, p);
                p.setShader(null);
            } else {
                p.setColor(placeholder);
                c.drawRoundRect(rect, rad, rad, p);
                float s = Math.min(w, h) * 0.22f, cx = w / 2, cy = h / 2;
                play.reset();
                play.moveTo(cx - s * 0.6f, cy - s);
                play.lineTo(cx + s, cy);
                play.lineTo(cx - s * 0.6f, cy + s);
                play.close();
                p.setColor(0xCCFFFFFF);
                c.drawPath(play, p);
            }
        }
    }

    /** TikTok-style heart: outline or filled. */
    static final class Heart extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean filled;
        private int color;

        Heart(Context c) { super(c); }

        void set(boolean filled, int color) {
            this.filled = filled;
            this.color = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            float ox = (w - s) / 2, oy = (h - s) / 2;
            path.reset();
            path.moveTo(ox + s * 0.5f, oy + s * 0.88f);
            path.cubicTo(ox + s * 0.10f, oy + s * 0.60f, ox - s * 0.02f, oy + s * 0.30f, ox + s * 0.20f, oy + s * 0.16f);
            path.cubicTo(ox + s * 0.34f, oy + s * 0.07f, ox + s * 0.46f, oy + s * 0.14f, ox + s * 0.5f, oy + s * 0.24f);
            path.cubicTo(ox + s * 0.54f, oy + s * 0.14f, ox + s * 0.66f, oy + s * 0.07f, ox + s * 0.80f, oy + s * 0.16f);
            path.cubicTo(ox + s * 1.02f, oy + s * 0.30f, ox + s * 0.90f, oy + s * 0.60f, ox + s * 0.5f, oy + s * 0.88f);
            path.close();
            p.setColor(color);
            p.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.085f);
            p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(path, p);
        }
    }
}
