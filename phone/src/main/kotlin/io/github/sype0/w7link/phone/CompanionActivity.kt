// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CompanionActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var confirm: Button
    private lateinit var cancel: Button
    private lateinit var access: Button
    private lateinit var watch: TextView
    private lateinit var healthView: TextView
    private lateinit var ring: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = column.text(22f)
        code = column.text(40f)
        confirm = column.button("Saatteki kod aynı, eşleştir") { LinkService.instance?.confirmPairing() }
        cancel = column.button("Vazgeç") { LinkService.instance?.cancelPairing() }
        access = column.button("Bildirim erişimini aç") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        watch = column.text(16f)
        healthView = column.text(16f)
        ring = column.button("Saati çaldır") {
            LinkService.instance?.let { it.ringWatch(!it.ringingWatch) }
        }
        column.button("Hangi uygulamalar saate gitsin") { showFilter() }
        column.button("Adım verisini CSV olarak kaydet") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_TITLE, "w7link-adim.csv"),
                REQUEST_EXPORT,
            )
        }
        column.button("Eşleşmeyi sıfırla") {
            AlertDialog.Builder(this)
                .setMessage("Saatle eşleşme silinsin mi? Saatte de sıfırlamanız gerekir.")
                .setPositiveButton("Sil") { _, _ -> LinkService.instance?.unpair() }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        setContentView(ScrollView(this).apply {
            addView(column)
            // The app targets edge-to-edge; keep the content clear of the system bars.
            fitsSystemWindows = true
        })
    }

    override fun onResume() {
        super.onResume()
        LinkService.uiListener = { refresh() }
        if (LinkService.hasPermissions(this)) {
            LinkService.start(this)
        } else if (!asked) {
            asked = true
            val wanted = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(wanted.toTypedArray(), 0)
        }
        refresh()
    }

    private var asked = false

    override fun onPause() {
        LinkService.uiListener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        LinkService.start(this)
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        val link = LinkService.instance
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || uri == null || link == null) return
        try {
            contentResolver.openOutputStream(uri)!!.bufferedWriter().use { link.health.exportCsv(it) }
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        val link = LinkService.instance
        val state = link?.state ?: LinkService.State.NO_PERMISSION
        status.text = LinkService.statusText(state)

        val pairing = state == LinkService.State.PAIRING
        code.text = link?.pairCode.orEmpty()
        code.show(pairing)
        confirm.show(pairing)
        cancel.show(pairing)

        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        access.show(!listeners.contains("$packageName/"))

        val connected = state == LinkService.State.CONNECTED
        watch.text = when {
            link == null -> ""
            !link.paired -> "Saatte W7Link'i açın; iki ekranda aynı kod çıkınca onaylayın."
            !connected || link.watchBattery < 0 -> ""
            else -> "Saat pili: %${link.watchBattery}" + if (link.watchCharging) " (şarjda)" else ""
        }
        ring.show(connected)
        ring.text = if (link?.ringingWatch == true) "Saati sustur" else "Saati çaldır"

        healthView.text = link?.let {
            val time = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
            val rows = it.health.latest(8)
            if (rows.isEmpty()) {
                "Henüz adım verisi yok"
            } else {
                "Adımlar (${it.health.count()} kayıt)\n" + rows.joinToString("\n") { s ->
                    "${time.format(Date(s.ts))}  ${s.steps} adım"
                }
            }
        }.orEmpty()
    }

    private fun showFilter() {
        val link = LinkService.instance ?: return
        val apps = link.seenApps().map { pkg ->
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            label to pkg
        }.sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "Bildirim gönderen uygulamalar geldikçe burada listelenir", Toast.LENGTH_LONG).show()
            return
        }
        val muted = link.mutedApps().toMutableSet()
        AlertDialog.Builder(this)
            .setTitle("Saate gidecek uygulamalar")
            .setMultiChoiceItems(
                apps.map { it.first }.toTypedArray(),
                BooleanArray(apps.size) { apps[it].second !in muted },
            ) { _, which, checked ->
                if (checked) muted.remove(apps[which].second) else muted.add(apps[which].second)
            }
            .setPositiveButton("Tamam") { _, _ -> link.setMutedApps(muted) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun LinearLayout.text(size: Float) = TextView(context).also {
        it.textSize = size
        it.setPadding(0, 12, 0, 12)
        addView(it)
    }

    private fun LinearLayout.button(label: String, onClick: () -> Unit) = Button(context).also {
        it.text = label
        it.isAllCaps = false
        it.setOnClickListener { onClick() }
        addView(it)
    }

    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private companion object {
        const val REQUEST_EXPORT = 1
    }
}
