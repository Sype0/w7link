package io.github.sype0.w7link.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var confirm: Button
    private lateinit var cancel: Button
    private lateinit var phone: TextView
    private lateinit var track: TextView
    private lateinit var transport: View
    private lateinit var volume: View
    private lateinit var find: Button
    private lateinit var health: TextView
    private lateinit var measure: Button
    private lateinit var background: Button
    private lateinit var unpair: Button
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep everything inside the round screen's safe area.
        val side = (resources.displayMetrics.widthPixels * 0.12f).toInt()
        val ends = (resources.displayMetrics.heightPixels * 0.2f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(side, ends, side, ends)
        }
        status = column.text(16f)
        code = column.text(30f)
        confirm = column.button("Telefondaki kod aynı") { LinkService.instance?.confirmPairing() }
        cancel = column.button("Vazgeç") { LinkService.instance?.cancelPairing() }
        phone = column.text(14f)

        track = column.text(14f)
        transport = column.mediaRow("⏮" to "prev", "⏯" to "play_pause", "⏭" to "next")
        volume = column.mediaRow("Ses −" to "vol_down", "Ses +" to "vol_up")

        find = column.button("Telefonumu bul") {
            LinkService.instance?.let { it.findPhone(!it.findingPhone) }
        }

        health = column.text(14f)
        measure = column.button("Şimdi ölç") { LinkService.instance?.sample() }
        background = column.button("Arka planda nabız izni ver") {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS_BACKGROUND), 1)
        }
        unpair = column.button("Eşleşmeyi sıfırla") { LinkService.instance?.unpair() }

        setContentView(ScrollView(this).apply {
            addView(column)
            // Lets the bezel scroll the page.
            isFocusableInTouchMode = true
            requestFocus()
        })
    }

    override fun onResume() {
        super.onResume()
        LinkService.uiListener = { refresh() }
        val missing = WANTED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !asked) {
            asked = true
            requestPermissions(missing.toTypedArray(), 0)
        }
        LinkService.start(this)
        refresh()
    }

    override fun onPause() {
        LinkService.uiListener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        // Restarting lets the service pick up the health type now that it is allowed to.
        LinkService.start(this)
        refresh()
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

        val connected = state == LinkService.State.CONNECTED
        phone.show(connected && link != null && link.phoneBattery >= 0)
        if (link != null) phone.text = "Telefon pili: %${link.phoneBattery}" + if (link.phoneCharging) " ⚡" else ""

        val media = link?.media
        track.show(connected)
        track.text = when {
            media == null -> "Çalan bir şey yok"
            else -> listOf(media.optString("title"), media.optString("artist")).filter { it.isNotEmpty() }
                .joinToString("\n").ifEmpty { "Medya" } +
                "\nSes ${media.optInt("vol")}/${media.optInt("volMax")}"
        }
        transport.show(connected)
        volume.show(connected)

        find.show(connected)
        find.text = if (link?.findingPhone == true) "Telefonu sustur" else "Telefonumu bul"

        val heart = link?.lastHeartRate ?: 0
        health.text = when {
            link?.isSampling == true -> "Ölçülüyor…"
            else -> (if (heart > 0) "♥ $heart bpm\n" else "") + "${link?.stepsToday ?: 0} adım"
        }
        measure.show(link != null)
        background.show(
            checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BODY_SENSORS_BACKGROUND) != PackageManager.PERMISSION_GRANTED
        )
        unpair.show(link?.paired == true)
    }

    private fun LinearLayout.text(size: Float) = TextView(context).also {
        it.textSize = size
        it.gravity = Gravity.CENTER
        it.setPadding(0, 8, 0, 8)
        addView(it)
    }

    private fun LinearLayout.button(label: String, onClick: () -> Unit) = Button(context).also {
        it.text = label
        it.isAllCaps = false
        it.setOnClickListener { onClick() }
        addView(it, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** A row of equal-width buttons, each sending one media command to the phone. */
    private fun LinearLayout.mediaRow(vararg buttons: Pair<String, String>): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, command) in buttons) {
            val button = Button(context).apply {
                text = label
                isAllCaps = false
                setOnClickListener { LinkService.instance?.mediaCommand(command) }
            }
            row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(row, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return row
    }

    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private companion object {
        val WANTED = listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
    }
}
