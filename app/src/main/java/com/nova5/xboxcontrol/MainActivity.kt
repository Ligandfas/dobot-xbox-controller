package com.nova5.xboxcontrol

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private val pad = PadState()
    private var teleop: Teleop? = null

    private lateinit var ipEdit: EditText
    private lateinit var speedEdit: EditText
    private lateinit var scriptAEdit: EditText
    private lateinit var scriptBEdit: EditText
    private lateinit var j5Check: Switch
    private lateinit var j3Check: Switch
    private lateinit var autoSwitch: Switch
    private lateinit var modeButtons: Map<JogMode, TextView>
    private lateinit var modeHint: TextView
    private lateinit var userIdxEdit: EditText
    private lateinit var toolIdxEdit: EditText
    private var jogMode = JogMode.BASE
    private lateinit var connectBtn: Button
    private lateinit var padStatus: TextView
    private lateinit var robotStatus: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val logBuf = ArrayDeque<String>()

    // Auto-detect / auto-connect
    private var discovery: Discovery? = null
    private var discoveryGen = 0
    private var isResumedNow = false
    private var userStopped = false // user pressed Disconnect: don't reconnect until asked

    private val prefs get() = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    // ------------------------------------------------------------- palette
    private object C {
        val BG = Color.parseColor("#0E1116")
        val SURFACE = Color.parseColor("#171B23")
        val SURFACE_HIGH = Color.parseColor("#20252F")
        val CONSOLE = Color.parseColor("#0A0C10")
        val OUTLINE = Color.parseColor("#2C3240")
        val TEXT = Color.parseColor("#E8ECF4")
        val MUTED = Color.parseColor("#8D96A8")
        val ACCENT = Color.parseColor("#4D86FF")
        val GREEN = Color.parseColor("#34C77B")
        val RED = Color.parseColor("#E5484D")
        val AMBER = Color.parseColor("#F5A524")
        val XBOX_A = Color.parseColor("#34C77B")
        val XBOX_X = Color.parseColor("#4D86FF")
    }

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    // ------------------------------------------------------- UI primitives
    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radiusDp)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun withRipple(bg: Drawable, rippleColor: Int = Color.argb(60, 255, 255, 255)) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null)

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dp(8), dp(8))
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                   top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun text(value: String, size: Float, color: Int = C.TEXT, face: Typeface? = null) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (face != null) typeface = face
        }

    private fun card(title: String, parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 20f, C.OUTLINE)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        c.addView(text(title.uppercase(), 11.5f, C.MUTED, medium).apply { letterSpacing = 0.12f })
        parent.addView(c, lp(top = 14))
        return c
    }

    private fun edit(value: String, hint: String, number: Boolean = false) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setSingleLine()
        textSize = 16f
        setTextColor(C.TEXT)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = rounded(C.SURFACE_HIGH, 12f, C.OUTLINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    /** Label above an input. Returns the input for chaining. */
    private fun field(parent: LinearLayout, label: String, input: View, badge: Pair<String, Int>? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (badge != null) {
            row.addView(text(badge.first, 11f, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(badge.second) }
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) })
        }
        row.addView(text(label, 13f, C.MUTED))
        parent.addView(row, lp(top = 14))
        parent.addView(input, lp(top = 6))
        return input
    }

    private fun toggle(parent: LinearLayout, title: String, subtitle: String, checked: Boolean): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f))
        col.addView(text(subtitle, 12.5f, C.MUTED))
        row.addView(col, lp(w = 0, weight = 1f))
        val sw = Switch(this).apply { isChecked = checked }
        row.addView(sw, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row, lp(top = 14))
        return sw
    }

    private fun Button.style(fill: Int, textColor: Int, outline: Int? = null) {
        background = withRipple(rounded(fill, 16f, outline))
        setTextColor(textColor)
        isAllCaps = false
        textSize = 15f
        typeface = medium
        stateListAnimator = null
        minHeight = dp(52)
    }

    private fun chip(): TextView = text("", 12.5f, C.TEXT, medium).apply {
        gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = dp(8)
        setPadding(dp(12), dp(8), dp(14), dp(8))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun setChip(chip: TextView, label: String, color: Int) {
        chip.text = label
        chip.setCompoundDrawablesWithIntrinsicBounds(dot(color), null, null, null)
        chip.background = rounded(withAlpha(color, 30), 100f, withAlpha(color, 90))
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo) },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Nova 5 Control", 22f, C.TEXT, Typeface.DEFAULT_BOLD))
        titles.addView(text("Dobot teleoperation · Xbox controller", 13f, C.MUTED))
        header.addView(titles)
        root.addView(header)

        // Status chips
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        robotStatus = chip()
        padStatus = chip()
        chips.addView(robotStatus, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        chips.addView(padStatus, lp(w = 0, weight = 1f))
        root.addView(chips, lp(top = 18))
        setRobotState("Robot offline", C.MUTED)

        // Log console
        val logCard = card("Activity", root)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.parseColor("#A9B6CE"))
            setLineSpacing(0f, 1.15f)
        }
        logScroll = ScrollView(this).apply {
            background = rounded(C.CONSOLE, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(logView)
        }
        logCard.addView(logScroll, lp(h = dp(170), top = 12))

        // Connection
        val conn = card("Connection", root)
        ipEdit = edit(prefs.getString("ip", "192.168.5.1") ?: "192.168.5.1", "192.168.5.1")
        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ipRow.addView(ipEdit, lp(w = 0, weight = 1f))
        ipRow.addView(Button(this).apply {
            text = "Find"
            style(withAlpha(C.ACCENT, 38), C.ACCENT, withAlpha(C.ACCENT, 110))
            minHeight = dp(46); minimumHeight = dp(46); minWidth = dp(76); minimumWidth = dp(76)
            setOnClickListener { userStopped = false; startDiscovery(repeat = false) }
        }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        field(conn, "Robot IP address", ipRow)
        speedEdit = field(conn, "Start speed % (1-100) · keep it low",
            edit(prefs.getInt("speed", 15).toString(), "15", number = true)) as EditText
        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { if (teleop == null) connect() else disconnect() }
        }
        autoSwitch = toggle(conn, "Auto-connect", "Find the robot on this network and connect",
            prefs.getBoolean("auto", true))
        autoSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("auto", on).apply()
            if (on) { userStopped = false; if (isResumedNow && teleop == null) startDiscovery(repeat = true) }
            else if (discovery != null) { stopDiscovery(); setRobotState("Robot offline", C.MUTED) }
        }
        styleConnect(connected = false)
        conn.addView(connectBtn, lp(top = 18))

        // Robot power
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Enable"
            style(withAlpha(C.GREEN, 38), C.GREEN, withAlpha(C.GREEN, 110))
            setOnClickListener { teleop?.requestEnable() }
        }, lp(w = 0, weight = 1f).apply { rightMargin = dp(10) })
        row.addView(Button(this).apply {
            text = "STOP · Disable"
            style(C.RED, Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { teleop?.requestDisable() }
        }, lp(w = 0, weight = 1.4f))
        root.addView(row, lp(top = 14))

        // Jog mode
        val jog = card("Jog mode", root)
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.SURFACE_HIGH, 14f, C.OUTLINE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        modeButtons = JogMode.values().associateWith { m ->
            text(m.label, 14f, C.MUTED, medium).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setOnClickListener { setJogMode(m, fromPad = false) }
                seg.addView(this, lp(w = 0, weight = 1f))
            }
        }
        jog.addView(seg, lp(top = 12))
        modeHint = text("", 12.5f, C.MUTED)
        jog.addView(modeHint, lp(top = 10))
        val idxRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val userCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        userIdxEdit = edit(prefs.getInt("userIdx", 0).toString(), "0", number = true)
        field(userCol, "User frame (0-9)", userIdxEdit)
        val toolCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolIdxEdit = edit(prefs.getInt("toolIdx", 0).toString(), "0", number = true)
        field(toolCol, "Tool (0-9)", toolIdxEdit)
        idxRow.addView(userCol, lp(w = 0, weight = 1f).apply { rightMargin = dp(10) })
        idxRow.addView(toolCol, lp(w = 0, weight = 1f))
        jog.addView(idxRow)
        val idxWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { applyIndices() }
        }
        userIdxEdit.addTextChangedListener(idxWatcher)
        toolIdxEdit.addTextChangedListener(idxWatcher)
        jogMode = try { JogMode.valueOf(prefs.getString("jogMode", "BASE") ?: "BASE") } catch (_: Exception) { JogMode.BASE }
        renderJogMode()

        // Scripts
        val scripts = card("Scripts", root)
        scriptAEdit = field(scripts, "A button runs",
            edit(prefs.getString("scriptA", "script1") ?: "script1", "script1"), "A" to C.XBOX_A) as EditText
        scriptBEdit = field(scripts, "X button runs",
            edit(prefs.getString("scriptB", "script2") ?: "script2", "script2"), "X" to C.XBOX_X) as EditText

        // Options
        val opts = card("Options", root)
        j5Check = toggle(opts, "Invert J5 triggers", "RT = J5-, LT = J5+", prefs.getBoolean("j5Invert", true))
        j3Check = toggle(opts, "Invert D-pad J3", "Right = J3-, left = J3+", prefs.getBoolean("j3Invert", false))

        // Controls reference
        val ctl = card("Controls", root)
        listOf(
            "Start" to "Enable robot", "Back" to "Disable robot",
            "L stick" to "X / Y  ·  J1 / J2", "R stick" to "Z / Rz  ·  J3 / J4",
            "LT  RT" to "Rx  ·  J5", "LB  RB" to "Ry  ·  J6",
            "D-pad ◀ ▶" to "J3", "D-pad ▲ ▼" to "Speed ±10%",
            "Y" to "Next jog mode", "B" to "Clear error",
            "A  X" to "Run scripts", "L3  /  R3" to "Stop  /  Pause-continue script",
        ).forEach { (key, action) ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.addView(text(key, 12f, C.TEXT, medium).apply {
                gravity = Gravity.CENTER
                background = rounded(C.SURFACE_HIGH, 8f, C.OUTLINE)
                setPadding(dp(6), dp(4), dp(6), dp(4))
            }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
            r.addView(text(action, 13.5f, C.MUTED))
            ctl.addView(r, lp(top = 10))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.BG)
            isFillViewport = true
            addView(root)
        })
        appendLog("Ready. Auto-connect finds the robot, or enter its IP and tap Connect.")
    }

    private fun idx(e: EditText) = e.text.toString().toIntOrNull()?.coerceIn(0, 9) ?: 0

    private fun applyIndices() {
        val u = idx(userIdxEdit); val t = idx(toolIdxEdit)
        prefs.edit().putInt("userIdx", u).putInt("toolIdx", t).apply()
        teleop?.let { it.userIndex = u; it.toolIndex = t }
    }

    private fun setJogMode(m: JogMode, fromPad: Boolean) {
        if (m == jogMode && !fromPad) return
        jogMode = m
        prefs.edit().putString("jogMode", m.name).apply()
        if (!fromPad) {
            teleop?.mode = m
            appendLog("Mode: ${m.label}")
        }
        renderJogMode()
    }

    private fun renderJogMode() {
        modeButtons.forEach { (m, v) ->
            val on = m == jogMode
            v.background = if (on) rounded(C.ACCENT, 10f) else null
            v.setTextColor(if (on) Color.WHITE else C.MUTED)
        }
        modeHint.text = when (jogMode) {
            JogMode.BASE -> "Sticks move the TCP along the base / user frame axes."
            JogMode.TOOL -> "Sticks move the TCP along its own tool axes."
            JogMode.JOINT -> "Sticks drive individual joints J1-J6."
        }
    }

    private fun styleConnect(connected: Boolean) {
        if (connected) {
            connectBtn.text = "Disconnect"
            connectBtn.style(Color.TRANSPARENT, C.TEXT, C.OUTLINE)
        } else {
            connectBtn.text = "Connect"
            connectBtn.style(C.ACCENT, Color.WHITE)
        }
    }

    private fun setRobotState(label: String, color: Int) = setChip(robotStatus, label, color)

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

        stopDiscovery()
        userStopped = false
        pad.reset()
        styleConnect(connected = true)
        setRobotState("Connecting…", C.AMBER)
        teleop = Teleop(
            ctx = applicationContext, ip = ip, scriptA = a, scriptB = b,
            j5Invert = j5Check.isChecked, j3Invert = j3Check.isChecked,
            startSpeed = speed, pad = pad,
            log = ::postLog,
            onEnded = {
                runOnUiThread {
                    teleop = null
                    styleConnect(connected = false)
                    setRobotState("Robot offline", C.MUTED)
                    // Connection dropped (not a user Disconnect / app pause): look for the robot again.
                    if (isResumedNow && autoSwitch.isChecked && !userStopped) startDiscovery(repeat = true, delayMs = 2000)
                }
            },
            onConnected = { runOnUiThread { setRobotState("Robot $ip", C.GREEN) } },
            initialMode = jogMode,
            onModeChanged = { m -> runOnUiThread { setJogMode(m, fromPad = true) } },
        ).also {
            it.userIndex = idx(userIdxEdit)
            it.toolIndex = idx(toolIdxEdit)
            it.start()
        }
    }

    private fun disconnect() {
        userStopped = true
        teleop?.shutdown()
    }

    // ------------------------------------------------------- auto-detect
    private fun startDiscovery(repeat: Boolean, delayMs: Long = 0) {
        stopDiscovery()
        if (teleop != null) return
        val gen = ++discoveryGen
        setRobotState("Searching…", C.AMBER)
        val preferred = (listOf(ipEdit.text.toString().trim(), prefs.getString("ip", "") ?: "") + DOBOT_DEFAULT_IPS)
            .filter { it.isNotEmpty() }.distinct()
        discovery = Discovery(applicationContext, preferred, repeat, delayMs, ::postLog,
            onFound = { ip ->
                runOnUiThread {
                    if (gen == discoveryGen && teleop == null) {
                        discovery = null
                        appendLog("Robot found at $ip")
                        ipEdit.setText(ip)
                        connect()
                    }
                }
            },
            onNotFound = {
                runOnUiThread {
                    if (gen == discoveryGen) {
                        discovery = null
                        appendLog("No robot found. Check the network and that TCP/IP mode is on.")
                        setRobotState("Robot not found", C.RED)
                    }
                }
            },
        ).also { it.start() }
    }

    private fun stopDiscovery() {
        discoveryGen++
        discovery?.cancel()
        discovery = null
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
        if (name != null) setChip(padStatus, name, C.GREEN)
        else setChip(padStatus, "No controller", C.RED)
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
        refreshPadStatus()
        isResumedNow = true
        userStopped = false
        if (autoSwitch.isChecked && teleop == null) startDiscovery(repeat = true)
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        isResumedNow = false
        stopDiscovery()
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
