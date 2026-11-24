package com.krocotovski.mentions;

import com.krocotovski.mentions.Model.A;
import com.krocotovski.mentions.Model.C;
import com.krocotovski.mentions.Model.Ev;
import com.krocotovski.mentions.Model.Item;
import com.krocotovski.mentions.Model.R;
import com.krocotovski.mentions.Model.Store;
import com.krocotovski.mentions.Model.Tag;
import com.krocotovski.mentions.Model.U;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns raw scan results into the two lists the sheet shows. */
final class Builder {

    final List<Item> comments = new ArrayList<>();
    final List<Item> reposts = new ArrayList<>();
    boolean repostsUnfiltered;
    int friendsTagged, friendsReplied, friendsLiked;

    private final Store s;
    private final String meUid, meName;
    private final Set<String> friendUids = new HashSet<>();
    private final Set<String> friendNames = new HashSet<>();

    Builder(Store s, String meUid, String meName) {
        this.s = s;
        this.meUid = meUid;
        this.meName = meName == null ? null : meName.toLowerCase(Locale.ROOT);
        build();
    }

    // ---------------------------------------------------------------- identity

    boolean isMe(String uid) {
        if (uid == null) return false;
        if (uid.equals(meUid)) return true;
        U u = s.users.get(uid);
        return u != null && u.name != null && meName != null && u.name.equalsIgnoreCase(meName);
    }

    private boolean isFriend(U u, List<Tag> tags) {
        if (u == null) return false;
        for (Tag t : tags) {
            if (t.uid != null && t.uid.equals(u.uid)) return true;
            if (t.name != null && (t.name.equalsIgnoreCase(u.name) || t.name.equalsIgnoreCase(u.nick))) return true;
        }
        return false;
    }

    private boolean isAnyFriend(U u) {
        if (u == null) return false;
        if (u.uid != null && friendUids.contains(u.uid)) return true;
        if (u.name != null && friendNames.contains(u.name.toLowerCase(Locale.ROOT))) return true;
        return u.nick != null && friendNames.contains(u.nick.toLowerCase(Locale.ROOT));
    }

    private U userOf(String uid, String fallbackName) {
        U u = s.user(uid);
        if (u != null) return u;
        u = new U();
        u.uid = uid;
        u.name = fallbackName;
        return u;
    }

    private static boolean taggedMe(String text, String meName) {
        return meName != null && text != null && text.toLowerCase(Locale.ROOT).contains("@" + meName);
    }

    // ---------------------------------------------------------------- build

    private void build() {
        Map<String, List<C>> children = new HashMap<>();
        Map<String, List<C>> byAweme = new HashMap<>();
        for (C c : s.comments.values()) {
            if (c.replyId != null) add(children, c.replyId, c);
            if (c.replyTo != null && !c.replyTo.equals(c.replyId)) add(children, c.replyTo, c);
            if (c.aweme != null) add(byAweme, c.aweme, c);
        }
        for (String[] p : s.pairs) {
            C a = s.comments.get(p[0]), b = s.comments.get(p[1]);
            if (a == null || b == null) continue;
            if (isMe(a.uid) && !isMe(b.uid) && b.replyId == null) add(children, a.cid, b);
            if (isMe(b.uid) && !isMe(a.uid) && a.replyId == null) add(children, b.cid, a);
        }

        // ---- my comments with @tags
        for (C c : s.comments.values()) {
            if (!isMe(c.uid) || c.tags.isEmpty()) continue;
            Item it = new Item();
            it.type = Item.COMMENT;
            it.cid = c.cid;
            it.aweme = c.aweme;
            it.text = c.text;
            it.time = c.time;
            it.diggs = c.diggs;
            it.author = userOf(c.uid, meName);
            it.video = c.aweme != null ? s.awemes.get(c.aweme) : null;
            for (Tag t : c.tags) {
                U tu = t.name == null ? s.user(t.uid) : null;
                it.tags.add(tu != null && tu.name != null ? new Tag(t.uid, tu.name) : t);
            }
            for (Tag t : it.tags) {
                if (t.uid != null) friendUids.add(t.uid);
                if (t.name != null) friendNames.add(t.name.toLowerCase(Locale.ROOT));
            }

            Set<String> seen = new HashSet<>();
            List<C> kids = children.get(c.cid);
            if (kids != null) for (C k : kids) {
                if (isMe(k.uid) || !seen.add("c" + k.cid)) continue;
                R r = new R();
                r.kind = R.REPLY;
                r.user = userOf(k.uid, null);
                r.text = k.text;
                r.cid = k.cid;
                r.time = k.time;
                r.friend = isFriend(r.user, c.tags);
                it.reactions.add(r);
            }
            Set<String> likes = s.commentLikes.get(c.cid);
            if (likes != null) for (String uid : likes) {
                if (isMe(uid) || !seen.add("l" + uid)) continue;
                R r = new R();
                r.kind = R.LIKE;
                r.user = userOf(uid, null);
                r.friend = isFriend(r.user, c.tags);
                it.reactions.add(r);
            }
            // a tagged friend answering with a separate comment that tags me back
            List<C> same = c.aweme != null ? byAweme.get(c.aweme) : null;
            if (same != null) for (C k : same) {
                if (k == c || isMe(k.uid) || k.time < c.time || seen.contains("c" + k.cid)) continue;
                U ku = userOf(k.uid, null);
                if (!isFriend(ku, c.tags) || !taggedMe(k.text, meName)) continue;
                seen.add("c" + k.cid);
                R r = new R();
                r.kind = R.VIDEO_COMMENT;
                r.user = ku;
                r.text = k.text;
                r.cid = k.cid;
                r.time = k.time;
                r.friend = true;
                it.reactions.add(r);
            }
            sortReactions(it);
            comments.add(it);
        }

        // ---- my reposts
        Map<String, Item> mine = new HashMap<>();
        for (Ev ev : s.events) {
            boolean byMe = false;
            for (String uid : ev.uids) if (isMe(uid)) byMe = true;
            if (ev.kind != Ev.REPOST || !(byMe || ev.uids.isEmpty())) continue;
            Item it = repostItem(mine, ev.aweme);
            if (ev.text != null && (it.text == null || it.text.isEmpty())) it.text = ev.text;
            it.time = Math.max(it.time, ev.time);
        }
        for (A a : s.awemes.values()) {
            for (String uid : a.reposters) if (isMe(uid)) { repostItem(mine, a.id); break; }
        }
        for (Item it : mine.values()) {
            Scanner.addRegexTags(it.text, it.tags);
            for (Tag t : it.tags) if (t.name != null) friendNames.add(t.name.toLowerCase(Locale.ROOT));
        }
        for (Ev ev : s.events) {
            Item it = mine.get(ev.aweme);
            if (it == null || ev.kind == Ev.REPOST) continue;
            for (String uid : ev.uids) {
                if (isMe(uid)) continue;
                R r = new R();
                r.kind = ev.kind == Ev.LIKE ? R.LIKE : R.REPLY;
                r.user = userOf(uid, null);
                r.time = ev.time;
                if (ev.cid != null) {
                    C cc = s.comments.get(ev.cid);
                    r.cid = ev.cid;
                    r.text = cc != null ? cc.text : null;
                }
                r.friend = isFriend(r.user, it.tags) || isAnyFriend(r.user);
                it.reactions.add(r);
            }
        }
        // friends commenting under a video I reposted
        for (Item it : mine.values()) {
            List<C> same = byAweme.get(it.aweme);
            if (same == null) continue;
            Set<String> have = new HashSet<>();
            for (R r : it.reactions) if (r.cid != null) have.add(r.cid);
            for (C k : same) {
                if (isMe(k.uid) || have.contains(k.cid)) continue;
                U ku = userOf(k.uid, null);
                if (!(isFriend(ku, it.tags) || isAnyFriend(ku))) continue;
                R r = new R();
                r.kind = R.VIDEO_COMMENT;
                r.user = ku;
                r.text = k.text;
                r.cid = k.cid;
                r.time = k.time;
                r.friend = true;
                it.reactions.add(r);
            }
            sortReactions(it);
        }
        List<Item> tagged = new ArrayList<>();
        for (Item it : mine.values()) if (!it.tags.isEmpty() || it.friendReactions() > 0) tagged.add(it);
        if (tagged.isEmpty() && !mine.isEmpty()) {
            tagged.addAll(mine.values());
            repostsUnfiltered = true;
        }
        reposts.addAll(tagged);

        Collections.sort(comments, (a, b) -> Long.compare(b.time, a.time));
        Collections.sort(reposts, (a, b) -> Long.compare(b.time, a.time));

        // ---- header numbers
        Set<String> tagged2 = new HashSet<>(), replied = new HashSet<>(), liked = new HashSet<>();
        for (List<Item> l : twoLists()) for (Item it : l) {
            for (Tag t : it.tags) tagged2.add(t.uid != null ? t.uid : ("n" + (t.name == null ? "" : t.name.toLowerCase(Locale.ROOT))));
            for (R r : it.reactions) {
                if (!r.friend || r.user == null) continue;
                String k = r.user.uid != null ? r.user.uid : r.user.label();
                if (r.kind == R.LIKE) liked.add(k); else replied.add(k);
            }
        }
        friendsTagged = tagged2.size();
        friendsReplied = replied.size();
        friendsLiked = liked.size();
    }

    private List<List<Item>> twoLists() {
        List<List<Item>> l = new ArrayList<>();
        l.add(comments);
        l.add(reposts);
        return l;
    }

    private Item repostItem(Map<String, Item> mine, String aweme) {
        Item it = mine.get(aweme);
        if (it == null) {
            it = new Item();
            it.type = Item.REPOST;
            it.aweme = aweme;
            it.video = s.awemes.get(aweme);
            it.author = userOf(meUid, meName);
            mine.put(aweme, it);
        }
        return it;
    }

    private static void sortReactions(Item it) {
        Collections.sort(it.reactions, (a, b) -> {
            if (a.friend != b.friend) return a.friend ? -1 : 1;
            if (a.kind != b.kind) return a.kind == R.LIKE ? 1 : (b.kind == R.LIKE ? -1 : 0);
            return Long.compare(a.time, b.time);
        });
    }

    private static <K, V> void add(Map<K, List<V>> m, K k, V v) {
        List<V> l = m.get(k);
        if (l == null) m.put(k, l = new ArrayList<>());
        if (!l.contains(v)) l.add(v);
    }
}
