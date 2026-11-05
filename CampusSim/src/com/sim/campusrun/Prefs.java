package com.sim.campusrun;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地状态存档：退出/重进保留上次的一切（路线、参数、视角、搜索到的默认地点等） */
final class Prefs {

    private static final String FILE = "campus_state";
    private static final String KEY = "state_json";

    static void save(Context c, String json) {
        try {
            SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, json).apply();
        } catch (Throwable ignored) { }
    }

    static String load(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            return sp.getString(KEY, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private Prefs() { }
}
