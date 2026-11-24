package com.krocotovski.mentions;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny image loader on top of host().fetch(): 3 threads, memory LRU, remembers dead URLs. */
final class Img {
    interface Fetch { byte[] get(String url); }
    interface Target { void set(Bitmap b); Object getTag(); void setTag(Object o); }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final Set<String> DEAD = new HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ExecutorService pool;
    static Fetch fetch;

    private Img() {}

    static void load(final String url, final Target t, final int px) {
        t.setTag(url);
        if (url == null || fetch == null) return;
        Bitmap hit = CACHE.get(url);
        if (hit != null) { t.set(hit); return; }
        synchronized (DEAD) { if (DEAD.contains(url)) return; }
        synchronized (Img.class) {
            if (pool == null) pool = Executors.newFixedThreadPool(3);
        }
        pool.execute(() -> {
            Bitmap b = null;
            try {
                byte[] raw = fetch.get(url); // null on any network error, per docs
                if (raw != null) b = decode(raw, px);
            } catch (Throwable ignored) {
            }
            if (b == null) {
                synchronized (DEAD) { DEAD.add(url); } // expired CDN signatures: don't retry forever
                return;
            }
            CACHE.put(url, b);
            final Bitmap fb = b;
            MAIN.post(() -> { if (url.equals(t.getTag())) t.set(fb); });
        });
    }

    private static Bitmap decode(byte[] raw, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    static synchronized void stop() {
        if (pool != null) { pool.shutdownNow(); pool = null; }
        CACHE.evictAll();
    }
}
