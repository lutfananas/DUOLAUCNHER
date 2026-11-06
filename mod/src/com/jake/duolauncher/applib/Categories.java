package com.jake.duolauncher.applib;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Automatic app categories (Android category + package/label keywords) and install info. */
final class Categories {

    private static final Map<String, Integer> CATEGORY_CACHE = new HashMap<>();
    private static final Map<String, long[]> INSTALL_CACHE = new HashMap<>();

    private Categories() {
    }

    static int categoryOf(PackageManager pm, String pkg) {
        synchronized (CATEGORY_CACHE) {
            Integer c = CATEGORY_CACHE.get(pkg);
            if (c != null) return c;
        }
        int cat = ApplicationInfo.CATEGORY_UNDEFINED;
        try {
            cat = pm.getApplicationInfo(pkg, 0).category;
        } catch (Exception ignored) {
        }
        synchronized (CATEGORY_CACHE) {
            CATEGORY_CACHE.put(pkg, cat);
        }
        return cat;
    }

    /** Returns {firstInstallTime, isSystem(1/0)}. */
    static long[] installInfo(PackageManager pm, String pkg) {
        synchronized (INSTALL_CACHE) {
            long[] c = INSTALL_CACHE.get(pkg);
            if (c != null) return c;
        }
        long[] info = {0, 1};
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            boolean system = pi.applicationInfo != null
                    && (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            info = new long[]{pi.firstInstallTime, system ? 1 : 0};
        } catch (Exception ignored) {
        }
        synchronized (INSTALL_CACHE) {
            INSTALL_CACHE.put(pkg, info);
        }
        return info;
    }

    static String categoryName(int category, String pkg, String label) {
        String s = (pkg + " " + label).toLowerCase(Locale.ROOT);
        // Strong keyword matches first: many apps don't declare a category.
        if (has(s, "game", "games", "mobilelegends", "pubg", "freefire", "roblox", "minecraft",
                "supercell", "clashof", "candycrush", "genshin", "hoyoverse", "moonton", "garena", "chess")) {
            return "Games";
        }
        switch (category) {
            case ApplicationInfo.CATEGORY_GAME: return "Games";
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO: return "Entertainment";
            case ApplicationInfo.CATEGORY_IMAGE: return "Creativity";
            case ApplicationInfo.CATEGORY_NEWS: return "Information & Reading";
            case ApplicationInfo.CATEGORY_MAPS: return "Travel";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Productivity & Finance";
            case ApplicationInfo.CATEGORY_ACCESSIBILITY: return "Utilities";
            default: break;
        }
        if (has(s, "whatsapp", "telegram", "facebook", "instagram", "twitter", "messenger", "discord",
                "snapchat", "signal", "wechat", "messaging", "mms", "contacts", "dialer", "phone",
                "threads", "linkedin", "reddit", "jp.naver.line", "viber", "skype", "tinder", "bumble",
                "bluesky", "mastodon", "michat", "kakao")) {
            return "Social";
        }
        if (has(s, "youtube", "netflix", "spotify", "music", "video", "tiktok", "musically", "twitch",
                "primevideo", "disney", "hotstar", "player", "podcast", "vidio", "joox", "soundcloud",
                "deezer", "radio", "iqiyi", "wetv", "viu", "hbo", "tv", "anime", "webtoon", "movie")) {
            return "Entertainment";
        }
        if (has(s, "camera", "photo", "gallery", "snapseed", "lightroom", "capcut", "canva", "picsart",
                "editor", "vsco", "inshot", "remini", "draw", "sketch", "paint")) {
            return "Creativity";
        }
        if (has(s, "docs", "sheets", "slides", "drive", "office", "word", "excel", "powerpoint", "gmail",
                "mail", "outlook", "calendar", "notes", "keep", "bank", "pay", "wallet", "finance",
                "bca", "mandiri", "bri", "bni", "dana", "ovo", "gopay", "jenius", "notion", "todo",
                "task", "zoom", "meet", "teams", "slack", "trello", "pdf", "scanner", "money", "invest",
                "bibit", "ajaib", "stock", "crypto", "binance", "indodax", "paypal", "authenticator")) {
            return "Productivity & Finance";
        }
        if (has(s, "shop", "tokopedia", "shopee", "lazada", "amazon", "blibli", "bukalapak", "food",
                "alfamart", "indomaret", "supermarket", "alfagift", "store.", "ikea", "zalora", "temu", "aliexpress", "mcdonald",
                "kfc", "starbucks", "restaurant", "grocery")) {
            return "Shopping & Food";
        }
        if (has(s, "maps", "waze", "grab", "gojek", "uber", "traveloka", "tiket", "airbnb", "booking",
                "krl", "transport", "flight", "airline", "garuda", "lionair", "kai.", "trip", "travel",
                "agoda", "maxim", "indrive")) {
            return "Travel";
        }
        if (has(s, "health", "fit", "strava", "halodoc", "alodokter", "workout", "sleep", "medit",
                "calm", "headspace", "step", "peduli", "satusehat", "bpjs")) {
            return "Health & Fitness";
        }
        if (has(s, "news", "kindle", "books", "wiki", "reader", "detik", "kompas", "medium", "quora",
                "dictionary", "translate", "duolingo", "learn", "quizlet", "brainly",
                "ruangguru", "weather", "flipboard")) {
            return "Information & Reading";
        }
        if (has(s, "settings", "calculator", "clock", "files", "filemanager", "browser", "chrome",
                "firefox", "opera", "launcher", "vpn", "recorder", "compass", "flashlight", "torch",
                "vending", "googlequicksearchbox", "assistant", "gms", "security", "cleaner", "backup",
                "bluetooth", "wifi", "clipboard", "keyboard", "inputmethod", "android.", "samsung",
                "miui", "xiaomi", "oppo", "coloros", "vivo", "huawei", "sec.", "nothing",
                "motorola", "oneplus", "google.android")) {
            return "Utilities";
        }
        return AppLibraryView.OTHER;
    }

    private static boolean has(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

}
