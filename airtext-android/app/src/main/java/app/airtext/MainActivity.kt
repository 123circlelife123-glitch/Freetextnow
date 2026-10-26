package app.airtext

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.*
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class MainActivity : Activity() {
    private val service = "app.airtext.v1"
    private lateinit var conns: ConnectionsClient
    private lateinit var root: LinearLayout
    private lateinit var myName: String
    private val names = linkedMapOf<String, String>()
    private val found = linkedSetOf<String>()
    private val linked = linkedSetOf<String>()
    private val chats = mutableMapOf<String, StringBuilder>()
    private val unread = mutableMapOf<String, Int>()
    private var open: String? = null
    private var draft = ""
    private var msgView: TextView? = null
    private var scroll: ScrollView? = null
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("airtext", MODE_PRIVATE)
        myName = prefs.getString("name", null)
            ?: "${Build.MODEL}-${(10..99).random()}".also { prefs.edit().putString("name", it).apply() }
        conns = Nearby.getConnectionsClient(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        setContentView(root)
        render()
        askPerms()
    }

    private fun perms(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            p.add(Manifest.permission.BLUETOOTH_CONNECT)
            p.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT <= 32) p.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        return p.toTypedArray()
    }

    private fun askPerms() {
        val missing = perms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) start() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) start()
        else toast("Airtext needs the Nearby permissions to find other phones")
    }

    private fun start() {
        if (started) return
        started = true
        val strategy = Strategy.P2P_CLUSTER
        conns.startAdvertising(myName, service, connCb, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { toast("Advertising failed: ${it.message}") }
        conns.startDiscovery(service, discCb, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { toast("Scanning failed: ${it.message}") }
        render()
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            names[id] = info.endpointName
            found.add(id)
            render()
        }
        override fun onEndpointLost(id: String) {
            found.remove(id)
            render()
        }
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            names[id] = info.endpointName
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Connect to ${info.endpointName}?")
                .setMessage("Both phones should show this code: ${info.authenticationDigits}")
                .setPositiveButton("Accept") { _, _ -> conns.acceptConnection(id, payloadCb) }
                .setNegativeButton("Decline") { _, _ -> conns.rejectConnection(id) }
                .show()
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) linked.add(id) else toast("Connection failed")
            render()
        }
        override fun onDisconnected(id: String) {
            linked.remove(id)
            if (open == id) open = null
            render()
        }
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
            log(id, names[id] ?: "Them", text)
            if (open != id) render()
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private fun send(id: String, text: String) {
        conns.sendPayload(id, Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
            .addOnSuccessListener { log(id, "You", text) }
            .addOnFailureListener { toast("Not sent") }
    }

    private fun log(id: String, who: String, text: String) {
        chats.getOrPut(id) { StringBuilder() }.append(who).append(": ").append(text).append("\n\n")
        if (open == id) {
            msgView?.text = chats[id]?.toString() ?: ""
            scroll?.post { scroll?.fullScroll(ScrollView.FOCUS_DOWN) }
        } else if (who != "You") {
            unread[id] = (unread[id] ?: 0) + 1
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun render() {
        root.removeAllViews()
        msgView = null
        scroll = null
        val id = open
        if (id == null) home() else chat(id)
    }

    private fun home() {
        root.addView(TextView(this).apply { text = "Airtext"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "You are $myName. Works with no internet or plan. Keep Bluetooth, location and Wi-Fi switched on."
            textSize = 13f
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ids = (found + linked).toList()
        if (ids.isEmpty()) {
            list.addView(TextView(this).apply { text = "\nLooking for nearby phones running Airtext..." })
        }
        for (id in ids) {
            list.addView(Button(this).apply {
                val n = unread[id] ?: 0
                val status = if (id in linked) {
                    if (n > 0) "connected ($n new)" else "connected"
                } else "tap to connect"
                text = "${names[id] ?: id}  -  $status"
                setOnClickListener {
                    if (id in linked) {
                        open = id
                        unread[id] = 0
                        render()
                    } else {
                        conns.requestConnection(myName, id, connCb)
                            .addOnFailureListener { toast("Could not connect: ${it.message}") }
                        toast("Request sent")
                    }
                }
            })
        }
        root.addView(ScrollView(this).apply { addView(list) })
    }

    private fun chat(id: String) {
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply { text = "<"; setOnClickListener { open = null; render() } })
        top.addView(TextView(this).apply { text = names[id] ?: id; textSize = 20f })
        root.addView(top)

        val mv = TextView(this).apply { textSize = 16f; text = chats[id]?.toString() ?: "No messages yet." }
        val sv = ScrollView(this)
        sv.addView(mv)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        msgView = mv
        scroll = sv

        val row = LinearLayout(this)
        val et = EditText(this).apply {
            hint = "Text message"
            setText(draft)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { draft = s?.toString() ?: "" }
            })
        }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Send"
            setOnClickListener {
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) {
                    et.setText("")
                    send(id, t)
                }
            }
        })
        root.addView(row)
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (open != null) { open = null; render() } else super.onBackPressed()
    }

    override fun onDestroy() {
        conns.stopAdvertising()
        conns.stopDiscovery()
        conns.stopAllEndpoints()
        super.onDestroy()
    }
}
