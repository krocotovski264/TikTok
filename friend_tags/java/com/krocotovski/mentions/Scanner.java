package com.krocotovski.mentions;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.krocotovski.mentions.Model.A;
import com.krocotovski.mentions.Model.C;
import com.krocotovski.mentions.Model.Ev;
import com.krocotovski.mentions.Model.Store;
import com.krocotovski.mentions.Model.Tag;
import com.krocotovski.mentions.Model.U;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads TikTok's own SQLite files read-only and pulls out anything that looks like
 * a comment, a video, a user, a like notice or a repost.
 *
 * It does not depend on table names: TikTok renames them between releases. Every row is
 * turned into a JSON object (column -> value), JSON stored inside columns is parsed
 * recursively, and objects are recognised by the field names of TikTok's network
 * models (cid / text / aweme_id / text_extra / reply_id / digg_count / from_user ...),
 * which stay stable because the server sends them.
 */
final class Scanner {

    interface Progress { void at(String what, int percent); }

    private static final Pattern AT = Pattern.compile("@([A-Za-z0-9_.]{2,24})");
    private static final Pattern NUM = Pattern.compile("[0-9]{6,}");
    private static final Pattern IM_DB = Pattern.compile("^([0-9]{6,})_.*\\.db$");
    private static final Set<String> ACTOR_KEYS = new HashSet<>(Arrays.asList(
            "from_user", "from_users", "user", "users", "user_list", "digg_users", "digg_user_list",
            "operator", "operators", "sender", "actor", "user_info", "from_user_info", "repost_user",
            "repost_users", "reposter", "reposters"));
    private static final Set<String> ID_COLS = new HashSet<>(Arrays.asList(
            "cid", "comment_id", "aweme_id", "item_id"));

    private static final int ROWS_PER_TABLE = 4000;
    private static final int MAX_JSON = 600_000;
    private static final long BUDGET_MS = 40_000;

    private final Context ctx;
    private final Progress progress;
    private final Store s = new Store();
    private long deadline;

    Scanner(Context ctx, Progress progress) {
        this.ctx = ctx;
        this.progress = progress;
    }

    Store run() {
        long t0 = System.currentTimeMillis();
        deadline = t0 + BUDGET_MS;
        File dir = ctx.getDatabasePath("x").getParentFile();
        File[] all = dir == null ? null : dir.listFiles();
        List<File> dbs = new ArrayList<>();
        if (all != null) {
            for (File f : all) {
                String n = f.getName();
                if (!f.isFile() || n.endsWith("-wal") || n.endsWith("-shm") || n.endsWith("-journal")) continue;
                if (f.length() < 4096) continue;
                Matcher m = IM_DB.matcher(n);
                if (m.find()) s.vote(m.group(1), 3); // IM databases are named after the logged-in uid
                dbs.add(f);
            }
        }
        // small first: notice/comment caches are small, giant feed caches go last
        Collections.sort(dbs, (a, b) -> Long.compare(a.length(), b.length()));
        for (int i = 0; i < dbs.size(); i++) {
            if (System.currentTimeMillis() > deadline) {
                s.diag.append("! time budget hit, skipped ").append(dbs.size() - i).append(" db\n");
                break;
            }
            File f = dbs.get(i);
            if (progress != null) progress.at(f.getName(), i * 100 / Math.max(1, dbs.size()));
            scanDb(f);
        }
        s.tookMs = System.currentTimeMillis() - t0;
        return s;
    }

    // ---------------------------------------------------------------- sqlite

    private void scanDb(File f) {
        SQLiteDatabase db = null;
        int before = s.comments.size();
        int tablesHit = 0;
        try {
            db = SQLiteDatabase.openDatabase(f.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            List<String> tables = new ArrayList<>();
            Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            try {
                while (c.moveToNext()) tables.add(c.getString(0));
            } finally {
                c.close();
            }
            s.dbs++;
            for (String t : tables) {
                if (t.startsWith("sqlite_") || t.equals("android_metadata") || t.equals("room_master_table")) continue;
                if (System.currentTimeMillis() > deadline) break;
                int r = scanTable(db, f.getName(), t);
                if (r > 0) tablesHit++;
            }
        } catch (Throwable e) {
            s.failedDbs++;
            s.diag.append("x ").append(f.getName()).append(": ").append(short_(e)).append('\n');
            return;
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
        int got = s.comments.size() - before;
        if (tablesHit > 0) {
            s.diag.append("+ ").append(f.getName()).append(" (").append(f.length() / 1024).append(" KB): ")
                    .append(tablesHit).append(" tables with data, +").append(got).append(" comments\n");
        }
    }

    /** @return rows that produced at least one comment/video/event */
    private int scanTable(SQLiteDatabase db, String dbName, String table) {
        List<String> cols = new ArrayList<>();
        List<String> types = new ArrayList<>();
        Cursor info = null;
        try {
            info = db.rawQuery("PRAGMA table_info(" + q(table) + ")", null);
            while (info.moveToNext()) {
                cols.add(info.getString(1));
                String ty = info.getString(2);
                types.add(ty == null ? "" : ty.toUpperCase(Locale.ROOT));
            }
        } catch (Throwable e) {
            return 0;
        } finally {
            if (info != null) info.close();
        }
        if (cols.isEmpty()) return 0;
        s.tables++;

        boolean direct = false;
        StringBuilder sel = new StringBuilder();
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            if (ID_COLS.contains(col.toLowerCase(Locale.ROOT))) direct = true;
            if (sel.length() > 0) sel.append(',');
            sel.append("CAST(").append(q(col)).append(" AS TEXT)");
            String ty = types.get(i);
            if (ty.contains("INT") || ty.contains("REAL") || ty.contains("FLOA") || ty.contains("DOUB")) continue;
            if (where.length() > 0) where.append(" OR ");
            String qc = q(col);
            where.append(qc).append(" LIKE '%\"cid\"%' OR ")
                 .append(qc).append(" LIKE '%aweme_id%' OR ")
                 .append(qc).append(" LIKE '%repost%' OR ")
                 .append(qc).append(" LIKE '%digg%'");
        }
        if (!direct && where.length() == 0) return 0;
        String base = "SELECT " + sel + " FROM " + q(table) + (direct ? "" : " WHERE " + where);
        Cursor c = null;
        int useful = 0;
        String path = dbName + ":" + table;
        try {
            try {
                c = db.rawQuery(base + " ORDER BY rowid DESC LIMIT " + ROWS_PER_TABLE, null);
            } catch (Throwable noRowid) {
                c = db.rawQuery(base + " LIMIT " + ROWS_PER_TABLE, null);
            }
            while (c.moveToNext()) {
                if (System.currentTimeMillis() > deadline) break;
                JSONObject row = new JSONObject();
                for (int i = 0; i < cols.size(); i++) {
                    String v;
                    try {
                        v = c.getString(i);
                    } catch (Throwable tooBig) {
                        continue;
                    }
                    if (v != null) row.put(cols.get(i), v);
                }
                s.rows++;
                int before = s.comments.size() + s.awemes.size() + s.events.size();
                walk(row, path, table, 0);
                if (s.comments.size() + s.awemes.size() + s.events.size() > before) useful++;
            }
        } catch (Throwable e) {
            s.diag.append("  ~ ").append(path).append(": ").append(short_(e)).append('\n');
        } finally {
            if (c != null) c.close();
        }
        if (useful > 0) s.diag.append("  ").append(path).append(" → ").append(useful).append(" rows\n");
        return useful;
    }

    // ---------------------------------------------------------------- json walk

    private static final class Found {
        final List<C> cs = new ArrayList<>();
        final List<U> us = new ArrayList<>();
        final List<A> as = new ArrayList<>();

        void add(Found o) {
            cs.addAll(o.cs);
            us.addAll(o.us);
            as.addAll(o.as);
        }
    }

    private Found walk(Object v, String path, String key, int depth) {
        Found f = new Found();
        if (v == null || depth > 14) return f;
        if (v instanceof String) {
            String str = ((String) v).trim();
            int n = str.length();
            if (n > 1 && n < MAX_JSON) {
                char a = str.charAt(0);
                if ((a == '{' && str.charAt(n - 1) == '}') || (a == '[' && str.charAt(n - 1) == ']')) {
                    try {
                        Object j = new JSONTokener(str).nextValue();
                        if (j instanceof JSONObject || j instanceof JSONArray) return walk(j, path, key, depth + 1);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return f;
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            int n = Math.min(arr.length(), 400);
            for (int i = 0; i < n; i++) f.add(walk(arr.opt(i), path, key, depth + 1));
            return f;
        }
        if (!(v instanceof JSONObject)) return f;

        JSONObject o = (JSONObject) v;
        String here = path + "/" + key;
        U u = user(o);
        C c = comment(o);
        A a = aweme(o);

        Found kids = new Found();
        List<U> actors = new ArrayList<>();
        StringBuilder keys = new StringBuilder();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            keys.append(k).append(',');
            Found ff = walk(o.opt(k), here, k, depth + 1);
            String kl = k.toLowerCase(Locale.ROOT);
            if (ACTOR_KEYS.contains(kl)) actors.addAll(ff.us);
            if (a != null && kl.contains("repost")) {
                for (U ru : ff.us) if (ru.uid != null) a.reposters.add(ru.uid);
            }
            kids.add(ff);
        }

        if (u != null) {
            f.us.add(u);
            return f; // a user object: nested stuff is avatars etc.
        }
        if (c != null) {
            f.cs.add(c);
            f.cs.addAll(kids.cs); // reply lists embedded in a comment
            f.as.addAll(kids.as);
            return f;
        }
        if (a != null) {
            f.as.add(a);
            f.cs.addAll(kids.cs);
            return f;
        }

        infer(o, here, keys.toString(), kids, actors);
        f.add(kids);
        return f;
    }

    /** Container object (a notice, a row, a list wrapper): guess relations between what is inside. */
    private void infer(JSONObject o, String path, String keys, Found kids, List<U> actors) {
        StringBuilder ctxB = new StringBuilder(path).append('|').append(keys);
        for (String tk : new String[]{"type", "notice_type", "sub_type", "label", "action", "event"}) {
            Object tv = o.opt(tk);
            if (tv instanceof String) ctxB.append('|').append(tv);
        }
        String cx = ctxB.toString().toLowerCase(Locale.ROOT);
        boolean digg = cx.contains("digg") || cx.contains("like");
        boolean repost = cx.contains("repost");

        List<C> cs = distinctC(kids.cs);
        List<A> as = distinctA(kids.as);
        Set<String> actorIds = new HashSet<>();
        for (U au : actors) if (au.uid != null) actorIds.add(au.uid);

        // like notice on a comment: the liked comment is the one not written by the likers
        if (digg && !actorIds.isEmpty() && !cs.isEmpty() && cs.size() <= 3) {
            C target = null;
            for (C cc : cs) {
                if (cc.uid != null && !actorIds.contains(cc.uid)) { target = cc; break; }
            }
            if (target != null) {
                Set<String> likes = s.commentLikes.get(target.cid);
                if (likes == null) s.commentLikes.put(target.cid, likes = new HashSet<>());
                likes.addAll(actorIds);
                s.vote(target.uid, 1); // whoever gets like notices is the account owner
            }
        }
        // reply notice: two comments side by side
        if (cs.size() == 2) s.pairs.add(new String[]{cs.get(0).cid, cs.get(1).cid});

        if (repost && !as.isEmpty() && as.size() <= 2) {
            Ev ev = new Ev();
            ev.aweme = as.get(0).id;
            ev.uids.addAll(actorIds);
            ev.kind = digg ? Ev.LIKE : (!cs.isEmpty() ? Ev.COMMENT : Ev.REPOST);
            ev.cid = cs.isEmpty() ? null : cs.get(0).cid;
            ev.text = firstText(o);
            ev.time = lng(o, "create_time", "time", "repost_time", "created_at");
            ev.path = path;
            s.events.add(ev);
        }
    }

    // ---------------------------------------------------------------- recognisers

    private U user(JSONObject o) {
        String uid = str(o, "uid", "user_id");
        if (uid == null || !NUM.matcher(uid).matches()) return null;
        String name = str(o, "unique_id", "uniqueId");
        String nick = str(o, "nickname", "nick_name");
        if (name == null && nick == null) return null;
        if (o.has("cid") || o.has("aweme_id")) return null;
        U u = s.users.get(uid);
        if (u == null) {
            u = new U();
            u.uid = uid;
            s.users.put(uid, u);
        }
        if (name != null) u.name = name;
        if (nick != null) u.nick = nick;
        String av = img(o, "avatar_thumb", "avatar_168x168", "avatar_medium", "avatar_larger", "avatar_url", "avatar");
        if (av != null) u.avatar = av;
        return u;
    }

    private C comment(JSONObject o) {
        String cid = str(o, "cid", "comment_id");
        if (cid == null || !NUM.matcher(cid).matches()) return null;
        String text = str(o, "text", "content", "comment_text");
        if (text == null) return null;
        C c = s.comments.get(cid);
        if (c == null) {
            c = new C();
            c.cid = cid;
            s.comments.put(cid, c);
        }
        c.text = text;
        if (c.aweme == null) c.aweme = num(str(o, "aweme_id", "item_id", "group_id", "video_id"));
        String rid = num(str(o, "reply_id", "parent_id", "reply_comment_id"));
        if (rid != null && !rid.equals(cid)) c.replyId = rid;
        String rr = num(str(o, "reply_to_reply_id"));
        if (rr != null && !rr.equals(cid)) c.replyTo = rr;
        c.time = Math.max(c.time, lng(o, "create_time", "created_at", "time"));
        c.diggs = (int) Math.max(c.diggs, lng(o, "digg_count", "like_count"));
        c.replies = (int) Math.max(c.replies, lng(o, "reply_comment_total", "reply_count"));

        JSONObject uo = o.optJSONObject("user");
        if (uo == null && o.opt("user") instanceof String) {
            try { uo = new JSONObject(o.optString("user")); } catch (Throwable ignored) {}
        }
        U au = uo != null ? user(uo) : null;
        if (au != null) c.uid = au.uid;
        else {
            String uid = num(str(o, "user_id", "uid", "author_id"));
            if (uid != null) c.uid = uid;
        }

        if (c.tags.isEmpty()) {
            Object te = o.opt("text_extra");
            JSONArray arr = null;
            if (te instanceof JSONArray) arr = (JSONArray) te;
            else if (te instanceof String) try { arr = new JSONArray((String) te); } catch (Throwable ignored) {}
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    if (!e.optString("hashtag_name", "").isEmpty() || e.optInt("type", 0) == 1) continue;
                    String muid = num(str(e, "user_id", "uid"));
                    int st = e.optInt("start", -1), en = e.optInt("end", -1);
                    String name = null;
                    if (st >= 0 && en > st && en <= text.length()) name = text.substring(st, en).trim();
                    if (name != null && name.startsWith("@")) name = name.substring(1);
                    if (muid != null || (name != null && !name.isEmpty())) c.tags.add(new Tag(muid, name));
                }
            }
            addRegexTags(text, c.tags);
        }
        return c;
    }

    static void addRegexTags(String text, List<Tag> tags) {
        if (text == null) return;
        Matcher m = AT.matcher(text);
        while (m.find()) {
            String n = m.group(1);
            boolean dup = false;
            for (Tag t : tags) {
                if (t.name != null && (t.name.equalsIgnoreCase(n) || t.name.toLowerCase(Locale.ROOT).startsWith(n.toLowerCase(Locale.ROOT)))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) tags.add(new Tag(null, n));
        }
    }

    private A aweme(JSONObject o) {
        String id = num(str(o, "aweme_id", "item_id"));
        if (id == null) return null;
        if (o.has("cid") || o.has("comment_id")) return null;
        if (!(o.has("desc") || o.has("video") || o.has("author") || o.has("cover"))) return null;
        A a = s.awemes.get(id);
        if (a == null) {
            a = new A();
            a.id = id;
            s.awemes.put(id, a);
        }
        String d = str(o, "desc");
        if (d != null) a.desc = d;
        JSONObject video = o.optJSONObject("video");
        String cov = video != null ? img(video, "cover", "origin_cover", "dynamic_cover") : null;
        if (cov == null) cov = img(o, "cover", "cover_url");
        if (cov != null) a.cover = cov;
        JSONObject au = o.optJSONObject("author");
        if (au != null) {
            U u = user(au);
            if (u != null) a.authorUid = u.uid;
        }
        return a;
    }

    // ---------------------------------------------------------------- helpers

    private static String firstText(JSONObject o) {
        String t = str(o, "repost_comment", "repost_text", "comment", "text", "content", "title");
        if (t != null && (t.startsWith("{") || t.startsWith("["))) return null;
        return t;
    }

    private static String img(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof String && ((String) v).startsWith("http")) return (String) v;
            if (v instanceof JSONObject) {
                JSONArray list = ((JSONObject) v).optJSONArray("url_list");
                if (list == null || list.length() == 0) continue;
                String pick = null;
                for (int i = 0; i < list.length(); i++) {
                    String u = list.optString(i, null);
                    if (u == null) continue;
                    if (pick == null) pick = u;
                    String l = u.toLowerCase(Locale.ROOT);
                    if (l.contains(".jpeg") || l.contains(".jpg") || l.contains(".webp") || l.contains(".png")) return u;
                }
                if (pick != null) return pick;
            }
        }
        return null;
    }

    static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            if (v instanceof String) {
                String s = (String) v;
                if (!s.isEmpty() && !s.equals("null")) return s;
            } else if (v instanceof Long || v instanceof Integer) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static long lng(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) try { return Long.parseLong((String) v); } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static String num(String s) {
        return s != null && NUM.matcher(s).matches() && !s.equals("0") ? s : null;
    }

    private static List<C> distinctC(List<C> in) {
        List<C> out = new ArrayList<>();
        for (C c : in) if (!out.contains(c)) out.add(c);
        return out;
    }

    private static List<A> distinctA(List<A> in) {
        List<A> out = new ArrayList<>();
        for (A a : in) if (!out.contains(a)) out.add(a);
        return out;
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String short_(Throwable e) {
        String m = e.getMessage();
        if (m != null && m.length() > 120) m = m.substring(0, 120);
        return e.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }
}
