package com.krocotovski.mentions;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.krocotovski.mentions.Model.Item;
import com.krocotovski.mentions.Model.R;
import com.krocotovski.mentions.Model.Store;
import com.krocotovski.mentions.Model.U;
import com.ss.android.ugc.aweme.ttp.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * «Отметки друзей»: ваши комментарии и репосты, где вы отметили друзей через @,
 * их ответы и лайки. Открывается строкой на странице «Плагины» в настройках мода.
 *
 * Не перехватывает методы TikTok и не ходит по обфусцированным именам: только читает
 * свои же базы приложения (read-only) и открывает видео обычным Intent'ом.
 */
public final class Mentions extends Plugin {

    private static volatile Store store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context app;
    private volatile boolean busy, stopped;
    private Builder cached;
    private String cachedKey;

    static Store store() {
        return store;
    }

    @Override
    public void onStart(Context context) {
        Context ac = context.getApplicationContext();
        app = ac != null ? ac : context;
        Img.fetch = url -> host().fetch(url);
        host().addSettingsRow("Отметки друзей",
                "Комментарии и репосты с @ — ответы и лайки друзей", () -> open(false));
    }

    @Override
    public void onStop() {
        stopped = true;
        Img.stop();
    }

    // ---------------------------------------------------------------- flow

    private void open(boolean rescan) {
        if (stopped) {
            host().showWindow("Отметки друзей", "Плагин выключен. Включите его и перезапустите TikTok.");
            return;
        }
        if (!rescan && store != null) {
            show();
            return;
        }
        if (busy) return;
        busy = true;
        host().progress("Отметки друзей: читаю базы TikTok", 0);
        host().away("friend-tags scan", () -> {
            try {
                Store s = new Scanner(app, (what, pct) -> host().progress("Отметки: " + what, pct)).run();
                store = s;
                cached = null;
                host().log("friend-tags: " + summary());
            } catch (Throwable t) {
                host().log("friend-tags scan failed: " + Log.getStackTraceString(t));
            } finally {
                busy = false;
                host().progressGone();
            }
            main.post(this::show);
        });
    }

    private void show() {
        if (store == null) {
            host().showWindow("Отметки друзей", "Не удалось прочитать данные — подробности в журнале мода.");
            return;
        }
        Activity a = host().screen();
        if (a == null || a.isFinishing()) {
            host().showWindow("Отметки друзей", plainList());
            return;
        }
        try {
            new Sheet(a, sheetHost).show();
        } catch (Throwable t) {
            host().log("friend-tags ui: " + Log.getStackTraceString(t));
            host().showWindow("Отметки друзей", plainList());
        }
    }

    // ---------------------------------------------------------------- identity

    /** @return {uid, uniqueName}; either may be null */
    private String[] me() {
        Store s = store;
        String set = host().prefs().getString("me", "");
        set = set == null ? "" : set.trim();
        if (set.startsWith("@")) set = set.substring(1);
        String uid = null, name = null;
        if (!set.isEmpty()) {
            if (set.matches("[0-9]{6,}")) uid = set;
            else name = set;
        } else if (s != null) {
            uid = s.guessMe();
        }
        if (s != null) {
            if (uid != null && name == null) {
                U u = s.user(uid);
                if (u != null) name = u.name;
            }
            if (uid == null && name != null) {
                for (U u : s.users.values()) {
                    if (name.equalsIgnoreCase(u.name)) { uid = u.uid; break; }
                }
            }
        }
        return new String[]{uid, name};
    }

    private Builder data() {
        String[] me = me();
        String key = me[0] + "|" + me[1];
        Builder b = cached;
        if (b == null || !key.equals(cachedKey)) {
            b = new Builder(store, me[0], me[1]);
            cached = b;
            cachedKey = key;
        }
        return b;
    }

    private String meLabel() {
        String[] me = me();
        if (me[1] != null) return "@" + me[1];
        if (me[0] != null) return "id" + me[0];
        return "не определён";
    }

    // ---------------------------------------------------------------- text

    private String summary() {
        Store s = store;
        if (s == null) return "";
        return String.format(Locale.getDefault(),
                "Баз: %d (не открылись: %d) · таблиц: %d · строк: %d · %.1f с\nКомментариев: %d · видео: %d · пользователей: %d · событий репоста: %d",
                s.dbs, s.failedDbs, s.tables, s.rows, s.tookMs / 1000f,
                s.comments.size(), s.awemes.size(), s.users.size(), s.events.size());
    }

    private void showDiag() {
        Store s = store;
        if (s == null) return;
        StringBuilder sb = new StringBuilder(summary()).append("\n\nВы: ").append(meLabel()).append('\n');
        List<Map.Entry<String, Integer>> votes = new ArrayList<>(s.meVotes.entrySet());
        Collections.sort(votes, (x, y) -> y.getValue() - x.getValue());
        for (int i = 0; i < Math.min(3, votes.size()); i++) {
            U u = s.user(votes.get(i).getKey());
            sb.append("  кандидат ").append(votes.get(i).getKey())
              .append(u != null && u.name != null ? " @" + u.name : "")
              .append(" — ").append(votes.get(i).getValue()).append('\n');
        }
        Builder b = data();
        sb.append("Моих комментариев с @: ").append(b.comments.size())
          .append(", репостов: ").append(b.reposts.size()).append("\n\n").append(s.diag);
        String text = sb.toString();
        for (String line : text.split("\n")) host().log("friend-tags | " + line);
        host().showWindow("Диагностика", text.length() > 6000 ? text.substring(0, 6000) + "\n…полностью — в журнале мода" : text);
    }

    /** Fallback for when there is no activity to attach the sheet to. */
    private String plainList() {
        Builder b = data();
        StringBuilder sb = new StringBuilder();
        sb.append("Отмечено: ").append(b.friendsTagged).append(" · ответили: ").append(b.friendsReplied)
          .append(" · лайкнули: ").append(b.friendsLiked).append("\n\n");
        int n = 0;
        for (Item it : b.comments) {
            if (n++ >= 30) break;
            sb.append("💬 ").append(it.text).append('\n');
            for (R r : it.reactions) {
                if (!r.friend) continue;
                sb.append("   ").append(r.user.label()).append(r.kind == R.LIKE ? " ♥" : ": " + r.text).append('\n');
            }
        }
        for (Item it : b.reposts) {
            if (n++ >= 50) break;
            sb.append("🔁 ").append(it.text != null ? it.text : "репост ").append(it.aweme).append('\n');
        }
        if (n == 0) sb.append("Ничего не найдено. Откройте «Входящие → Вся активность» и повторите.");
        return sb.toString();
    }

    private final Sheet.Host sheetHost = new Sheet.Host() {
        @Override public Builder data() { return Mentions.this.data(); }
        @Override public String meLabel() { return Mentions.this.meLabel(); }
        @Override public void setMe(String v) {
            host().prefs().edit().putString("me", v == null ? "" : v).apply();
            cached = null;
        }
        @Override public void rescan() { open(true); }
        @Override public void showDiag() { Mentions.this.showDiag(); }
        @Override public String summary() { return Mentions.this.summary(); }
    };
}
