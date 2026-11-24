package com.ss.android.ugc.aweme.ttp.plugin;

import android.app.Activity;
import android.content.Context;

/** Compile-only stub. The real class lives in the mod and is NOT packed into the dex. */
public abstract class Plugin {
    public static final int API = 3;
    public PluginContext host() { return null; }
    public void onStart(Context context) {}
    public void onActivityCreated(Activity a) {}
    public void onActivityResumed(Activity a) {}
    public void onActivityPaused(Activity a) {}
    public void onStop() {}
}
