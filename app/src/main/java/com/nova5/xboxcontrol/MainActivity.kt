package com.nova5.xboxcontrol

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Bundle
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private val pad = PadState()
    private var teleop: Teleop? = null

    private lateinit var ipEdit: EditText
    private lateinit var speedEdit: EditText
    private lateinit var scriptAEdit: EditText
    private lateinit var scriptBEdit: EditText
    private lateinit var j5Check: CheckBox
    private lateinit var j3Check: CheckBox
    private lateinit var connectBtn: Button
    private lateinit var padStatus: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val logBuf = ArrayDeque<String>()

    private val prefs get() = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    // ------------------------------------------------------------------ UI
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(10), 0, 0)
    }

    private fun edit(value: String, number: Boolean = false) = EditText(this).apply {
        setText(value)
        setSingleLine()
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
        else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        // Don't let the gamepad steal focus into text fields.
        isFocusable = true
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Nova 5 Xbox Control"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        padStatus = TextView(this).apply { textSize = 13f }
        root.addView(padStatus)

        root.addView(label("Robot IP"))
        ipEdit = edit(prefs.getString("ip", "192.168.5.1") ?: "192.168.5.1")
        root.addView(ipEdit)

        root.addView(label("Start speed % (1-100) - keep it low"))
        speedEdit = edit(prefs.getInt("speed", 15).toString(), number = true)
        root.addView(speedEdit)

        root.addView(label("Script for A button"))
        scriptAEdit = edit(prefs.getString("scriptA", "script1") ?: "script1")
        root.addView(scriptAEdit)

        root.addView(label("Script for X button"))
        scriptBEdit = edit(prefs.getString("scriptB", "script2") ?: "script2")
        root.addView(scriptBEdit)

        j5Check = CheckBox(this).apply {
            text = "Invert J5 triggers (RT = J5-, LT = J5+)"
            isChecked = prefs.getBoolean("j5Invert", true)
        }
        root.addView(j5Check)
        j3Check = CheckBox(this).apply {
            text = "Invert D-pad J3 (right = J3-, left = J3+)"
            isChecked = prefs.getBoolean("j3Invert", false)
        }
        root.addView(j3Check)

        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { if (teleop == null) connect() else disconnect() }
        }
        root.addView(connectBtn)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Enable"
            setOnClickListener { teleop?.requestEnable() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "STOP / DISABLE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(200, 30, 30))
            setOnClickListener { teleop?.requestDisable() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))

        setContentView(ScrollView(this).apply { addView(root) })
        appendLog("Controls: Start=enable  Back=disable  Y=Cartesian/Joint  B=clear error")
        appendLog("A/X=run scripts  L3=stop script  R3=pause/continue  D-pad up/down=speed")
    }

    private fun appendLog(line: String) {
        logBuf.addLast(line)
        while (logBuf.size > 200) logBuf.removeFirst()
        logView.text = logBuf.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun postLog(line: String) = runOnUiThread { appendLog(line) }

    // ------------------------------------------------------------ connection
    private fun connect() {
        val ip = ipEdit.text.toString().trim()
        if (ip.isEmpty()) { appendLog("Enter the robot IP first."); return }
        val speed = speedEdit.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 15
        val a = scriptAEdit.text.toString().trim()
        val b = scriptBEdit.text.toString().trim()
        prefs.edit()
            .putString("ip", ip).putInt("speed", speed)
            .putString("scriptA", a).putString("scriptB", b)
            .putBoolean("j5Invert", j5Check.isChecked)
            .putBoolean("j3Invert", j3Check.isChecked)
            .apply()

        pad.reset()
        connectBtn.text = "Disconnect"
        teleop = Teleop(
            ctx = applicationContext, ip = ip, scriptA = a, scriptB = b,
            j5Invert = j5Check.isChecked, j3Invert = j3Check.isChecked,
            startSpeed = speed, pad = pad,
            log = ::postLog,
            onEnded = { runOnUiThread { teleop = null; connectBtn.text = "Connect" } },
        ).also { it.start() }
    }

    private fun disconnect() {
        teleop?.shutdown()
    }

    // ----------------------------------------------------- controller input
    private fun has(src: Int, mask: Int) = (src and mask) == mask

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (has(ev.source, InputDevice.SOURCE_JOYSTICK) && ev.action == MotionEvent.ACTION_MOVE) {
            pad.lx = ev.getAxisValue(MotionEvent.AXIS_X)
            pad.ly = ev.getAxisValue(MotionEvent.AXIS_Y)
            // Right stick is Z/RZ on most Xbox pads, RX/RY on some others.
            val z = ev.getAxisValue(MotionEvent.AXIS_Z)
            val rxAlt = ev.getAxisValue(MotionEvent.AXIS_RX)
            val rz = ev.getAxisValue(MotionEvent.AXIS_RZ)
            val ryAlt = ev.getAxisValue(MotionEvent.AXIS_RY)
            pad.rx = if (abs(z) >= abs(rxAlt)) z else rxAlt
            pad.ry = if (abs(rz) >= abs(ryAlt)) rz else ryAlt
            pad.lt = maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
            pad.rt = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
            pad.hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            pad.hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val fromPad = has(ev.source, InputDevice.SOURCE_GAMEPAD) ||
                has(ev.source, InputDevice.SOURCE_JOYSTICK)
        if (!fromPad) return super.dispatchKeyEvent(ev) // phone Back button etc.

        // Many Xbox pads report the View/Back button as KEYCODE_BACK.
        val code = if (ev.keyCode == KeyEvent.KEYCODE_BACK) KeyEvent.KEYCODE_BUTTON_SELECT else ev.keyCode
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> pad.keys.add(code)
            KeyEvent.ACTION_UP -> pad.keys.remove(code)
        }
        return true // consume so buttons don't click on-screen widgets
    }

    // ------------------------------------------------- safety / lifecycle
    private fun refreshPadStatus() {
        val name = InputDevice.getDeviceIds().asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { has(it.sources, InputDevice.SOURCE_GAMEPAD) || has(it.sources, InputDevice.SOURCE_JOYSTICK) }
            ?.name
        padStatus.text = if (name != null) "Controller: $name" else "No controller detected - pair/plug in your Xbox pad"
        padStatus.setTextColor(if (name != null) Color.rgb(0, 120, 0) else Color.rgb(180, 0, 0))
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
        refreshPadStatus()
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        // Never keep driving the robot while the app is in the background.
        pad.reset()
        teleop?.shutdown()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshPadStatus()
    override fun onInputDeviceChanged(deviceId: Int) = refreshPadStatus()
    override fun onInputDeviceRemoved(deviceId: Int) {
        pad.reset() // teleop loop sees neutral input and sends MoveJog()
        appendLog("Controller disconnected - jog stopped")
        refreshPadStatus()
    }
}
