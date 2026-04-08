package com.example.dsspotcontrolled_node

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.dsspotcontrolled_node.core.AppConfig
import com.example.dsspotcontrolled_node.core.NodeSettings
import com.example.dsspotcontrolled_node.core.NodeSettingsStore
import com.example.dsspotcontrolled_node.data.remote.websocket.NodeWebSocketClient
import com.example.dsspotcontrolled_node.data.remote.websocket.WebSocketState
import com.example.dsspotcontrolled_node.databinding.ActivityMainBinding
import com.example.dsspotcontrolled_node.domain.model.NodeStatus
import com.example.dsspotcontrolled_node.websocket.ConfigUpdateMessage
import com.example.dsspotcontrolled_node.websocket.ErrorServerMessage
import com.example.dsspotcontrolled_node.websocket.IncomingMessageHandler
import com.example.dsspotcontrolled_node.websocket.InvalidServerMessage
import com.example.dsspotcontrolled_node.websocket.OutgoingMessageFactory
import com.example.dsspotcontrolled_node.websocket.PingMessage
import com.example.dsspotcontrolled_node.websocket.PlayMessage
import com.example.dsspotcontrolled_node.websocket.RegisterAckMessage
import com.example.dsspotcontrolled_node.websocket.ServerMessage
import com.example.dsspotcontrolled_node.websocket.SetEnabledMessage
import com.example.dsspotcontrolled_node.websocket.StopMessage
import com.example.dsspotcontrolled_node.websocket.SyncRequiredMessage
import com.example.dsspotcontrolled_node.websocket.UnknownServerMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), NodeWebSocketClient.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var nodeSettingsStore: NodeSettingsStore
    private lateinit var nodeWebSocketClient: NodeWebSocketClient

    private val outgoingMessageFactory = OutgoingMessageFactory()
    private val incomingMessageHandler = IncomingMessageHandler()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val eventLog = mutableListOf<String>()

    private var currentNodeId: String = ""
    private var currentSpotId: String? = null
    private var nodeEnabled: Boolean = true
    private var nodeStatus: NodeStatus = NodeStatus.OFFLINE
    private var connectionState: WebSocketState = WebSocketState.DISCONNECTED

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeatNow()
            uiHandler.postDelayed(this, AppConfig.HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nodeSettingsStore = NodeSettingsStore(applicationContext)
        nodeWebSocketClient = NodeWebSocketClient(
            messageFactory = outgoingMessageFactory,
            incomingMessageHandler = incomingMessageHandler,
            listener = this
        )
        currentNodeId = nodeSettingsStore.getOrCreateNodeId()

        loadStoredSettings()
        bindActions()
        refreshUiState()
        appendEvent("Node app initialized.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeatLoop()
        nodeWebSocketClient.shutdown()
    }

    override fun onConnectionStateChanged(state: WebSocketState) {
        runOnUiThread {
            connectionState = state
            when (state) {
                WebSocketState.CONNECTED -> {
                    nodeStatus = NodeStatus.ONLINE
                    startHeartbeatLoop()
                }
                WebSocketState.DISCONNECTED -> {
                    nodeStatus = NodeStatus.OFFLINE
                    currentSpotId = null
                    stopHeartbeatLoop()
                }
                WebSocketState.CONNECTING, WebSocketState.RECONNECTING -> {
                    stopHeartbeatLoop()
                }
            }
            refreshUiState()
        }
    }

    override fun onServerMessage(message: ServerMessage) {
        runOnUiThread {
            when (message) {
                is RegisterAckMessage -> handleRegisterAck(message)
                is PingMessage -> {
                    appendEvent("PING received, replying with heartbeat.")
                    sendHeartbeatNow()
                }
                is SetEnabledMessage -> handleSetEnabled(message)
                is SyncRequiredMessage -> handleSyncRequired(message)
                is PlayMessage -> handlePlayCommand(message)
                is StopMessage -> handleStopCommand(message)
                is ConfigUpdateMessage -> {
                    appendEvent("CONFIG_UPDATE received. autoplaySelected=${message.autoplaySelected}")
                }
                is ErrorServerMessage -> {
                    nodeStatus = NodeStatus.ERROR
                    appendEvent("Server ERROR ${message.errorCode}: ${message.errorMessage}")
                }
                is InvalidServerMessage -> {
                    appendEvent("Invalid message ignored: ${message.reason}")
                }
                is UnknownServerMessage -> {
                    appendEvent("Unknown message ignored: ${message.type}")
                }
            }
            refreshUiState()
        }
    }

    override fun onSocketEvent(message: String) {
        runOnUiThread {
            appendEvent(message)
        }
    }

    private fun bindActions() {
        binding.btnSaveSettings.setOnClickListener {
            saveSettingsFromForm()
        }

        binding.btnConnect.setOnClickListener {
            val settings = readSettingsFromForm()
            if (!validateSettings(settings)) {
                return@setOnClickListener
            }

            nodeSettingsStore.saveSettings(settings)
            appendEvent("Settings saved. Connecting...")

            val config = NodeWebSocketClient.ConnectionConfig(
                serverUrl = settings.serverUrl,
                nodeId = currentNodeId,
                authToken = settings.authToken,
                displayName = settings.displayName,
                appVersion = appVersionName(),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            )
            nodeWebSocketClient.connect(config)
            refreshUiState()
        }

        binding.btnDisconnect.setOnClickListener {
            nodeWebSocketClient.disconnect()
            refreshUiState()
        }

        binding.btnSendHeartbeat.setOnClickListener {
            sendHeartbeatNow()
        }
    }

    private fun handleRegisterAck(message: RegisterAckMessage) {
        nodeEnabled = message.enabled
        nodeStatus = if (message.enabled) NodeStatus.READY else NodeStatus.STOPPED
        appendEvent(
            "REGISTER_ACK received. enabled=${message.enabled}, syncRequired=${message.syncRequired}"
        )
        nodeWebSocketClient.sendStatusUpdate(
            status = nodeStatus,
            currentSpotId = currentSpotId,
            details = if (message.syncRequired) {
                "Connected. Waiting for sync command."
            } else {
                "Connected and ready."
            }
        )
    }

    private fun handleSetEnabled(message: SetEnabledMessage) {
        nodeEnabled = message.enabled
        nodeStatus = if (message.enabled) NodeStatus.READY else NodeStatus.STOPPED
        appendEvent("SET_ENABLED received. enabled=${message.enabled}")
        nodeWebSocketClient.sendStatusUpdate(
            status = nodeStatus,
            currentSpotId = currentSpotId,
            details = if (message.enabled) "Node enabled by server." else "Node disabled by server."
        )
    }

    private fun handleSyncRequired(message: SyncRequiredMessage) {
        nodeStatus = NodeStatus.SYNCING
        appendEvent("SYNC_REQUIRED received (${message.spots.size} spots). Sync manager not integrated yet.")
        nodeWebSocketClient.sendSyncResultFailure(
            requestId = message.requestId,
            failedSpotIds = message.spots.map { it.spotId },
            errorMessage = "Sync manager not integrated in this milestone."
        )
        nodeStatus = if (nodeEnabled) NodeStatus.READY else NodeStatus.STOPPED
    }

    private fun handlePlayCommand(message: PlayMessage) {
        if (!nodeEnabled) {
            appendEvent("PLAY ignored: node is disabled.")
            nodeWebSocketClient.sendPlaybackError(
                requestId = message.requestId,
                spotId = message.spotId,
                errorCode = "NODE_DISABLED",
                errorMessage = "Node is disabled and cannot execute PLAY."
            )
            return
        }

        currentSpotId = message.spotId
        nodeStatus = NodeStatus.ERROR
        appendEvent("PLAY received for ${message.spotId}, but playback engine is not integrated yet.")
        nodeWebSocketClient.sendPlaybackError(
            requestId = message.requestId,
            spotId = message.spotId,
            errorCode = "PLAYBACK_ENGINE_FAILURE",
            errorMessage = "Playback engine integration pending."
        )
    }

    private fun handleStopCommand(message: StopMessage) {
        val stoppedSpot = currentSpotId
        currentSpotId = null
        nodeStatus = if (nodeEnabled) NodeStatus.READY else NodeStatus.STOPPED
        appendEvent("STOP received. Previous spot=$stoppedSpot")
        nodeWebSocketClient.sendStatusUpdate(
            status = nodeStatus,
            currentSpotId = currentSpotId,
            details = "Stop command received."
        )
    }

    private fun loadStoredSettings() {
        val settings = nodeSettingsStore.loadSettings()
        binding.etServerUrl.setText(settings.serverUrl)
        binding.etAuthToken.setText(settings.authToken)
        binding.etDisplayName.setText(settings.displayName)
    }

    private fun saveSettingsFromForm() {
        val settings = readSettingsFromForm()
        if (!validateSettings(settings)) {
            return
        }
        nodeSettingsStore.saveSettings(settings)
        appendEvent("Settings saved.")
    }

    private fun readSettingsFromForm(): NodeSettings {
        return NodeSettings(
            serverUrl = binding.etServerUrl.text?.toString().orEmpty().trim(),
            authToken = binding.etAuthToken.text?.toString().orEmpty().trim(),
            displayName = binding.etDisplayName.text?.toString().orEmpty().trim()
        )
    }

    private fun validateSettings(settings: NodeSettings): Boolean {
        if (settings.serverUrl.isBlank()) {
            appendEvent("Server URL is required.")
            return false
        }
        if (!settings.serverUrl.startsWith("ws://") && !settings.serverUrl.startsWith("wss://")) {
            appendEvent("Server URL must start with ws:// or wss://")
            return false
        }
        if (settings.authToken.isBlank()) {
            appendEvent("Auth token is required.")
            return false
        }
        return true
    }

    private fun sendHeartbeatNow() {
        if (!nodeWebSocketClient.isConnected()) {
            return
        }
        val sent = nodeWebSocketClient.sendHeartbeat(nodeStatus, currentSpotId)
        if (!sent) {
            appendEvent("Heartbeat send failed.")
        }
    }

    private fun startHeartbeatLoop() {
        uiHandler.removeCallbacks(heartbeatRunnable)
        uiHandler.postDelayed(heartbeatRunnable, AppConfig.HEARTBEAT_INTERVAL_MS)
        appendEvent("Heartbeat scheduler started (${AppConfig.HEARTBEAT_INTERVAL_MS / 1000}s).")
    }

    private fun stopHeartbeatLoop() {
        uiHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun refreshUiState() {
        binding.tvNodeIdValue.text = currentNodeId
        binding.tvConnectionValue.text = connectionState.name
        binding.tvStatusValue.text = nodeStatus.name + if (currentSpotId != null) " (${currentSpotId})" else ""
        binding.btnConnect.isEnabled = connectionState != WebSocketState.CONNECTING &&
            connectionState != WebSocketState.RECONNECTING &&
            connectionState != WebSocketState.CONNECTED
        binding.btnDisconnect.isEnabled = connectionState != WebSocketState.DISCONNECTED
        binding.btnSendHeartbeat.isEnabled = connectionState == WebSocketState.CONNECTED
    }

    private fun appendEvent(message: String) {
        val timestamp = timeFormat.format(Date())
        eventLog.add(0, "[$timestamp] $message")
        while (eventLog.size > 40) {
            eventLog.removeLast()
        }
        binding.tvEventLog.text = eventLog.joinToString(separator = "\n")
    }

    private fun appVersionName(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return packageInfo.versionName ?: "1.0.0"
    }
}
