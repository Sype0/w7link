// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Step counts pulled from the watch. Stays on the phone. Heart data lives in Heartline's own database. */
class HealthDb(context: Context) : SQLiteOpenHelper(context, "health.db", null, 1) {
    class Sample(val ts: Long, val hr: Int, val steps: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (ts INTEGER PRIMARY KEY, hr INTEGER NOT NULL, steps INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(ts: Long, hr: Int, steps: Int) {
        val values = ContentValues().apply {
            put("ts", ts)
            put("hr", hr)
            put("steps", steps)
        }
        writableDatabase.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun latest(limit: Int): List<Sample> {
        val out = ArrayList<Sample>()
        readableDatabase.rawQuery("SELECT ts, hr, steps FROM samples ORDER BY ts DESC LIMIT $limit", null).use {
            while (it.moveToNext()) out.add(Sample(it.getLong(0), it.getInt(1), it.getInt(2)))
        }
        return out
    }

    fun count(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
            it.moveToFirst()
            it.getLong(0)
        }

    fun exportCsv(out: Writer) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        out.write("time,steps_today\n")
        readableDatabase.rawQuery("SELECT ts, hr, steps FROM samples ORDER BY ts", null).use {
            while (it.moveToNext()) {
                out.write("${fmt.format(Date(it.getLong(0)))},${it.getInt(2)}\n")
            }
        }
    }
}
