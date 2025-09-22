package com.example.wifi_direct_flash

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.Manifest
import android.content.*
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.p2p.*
import android.os.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import java.io.*
import java.net.*

class MainActivity : AppCompatActivity() {

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private lateinit var intentFilter: IntentFilter

    private lateinit var btnDiscover: Button
    private lateinit var listPeers: ListView
    private lateinit var btnSend: Button
    private lateinit var edtMessage: EditText
    private lateinit var tvChat: TextView

    private val peers = mutableListOf<WifiP2pDevice>()
    private var peerNames = mutableListOf<String>()
    private var device: WifiP2pDevice? = null

    private var info: WifiP2pInfo? = null
    private var isHost = false

    private lateinit var tvStatus: TextView


    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {  }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnDiscover = findViewById(R.id.btnDiscover)
        listPeers   = findViewById(R.id.listPeers)
        btnSend     = findViewById(R.id.btnSend)
        edtMessage  = findViewById(R.id.edtMessage)
        tvChat      = findViewById(R.id.tvChat)

        // Inicializa Wi-Fi P2P
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        // Permissões para localização por exemplo
        permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ))

        // IntentFilter
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {

                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            tvStatus.text = "Wi-Fi Direct ativado"
                        } else {
                            tvStatus.text = "Wi-Fi Direct desativado"
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager.requestPeers(channel) { list ->
                            peers.clear()
                            peers.addAll(list.deviceList)
                            peerNames = peers.map { it.deviceName }.toMutableList()
                            listPeers.adapter = ArrayAdapter(
                                this@MainActivity,
                                android.R.layout.simple_list_item_1,
                                peerNames
                            )
                            tvStatus.text = if (peers.isNotEmpty())
                                "Dispositivos encontrados: ${peers.size}"
                            else
                                "Nenhum dispositivo encontrado"
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                        )
                        if (networkInfo?.isConnected == true) {
                            tvStatus.text = "Conectado! Obtendo informações…"
                            manager.requestConnectionInfo(channel) { p2pInfo ->
                                info = p2pInfo
                                if (p2pInfo.groupFormed) {
                                    if (p2pInfo.isGroupOwner) {
                                        isHost = true
                                        tvStatus.text = "Conectado como Host (Grupo formado)"
                                        val server = ServerClass()
                                        server.start()
                                        sendReceive = server.sendReceiveInstance
                                    } else {
                                        tvStatus.text = "Conectado como Cliente (Grupo formado)"
                                        val client = ClientClass(p2pInfo.groupOwnerAddress)
                                        client.start()
                                        sendReceive = client.sendReceiveInstance
                                    }
                                }
                            }
                        } else {
                            tvStatus.text = "Desconectado"
                        }
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val myDevice: WifiP2pDevice? =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        myDevice?.let {
                            tvStatus.text = "Meu dispositivo: ${it.deviceName} (${it.status})"
                        }
                    }
                }
            }
        }


        // Botão: Descobrir
        btnDiscover.setOnClickListener {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Procurando dispositivos...", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(reason: Int) {
                    Toast.makeText(this@MainActivity, "Falha ao procurar: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        }

        // Clique na lista -> conectar
        listPeers.setOnItemClickListener { _, _, position, _ ->
            device = peers[position]
            val config = WifiP2pConfig().apply { deviceAddress = device!!.deviceAddress }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Conectando a ${device!!.deviceName}", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(reason: Int) {
                    Toast.makeText(this@MainActivity, "Falha na conexão: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        }

        // Enviar mensagem
        btnSend.setOnClickListener {
            val msg = edtMessage.text.toString()
            if (msg.isBlank()) return@setOnClickListener

            if (sendReceive == null || !sendReceive!!.isAlive) {
                Toast.makeText(this, "Conexão perdida ou ainda não conectou. Conecte pelo app.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Thread {
                try {
                    sendReceive?.write(msg.toByteArray())
                    runOnUiThread { tvChat.append("\nVocê: $msg") }
                } catch (e: IOException) {
                    runOnUiThread {
                        Toast.makeText(this, "Erro ao enviar: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
            edtMessage.text.clear()
        }

    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }
    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }
    private var sendReceive: SendReceive? = null


    // ===== Threads de Comunicação =====
    inner class ServerClass : Thread() {
        var sendReceiveInstance: SendReceive? = null
        override fun run() {
            try {
                val serverSocket = ServerSocket(8888)
                val socket = serverSocket.accept()
                sendReceiveInstance = SendReceive(socket).also { it.start() }
            } catch (e: IOException) { e.printStackTrace() }
        }
    }

    inner class ClientClass(hostAddress: InetAddress) : Thread() {
        private val host = hostAddress
        var sendReceiveInstance: SendReceive? = null
        override fun run() {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, 8888), 5000)
                sendReceiveInstance = SendReceive(socket).also { it.start() }
            } catch (e: IOException) { e.printStackTrace() }
        }
    }


    inner class SendReceive(private val socket: Socket) : Thread() {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()

        fun write(bytes: ByteArray) {
            try {
                output.write(bytes)
                output.flush()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        override fun run() {
            val buffer = ByteArray(1024)
            var bytes: Int
            try {
                while (!Thread.currentThread().isInterrupted) {  // <-- muda aqui
                    bytes = input.read(buffer)
                    if (bytes > 0) {
                        val msg = String(buffer, 0, bytes)
                        runOnUiThread { tvChat.append("\nParceiro: $msg") }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

    }



}
