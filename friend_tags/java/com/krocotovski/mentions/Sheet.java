package com.krocotovski.mentions;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.krocotovski.mentions.Model.Item;
import com.krocotovski.mentions.Model.R;
import com.krocotovski.mentions.Model.Tag;
import com.krocotovski.mentions.Model.U;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Bottom sheet styled like TikTok's comment panel + activity inbox. */
final class Sheet {

    interface Host {
        Builder data();
        String meLabel();
        void setMe(String value);
        void rescan();
        void showDiag();
        String summary();
    }

    private static final int ACCENT = 0xFFFE2C55; // TikTok red
    private static final int CYAN = 0xFF25F4EE;

    private final Activity a;
    private final Host host;
    private final float dp;
    private final boolean dark;
    private final int bg, fg, fg2, line, chip, placeholder;

    private Dialog dialog;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView sub;
    private final TextView[] tabText = new TextView[2];
    private final View[] tabBar = new View[2];
    private int tab;
    private Builder data;

    Sheet(Activity a, Host host) {
        this.a = a;
        this.host = host;
        this.dp = a.getResources().getDisplayMetrics().density;
        this.dark = isDark(a);
        bg = dark ? 0xFF121212 : 0xFFFFFFFF;
        fg = dark ? 0xF2FFFFFF : 0xFF161823;
        fg2 = dark ? 0x8CFFFFFF : 0xFF8A8B91;
        line = dark ? 0x1FFFFFFF : 0x1F161823;
        chip = dark ? 0x14FFFFFF : 0x0D161823;
        placeholder = dark ? 0xFF2A2A2A : 0xFFE3E3E4;
    }

    // ---------------------------------------------------------------- shell

    void show() {
        data = host.data();
        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        float r = 12 * dp;
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(shape);

        // header
        FrameLayout head = new FrameLayout(a);
        TextView title = text("Отметки друзей", 15, fg, true);
        title.setGravity(Gravity.CENTER);
        head.addView(title, new FrameLayout.LayoutParams(-1, dpi(48)));
        TextView refresh = text("↻", 20, fg, false);
        refresh.setGravity(Gravity.CENTER);
        refresh.setOnClickListener(v -> { dialog.dismiss(); host.rescan(); });
        head.addView(refresh, new FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.START));
        TextView close = text("✕", 17, fg, false);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.END));
        sheet.addView(head);

        sub = text("", 12, fg2, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dpi(8));
        sub.setOnClickListener(v -> askMe());
        sheet.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        // tabs
        LinearLayout tabs = new LinearLayout(a);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            LinearLayout t = new LinearLayout(a);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            tabText[i] = text("", 15, fg, true);
            tabText[i].setPadding(0, dpi(10), 0, dpi(8));
            t.addView(tabText[i]);
            tabBar[i] = new View(a);
            tabBar[i].setBackgroundColor(fg);
            t.addView(tabBar[i], new LinearLayout.LayoutParams(dpi(44), dpi(2)));
            t.setOnClickListener(v -> { tab = idx; render(); });
            tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        sheet.addView(tabs);
        sheet.addView(divider());

        scroll = new ScrollView(a);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (dm.heightPixels * 0.82f));
            w.setDimAmount(0.45f);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        render();
        dialog.show();
    }

    private void render() {
        sub.setText("вы: " + host.meLabel() + "  ·  изменить");
        tabText[0].setText("Комментарии " + data.comments.size());
        tabText[1].setText("Репосты " + data.reposts.size());
        for (int i = 0; i < 2; i++) {
            tabText[i].setTextColor(i == tab ? fg : fg2);
            tabBar[i].setVisibility(i == tab ? View.VISIBLE : View.INVISIBLE);
        }
        list.removeAllViews();
        list.addView(stats());
        list.addView(divider());

        List<Item> items = tab == 0 ? data.comments : data.reposts;
        if (tab == 1 && data.repostsUnfiltered && !items.isEmpty()) {
            TextView note = text("Репостов с отметками не нашлось — показаны все ваши репосты.", 12, fg2, false);
            note.setPadding(dpi(16), dpi(10), dpi(16), 0);
            list.addView(note);
        }
        if (items.isEmpty()) list.addView(empty());
        for (Item it : items) list.addView(row(it));

        TextView foot = text(host.summary() + "\nДиагностика ›", 11, fg2, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dpi(16), dpi(18), dpi(16), dpi(28));
        foot.setOnClickListener(v -> host.showDiag());
        list.addView(foot);
        scroll.scrollTo(0, 0);
    }

    // ---------------------------------------------------------------- pieces

    /** Profile-style counters: following / followers / likes. */
    private View stats() {
        LinearLayout box = new LinearLayout(a);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dpi(14), 0, dpi(14));
        int[] n = {data.friendsTagged, data.friendsReplied, data.friendsLiked};
        String[] l = {"Отмечено", "Ответили", "Лайкнули"};
        for (int i = 0; i < 3; i++) {
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER);
            c.addView(text(String.valueOf(n[i]), 17, fg, true));
            c.addView(text(l[i], 13, fg2, false));
            box.addView(c, new LinearLayout.LayoutParams(dpi(96), -2));
        }
        return box;
    }

    private View empty() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dpi(32), dpi(40), dpi(32), dpi(16));
        TextView at = text("@", 34, fg2, true);
        at.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dpi(2), fg2);
        at.setBackground(ring);
        box.addView(at, new LinearLayout.LayoutParams(dpi(72), dpi(72)));
        TextView h = text(tab == 0 ? "Нет комментариев с отметками" : "Нет репостов с отметками", 17, fg, true);
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, dpi(16), 0, dpi(6));
        box.addView(h);
        TextView d = text("TikTok хранит на телефоне только то, что вы уже открывали: уведомления, "
                + "комментарии под просмотренными видео, свой профиль. Откройте «Входящие → Вся активность» "
                + "и вкладку репостов в профиле, затем нажмите ↻.\n\nЕсли ваш ник определился неверно — "
                + "нажмите «изменить» сверху.", 14, fg2, false);
        d.setGravity(Gravity.CENTER);
        box.addView(d);
        TextView btn = text("Указать мой @ник", 15, Color.WHITE, true);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(ACCENT);
        bb.setCornerRadius(4 * dp);
        btn.setBackground(bb);
        btn.setPadding(dpi(24), dpi(11), dpi(24), dpi(11));
        btn.setOnClickListener(v -> askMe());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dpi(20);
        box.addView(btn, lp);
        return box;
    }

    /** One item: avatar · text · cover, like TikTok's activity inbox, replies underneath like the comment panel. */
    private View row(final Item it) {
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setPadding(dpi(16), dpi(12), dpi(16), dpi(12));
        outer.setBackground(ripple());
        outer.setOnClickListener(v -> open(a, it.aweme, it.type == Item.COMMENT ? it.cid : null, videoAuthor(it)));

        Views.Avatar av = new Views.Avatar(a);
        av.bind(it.author != null ? it.author.title() : "я", it.author != null ? it.author.avatar : null, dpi(44), 0);
        outer.addView(av, new LinearLayout.LayoutParams(dpi(40), dpi(40)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.leftMargin = dpi(12);
        cp.rightMargin = dpi(10);
        outer.addView(col, cp);

        SpannableStringBuilder head = new SpannableStringBuilder();
        append(head, it.author != null ? it.author.title() : "Вы", new StyleSpan(Typeface.BOLD));
        append(head, it.type == Item.COMMENT ? "  прокомментировал(а)" : "  репостнул(а)", new ForegroundColorSpan(fg2));
        col.addView(text(head, 13, fg));

        String body = it.text;
        if ((body == null || body.isEmpty()) && it.type == Item.REPOST) {
            body = it.video != null && it.video.desc != null ? it.video.desc : "Видео";
        }
        TextView tv = text(withTags(body == null ? "" : body, it.tags), 15, fg);
        tv.setPadding(0, dpi(3), 0, 0);
        col.addView(tv);

        // meta line: time · video author · heart + count
        LinearLayout meta = new LinearLayout(a);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, dpi(5), 0, 0);
        String m = ago(it.time);
        String va = videoAuthor(it);
        if (va != null) m = (m.isEmpty() ? "" : m + "  ·  ") + "видео @" + va;
        meta.addView(text(m, 13, fg2, false));
        if (it.diggs > 0) {
            Views.Heart h = new Views.Heart(a);
            h.set(false, fg2);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dpi(14), dpi(14));
            hp.leftMargin = dpi(12);
            meta.addView(h, hp);
            TextView cnt = text(" " + compact(it.diggs), 13, fg2, false);
            meta.addView(cnt);
        }
        col.addView(meta);

        if (!it.tags.isEmpty()) col.addView(tagChips(it));
        addReactions(col, it);

        Views.Cover cov = new Views.Cover(a, placeholder);
        cov.bind(it.video != null ? it.video.cover : null, dpi(64));
        outer.addView(cov, new LinearLayout.LayoutParams(dpi(46), dpi(62)));
        return outer;
    }

    /** "@a ✓  @b" pills: who was tagged and who already reacted. */
    private View tagChips(Item it) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dpi(8), 0, 0);
        LinearLayout lineL = null;
        int inLine = 0;
        for (Tag t : it.tags) {
            if (lineL == null || inLine == 3) {
                lineL = new LinearLayout(a);
                wrap.addView(lineL);
                inLine = 0;
            }
            boolean reacted = false;
            for (R r : it.reactions) {
                if (!r.friend || r.user == null) continue;
                if ((t.uid != null && t.uid.equals(r.user.uid))
                        || (t.name != null && (t.name.equalsIgnoreCase(r.user.name) || t.name.equalsIgnoreCase(r.user.nick)))) {
                    reacted = true;
                    break;
                }
            }
            String name = t.name != null ? t.name : (t.uid != null ? "id" + t.uid : "?");
            TextView pill = text("@" + name + (reacted ? "  ✓" : ""), 12, reacted ? fg : fg2, reacted);
            GradientDrawable g = new GradientDrawable();
            g.setColor(reacted ? (dark ? 0x2625F4EE : 0x1A00A3B4) : chip);
            g.setCornerRadius(10 * dp);
            pill.setBackground(g);
            pill.setPadding(dpi(8), dpi(3), dpi(8), dpi(3));
            pill.setSingleLine(true);
            pill.setEllipsize(TextUtils.TruncateAt.END);
            pill.setMaxWidth(dpi(120));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dpi(6);
            lp.bottomMargin = dpi(4);
            lineL.addView(pill, lp);
            inLine++;
        }
        return wrap;
    }

    private void addReactions(final LinearLayout col, final Item it) {
        if (it.reactions.isEmpty()) {
            TextView none = text(it.tags.isEmpty() ? "Пока без реакций" : "Отмеченные пока не отреагировали", 12, fg2, false);
            none.setPadding(0, dpi(6), 0, 0);
            col.addView(none);
            return;
        }
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        col.addView(box);
        final int first = Math.min(2, it.reactions.size());
        for (int i = 0; i < first; i++) box.addView(reaction(it, it.reactions.get(i)));
        int rest = it.reactions.size() - first;
        if (rest > 0) {
            LinearLayout more = new LinearLayout(a);
            more.setGravity(Gravity.CENTER_VERTICAL);
            more.setPadding(0, dpi(10), 0, dpi(2));
            View dash = new View(a);
            dash.setBackgroundColor(fg2);
            more.addView(dash, new LinearLayout.LayoutParams(dpi(22), Math.max(1, dpi(1))));
            TextView mt = text("  Посмотреть ещё " + rest + "  ˅", 13, fg2, true);
            more.addView(mt);
            more.setOnClickListener(v -> {
                box.removeView(more);
                for (int i = first; i < it.reactions.size(); i++) box.addView(reaction(it, it.reactions.get(i)));
            });
            box.addView(more);
        }
    }

    private View reaction(final Item it, final R r) {
        LinearLayout row = new LinearLayout(a);
        row.setPadding(0, dpi(10), 0, 0);
        row.setOnClickListener(v -> open(a, it.aweme, r.cid != null ? r.cid : (it.type == Item.COMMENT ? it.cid : null), videoAuthor(it)));

        Views.Avatar av = new Views.Avatar(a);
        U u = r.user;
        av.bind(u != null ? u.title() : "?", u != null ? u.avatar : null, dpi(28), r.friend ? CYAN : 0);
        row.addView(av, new LinearLayout.LayoutParams(dpi(26), dpi(26)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.leftMargin = dpi(8);
        row.addView(col, cp);

        LinearLayout nameLine = new LinearLayout(a);
        nameLine.setGravity(Gravity.CENTER_VERTICAL);
        nameLine.addView(text(u != null ? u.title() : "?", 13, fg2, true));
        if (r.friend) {
            TextView pill = text("отмечен(а)", 10, ACCENT, true);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0x1FFE2C55);
            g.setCornerRadius(3 * dp);
            pill.setBackground(g);
            pill.setPadding(dpi(4), 0, dpi(4), dpi(1));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
            pp.leftMargin = dpi(6);
            nameLine.addView(pill, pp);
        }
        col.addView(nameLine);

        String what;
        if (r.kind == R.LIKE) {
            what = it.type == Item.COMMENT ? "понравился ваш комментарий" : "понравился ваш репост";
            col.addView(text(what, 14, fg2, false));
        } else {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (r.kind == R.VIDEO_COMMENT) append(sb, "в комментариях: ", new ForegroundColorSpan(fg2));
            else if (it.type == Item.REPOST) append(sb, "ответ: ", new ForegroundColorSpan(fg2));
            sb.append(withTags(r.text == null ? "…" : r.text, null));
            col.addView(text(sb, 14, fg));
        }
        String t = ago(r.time);
        if (!t.isEmpty()) col.addView(text(t, 12, fg2, false));

        Views.Heart h = new Views.Heart(a);
        h.set(r.kind == R.LIKE, r.kind == R.LIKE ? ACCENT : 0);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dpi(16), dpi(16));
        hp.topMargin = dpi(4);
        hp.leftMargin = dpi(6);
        row.addView(h, hp);
        return row;
    }

    // ---------------------------------------------------------------- actions

    private void askMe() {
        final EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint("@ваш_ник или uid");
        String cur = host.meLabel();
        if (cur.startsWith("@")) e.setText(cur);
        FrameLayout pad = new FrameLayout(a);
        pad.setPadding(dpi(20), dpi(8), dpi(20), 0);
        pad.addView(e);
        new AlertDialog.Builder(a, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Ваш аккаунт")
                .setMessage("Нужен, чтобы отличить ваши комментарии от чужих. Пусто — определить автоматически.")
                .setView(pad)
                .setPositiveButton("Сохранить", (d, w) -> {
                    host.setMe(e.getText().toString().trim());
                    data = host.data();
                    render();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * Opens the video inside this very TikTok build. The share-link form with share_comment_id
     * is what TikTok itself uses for "share comment", so the app scrolls the panel to that comment.
     */
    static void open(Activity a, String aweme, String cid, String author) {
        if (aweme == null) return;
        String pkg = a.getPackageName();
        String c = cid != null ? cid : "";
        String[] uris = {
                "https://www.tiktok.com/@" + (author != null ? author : "tiktok") + "/video/" + aweme
                        + (cid != null ? "?share_comment_id=" + c + "&share_item_id=" + aweme : ""),
                "snssdk1233://aweme/detail/" + aweme + (cid != null ? "?share_comment_id=" + c + "&cid=" + c : ""),
                "snssdk1180://aweme/detail/" + aweme + (cid != null ? "?share_comment_id=" + c + "&cid=" + c : ""),
        };
        for (String u : uris) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg);
            try {
                if (!a.getPackageManager().queryIntentActivities(i, 0).isEmpty()) {
                    a.startActivity(i);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uris[0])));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- helpers

    private String videoAuthor(Item it) {
        if (it.video == null || it.video.authorUid == null) return null;
        Model.Store st = Mentions.store();
        U u = st != null ? st.user(it.video.authorUid) : null;
        return u != null ? u.name : null;
    }

    private CharSequence withTags(String text, List<Tag> tags) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        // TikTok renders @mentions bold in the same colour
        int i = 0;
        while ((i = text.indexOf('@', i)) >= 0) {
            int j = i + 1;
            if (tags != null) {
                for (Tag t : tags) {
                    if (t.name != null && text.regionMatches(true, j, t.name, 0, t.name.length())) {
                        j += t.name.length();
                        break;
                    }
                }
            }
            if (j == i + 1) while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_' || text.charAt(j) == '.')) j++;
            if (j > i + 1) sb.setSpan(new StyleSpan(Typeface.BOLD), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = j;
        }
        return sb;
    }

    private static void append(SpannableStringBuilder sb, String s, Object span) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(span, st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static String ago(long t) {
        if (t <= 0) return "";
        if (t > 100_000_000_000L) t /= 1000; // ms -> s
        long d = System.currentTimeMillis() / 1000 - t;
        if (d < 60) return "только что";
        if (d < 3600) return (d / 60) + " мин.";
        if (d < 86400) return (d / 3600) + " ч.";
        if (d < 7 * 86400) return (d / 86400) + " дн.";
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(t * 1000);
        String f = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ? "dd.MM" : "dd.MM.yy";
        return new SimpleDateFormat(f, Locale.getDefault()).format(new Date(t * 1000));
    }

    private static String compact(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.US, "%.1fK", n / 1000f).replace(".0K", "K");
        return String.format(Locale.US, "%.1fM", n / 1_000_000f).replace(".0M", "M");
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x22FFFFFF : 0x14000000), null, new ColorDrawable(Color.WHITE));
    }

    private View divider() {
        View v = new View(a);
        v.setBackgroundColor(line);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dpi(0.5f))));
        return v;
    }

    private TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView text(CharSequence s, float sp, int color) {
        return text(s, sp, color, false);
    }

    private int dpi(float v) {
        return (int) (v * dp + 0.5f);
    }

    private static boolean isDark(Activity a) {
        try {
            TypedValue tv = new TypedValue();
            if (a.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)
                    && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                int c = tv.data;
                double lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
                return lum < 0.5;
            }
        } catch (Throwable ignored) {
        }
        int mode = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
