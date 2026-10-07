// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.content.Context
import android.content.SharedPreferences

/** The companion's on/off options. Read where they apply, so a change takes effect at once. */
enum class CompanionPref(private val key: String, private val default: Boolean) {
    NOTIFICATIONS("opt_notifications", true),
    CALLS("opt_calls", true),
    HIDE_CONTENT("opt_hide_content", false),
    ONLY_WHEN_LOCKED("opt_only_locked", false),
    RESPECT_DND("opt_respect_dnd", true),
    INCLUDE_SILENT("opt_silent", false),
    INCLUDE_ONGOING("opt_ongoing", false),
    MEDIA("opt_media", true),
    LOW_BATTERY_ALERT("opt_low_battery", true),

    /** Applied on the watch; the phone sends it over whenever it changes. */
    DISCONNECT_ALERT("opt_disconnect_alert", false),
    ;

    fun get(context: Context) = CompanionPrefs.of(context).getBoolean(key, default)

    fun set(context: Context, value: Boolean) = CompanionPrefs.of(context).edit().putBoolean(key, value).apply()
}

object CompanionPrefs {
    private const val MUTED = "muted"
    private const val SEEN = "seen"

    fun of(context: Context): SharedPreferences = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    /** Apps whose notifications stay on the phone. */
    fun mutedApps(context: Context): Set<String> = HashSet(of(context).getStringSet(MUTED, emptySet())!!)

    fun setMutedApps(context: Context, apps: Set<String>) = of(context).edit().putStringSet(MUTED, HashSet(apps)).apply()

    /** Apps that have posted something forwardable; listed even when they have no launcher icon. */
    fun seenApps(context: Context): Set<String> = HashSet(of(context).getStringSet(SEEN, emptySet())!!)

    fun noteSeen(context: Context, pkg: String) {
        val seen = of(context).getStringSet(SEEN, emptySet())!!
        if (pkg !in seen) of(context).edit().putStringSet(SEEN, HashSet(seen).apply { add(pkg) }).apply()
    }
}
