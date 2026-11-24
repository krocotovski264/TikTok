package com.ss.android.ugc.aweme.ttp.plugin;

import android.app.Activity;
import android.content.SharedPreferences;

/** Compile-only stub, signatures guessed from the docs. */
public interface PluginContext {
    String id();
    SharedPreferences prefs();
    void log(String line);
    void addSettingsRow(String title, String detail, Runnable action);
    void showWindow(String title, String message);
    void offer(String label, Runnable action);
    void progress(String what, int percent);
    void progressGone();
    Activity screen();
    void away(String what, Runnable work);
    byte[] fetch(String url);
}
