package com.krocotovski.mentions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain data holders shared by the scanner, the builder and the UI. */
final class Model {
    private Model() {}

    /** A TikTok user as seen anywhere in the local data. */
    static final class U {
        String uid, name, nick, avatar;

        String label() {
            if (name != null && !name.isEmpty()) return "@" + name;
            if (nick != null && !nick.isEmpty()) return nick;
            return uid != null ? "id" + uid : "?";
        }

        String title() {
            if (nick != null && !nick.isEmpty()) return nick;
            return label();
        }
    }

    /** A mention inside a comment/repost caption: user id (if known) and the visible name. */
    static final class Tag {
        final String uid, name;
        Tag(String uid, String name) { this.uid = uid; this.name = name; }
    }

    /** A comment object (cid, text, author, video, parent). */
    static final class C {
        String cid, text, aweme, replyId, replyTo, uid;
        long time;
        int diggs, replies;
        final List<Tag> tags = new ArrayList<>();
    }

    /** A video (aweme). */
    static final class A {
        String id, desc, cover, authorUid;
        final Set<String> reposters = new HashSet<>();
    }

    /** Something that happened around a repost: the repost itself, a like on it, a comment. */
    static final class Ev {
        static final int REPOST = 0, LIKE = 1, COMMENT = 2;
        String aweme, text, cid, path;
        int kind;
        long time;
        final Set<String> uids = new HashSet<>();
    }

    /** Everything the scanner found. */
    static final class Store {
        final Map<String, U> users = new HashMap<>();
        final Map<String, C> comments = new HashMap<>();
        final Map<String, A> awemes = new HashMap<>();
        final Map<String, Set<String>> commentLikes = new HashMap<>();
        final List<String[]> pairs = new ArrayList<>();
        final List<Ev> events = new ArrayList<>();
        final Map<String, Integer> meVotes = new HashMap<>();
        final StringBuilder diag = new StringBuilder();
        int dbs, tables, rows, failedDbs;
        long tookMs;

        void vote(String uid, int w) {
            if (uid == null) return;
            Integer v = meVotes.get(uid);
            meVotes.put(uid, (v == null ? 0 : v) + w);
        }

        String guessMe() {
            String best = null;
            int bv = 0;
            for (Map.Entry<String, Integer> e : meVotes.entrySet()) {
                if (e.getValue() > bv) { bv = e.getValue(); best = e.getKey(); }
            }
            return best;
        }

        U user(String uid) {
            return uid == null ? null : users.get(uid);
        }
    }

    /** One reaction under an item. */
    static final class R {
        static final int REPLY = 0, LIKE = 1, VIDEO_COMMENT = 2;
        int kind;
        U user;
        String text, cid;
        long time;
        boolean friend;
    }

    /** One row of the list: my comment or my repost. */
    static final class Item {
        static final int COMMENT = 0, REPOST = 1;
        int type;
        String aweme, cid, text;
        long time;
        int diggs;
        U author;
        A video;
        final List<Tag> tags = new ArrayList<>();
        final List<R> reactions = new ArrayList<>();

        int friendReactions() {
            int n = 0;
            for (R r : reactions) if (r.friend) n++;
            return n;
        }
    }
}
