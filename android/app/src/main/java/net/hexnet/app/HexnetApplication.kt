package net.hexnet.app

import android.app.Application
import net.hexnet.app.core.database.HexnetDatabase
import net.hexnet.app.data.local.PreferencesDataStore

class HexnetApplication : Application() {
    val database: HexnetDatabase by lazy { HexnetDatabase.getInstance(this) }
    val preferencesDataStore: PreferencesDataStore by lazy { PreferencesDataStore(this) }
    val identityManager: net.hexnet.app.core.crypto.IdentityManager by lazy {
        net.hexnet.app.core.crypto.IdentityManager(net.hexnet.app.core.crypto.EncryptedKeyStorage(this))
    }
    val noiseSessionManager: net.hexnet.app.core.crypto.NoiseSessionManager by lazy {
        net.hexnet.app.core.crypto.NoiseSessionManager(identityManager)
    }
    val messagingRepository: net.hexnet.app.data.repository.MessagingRepository by lazy {
        net.hexnet.app.data.repository.MessagingRepository(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao()
        )
    }
    val contactsRepository: net.hexnet.app.data.repository.ContactsRepository by lazy {
        net.hexnet.app.data.repository.ContactsRepository(
            peerDao = database.peerDao()
        )
    }
    val meshNetworkRepository: net.hexnet.app.data.repository.MeshNetworkRepository by lazy {
        net.hexnet.app.data.repository.MeshNetworkRepository(
            eventDao = database.networkEventDao()
        )
    }

    override fun onCreate() {
        super.onCreate()
    }

    fun startMeshService(localHexAddress: String) {
        val intent = android.content.Intent(this, net.hexnet.app.core.transport.ble.HexnetMeshForegroundService::class.java).apply {
            action = net.hexnet.app.core.transport.ble.HexnetMeshForegroundService.ACTION_START_MESH
            putExtra(net.hexnet.app.core.transport.ble.HexnetMeshForegroundService.EXTRA_LOCAL_HEX_ADDRESS, localHexAddress)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    fun stopMeshService() {
        val intent = android.content.Intent(this, net.hexnet.app.core.transport.ble.HexnetMeshForegroundService::class.java).apply {
            action = net.hexnet.app.core.transport.ble.HexnetMeshForegroundService.ACTION_STOP_MESH
        }
        startService(intent)
    }
}
