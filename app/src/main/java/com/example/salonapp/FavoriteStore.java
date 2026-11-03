package com.example.salonapp;

import android.content.Context;
import java.util.HashSet;
import java.util.Set;

/** Locally saved favorites for product and service cards. */
public final class FavoriteStore {
    private static final String PREFS = "shop_favorites";
    private FavoriteStore() {}

    public static boolean contains(Context context, String collection, String itemId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(collection, java.util.Collections.emptySet()).contains(itemId);
    }

    public static boolean toggle(Context context, String collection, String itemId) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = new HashSet<>(prefs.getStringSet(collection, java.util.Collections.emptySet()));
        boolean favorite;
        if (saved.remove(itemId)) favorite = false;
        else { saved.add(itemId); favorite = true; }
        prefs.edit().putStringSet(collection, saved).apply();
        return favorite;
    }
}
