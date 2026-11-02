package com.ia.daemon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.GeneralPath
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicScrollBarUI
import kotlin.concurrent.thread
import kotlin.math.*

// ============================================================================
// MODELOS DE DATOS DE CUENTAS, ROLES Y BÓVEDA EN LA NUBE
// ============================================================================
data class ChatRequest(
    val prompt: String = "",
    val username: String = "",
    val password: String = "",
    val fromBrowser: Boolean = true
)

data class IntentNode(
    val tag: String = "",
    val patterns: List<String> = emptyList(),
    val responses: List<String> = emptyList(),
    val action: String = "NONE"
)

data class WorkspaceMode(
    val id: String = "",
    val name: String = "",
    val triggers: List<String> = emptyList(),
    val appsToOpen: List<String> = emptyList(),
    val appsToClose: List<String> = emptyList(),
    val spotifyPlaylist: String = "",
    val greeting: String = ""
)

data class ChatMessage(
    val role: String = "",
    val content: String = ""
)

data class UserAccount(
    val username: String = "",
    var password: String = "",
    var role: String = "USER", // "ADMIN" (Cristian) o "USER"
    val personalFacts: MutableList<String> = mutableListOf(),
    val personalKnowledge: MutableList<IntentNode> = mutableListOf(),
    val personalModes: MutableList<WorkspaceMode> = mutableListOf(),
    val recentHistory: MutableList<ChatMessage> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class CloudVaultState(
    var lastUpdated: Long = System.currentTimeMillis(),
    val globalKnowledge: MutableList<IntentNode> = mutableListOf(),
    val accounts: MutableList<UserAccount> = mutableListOf()
)

data class LocalDeviceConfig(
    var cloudDatabaseUrl: String = "",
    var currentUsername: String = "cristian",
    var currentPassword: String = "admin2026"
)

data class OllamaOptions(
    val temperature: Double = 0.7,
    val num_predict: Int = 240
)

data class OllamaPayload(
    val model: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
    val options: OllamaOptions = OllamaOptions()
)

data class CloudChatPayload(
    val messages: List<ChatMessage> = emptyList(),
    val model: String = "openai",
    val seed: Int = 42,
    val jsonMode: Boolean = false
)

// ============================================================================
// 1. SERVICIO DE BÓVEDA EN LA NUBE MULTI-DISPOSITIVO + CUENTA DE ADMINISTRADOR
// ============================================================================
@Service
class CloudVaultService {

    private val vaultCacheFile = File("daemon_cloud_vault.json")
    private val configFile = File("daemon_device_config.json")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    var deviceConfig = LocalDeviceConfig()
    var vault = CloudVaultState()

    @Volatile var cloudSyncStatus: String = "LOCAL CACHE"

    init {
        loadDeviceConfig()
        loadOrInitializeVault()
        startBackgroundCloudSync()
    }

    private fun loadDeviceConfig() {
        val envUrl = System.getenv("DAEMON_CLOUD_DB_URL") ?: ""
        if (configFile.exists()) {
            try {
                deviceConfig = objectMapper.readValue(configFile, LocalDeviceConfig::class.java)
            } catch (e: Exception) {}
        }
        if (envUrl.isNotBlank() && deviceConfig.cloudDatabaseUrl.isBlank()) {
            deviceConfig.cloudDatabaseUrl = envUrl
        }
        saveDeviceConfig()
    }

    fun saveDeviceConfig() {
        try {
            objectMapper.writeValue(configFile, deviceConfig)
        } catch (e: Exception) {}
    }

    private fun loadOrInitializeVault() {
        if (vaultCacheFile.exists()) {
            try {
                vault = objectMapper.readValue(vaultCacheFile, CloudVaultState::class.java)
            } catch (e: Exception) {}
        }

        // Garantizar que la Base de Conocimientos Global tenga los nodos maestros
        if (vault.globalKnowledge.isEmpty()) {
            vault.globalKnowledge.addAll(buildMasterGlobalKnowledge())
        }

        // Garantizar que SIEMPRE exista tu Cuenta Maestra de Administrador ("cristian")
        val adminExists = vault.accounts.any { it.username.equals("cristian", true) }
        if (!adminExists) {
            val adminAccount = UserAccount(
                username = "cristian",
                password = deviceConfig.currentPassword.ifBlank { "admin2026" },
                role = "ADMIN",
                personalFacts = mutableListOf(
                    "Es Cristian Alexis, creador y Administrador Maestro de Daemon.",
                    "Estudia Ingeniería en Sistemas Computacionales en la ESCOM del IPN."
                ),
                personalKnowledge = mutableListOf(),
                personalModes = buildDefaultModesForUser("Cristian"),
                recentHistory = mutableListOf(),
                updatedAt = System.currentTimeMillis()
            )
            vault.accounts.add(adminAccount)
        } else {
            // Asegurar que cristian siempre conserve el rol ADMIN
            vault.accounts.firstOrNull { it.username.equals("cristian", true) }?.role = "ADMIN"
        }

        saveLocalVaultCache()
        pullFromCloudNow()
    }

    fun saveLocalVaultCache() {
        try {
            vault.lastUpdated = System.currentTimeMillis()
            objectMapper.writeValue(vaultCacheFile, vault)
        } catch (e: Exception) {}
    }

    // ========================================================================
    // SINCRONIZACIÓN REAL EN LA NUBE (FIREBASE REALTIME DB / REST CLOUD SERVER)
    // ========================================================================
    private fun resolveCloudRestUrl(): String? {
        val raw = deviceConfig.cloudDatabaseUrl.trim()
        if (raw.isBlank()) return null
        return when {
            raw.contains("firebaseio.com") || raw.contains("firebasedatabase.app") -> {
                val base = raw.trimEnd('/')
                if (base.endsWith(".json")) base else "$base/daemon_vault.json"
            }
            else -> {
                val base = raw.trimEnd('/')
                if (base.endsWith("/api/daemon/cloud-vault")) base else "$base/api/daemon/cloud-vault"
            }
        }
    }

    fun pushToCloudAsync() {
        saveLocalVaultCache()
        val url = resolveCloudRestUrl() ?: return
        thread(isDaemon = true) {
            try {
                val json = objectMapper.writeValueAsString(vault)
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build()
                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                cloudSyncStatus = if (res.statusCode() in 200..299) "CLOUD SYNCED ☁" else "CLOUD ERR ${res.statusCode()}"
            } catch (e: Exception) {
                cloudSyncStatus = "OFFLINE CACHE"
            }
        }
    }

    fun pullFromCloudNow(): Boolean {
        val url = resolveCloudRestUrl() ?: return false
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200 && res.body().isNotBlank() && res.body().trim() != "null") {
                val remoteVault = objectMapper.readValue(res.body(), CloudVaultState::class.java)
                mergeCloudVault(remoteVault)
                cloudSyncStatus = "CLOUD SYNCED ☁"
                true
            } else {
                // Si la nube está vacía por primera vez, subimos nuestra bóveda inicial
                pushToCloudAsync()
                true
            }
        } catch (e: Exception) {
            cloudSyncStatus = "OFFLINE CACHE"
            false
        }
    }

    // Fusiona inteligentemente las cuentas y conocimientos de la nube con el dispositivo actual
    @Synchronized
    fun mergeCloudVault(remote: CloudVaultState) {
        // 1. Fusionar conocimientos globales del Administrador
        for (rNode in remote.globalKnowledge) {
            val idx = vault.globalKnowledge.indexOfFirst { it.tag.equals(rNode.tag, true) }
            if (idx < 0) {
                vault.globalKnowledge.add(rNode)
            } else if (remote.lastUpdated >= vault.lastUpdated) {
                vault.globalKnowledge[idx] = rNode
            }
        }

        // 2. Fusionar cuentas de usuario (gana la versión más reciente de cada usuario)
        for (rAcc in remote.accounts) {
            val localIdx = vault.accounts.indexOfFirst { it.username.equals(rAcc.username, true) }
            if (localIdx < 0) {
                vault.accounts.add(rAcc)
            } else {
                val localAcc = vault.accounts[localIdx]
                if (rAcc.updatedAt >= localAcc.updatedAt) {
                    val mergedFacts = (localAcc.personalFacts + rAcc.personalFacts).distinct().toMutableList()
                    val mergedKnow = (localAcc.personalKnowledge + rAcc.personalKnowledge).distinctBy { it.tag }.toMutableList()
                    vault.accounts[localIdx] = rAcc.copy(
                        role = if (rAcc.username.equals("cristian", true)) "ADMIN" else rAcc.role,
                        personalFacts = mergedFacts,
                        personalKnowledge = mergedKnow
                    )
                }
            }
        }
        objectMapper.writeValue(vaultCacheFile, vault)
    }

    private fun startBackgroundCloudSync() {
        thread(isDaemon = true, name = "Daemon-Cloud-Vault-Sync") {
            while (true) {
                try {
                    if (deviceConfig.cloudDatabaseUrl.isNotBlank()) {
                        pullFromCloudNow()
                    }
                } catch (e: Exception) {}
                Thread.sleep(15000) // Sincronización silenciosa cada 15 segundos
            }
        }
    }

    // ========================================================================
    // GESTIÓN DE CUENTAS Y PRIVILEGIOS DE ADMINISTRADOR
    // ========================================================================
    fun getCurrentAccount(): UserAccount {
        return vault.accounts.firstOrNull { it.username.equals(deviceConfig.currentUsername, true) }
            ?: vault.accounts.first()
    }

    fun isAdminLoggedIn(): Boolean = getCurrentAccount().role.equals("ADMIN", true)

    fun loginOrSwitchUser(username: String, password: String): String {
        pullFromCloudNow()
        val cleanUser = username.trim().lowercase()
        val acc = vault.accounts.firstOrNull { it.username.equals(cleanUser, true) }
            ?: return "El usuario '$cleanUser' no existe en la nube de Daemon. Puedes crearlo con: 'registrar usuario $cleanUser clave <tu_clave>'."

        if (acc.password != password.trim()) {
            return "Contraseña incorrecta para la cuenta '$cleanUser'."
        }

        deviceConfig.currentUsername = acc.username
        deviceConfig.currentPassword = acc.password
        saveDeviceConfig()

        val badge = if (acc.role == "ADMIN") "👑 ADMINISTRADOR MAESTRO" else "👤 USUARIO ESTÁNDAR"
        return "Sesión iniciada en la nube como '${acc.username.uppercase()}' [$badge]. Se cargaron ${acc.personalFacts.size} recuerdos personales y ${acc.personalKnowledge.size} nodos propios."
    }

    fun registerNewUser(username: String, password: String, role: String = "USER"): String {
        pullFromCloudNow()
        val cleanUser = username.trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
        if (cleanUser.length < 3) return "El nombre de usuario debe tener al menos 3 caracteres."
        if (vault.accounts.any { it.username.equals(cleanUser, true) }) {
            return "El usuario '$cleanUser' ya está registrado en la nube."
        }

        val newAcc = UserAccount(
            username = cleanUser,
            password = password.trim().ifBlank { "1234" },
            role = if (cleanUser == "cristian") "ADMIN" else role,
            personalFacts = mutableListOf("Usuario registrado en Daemon Cloud: $cleanUser"),
            personalKnowledge = mutableListOf(),
            personalModes = buildDefaultModesForUser(cleanUser),
            recentHistory = mutableListOf(),
            updatedAt = System.currentTimeMillis()
        )
        vault.accounts.add(newAcc)
        deviceConfig.currentUsername = newAcc.username
        deviceConfig.currentPassword = newAcc.password
        saveDeviceConfig()
        pushToCloudAsync()

        return "Cuenta en la nube creada e iniciada para '${newAcc.username.uppercase()}'. Todo lo que platiques o le enseñes estará disponible en cualquiera de tus dispositivos."
    }

    fun addPersonalFactToCurrentUser(fact: String): String {
        val acc = getCurrentAccount()
        if (!acc.personalFacts.contains(fact)) {
            acc.personalFacts.add(fact)
            acc.updatedAt = System.currentTimeMillis()
            pushToCloudAsync()
        }
        return "Guardado en la memoria en la nube de '${acc.username.uppercase()}': '$fact'. Disponible en todos tus dispositivos."
    }

    fun addPersonalKnowledgeToCurrentUser(tag: String, patterns: List<String>, response: String): String {
        val acc = getCurrentAccount()
        acc.personalKnowledge.removeIf { it.tag.equals(tag, true) }
        acc.personalKnowledge.add(IntentNode(tag = tag, patterns = patterns, responses = listOf(response)))
        acc.updatedAt = System.currentTimeMillis()
        pushToCloudAsync()
        return "Conocimiento guardado en la cuenta personal de '${acc.username.uppercase()}' (${patterns.size} patrones)."
    }

    // --- FUNCIONES EXCLUSIVAS DE ADMINISTRADOR (CRISTIAN) ---
    fun adminAddGlobalKnowledge(tag: String, patterns: List<String>, response: String): String {
        if (!isAdminLoggedIn()) {
            return "⛔ Acceso denegado. Solo la cuenta de Administrador (Cristian) puede modificar la Base de Conocimiento Global."
        }
        vault.globalKnowledge.removeIf { it.tag.equals(tag, true) }
        vault.globalKnowledge.add(IntentNode(tag = tag, patterns = patterns, responses = listOf(response)))
        pushToCloudAsync()
        return "👑 [ADMIN] Nodo global '$tag' agregado a la nube. Ahora está activo para todas las cuentas y dispositivos."
    }

    fun adminDeleteGlobalKnowledge(targetTag: String): String {
        if (!isAdminLoggedIn()) {
            return "⛔ Acceso denegado. Requiere permisos de Administrador."
        }
        val removed = vault.globalKnowledge.removeIf { it.tag.contains(targetTag, true) }
        pushToCloudAsync()
        return if (removed) "👑 [ADMIN] Nodo global '$targetTag' eliminado de la nube."
        else "👑 [ADMIN] No se encontró un nodo global con el identificador '$targetTag'."
    }

    fun adminListUsers(): String {
        if (!isAdminLoggedIn()) return "⛔ Comando exclusivo para la cuenta de Administrador."
        val summary = vault.accounts.joinToString("\n") { acc ->
            "• ${acc.username.uppercase()} [${acc.role}] -> ${acc.personalFacts.size} recuerdos | ${acc.personalKnowledge.size} nodos"
        }
        return "👑 [PANEL DE ADMINISTRADOR // USUARIOS EN LA NUBE (${vault.accounts.size})]\n$summary"
    }

    fun adminDeleteUser(targetUser: String): String {
        if (!isAdminLoggedIn()) return "⛔ Comando exclusivo para el Administrador."
        val clean = targetUser.trim().lowercase()
        if (clean == "cristian") return "👑 No puedes eliminar la cuenta maestra del creador."
        val removed = vault.accounts.removeIf { it.username.equals(clean, true) }
        pushToCloudAsync()
        return if (removed) "👑 [ADMIN] Cuenta '$clean' eliminada de la nube." else "No se encontró el usuario '$clean'."
    }

    fun adminSetUserRole(targetUser: String, newRole: String): String {
        if (!isAdminLoggedIn()) return "⛔ Comando exclusivo para el Administrador."
        val acc = vault.accounts.firstOrNull { it.username.equals(targetUser.trim(), true) }
            ?: return "No existe el usuario '$targetUser'."
        acc.role = newRole.uppercase()
        acc.updatedAt = System.currentTimeMillis()
        pushToCloudAsync()
        return "👑 [ADMIN] El rol de '${acc.username.uppercase()}' fue actualizado a ${acc.role}."
    }

    fun adminSetCloudDatabaseUrl(newUrl: String): String {
        if (!isAdminLoggedIn()) return "⛔ Solo el Administrador puede cambiar el servidor de base de datos en la nube."
        deviceConfig.cloudDatabaseUrl = newUrl.trim()
        saveDeviceConfig()
        val ok = pullFromCloudNow()
        pushToCloudAsync()
        return if (ok) {
            "👑 [ADMIN] Base de datos en la nube conectada y sincronizada en: ${deviceConfig.cloudDatabaseUrl}"
        } else {
            "👑 [ADMIN] URL guardada (${deviceConfig.cloudDatabaseUrl}). Se sincronizará en cuanto haya conexión."
        }
    }

    // Busca una respuesta primero en la base personal del usuario activo y luego en la base Global del Admin
    fun findKnowledgeMatch(normalizedInput: String): String? {
        val currentAcc = getCurrentAccount()
        val allNodes = currentAcc.personalKnowledge + vault.globalKnowledge

        for (node in allNodes) {
            for (pat in node.patterns) {
                val normPat = normalizeSimple(pat)
                if (normPat.isNotBlank() && (normalizedInput == normPat || normalizedInput.contains(normPat))) {
                    return node.responses.firstOrNull()
                }
            }
        }
        return null
    }

    private fun normalizeSimple(text: String): String {
        val temp = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return temp.replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun buildDefaultModesForUser(userLabel: String): MutableList<WorkspaceMode> = mutableListOf(
        WorkspaceMode(
            id = "TRABAJO",
            name = "PROTOCOLO TRABAJO",
            triggers = listOf("vamos a trabajar", "modo trabajo", "protocolo trabajo", "hora de trabajar", "vamos a programar"),
            appsToOpen = listOf("vscode", "notion", "opera gx"),
            appsToClose = emptyList(),
            spotifyPlaylist = "mis me gusta",
            greeting = "Modo Trabajo activo para $userLabel. Enfocando Visual Studio Code, Notion, Opera GX y tu música en Mis Me Gusta."
        ),
        WorkspaceMode(
            id = "TAREA",
            name = "PROTOCOLO TAREA",
            triggers = listOf("vamos a hacer tarea", "modo tarea", "protocolo tarea", "hora de hacer tarea", "tengo tarea"),
            appsToOpen = listOf("vscode", "notion", "opera gx"),
            appsToClose = listOf("discord", "steam"),
            spotifyPlaylist = "mis me gusta",
            greeting = "Modo Tarea activo para $userLabel. Enfocando Visual Studio Code, Notion, Opera GX y reproduciendo Mis Me Gusta."
        ),
        WorkspaceMode(
            id = "ESTUDIO",
            name = "PROTOCOLO ESTUDIO",
            triggers = listOf("vamos a estudiar", "modo estudio", "protocolo estudio", "modo escuela", "modo escom"),
            appsToOpen = listOf("notion", "opera gx", "vscode"),
            appsToClose = listOf("discord", "steam"),
            spotifyPlaylist = "mis me gusta",
            greeting = "Modo Estudio activo para $userLabel. Enfocando Notion, Opera GX, Visual Studio Code y tus canciones de Mis Me Gusta."
        ),
        WorkspaceMode(
            id = "GAMING",
            name = "PROTOCOLO GAMING",
            triggers = listOf("vamos a jugar", "modo gaming", "modo juego", "protocolo gaming", "hora de jugar"),
            appsToOpen = listOf("discord"),
            appsToClose = listOf("vscode", "notion"),
            spotifyPlaylist = "mis me gusta",
            greeting = "Modo Gaming activo para $userLabel. Cerrando aplicaciones de trabajo, enfocando Discord y tu música."
        )
    )

    private fun buildMasterGlobalKnowledge(): List<IntentNode> = listOf(
        IntentNode(
            tag = "global_identidad",
            patterns = listOf("quien eres", "que es daemon", "como funcionas"),
            responses = listOf("Soy Daemon v11.0, un sistema operativo de inteligencia artificial con memoria sincronizada en la nube por cuenta de usuario y administrado por Cristian Alexis.")
        ),
        IntentNode(
            tag = "global_proceso_hilo",
            patterns = listOf("que es un proceso", "diferencia entre proceso e hilo", "que es un thread"),
            responses = listOf("Un proceso es un programa en ejecución con su propio espacio de memoria aislado; un hilo (thread) es una unidad ligera de ejecución que comparte la memoria de ese proceso.")
        ),
        IntentNode(
            tag = "global_mutex_semaforo",
            patterns = listOf("que es un mutex", "diferencia entre mutex y semaforo"),
            responses = listOf("Un Mutex es un cerrojo binario de exclusión mutua para proteger secciones críticas, mientras que un Semáforo emplea un contador entero para sincronizar múltiples hilos o procesos.")
        ),
        IntentNode(
            tag = "global_fpga_vhdl",
            patterns = listOf("que es una fpga", "que es vhdl"),
            responses = listOf("Una FPGA es un circuito integrado de hardware digital reconfigurable que se programa mediante lenguajes de descripción de hardware como VHDL para sintetizar lógica paralela real.")
        )
    )
}

// ============================================================================
// 2. MOTOR MULTIPLATAFORMA DEL SISTEMA OPERATIVO (WINDOWS / MACOS / LINUX)
// ============================================================================
@Service
class SystemScanner(
    private val cloudVault: CloudVaultService
) {

    val osName: String = System.getProperty("os.name").lowercase()
    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac") || osName.contains("darwin")
    val isLinux = osName.contains("nix") || osName.contains("nux") || osName.contains("aix")

    private val installedApps = mutableMapOf("init" to "init").apply { clear() }
    private val playlistsFile = File("daemon_playlists.json")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val savedPlaylists = mutableMapOf("init" to "init").apply { clear() }

    @Volatile var activeModeName: String = "NÚCLEO ACTIVO"
    @Volatile var currentSpotifyTrack: String = "ACTIVO"
    @Volatile var cachedCpuLoad: Int = 4
    @Volatile var cachedRamMb: Int = 54
    @Volatile var isFullPanelVisible: Boolean = false
    @Volatile private var lastMediaActionTimestamp: Long = 0L

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    init {
        scanInstalledApplications()
        loadPlaylists()
        ensureDesktopAndStartupApp()
        startLowPowerTelemetryMonitor()
    }

    fun getOSLabel(): String = when {
        isWindows -> "WIN"
        isMac -> "MAC"
        isLinux -> "LINUX"
        else -> "OS"
    }

    fun runPowerShell(script: String, wait: Boolean = false): String {
        if (!isWindows) return ""
        return try {
            val utf16Bytes = script.toByteArray(StandardCharsets.UTF_16LE)
            val base64 = Base64.getEncoder().encodeToString(utf16Bytes)
            val proc = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", base64
            ).start()
            if (wait) {
                val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                proc.waitFor()
                output
            } else {
                ""
            }
        } catch (e: Exception) { "" }
    }

    fun runAppleScript(script: String, wait: Boolean = false): String {
        if (!isMac) return ""
        return try {
            val proc = ProcessBuilder("osascript", "-e", script).start()
            if (wait) {
                val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                proc.waitFor()
                output
            } else {
                ""
            }
        } catch (e: Exception) { "" }
    }

    fun runLinuxShell(cmd: String, wait: Boolean = false): String {
        if (!isLinux) return ""
        return try {
            val proc = ProcessBuilder("sh", "-c", cmd).start()
            if (wait) {
                val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                proc.waitFor()
                output
            } else {
                ""
            }
        } catch (e: Exception) { "" }
    }

    private fun startLowPowerTelemetryMonitor() {
        thread(isDaemon = true, name = "Daemon-Eco-Telemetry") {
            var gcCounter = 0
            while (true) {
                try {
                    gcCounter++
                    if (gcCounter >= 6) {
                        gcCounter = 0
                        System.gc()
                    }
                    val rt = Runtime.getRuntime()
                    cachedRamMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt().coerceAtLeast(24)

                    val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                    val load = (osBean.processCpuLoad * 100).toInt()
                    cachedCpuLoad = if (load in 1..100) load else 2

                    if (isFullPanelVisible) {
                        val status = getSpotifyStatusAndTrack()
                        currentSpotifyTrack = when {
                            status == "CLOSED" -> "SPOTIFY CERRADO"
                            status == "PAUSED" -> "EN PAUSA"
                            else -> status.removePrefix("PLAYING:").take(32)
                        }
                    }
                } catch (e: Exception) {}
                Thread.sleep(8000)
            }
        }
    }

    fun getSpotifyStatusAndTrack(): String {
        return when {
            isWindows -> {
                val script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -First 1 -ExpandProperty MainWindowTitle"
                val title = runPowerShell(script, wait = true).trim()
                when {
                    title.isBlank() -> "CLOSED"
                    !title.contains(" - ") -> "PAUSED"
                    else -> "PLAYING:$title"
                }
            }
            isMac -> {
                val script = """
                    if application "Spotify" is running then
                        tell application "Spotify"
                            if player state is playing then
                                return "PLAYING:" & (artist of current track) & " - " & (name of current track)
                            else
                                return "PAUSED"
                            end if
                        end tell
                    else
                        return "CLOSED"
                    end if
                """.trimIndent()
                val out = runAppleScript(script, wait = true)
                if (out.isBlank()) "CLOSED" else out
            }
            isLinux -> {
                val status = runLinuxShell("playerctl -p spotify status 2>/dev/null", wait = true)
                when {
                    status.equals("Playing", true) -> {
                        val meta = runLinuxShell("playerctl -p spotify metadata --format '{{ artist }} - {{ title }}' 2>/dev/null", wait = true)
                        "PLAYING:$meta"
                    }
                    status.equals("Paused", true) -> "PAUSED"
                    else -> "CLOSED"
                }
            }
            else -> "CLOSED"
        }
    }

    // Si la aplicación ya está abierta, solo la amplía y pone al frente sin abrir nuevas pestañas
    fun openOrFocusApp(normalizedCommand: String): String {
        val requestedApp = normalizedCommand
            .replace(Regex("""^.*?(?:abre|abrir|inicia|iniciar|ejecuta|ejecutar|lanza|pon)\s+(?:la\s+aplicacion\s+|el\s+programa\s+|la\s+app\s+|el\s+|la\s+)?"""), "")
            .trim()

        val canonical = when {
            requestedApp == "visual" || requestedApp == "vs code" || requestedApp == "code" || requestedApp == "vscode" -> "vscode"
            requestedApp.contains("opera") -> "opera gx"
            requestedApp.contains("notion") -> "notion"
            requestedApp.contains("spotify") -> "spotify"
            requestedApp.contains("discord") -> "discord"
            else -> requestedApp
        }

        return when {
            isWindows -> openOrFocusWindowsApp(canonical)
            isMac -> openOrFocusMacApp(canonical)
            isLinux -> openOrFocusLinuxApp(canonical)
            else -> "Sistema no soportado."
        }
    }

    private fun openOrFocusWindowsApp(canonical: String): String {
        val processName = when (canonical) {
            "vscode" -> "Code"
            "opera gx" -> "opera"
            "notion" -> "Notion"
            "spotify" -> "Spotify"
            "discord" -> "Discord"
            "calculadora" -> "CalculatorApp"
            "bloc de notas" -> "notepad"
            else -> ""
        }

        val entry = installedApps.entries.firstOrNull { it.key == canonical }
            ?: installedApps.entries.firstOrNull { it.key.contains(canonical) || canonical.contains(it.key) }
        val exePath = if (canonical == "opera gx") (findWindowsOperaGX() ?: entry?.value) else entry?.value

        if (processName.isNotBlank()) {
            val psFocusOrLaunch = """
                ${'$'}procs = @(Get-Process -Name "$processName" -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 })
                if (${'$'}procs.Count -gt 0) {
                    ${'$'}hwnd = ${'$'}procs[0].MainWindowHandle
                    ${'$'}code = 'using System; using System.Runtime.InteropServices; public class WinFocus { [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd); [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd); }'
                    Add-Type -TypeDefinition ${'$'}code -ErrorAction SilentlyContinue
                    if ([WinFocus]::IsIconic(${'$'}hwnd)) {
                        [WinFocus]::ShowWindowAsync(${'$'}hwnd, 9) | Out-Null
                    } else {
                        [WinFocus]::ShowWindowAsync(${'$'}hwnd, 3) | Out-Null
                    }
                    [WinFocus]::SetForegroundWindow(${'$'}hwnd) | Out-Null
                    Write-Output "FOCUSED"
                } else {
                    Write-Output "NOT_RUNNING"
                }
            """.trimIndent()

            val status = runPowerShell(psFocusOrLaunch, wait = true)
            if (status.contains("FOCUSED")) {
                return "Ampliando ventana activa de ${canonical.uppercase()}."
            }
        }

        if (exePath != null) {
            ProcessBuilder("cmd", "/c", "start", "", exePath).start()
            return "Iniciando ${canonical.uppercase()}."
        }
        return "No encontré '$canonical' instalado en este dispositivo."
    }

    private fun openOrFocusMacApp(canonical: String): String {
        val macAppName = when (canonical) {
            "vscode" -> "Visual Studio Code"
            "opera gx" -> if (File("/Applications/Opera GX.app").exists()) "Opera GX" else "Opera"
            "notion" -> "Notion"
            "spotify" -> "Spotify"
            "discord" -> "Discord"
            "calculadora" -> "Calculator"
            else -> installedApps[canonical] ?: canonical
        }

        val script = """
            if application "$macAppName" is running then
                tell application "$macAppName" to activate
                tell application "System Events"
                    tell process "$macAppName"
                        set frontmost to true
                        try
                            repeat with w in windows
                                if value of attribute "AXMinimized" of w is true then
                                    set value of attribute "AXMinimized" of w to false
                                end if
                            end repeat
                        end try
                    end tell
                end tell
                return "FOCUSED"
            else
                tell application "$macAppName" to activate
                return "LAUNCHED"
            end if
        """.trimIndent()

        val result = runAppleScript(script, wait = true)
        return if (result == "FOCUSED") "Ampliando ${macAppName.uppercase()} en tu Mac."
        else "Iniciando ${macAppName.uppercase()} en tu Mac."
    }

    private fun openOrFocusLinuxApp(canonical: String): String {
        val entry = installedApps.entries.firstOrNull { it.key == canonical }
        val target = entry?.value ?: canonical
        runLinuxShell("wmctrl -a \"$canonical\" || $target &")
        return "Activando ${canonical.uppercase()}."
    }

    fun controlSpotifyExact(command: String): String {
        val now = System.currentTimeMillis()
        if (now - lastMediaActionTimestamp < 450) return "Comando en proceso."
        lastMediaActionTimestamp = now

        val state = getSpotifyStatusAndTrack()
        val isOpen = state != "CLOSED"
        val isPlaying = state.startsWith("PLAYING:")

        if (!isOpen && (command == "PLAY" || command == "NEXT" || command == "PREV")) {
            playLikedSongsInSpotify(forceRestart = true)
            return "Abriendo Spotify e iniciando tus canciones de Mis Me Gusta."
        }

        return when {
            isWindows -> {
                when (command) {
                    "PAUSE" -> {
                        if (!isPlaying) return "Spotify ya está en pausa."
                        triggerWindowsMediaKey(0xB3, 1)
                        currentSpotifyTrack = "EN PAUSA"
                        "Música en pausa."
                    }
                    "PLAY" -> {
                        if (isPlaying) return "La música ya se está reproduciendo."
                        triggerWindowsMediaKey(0xB3, 1)
                        "Reanudando reproducción en Spotify."
                    }
                    "NEXT" -> { triggerWindowsMediaKey(0xB0, 1); "Siguiente canción en Spotify." }
                    "PREV" -> { triggerWindowsMediaKey(0xB1, 1); "Canción anterior en Spotify." }
                    else -> "Comando ejecutado."
                }
            }
            isMac -> {
                when (command) {
                    "PAUSE" -> { runAppleScript("tell application \"Spotify\" to pause"); currentSpotifyTrack = "EN PAUSA"; "Música en pausa." }
                    "PLAY" -> { runAppleScript("tell application \"Spotify\" to play"); "Reanudando reproducción en Spotify." }
                    "NEXT" -> { runAppleScript("tell application \"Spotify\" to next track"); "Siguiente canción en Spotify." }
                    "PREV" -> { runAppleScript("tell application \"Spotify\" to previous track"); "Canción anterior en Spotify." }
                    else -> "Comando ejecutado."
                }
            }
            isLinux -> {
                when (command) {
                    "PAUSE" -> { runLinuxShell("playerctl -p spotify pause"); "Música en pausa." }
                    "PLAY" -> { runLinuxShell("playerctl -p spotify play"); "Reanudando reproducción." }
                    "NEXT" -> { runLinuxShell("playerctl -p spotify next"); "Siguiente canción." }
                    "PREV" -> { runLinuxShell("playerctl -p spotify previous"); "Canción anterior." }
                    else -> "Comando ejecutado."
                }
            }
            else -> "Comando ejecutado."
        }
    }

    private fun triggerWindowsMediaKey(vkCode: Int, repeat: Int = 1) {
        val ps = """
            ${'$'}code = 'using System; using System.Runtime.InteropServices; public class Win32Key { [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo); }'
            Add-Type -TypeDefinition ${'$'}code -ErrorAction SilentlyContinue
            for (${'$'}i = 0; ${'$'}i -lt $repeat; ${'$'}i++) {
                [Win32Key]::keybd_event([byte]$vkCode, 0, 0, 0)
                Start-Sleep -Milliseconds 25
                [Win32Key]::keybd_event([byte]$vkCode, 0, 2, 0)
                if ($repeat -gt 1) { Start-Sleep -Milliseconds 20 }
            }
        """.trimIndent()
        runPowerShell(ps, wait = false)
    }

    fun playLikedSongsInSpotify(forceRestart: Boolean = false): String {
        thread {
            try {
                if (!forceRestart) {
                    val currentStatus = getSpotifyStatusAndTrack()
                    if (currentStatus.startsWith("PLAYING:")) {
                        currentSpotifyTrack = currentStatus.removePrefix("PLAYING:").take(32)
                        return@thread
                    }
                }
                when {
                    isWindows -> {
                        val ps = """
                            Start-Process "spotify:collection:tracks"
                            Start-Sleep -Milliseconds 2400
                            ${'$'}wsh = New-Object -ComObject WScript.Shell
                            ${'$'}sp = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | Select-Object -First 1
                            if (${'$'}null -ne ${'$'}sp) {
                                ${'$'}wsh.AppActivate(${'$'}sp.Id) | Out-Null
                                Start-Sleep -Milliseconds 400
                                ${'$'}wsh.SendKeys("{ENTER}")
                                Start-Sleep -Milliseconds 600
                                ${'$'}spCheck = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowTitle -ne "" } | Select-Object -First 1
                                if (${'$'}null -ne ${'$'}spCheck -and ${'$'}spCheck.MainWindowTitle -notlike "* - *") {
                                    ${'$'}wsh.AppActivate(${'$'}spCheck.Id) | Out-Null
                                    Start-Sleep -Milliseconds 250
                                    ${'$'}wsh.SendKeys("{TAB}{ENTER}")
                                }
                            }
                        """.trimIndent()
                        runPowerShell(ps, wait = false)
                    }
                    isMac -> {
                        val macScript = """
                            tell application "Spotify"
                                open location "spotify:collection:tracks"
                                delay 1.6
                                play
                            end tell
                        """.trimIndent()
                        runAppleScript(macScript, wait = false)
                    }
                    isLinux -> {
                        runLinuxShell("xdg-open spotify:collection:tracks && sleep 2 && playerctl -p spotify play")
                    }
                }
            } catch (e: Exception) {}
        }
        return "Reproduciendo tus canciones de Mis Me Gusta en Spotify."
    }

    fun controlBrowserVideo(): String {
        when {
            isWindows -> {
                val ps = """
                    ${'$'}wsh = New-Object -ComObject WScript.Shell
                    ${'$'}b = Get-Process opera, chrome, msedge, brave -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | Select-Object -First 1
                    if (${'$'}null -ne ${'$'}b) {
                        ${'$'}wsh.AppActivate(${'$'}b.Id) | Out-Null
                        Start-Sleep -Milliseconds 180
                        ${'$'}wsh.SendKeys("k")
                    }
                """.trimIndent()
                runPowerShell(ps, wait = false)
            }
            isMac -> {
                val macScript = """
                    if application "Opera GX" is running then
                        tell application "Opera GX" to activate
                    else if application "Opera" is running then
                        tell application "Opera" to activate
                    else if application "Google Chrome" is running then
                        tell application "Google Chrome" to activate
                    else if application "Safari" is running then
                        tell application "Safari" to activate
                    end if
                    delay 0.2
                    tell application "System Events" to keystroke "k"
                """.trimIndent()
                runAppleScript(macScript, wait = false)
            }
            isLinux -> {
                runLinuxShell("xdotool search --onlyvisible --Regexp 'Opera|Chrome|Firefox' windowactivate --sync key k")
            }
        }
        return "Alternando reproducción del video en el navegador sin alterar Spotify."
    }

    fun changeSystemVolume(direction: String): String {
        when {
            isWindows -> {
                when (direction) {
                    "UP" -> triggerWindowsMediaKey(0xAF, 5)
                    "DOWN" -> triggerWindowsMediaKey(0xAE, 5)
                    else -> triggerWindowsMediaKey(0xAD, 1)
                }
            }
            isMac -> {
                when (direction) {
                    "UP" -> runAppleScript("set volume output volume ((output volume of (get volume settings)) + 15)")
                    "DOWN" -> runAppleScript("set volume output volume ((output volume of (get volume settings)) - 15)")
                    else -> runAppleScript("set volume with output muted (not (output muted of (get volume settings)))")
                }
            }
            isLinux -> {
                when (direction) {
                    "UP" -> runLinuxShell("pactl set-sink-volume @DEFAULT_SINK@ +10%")
                    "DOWN" -> runLinuxShell("pactl set-sink-volume @DEFAULT_SINK@ -10%")
                    else -> runLinuxShell("pactl set-sink-mute @DEFAULT_SINK@ toggle")
                }
            }
        }
        return when (direction) {
            "UP" -> "Subiendo volumen del sistema."
            "DOWN" -> "Bajando volumen del sistema."
            else -> "Audio del sistema alternado."
        }
    }

    fun searchInOperaGX(rawQuery: String, platform: String = "GOOGLE"): String {
        val cleanQuery = rawQuery.trim()
        val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8)
        val targetUrl = when (platform) {
            "YOUTUBE" -> "https://www.youtube.com/results?search_query=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }

        try {
            when {
                isWindows -> {
                    val operaPath = findWindowsOperaGX()
                    if (operaPath != null) ProcessBuilder(operaPath, targetUrl).start()
                    else ProcessBuilder("cmd", "/c", "start", "", targetUrl).start()
                }
                isMac -> {
                    if (File("/Applications/Opera GX.app").exists()) {
                        ProcessBuilder("open", "-a", "Opera GX", targetUrl).start()
                    } else if (File("/Applications/Opera.app").exists()) {
                        ProcessBuilder("open", "-a", "Opera", targetUrl).start()
                    } else {
                        ProcessBuilder("open", targetUrl).start()
                    }
                }
                isLinux -> {
                    runLinuxShell("opera \"$targetUrl\" || xdg-open \"$targetUrl\" &")
                }
            }
        } catch (e: Exception) {}

        return if (platform == "YOUTUBE") "Buscando '$cleanQuery' en YouTube dentro de Opera GX."
        else "Buscando '$cleanQuery' en Opera GX."
    }

    private fun findWindowsOperaGX(): String? {
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val appData = System.getenv("APPDATA") ?: ""
        val candidates = listOf(
            File("$localAppData\\Programs\\Opera GX\\opera.exe"),
            File("$localAppData\\Programs\\Opera GX\\launcher.exe"),
            File("C:\\Program Files\\Opera GX\\opera.exe"),
            File("$appData\\Microsoft\\Windows\\Start Menu\\Programs\\Navegador Opera GX.lnk")
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath ?: installedApps["opera gx"]
    }

    fun executeWorkspaceMode(mode: WorkspaceMode): String {
        activeModeName = mode.id
        thread {
            try {
                mode.appsToClose.forEach { app ->
                    closeAppByName(app)
                    Thread.sleep(150)
                }
                mode.appsToOpen.forEach { app ->
                    openOrFocusApp(app)
                    Thread.sleep(350)
                }
                if (mode.spotifyPlaylist.isNotBlank()) {
                    Thread.sleep(600)
                    if (mode.spotifyPlaylist.contains("me gusta")) {
                        playLikedSongsInSpotify(forceRestart = false)
                    } else {
                        playPlaylist(mode.spotifyPlaylist, normalizeText(mode.spotifyPlaylist))
                    }
                }
            } catch (e: Exception) {}
        }
        return mode.greeting
    }

    fun createCustomModeForCurrentUser(name: String, appsList: List<String>): String {
        val acc = cloudVault.getCurrentAccount()
        val cleanId = normalizeText(name).uppercase().replace(" ", "_")
        val newMode = WorkspaceMode(
            id = cleanId,
            name = "PROTOCOLO $cleanId",
            triggers = listOf("modo ${normalizeText(name)}", "vamos a ${normalizeText(name)}"),
            appsToOpen = appsList.map { normalizeText(it) },
            appsToClose = emptyList(),
            spotifyPlaylist = "mis me gusta",
            greeting = "Activando modo $cleanId de la cuenta ${acc.username.uppercase()}."
        )
        acc.personalModes.removeIf { it.id == cleanId }
        acc.personalModes.add(newMode)
        acc.updatedAt = System.currentTimeMillis()
        cloudVault.pushToCloudAsync()
        return "Modo '$cleanId' guardado en la cuenta en la nube de '${acc.username.uppercase()}'."
    }

    fun scanInstalledApplications(): Int {
        installedApps.clear()
        try {
            when {
                isWindows -> scanWindowsApps()
                isMac -> scanMacApps()
                isLinux -> scanLinuxApps()
            }
        } catch (e: Exception) {}
        return installedApps.size
    }

    private fun scanWindowsApps() {
        val appData = System.getenv("APPDATA") ?: ""
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val programData = System.getenv("ProgramData") ?: "C:\\ProgramData"
        val userProfile = System.getProperty("user.home") ?: ""

        listOf(
            File("$appData\\Microsoft\\Windows\\Start Menu\\Programs"),
            File("$programData\\Microsoft\\Windows\\Start Menu\\Programs"),
            File("$userProfile\\Desktop")
        ).filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().maxDepth(3).forEach { file ->
                if (file.isFile && (file.extension.equals("lnk", true) || file.extension.equals("exe", true))) {
                    val cleanName = normalizeText(file.nameWithoutExtension)
                    if (cleanName.length > 2 && !cleanName.contains("uninstall")) {
                        installedApps[cleanName] = file.absolutePath
                    }
                }
            }
        }

        val directPaths = listOf(
            Pair("spotify", "$appData\\Spotify\\Spotify.exe"),
            Pair("vscode", "$localAppData\\Programs\\Microsoft VS Code\\Code.exe"),
            Pair("visual studio code", "$localAppData\\Programs\\Microsoft VS Code\\Code.exe"),
            Pair("visual", "$localAppData\\Programs\\Microsoft VS Code\\Code.exe"),
            Pair("notion", "$localAppData\\Programs\\Notion\\Notion.exe"),
            Pair("opera gx", "$localAppData\\Programs\\Opera GX\\opera.exe"),
            Pair("opera", "$localAppData\\Programs\\Opera GX\\opera.exe"),
            Pair("calculadora", "calc.exe"),
            Pair("bloc de notas", "notepad.exe"),
            Pair("explorador", "explorer.exe")
        )
        for (item in directPaths) {
            if (!item.second.contains("\\") || File(item.second).exists()) {
                installedApps[item.first] = item.second
            }
        }
    }

    private fun scanMacApps() {
        val home = System.getProperty("user.home") ?: ""
        listOf(
            File("/Applications"),
            File("/System/Applications"),
            File("/System/Applications/Utilities"),
            File("$home/Applications")
        ).filter { it.exists() }.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".app")) {
                    val appRealName = file.name.removeSuffix(".app")
                    val cleanName = normalizeText(appRealName)
                    installedApps[cleanName] = appRealName
                }
            }
        }
        installedApps["vscode"] = "Visual Studio Code"
        installedApps["visual"] = "Visual Studio Code"
        installedApps["visual studio code"] = "Visual Studio Code"
        installedApps["opera gx"] = if (File("/Applications/Opera GX.app").exists()) "Opera GX" else "Opera"
        installedApps["notion"] = "Notion"
        installedApps["spotify"] = "Spotify"
        installedApps["calculadora"] = "Calculator"
        installedApps["terminal"] = "Terminal"
    }

    private fun scanLinuxApps() {
        installedApps["vscode"] = "code"
        installedApps["visual"] = "code"
        installedApps["spotify"] = "spotify"
        installedApps["opera gx"] = "opera"
    }

    fun closeAppByName(normalizedCommand: String): String {
        val appName = normalizedCommand
            .replace(Regex("""^.*?(?:cierra|cerrar|termina|mata)\s+(?:la\s+aplicacion\s+|el\s+programa\s+|el\s+|la\s+)?"""), "")
            .trim()

        when {
            isWindows -> {
                val procName = when {
                    appName.contains("spotify") -> "Spotify"
                    appName.contains("discord") -> "Discord"
                    appName.contains("visual") || appName.contains("code") -> "Code"
                    appName.contains("notion") -> "Notion"
                    appName.contains("opera") -> "opera"
                    else -> appName.split(" ").first()
                }
                ProcessBuilder("cmd", "/c", "taskkill /IM $procName.exe /F").start()
            }
            isMac -> {
                val macApp = when {
                    appName.contains("spotify") -> "Spotify"
                    appName.contains("discord") -> "Discord"
                    appName.contains("visual") || appName.contains("code") -> "Visual Studio Code"
                    appName.contains("notion") -> "Notion"
                    appName.contains("opera") -> "Opera GX"
                    else -> appName
                }
                runAppleScript("tell application \"$macApp\" to quit")
            }
            isLinux -> {
                runLinuxShell("pkill -f \"$appName\"")
            }
        }
        return "He cerrado $appName."
    }

    fun minimizeAllWindows() {
        when {
            isWindows -> runPowerShell("(New-Object -ComObject Shell.Application).ToggleDesktop()", wait = false)
            isMac -> runAppleScript("tell application \"System Events\" to key code 103 using {command down}")
            isLinux -> runLinuxShell("wmctrl -k on || xdotool key super+d")
        }
    }

    fun getOpenWindowsReport(): String {
        return when {
            isWindows -> {
                val script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowTitle"
                val titles = runPowerShell(script, wait = true).lines().map { it.trim() }.filter { it.isNotBlank() }
                if (titles.isEmpty()) "No hay ventanas abiertas." else "Ventanas activas:\n" + titles.joinToString("\n") { "• $it" }
            }
            isMac -> {
                val script = "tell application \"System Events\" to get name of every process whose background only is false"
                val procs = runAppleScript(script, wait = true)
                "Aplicaciones activas en tu Mac: $procs"
            }
            else -> {
                val out = runLinuxShell("wmctrl -l", wait = true)
                "Ventanas activas:\n$out"
            }
        }
    }

    fun getCurrentlyPlayingSpotify(): String {
        val state = getSpotifyStatusAndTrack()
        return when {
            state == "CLOSED" -> "Spotify está cerrado en este momento."
            state == "PAUSED" -> "Spotify está abierto pero en pausa."
            else -> {
                val track = state.removePrefix("PLAYING:")
                currentSpotifyTrack = track.take(32)
                "Está sonando '$track' en Spotify."
            }
        }
    }

    private fun loadPlaylists() {
        savedPlaylists["mis me gusta"] = "spotify:collection:tracks"
        savedPlaylists["favoritas"] = "spotify:collection:tracks"
        savedPlaylists["reggaeton"] = "spotify:playlist:37i9dQZF1DWY7IeIP1cdjF"
        savedPlaylists["rock en espanol"] = "spotify:playlist:37i9dQZF1DWZjqjZMudx9T"
        savedPlaylists["programar"] = "spotify:playlist:37i9dQZF1DX5trt9i14X7j"
        savedPlaylists["entrenar"] = "spotify:playlist:37i9dQZF1DX76Wlfdnj7AP"
        if (playlistsFile.exists()) {
            try {
                val rootNode = objectMapper.readTree(playlistsFile)
                rootNode.fields().forEachRemaining { entry ->
                    savedPlaylists[entry.key] = entry.value.asText()
                }
            } catch (e: Exception) {}
        } else {
            savePlaylists()
        }
    }

    private fun savePlaylists() {
        try { objectMapper.writeValue(playlistsFile, savedPlaylists) } catch (e: Exception) {}
    }

    fun registerPlaylist(name: String, spotifyUrlOrUri: String): String {
        val cleanName = normalizeText(name)
        val uri = if (spotifyUrlOrUri.contains("open.spotify.com/playlist/")) {
            val id = spotifyUrlOrUri.substringAfter("playlist/").substringBefore("?").trim()
            "spotify:playlist:$id"
        } else {
            spotifyUrlOrUri.trim()
        }
        savedPlaylists[cleanName] = uri
        savePlaylists()
        return "Playlist '$cleanName' vinculada a $uri."
    }

    fun playPlaylist(rawCommand: String, normalized: String): String {
        if (normalized.contains("me gusta") || normalized.contains("favoritas")) {
            return playLikedSongsInSpotify(forceRestart = true)
        }

        val playlistQuery = normalized
            .replace(Regex("""^.*?(?:playlist|lista\s+de\s+reproduccion|lista|mis\s+canciones\s+de|musica\s+para)\s*(?:de\s+|llamada\s+|para\s+)?"""), "")
            .replace("en spotify", "")
            .trim()
            .ifBlank { "mis me gusta" }

        if (playlistQuery == "mis me gusta") return playLikedSongsInSpotify(forceRestart = true)

        val savedMatch = savedPlaylists.entries.firstOrNull {
            playlistQuery.contains(it.key) || it.key.contains(playlistQuery)
        }

        thread {
            try {
                val targetUri = savedMatch?.value ?: findSpotifyUri("playlist", playlistQuery)
                if (targetUri != null) {
                    launchSpotifyUriAndPlay(targetUri, isSearch = false)
                } else {
                    val encoded = URLEncoder.encode(playlistQuery, StandardCharsets.UTF_8).replace("+", "%20")
                    launchSpotifyUriAndPlay("spotify:search:$encoded", isSearch = true)
                }
            } catch (e: Exception) {}
        }

        return "Iniciando playlist '$playlistQuery' en Spotify."
    }

    fun playSongInSpotify(songQuery: String?): String {
        val cleanSong = songQuery?.trim() ?: ""
        if (cleanSong.isBlank() || cleanSong in setOf("musica", "la musica", "la cancion", "spotify")) {
            return controlSpotifyExact("PLAY")
        }
        if (cleanSong.contains("mis me gusta") || cleanSong.contains("canciones que me gustan")) {
            return playLikedSongsInSpotify(forceRestart = true)
        }

        thread {
            try {
                val trackUri = findSpotifyUri("track", cleanSong)
                if (trackUri != null) {
                    launchSpotifyUriAndPlay(trackUri, isSearch = false)
                } else {
                    val encoded = URLEncoder.encode(cleanSong, StandardCharsets.UTF_8).replace("+", "%20")
                    launchSpotifyUriAndPlay("spotify:search:$encoded", isSearch = true)
                }
            } catch (e: Exception) {}
        }
        return "Reproduciendo '$cleanSong' en Spotify."
    }

    private fun launchSpotifyUriAndPlay(spotifyUri: String, isSearch: Boolean) {
        when {
            isWindows -> {
                val keysToSend = if (isSearch) "{TAB}{ENTER}" else "{ENTER}"
                val ps = """
                    Start-Process "$spotifyUri"
                    Start-Sleep -Milliseconds 2200
                    ${'$'}wsh = New-Object -ComObject WScript.Shell
                    ${'$'}sp = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | Select-Object -First 1
                    if (${'$'}null -ne ${'$'}sp) {
                        ${'$'}wsh.AppActivate(${'$'}sp.Id) | Out-Null
                        Start-Sleep -Milliseconds 400
                        ${'$'}wsh.SendKeys("$keysToSend")
                    }
                """.trimIndent()
                runPowerShell(ps, wait = false)
            }
            isMac -> {
                val macScript = """
                    tell application "Spotify"
                        open location "$spotifyUri"
                        delay 1.6
                        play
                    end tell
                """.trimIndent()
                runAppleScript(macScript, wait = false)
            }
            isLinux -> {
                runLinuxShell("xdg-open \"$spotifyUri\" && sleep 2 && playerctl -p spotify play")
            }
        }
    }

    private fun findSpotifyUri(type: String, query: String): String? {
        val searchEngines = listOf(
            "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode("site:open.spotify.com/$type $query", StandardCharsets.UTF_8),
            "https://search.yahoo.com/search?p=" + URLEncoder.encode("site:open.spotify.com/$type $query", StandardCharsets.UTF_8)
        )
        val regex = Regex("""open\.spotify\.com/$type/([a-zA-Z0-9]{22})""")

        for (url in searchEngines) {
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .GET().build()
                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                val decodedBody = try {
                    URLDecoder.decode(res.body(), StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    res.body().replace("%2F", "/").replace("%3A", ":")
                }
                val match = regex.find(decodedBody)
                if (match != null) return "spotify:$type:${match.groupValues[1]}"
            } catch (e: Exception) {}
        }
        return null
    }

    fun ensureDesktopAndStartupApp(): String {
        return try {
            val workingDir = File(System.getProperty("user.dir")).absolutePath
            val libsDir = File(workingDir, "build/libs")
            val jarFile = libsDir.listFiles()?.firstOrNull { it.name.endsWith(".jar") && !it.name.contains("plain") }
                ?: File(workingDir, "daemon.jar")
            if (!jarFile.exists()) return ""

            when {
                isWindows -> {
                    val launcherVbs = File(workingDir, "DaemonApp.vbs")
                    val vbsContent = """
                        Set fso = CreateObject("Scripting.FileSystemObject")
                        Set shell = CreateObject("WScript.Shell")
                        shell.CurrentDirectory = "$workingDir"
                        javawPath = "javaw.exe"
                        If fso.FolderExists("C:\Program Files\Microsoft") Then
                            For Each folder In fso.GetFolder("C:\Program Files\Microsoft").SubFolders
                                If InStr(LCase(folder.Name), "jdk") > 0 Then
                                    javawPath = Chr(34) & folder.Path & "\bin\javaw.exe" & Chr(34)
                                End If
                            Next
                        End If
                        shell.Run javawPath & " -Xms32m -Xmx128m -XX:+UseSerialGC -jar " & Chr(34) & "${jarFile.absolutePath}" & Chr(34), 0, False
                    """.trimIndent()
                    launcherVbs.writeText(vbsContent, StandardCharsets.ISO_8859_1)

                    val psStartup = """
                        ${'$'}wsh = New-Object -ComObject WScript.Shell
                        ${'$'}desktop = [Environment]::GetFolderPath('Desktop')
                        ${'$'}startup = [Environment]::GetFolderPath('Startup')
                        ${'$'}s1 = ${'$'}wsh.CreateShortcut("${'$'}desktop\Daemon AI.lnk")
                        ${'$'}s1.TargetPath = "wscript.exe"
                        ${'$'}s1.Arguments = '"${launcherVbs.absolutePath}"'
                        ${'$'}s1.WorkingDirectory = "$workingDir"
                        ${'$'}s1.IconLocation = "shell32.dll,13"
                        ${'$'}s1.Save()
                        ${'$'}s2 = ${'$'}wsh.CreateShortcut("${'$'}startup\Daemon AI.lnk")
                        ${'$'}s2.TargetPath = "wscript.exe"
                        ${'$'}s2.Arguments = '"${launcherVbs.absolutePath}"'
                        ${'$'}s2.WorkingDirectory = "$workingDir"
                        ${'$'}s2.IconLocation = "shell32.dll,13"
                        ${'$'}s2.Save()
                        Set-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "DaemonAI" -Value "wscript.exe `"${launcherVbs.absolutePath}`"" -Force
                    """.trimIndent()
                    runPowerShell(psStartup, wait = false)
                    "Auto-inicio ligero registrado en Windows."
                }
                isMac -> {
                    val home = System.getProperty("user.home")
                    val javaBin = System.getProperty("java.home") + "/bin/java"
                    val launchAgentsDir = File("$home/Library/LaunchAgents")
                    if (!launchAgentsDir.exists()) launchAgentsDir.mkdirs()

                    val plistFile = File(launchAgentsDir, "com.cristian.daemon.ai.plist")
                    val plistXml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                        <dict>
                            <key>Label</key>
                            <string>com.cristian.daemon.ai</string>
                            <key>ProgramArguments</key>
                            <array>
                                <string>$javaBin</string>
                                <string>-Xms32m</string>
                                <string>-Xmx128m</string>
                                <string>-XX:+UseSerialGC</string>
                                <string>-jar</string>
                                <string>${jarFile.absolutePath}</string>
                            </array>
                            <key>WorkingDirectory</key>
                            <string>$workingDir</string>
                            <key>RunAtLoad</key>
                            <true/>
                        </dict>
                        </plist>
                    """.trimIndent()
                    plistFile.writeText(plistXml, StandardCharsets.UTF_8)

                    val cmdFile = File("$home/Desktop/Daemon AI.command")
                    cmdFile.writeText("#!/bin/bash\ncd \"$workingDir\"\n\"$javaBin\" -Xms32m -Xmx128m -XX:+UseSerialGC -jar \"${jarFile.absolutePath}\" &\n", StandardCharsets.UTF_8)
                    cmdFile.setExecutable(true)
                    "Auto-inicio ligero registrado en macOS."
                }
                else -> ""
            }
        } catch (e: Exception) { "" }
    }

    fun normalizeText(text: String): String {
        val temp = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return temp.replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .replace(Regex("[^a-z0-9\\s\\.]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

// ============================================================================
// 3. CEREBRO DE DAEMON CON MEMORIA POR USUARIO EN LA NUBE + COMANDOS ADMIN
// ============================================================================
@Service
class DaemonBrain(
    private val systemScanner: SystemScanner,
    val cloudVault: CloudVaultService
) {
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val ollamaUrl = "http://localhost:11434/api/chat"
    private var ollamaModel = "qwen2.5:1.5b"
    @Volatile var isOllamaOnline: Boolean = false

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile var uiState: String = "IDLE"
    @Volatile var isSpeakingNow: Boolean = false
    @Volatile var directListenUntil: Long = 0L

    private var starkHud: StarkHologramHUD? = null
    private val wakeWords = listOf("daemon", "daimon", "deimon", "daymon", "demon", "damon")

    init {
        checkOllamaStatus()
        launchStarkHologramHUD()
        startContinuousWakeWordEar()
        val acc = cloudVault.getCurrentAccount()
        speakAndDisplay(
            "NÚCLEO CLOUD v11.0",
            "Daemon en línea sobre ${systemScanner.getOSLabel()}. Sesión activa: ${acc.username.uppercase()} (${acc.role})."
        )
    }

    private fun startContinuousWakeWordEar() {
        if (!systemScanner.isWindows) return
        thread(isDaemon = true, name = "Daemon-LowPower-Ear") {
            Thread.sleep(2500)
            while (true) {
                try {
                    val psContinuousEar = """
                        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                        Add-Type -AssemblyName System.Speech
                        ${'$'}recognizers = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers()
                        if (${'$'}recognizers.Count -eq 0) { exit }
                        ${'$'}esRec = ${'$'}recognizers | Where-Object { ${'$'}_.Culture.Name -like 'es*' } | Select-Object -First 1
                        if (${'$'}null -eq ${'$'}esRec) { ${'$'}esRec = ${'$'}recognizers | Select-Object -First 1 }
                        
                        ${'$'}engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine(${'$'}esRec.Id)
                        ${'$'}engine.SetInputToDefaultAudioDevice()
                        
                        ${'$'}choices = New-Object System.Speech.Recognition.Choices
                        ${'$'}choices.Add(@(
                            'daemon', 'oye daemon', 'hola daemon',
                            'daemon pausa', 'daemon dale play', 'daemon play', 'daemon reproduce',
                            'daemon siguiente cancion', 'daemon siguiente', 'daemon cambia de cancion',
                            'daemon cancion anterior', 'daemon anterior',
                            'daemon sube el volumen', 'daemon baja el volumen', 'daemon silencia',
                            'daemon pausa el video', 'daemon dale play al video',
                            'daemon vamos a trabajar', 'daemon modo trabajo',
                            'daemon vamos a hacer tarea', 'daemon modo tarea',
                            'daemon vamos a estudiar', 'daemon modo estudio',
                            'daemon vamos a jugar', 'daemon modo gaming',
                            'daemon pon mis me gusta', 'daemon abre spotify', 'daemon abre opera gx',
                            'daemon abre visual', 'daemon abre notion', 'daemon abre el panel',
                            'pausa', 'dale play', 'siguiente cancion', 'cancion anterior',
                            'vamos a trabajar', 'vamos a hacer tarea', 'vamos a estudiar'
                        ))
                        ${'$'}gb = New-Object System.Speech.Recognition.GrammarBuilder(${'$'}choices)
                        ${'$'}gb.Culture = ${'$'}esRec.Culture
                        ${'$'}engine.LoadGrammar((New-Object System.Speech.Recognition.Grammar(${'$'}gb)))
                        
                        while (${'$'}true) {
                            ${'$'}res = ${'$'}engine.Recognize([TimeSpan]::FromSeconds(5))
                            if (${'$'}null -ne ${'$'}res -and ${'$'}res.Confidence -gt 0.42) {
                                Write-Output ("HEARD:" + ${'$'}res.Text)
                                [Console]::Out.Flush()
                            } else {
                                Start-Sleep -Milliseconds 150
                            }
                        }
                    """.trimIndent()

                    val utf16 = psContinuousEar.toByteArray(StandardCharsets.UTF_16LE)
                    val b64 = Base64.getEncoder().encodeToString(utf16)
                    val proc = ProcessBuilder(
                        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", b64
                    ).start()

                    val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val rawLine = line?.trim() ?: continue
                        if (!rawLine.startsWith("HEARD:")) continue
                        if (isSpeakingNow) continue

                        val heardText = rawLine.removePrefix("HEARD:").trim()
                        val normalized = systemScanner.normalizeText(heardText)
                        if (normalized.length < 3) continue

                        val isDirectWindowActive = System.currentTimeMillis() < directListenUntil
                        val hasWakeWord = wakeWords.any { w -> normalized.contains(w) }

                        if (hasWakeWord || isDirectWindowActive) {
                            var cleanCommand = normalized
                            for (w in wakeWords) {
                                cleanCommand = cleanCommand.replace(Regex("""^(?:oye\s+|hola\s+)?$w\b\s*"""), "").trim()
                            }

                            if (cleanCommand.isBlank() || cleanCommand in setOf("oye", "hola", "estas ahi", "me escuchas")) {
                                directListenUntil = System.currentTimeMillis() + 8000L
                                uiState = "LISTENING"
                                speakAndDisplay("DAEMON", "Sí, ${cloudVault.getCurrentAccount().username}, te escucho.")
                            } else {
                                directListenUntil = 0L
                                val reply = processThought(cleanCommand)
                                speakAndDisplay(cleanCommand, reply)
                            }
                        }
                    }
                    proc.waitFor()
                } catch (e: Exception) {
                    Thread.sleep(4000)
                }
            }
        }
    }

    fun activateDirectVoiceListenWindow() {
        directListenUntil = System.currentTimeMillis() + 8000L
        uiState = "LISTENING"
        starkHud?.setStatusText("TE ESCUCHO (${cloudVault.getCurrentAccount().username.uppercase()})...")
        if (systemScanner.isMac) {
            starkHud?.setOrbMode(false)
        }
    }

    private fun checkOllamaStatus(): Boolean {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/tags"))
                .timeout(Duration.ofSeconds(1))
                .GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) {
                val root = objectMapper.readTree(res.body()).path("models")
                if (root.isArray && root.size() > 0) {
                    val installedModels = root.map { it.path("name").asText() }
                    ollamaModel = installedModels.firstOrNull { it.contains("qwen") || it.contains("llama") }
                        ?: installedModels.first()
                    isOllamaOnline = true
                    return true
                }
            }
            isOllamaOnline = false
            false
        } catch (e: Exception) {
            isOllamaOnline = false
            false
        }
    }

    // ========================================================================
    // PROCESADOR CENTRAL (CUENTAS, ADMIN, MODOS POR USUARIO, SPOTIFY Y CHAT)
    // ========================================================================
    fun processThought(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isBlank()) return "Sin señal de entrada."
        uiState = "THINKING"

        var normalized = systemScanner.normalizeText(input)
        for (w in wakeWords) {
            normalized = normalized.replace(Regex("""^(?:oye\s+|hola\s+)?$w\b\s*"""), "").trim()
        }

        // --------------------------------------------------------------------
        // A) GESTIÓN DE CUENTAS Y SESIONES EN LA NUBE
        // --------------------------------------------------------------------
        // Ej: "iniciar sesion como cristian clave admin2026"
        val loginRegex = Regex("""^(?:iniciar\s+sesion|inicia\s+sesion|login|cambiar\s+a\s+usuario|entrar\s+como)\s+(?:como\s+)?([a-zA-Z0-9_]+)\s+(?:clave|password|contrasena|pin)\s+(.+)$""", RegexOption.IGNORE_CASE)
        loginRegex.find(input)?.let { m ->
            val reply = cloudVault.loginOrSwitchUser(m.groupValues[1], m.groupValues[2])
            starkHud?.refreshAccountBadges()
            return reply
        }

        // Ej: "registrar usuario mich clave 1234"
        val registerRegex = Regex("""^(?:registrar\s+usuario|crear\s+cuenta|nuevo\s+usuario|registra\s+a)\s+([a-zA-Z0-9_]+)\s+(?:clave|password|contrasena|pin)\s+(.+)$""", RegexOption.IGNORE_CASE)
        registerRegex.find(input)?.let { m ->
            val reply = cloudVault.registerNewUser(m.groupValues[1], m.groupValues[2])
            starkHud?.refreshAccountBadges()
            return reply
        }

        if (normalized in setOf("quien soy", "que cuenta esta activa", "mi cuenta", "estado de mi cuenta")) {
            val acc = cloudVault.getCurrentAccount()
            return "Estás conectado con la cuenta '${acc.username.uppercase()}' (Rol: ${acc.role}). Tienes ${acc.personalFacts.size} recuerdos personales y ${acc.personalKnowledge.size} conocimientos propios sincronizados en la nube (${cloudVault.cloudSyncStatus})."
        }

        if (normalized.contains("sincronizar nube") || normalized.contains("sincroniza ahora") || normalized == "sync") {
            val ok = cloudVault.pullFromCloudNow()
            cloudVault.pushToCloudAsync()
            return if (ok) "Sincronización con la nube completada exitosamente."
            else "Memoria guardada en caché local. Configura la URL de tu nube desde el botón [👑 ADMIN] para sincronizar entre dispositivos."
        }

        // --------------------------------------------------------------------
        // B) COMANDOS EXCLUSIVOS DE LA CUENTA DE ADMINISTRADOR (CRISTIAN)
        // --------------------------------------------------------------------
        if (input.lowercase().startsWith("admin global:") || input.lowercase().startsWith("admin entrena:")) {
            val body = input.substringAfter(":")
            if (body.contains("=")) {
                val patterns = body.substringBefore("=").split("/").map { it.trim() }.filter { it.isNotBlank() }
                val response = body.substringAfter("=").trim()
                val tag = "global_" + (System.currentTimeMillis() % 100000)
                return cloudVault.adminAddGlobalKnowledge(tag, patterns, response)
            }
        }

        if (normalized.startsWith("admin lista de usuarios") || normalized == "admin usuarios") {
            return cloudVault.adminListUsers()
        }

        if (normalized.startsWith("admin elimina usuario ")) {
            val target = normalized.removePrefix("admin elimina usuario ").trim()
            return cloudVault.adminDeleteUser(target)
        }

        if (normalized.startsWith("admin haz administrador a ")) {
            val target = normalized.removePrefix("admin haz administrador a ").trim()
            return cloudVault.adminSetUserRole(target, "ADMIN")
        }

        if (input.lowercase().startsWith("admin nube ") || input.lowercase().startsWith("conecta nube ")) {
            val url = input.substringAfter("nube ").trim()
            return cloudVault.adminSetCloudDatabaseUrl(url)
        }

        // --------------------------------------------------------------------
        // C) APRENDIZAJE PERSONAL EN LA NUBE (LIGADO A LA CUENTA ACTIVA)
        // --------------------------------------------------------------------
        if (normalized.startsWith("recuerda que ") || normalized.startsWith("aprende que ") || normalized.startsWith("memoriza que ")) {
            val fact = input.replace(Regex("""^(?:recuerda|aprende|memoriza)\s+que\s+""", RegexOption.IGNORE_CASE), "").trim()
            return recordAndReturn(input, cloudVault.addPersonalFactToCurrentUser(fact))
        }

        if (input.lowercase().startsWith("entrena:")) {
            val body = input.substringAfter(":")
            if (body.contains("=")) {
                val patterns = body.substringBefore("=").split("/").map { it.trim() }.filter { it.isNotBlank() }
                val response = body.substringAfter("=").trim()
                val tag = "usr_" + (System.currentTimeMillis() % 100000)
                return recordAndReturn(input, cloudVault.addPersonalKnowledgeToCurrentUser(tag, patterns, response))
            }
        }

        // --------------------------------------------------------------------
        // D) MODOS DE TRABAJO DEL USUARIO ACTIVO
        // --------------------------------------------------------------------
        val currentAcc = cloudVault.getCurrentAccount()
        val activeModes = if (currentAcc.personalModes.isNotEmpty()) currentAcc.personalModes else cloudVault.buildDefaultModesForUser(currentAcc.username)

        for (mode in activeModes) {
            val matchedTrigger = mode.triggers.any { t ->
                val normT = systemScanner.normalizeText(t)
                normalized == normT || normalized.contains(normT)
            }
            if (matchedTrigger) {
                return recordAndReturn(input, systemScanner.executeWorkspaceMode(mode))
            }
        }

        val createModeRegex = Regex("""^(?:crea|crear|nuevo|guarda)\s+modo\s+(.+?)\s+con\s+(.+)$""")
        createModeRegex.find(normalized)?.let { m ->
            val modeName = m.groupValues[1].trim()
            val apps = m.groupValues[2].split(",", " y ").map { it.trim() }.filter { it.isNotBlank() }
            return recordAndReturn(input, systemScanner.createCustomModeForCurrentUser(modeName, apps))
        }

        // --------------------------------------------------------------------
        // E) CONTROL DE VENTANAS, OPERA GX, VIDEO Y SPOTIFY
        // --------------------------------------------------------------------
        if (normalized.contains("abre el panel") || normalized.contains("expande el nucleo") || normalized.contains("modo ventana")) {
            starkHud?.setOrbMode(false)
            return recordAndReturn(input, "Desplegando el panel completo.")
        }
        if (normalized.contains("modo nucleo") || normalized.contains("minimiza el panel") || normalized.contains("hazte chiquito")) {
            starkHud?.setOrbMode(true)
            return recordAndReturn(input, "Contrayendo a modo núcleo flotante.")
        }

        val ytSearchRegex = Regex("""^(?:abre\s+opera\s+gx\s+y\s+|abre\s+youtube\s+y\s+)?(?:busca\s+en\s+youtube|buscar\s+en\s+youtube|pon\s+un\s+video\s+de|busca\s+un\s+video\s+de)\s+(.+)$""")
        ytSearchRegex.find(normalized)?.let { m ->
            val query = m.groupValues[1].replace("en opera gx", "").trim()
            return recordAndReturn(input, systemScanner.searchInOperaGX(query, "YOUTUBE"))
        }

        val operaSearchRegex = Regex("""^(?:abre\s+opera\s+gx\s+y\s+|abre\s+opera\s+y\s+)?(?:busca\s+en\s+opera\s+gx|busca\s+en\s+opera|buscar\s+en\s+opera\s+gx|busca\s+en\s+google|busca\s+en\s+internet|investiga\s+en\s+opera(?:\s+gx)?|busca\s+en\s+el\s+navegador)\s+(.+)$""")
        operaSearchRegex.find(normalized)?.let { m ->
            val query = m.groupValues[1].trim()
            return recordAndReturn(input, systemScanner.searchInOperaGX(query, "GOOGLE"))
        }

        if (normalized.contains("video") || normalized.contains("youtube")) {
            if (normalized.contains("pausa") || normalized.contains("deten") || normalized.contains("play") || normalized.contains("reanuda") || normalized.contains("continua")) {
                return recordAndReturn(input, systemScanner.controlBrowserVideo())
            }
        }

        val isPauseOrder = normalized in setOf(
            "pausa", "pon pausa", "ponle pausa", "pausa spotify", "pausa la musica",
            "pausa la cancion", "deten la musica", "deten la cancion", "para la musica", "pausar"
        ) || (normalized.contains("pausa") && !normalized.contains("video"))
        if (isPauseOrder) {
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PAUSE"))
        }

        val isPlayOrder = normalized in setOf(
            "play", "dale play", "pon play", "ponle play", "dale play a spotify",
            "reanuda la musica", "reanuda la cancion", "reanuda spotify", "continua la musica",
            "dale play a la cancion", "dale play a la musica", "quita la pausa", "reproduce"
        )
        if (isPlayOrder) {
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PLAY"))
        }

        val isNextTrack = normalized in setOf("siguiente", "cambiale", "otra", "otra cancion", "siguiente cancion", "siguiente pista", "siguiente rola", "salta la cancion") ||
            normalized.contains("siguiente cancion") || normalized.contains("cambia de cancion") ||
            normalized.contains("cambia la cancion") || normalized.contains("cambiale a la cancion") ||
            normalized.contains("pasa a la siguiente") || normalized.contains("salta esta cancion") ||
            normalized.contains("adelanta la cancion") || normalized.contains("pon la que sigue")
        if (isNextTrack) {
            return recordAndReturn(input, systemScanner.controlSpotifyExact("NEXT"))
        }

        val isPrevTrack = normalized in setOf("anterior", "atras", "cancion anterior", "pista anterior", "rola anterior") ||
            normalized.contains("cancion anterior") || normalized.contains("regresa la cancion") ||
            normalized.contains("pista anterior") || normalized.contains("pon la anterior") ||
            normalized.contains("regresale a la cancion")
        if (isPrevTrack) {
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PREV"))
        }

        if (normalized.contains("sube el volumen") || normalized.contains("subele al volumen") || normalized.contains("subele a la musica") || normalized == "subele" || normalized.contains("mas volumen")) {
            return recordAndReturn(input, systemScanner.changeSystemVolume("UP"))
        }
        if (normalized.contains("baja el volumen") || normalized.contains("bajale al volumen") || normalized.contains("bajale a la musica") || normalized == "bajale" || normalized.contains("menos volumen")) {
            return recordAndReturn(input, systemScanner.changeSystemVolume("DOWN"))
        }
        if (normalized.contains("silencia") || normalized.contains("pon mute") || normalized == "mute" || normalized.contains("quita el sonido")) {
            return recordAndReturn(input, systemScanner.changeSystemVolume("MUTE"))
        }

        if (normalized.contains("mis me gusta") || normalized.contains("canciones que me gustan")) {
            return recordAndReturn(input, systemScanner.playLikedSongsInSpotify(forceRestart = true))
        }
        if (normalized.startsWith("vincula playlist ") || normalized.startsWith("guarda playlist ")) {
            val name = input.substringAfter("playlist ").substringBefore(" con ").trim()
            val link = input.substringAfter(" con ").trim()
            return recordAndReturn(input, systemScanner.registerPlaylist(name, link))
        }
        if (normalized.contains("playlist") || normalized.contains("lista de reproduccion")) {
            return recordAndReturn(input, systemScanner.playPlaylist(input, normalized))
        }

        val songCommandRegex = Regex("""^(?:abre\s+spotify\s+y\s+)?(?:reproduce|pon\s+la\s+cancion|pon\s+musica\s+de|pon\s+en\s+spotify|dale\s+play\s+a|toca|busca\s+en\s+spotify)\s+(.+)$""")
        songCommandRegex.find(normalized)?.let { m ->
            val song = m.groupValues[1].replace("en spotify", "").trim()
            return recordAndReturn(input, systemScanner.playSongInSpotify(song))
        }
        if (normalized.endsWith(" en spotify") && (normalized.startsWith("pon ") || normalized.startsWith("escuchar "))) {
            val song = normalized.removePrefix("pon ").removePrefix("escuchar ").removeSuffix(" en spotify").trim()
            return recordAndReturn(input, systemScanner.playSongInSpotify(song))
        }

        if (normalized.contains("que cancion esta sonando") || normalized.contains("que suena en spotify") || normalized.contains("que estoy escuchando")) {
            return recordAndReturn(input, systemScanner.getCurrentlyPlayingSpotify())
        }
        if (normalized.contains("que ventanas estan abiertas") || normalized.contains("que programas estan corriendo")) {
            return recordAndReturn(input, systemScanner.getOpenWindowsReport())
        }
        if (normalized.contains("minimiza todo") || normalized.contains("muestra el escritorio")) {
            systemScanner.minimizeAllWindows()
            return recordAndReturn(input, "Despejando el escritorio.")
        }

        if (normalized.matches(Regex("""^(?:abre|abrir|inicia|ejecuta|lanza)\s+.+$"""))) {
            return recordAndReturn(input, systemScanner.openOrFocusApp(normalized))
        }
        if (normalized.matches(Regex("""^(?:cierra|cerrar|termina|mata)\s+.+$"""))) {
            return recordAndReturn(input, systemScanner.closeAppByName(normalized))
        }

        // --------------------------------------------------------------------
        // F) BASE DE CONOCIMIENTO EN LA NUBE (PERSONAL DEL USUARIO + GLOBAL ADMIN)
        // --------------------------------------------------------------------
        cloudVault.findKnowledgeMatch(normalized)?.let { exactNodeReply ->
            return recordAndReturn(input, exactNodeReply)
        }

        // --------------------------------------------------------------------
        // G) CÓRTEX CONVERSACIONAL CON LA MEMORIA EN LA NUBE DEL USUARIO ACTIVO
        // --------------------------------------------------------------------
        if (isOllamaOnline) {
            generateOllamaConversationalResponse(input)?.let { reply ->
                return recordAndReturn(input, reply)
            }
        }

        generateCloudConversationalResponse(input)?.let { reply ->
            return recordAndReturn(input, reply)
        }

        return recordAndReturn(input, "En línea (${currentAcc.username.uppercase()}). ¿Qué orden ejecutamos?")
    }

    private fun buildSystemPrompt(): String {
        val acc = cloudVault.getCurrentAccount()
        val horaActual = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm (dd/MM/yyyy)"))
        val factsContext = if (acc.personalFacts.isEmpty()) "" else "Memoria en la nube de ${acc.username}:\n" + acc.personalFacts.joinToString("\n") { "- $it" }
        val globalContext = if (cloudVault.vault.globalKnowledge.isEmpty()) "" else "Base de conocimiento global del Administrador:\n" +
            cloudVault.vault.globalKnowledge.take(15).joinToString("\n") { "- ${it.tag}: ${it.responses.firstOrNull() ?: ""}" }

        return """
            Eres Daemon (v11.0), un asistente de inteligencia artificial estilo J.A.R.V.I.S. con memoria en la nube multi-dispositivo, creado y administrado por Cristian Alexis.
            Actualmente estás atendiendo a la cuenta: ${acc.username.uppercase()} (Rol: ${acc.role}) en un dispositivo ${systemScanner.getOSLabel()}.
            Hora actual: $horaActual. Modo activo: ${systemScanner.activeModeName}.
            $factsContext
            $globalContext
            
            Directrices:
            1. Habla siempre en español de forma inteligente, leal y fluida, dirigiéndote a ${acc.username}.
            2. Sé conciso (1 a 2 párrafos cortos).
            3. Si ${acc.username} te pide ejecutar una acción durante la charla, añade al final una etiqueta exacta:
               [ACTION:MODE_WORK]
               [ACTION:MODE_HOMEWORK]
               [ACTION:MODE_STUDY]
               [ACTION:PLAY_LIKED]
               [ACTION:PLAY_SONG:nombre]
               [ACTION:PAUSE_MUSIC]
               [ACTION:PLAY_MUSIC]
               [ACTION:NEXT_TRACK]
               [ACTION:PREV_TRACK]
               [ACTION:PAUSE_VIDEO]
               [ACTION:SEARCH_OPERA:consulta]
               [ACTION:OPEN_APP:nombre]
        """.trimIndent()
    }

    private fun buildMessageHistory(userInput: String): List<ChatMessage> {
        val acc = cloudVault.getCurrentAccount()
        val list = mutableListOf(ChatMessage("system", buildSystemPrompt()))
        synchronized(acc.recentHistory) {
            acc.recentHistory.takeLast(10).forEach { turn ->
                list.add(ChatMessage(turn.role, turn.content))
            }
        }
        list.add(ChatMessage("user", userInput))
        return list
    }

    private fun generateOllamaConversationalResponse(userInput: String): String? {
        return try {
            val payload = OllamaPayload(
                model = ollamaModel,
                messages = buildMessageHistory(userInput),
                stream = false,
                options = OllamaOptions(temperature = 0.7, num_predict = 240)
            )
            val jsonBody = objectMapper.writeValueAsString(payload)
            val req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build()

            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return null

            val rawReply = objectMapper.readTree(res.body()).path("message").path("content").asText("").trim()
            if (rawReply.isBlank()) return null
            executeEmbeddedActions(rawReply)
        } catch (e: Exception) {
            isOllamaOnline = false
            null
        }
    }

    private fun generateCloudConversationalResponse(userInput: String): String? {
        return try {
            val payload = CloudChatPayload(
                messages = buildMessageHistory(userInput),
                model = "openai",
                seed = 42,
                jsonMode = false
            )
            val jsonBody = objectMapper.writeValueAsString(payload)
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://text.pollinations.ai/"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("User-Agent", "DaemonStarkCloud/11.0")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build()

            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return null

            val rawReply = res.body().trim()
            if (rawReply.isBlank()) return null
            executeEmbeddedActions(rawReply)
        } catch (e: Exception) {
            null
        }
    }

    private fun executeEmbeddedActions(llmReply: String): String {
        val acc = cloudVault.getCurrentAccount()
        val modes = if (acc.personalModes.isNotEmpty()) acc.personalModes else cloudVault.buildDefaultModesForUser(acc.username)
        val actionRegex = Regex("""\[ACTION:([A-Z_]+)(?::([^\]]+))?\]""")
        actionRegex.findAll(llmReply).forEach { match ->
            val actionType = match.groupValues[1].trim()
            val param = match.groupValues[2].trim()
            when (actionType) {
                "MODE_WORK" -> modes.firstOrNull { it.id == "TRABAJO" }?.let { systemScanner.executeWorkspaceMode(it) }
                "MODE_HOMEWORK" -> modes.firstOrNull { it.id == "TAREA" }?.let { systemScanner.executeWorkspaceMode(it) }
                "MODE_STUDY" -> modes.firstOrNull { it.id == "ESTUDIO" }?.let { systemScanner.executeWorkspaceMode(it) }
                "PLAY_LIKED" -> systemScanner.playLikedSongsInSpotify(forceRestart = false)
                "PLAY_SONG" -> systemScanner.playSongInSpotify(param)
                "PAUSE_MUSIC" -> systemScanner.controlSpotifyExact("PAUSE")
                "PLAY_MUSIC" -> systemScanner.controlSpotifyExact("PLAY")
                "NEXT_TRACK" -> systemScanner.controlSpotifyExact("NEXT")
                "PREV_TRACK" -> systemScanner.controlSpotifyExact("PREV")
                "PAUSE_VIDEO" -> systemScanner.controlBrowserVideo()
                "SEARCH_OPERA" -> systemScanner.searchInOperaGX(param, "GOOGLE")
                "OPEN_APP" -> systemScanner.openOrFocusApp(systemScanner.normalizeText(param))
            }
        }
        return llmReply.replace(actionRegex, "").trim()
    }

    // Guarda la conversación en la cuenta del usuario activo y la sincroniza en la nube
    private fun recordAndReturn(userPrompt: String, daemonReply: String): String {
        val acc = cloudVault.getCurrentAccount()
        synchronized(acc.recentHistory) {
            acc.recentHistory.add(ChatMessage("user", userPrompt))
            acc.recentHistory.add(ChatMessage("assistant", daemonReply))
            while (acc.recentHistory.size > 14) {
                acc.recentHistory.removeAt(0)
            }
        }
        acc.updatedAt = System.currentTimeMillis()
        cloudVault.pushToCloudAsync()
        return daemonReply
    }

    fun speakAndDisplay(userText: String, reply: String) {
        starkHud?.updateHologram(userText, reply)
        thread {
            try {
                isSpeakingNow = true
                uiState = "SPEAKING"
                val cleanSpeech = reply
                    .replace(Regex("```[\\s\\S]*?```"), "He proyectado el código en pantalla.")
                    .replace(Regex("[#*`_~>\\-•👑⛔☁👤]"), "")
                    .replace("\n", ". ")
                    .trim()

                if (cleanSpeech.isNotBlank()) {
                    when {
                        systemScanner.isWindows -> {
                            val psSpeech = """
                                Add-Type -AssemblyName System.Speech
                                ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
                                ${'$'}synth.Rate = 1
                                ${'$'}synth.Speak(${'$'}input)
                            """.trimIndent()
                            val utf16 = psSpeech.toByteArray(StandardCharsets.UTF_16LE)
                            val b64 = Base64.getEncoder().encodeToString(utf16)
                            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-EncodedCommand", b64).start()
                            proc.outputStream.use { it.write(cleanSpeech.toByteArray(StandardCharsets.UTF_8)) }
                            proc.waitFor()
                        }
                        systemScanner.isMac -> {
                            ProcessBuilder("say", cleanSpeech).start().waitFor()
                        }
                        systemScanner.isLinux -> {
                            systemScanner.runLinuxShell("spd-say \"$cleanSpeech\" || espeak -v es \"$cleanSpeech\"", wait = true)
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                isSpeakingNow = false
                uiState = if (System.currentTimeMillis() < directListenUntil) "LISTENING" else "IDLE"
            }
        }
    }

    private fun launchStarkHologramHUD() {
        try {
            System.setProperty("java.awt.headless", "false")
            if (!GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeLater {
                    starkHud = StarkHologramHUD(this, systemScanner, cloudVault)
                    starkHud?.isVisible = true
                }
            }
        } catch (e: Exception) {}
    }
}

// ============================================================================
// 4. INTERFAZ HOLOGRÁFICA CON SELECTOR DE CUENTA EN LA NUBE Y CONSOLA ADMIN
// ============================================================================
class StarkHologramHUD(
    private val brain: DaemonBrain,
    private val scanner: SystemScanner,
    private val cloudVault: CloudVaultService
) : JFrame("DAEMON CORE") {

    private var pressPoint = Point(0, 0)
    private var startDragScreen = Point(0, 0)
    private var wasDragged = false

    private var isMiniOrbMode = true
    private val fullWidth = 700
    private val fullHeight = 530
    private val orbSize = 104

    private val bgSpaceDark = Color(4, 10, 20)
    private val statusLabel = JLabel("ONLINE // CLIC DERECHO PARA EXPANDIR")
    private val accountBadgeLabel = JLabel("")
    private val outputArea = JTextArea()
    private val inputField = JTextField()
    private val hudCanvas = HolographicChassisPanel()
    private val fullHudContent = JPanel(BorderLayout(12, 8))

    init {
        isUndecorated = true
        if (scanner.isWindows) {
            type = Window.Type.UTILITY
        }
        background = bgSpaceDark
        isAlwaysOnTop = true

        rootPane.putClientProperty("Window.shadow", false)
        rootPane.isDoubleBuffered = true

        val screenBounds = Toolkit.getDefaultToolkit().screenSize
        val initialX = (screenBounds.width - orbSize - 32).coerceAtLeast(20)
        val initialY = 36

        hudCanvas.layout = BorderLayout()
        hudCanvas.border = EmptyBorder(16, 22, 18, 22)
        hudCanvas.isOpaque = true
        hudCanvas.background = bgSpaceDark

        refreshAccountBadges()

        // 1. CABECERA CON BOTÓN DE CUENTA EN LA NUBE Y CONSOLA DE ADMINISTRADOR
        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(2, 6, 6, 6)

            val leftTitle = JPanel(GridLayout(2, 1, 0, 0)).apply {
                isOpaque = false
                val mainTitle = JLabel("◢ J.A.R.V.I.S. // DAEMON CLOUD v11.0").apply {
                    foreground = Color(0, 242, 255)
                    font = Font("Monospaced", Font.BOLD, 13)
                }
                statusLabel.apply {
                    foreground = Color(255, 195, 0)
                    font = Font("Monospaced", Font.BOLD, 10)
                }
                add(mainTitle)
                add(statusLabel)
            }

            val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                isOpaque = false
                val accountBtn = createHoloButton("👤 CUENTA", Color(16, 235, 160), 82) { openAccountSwitcherDialog() }
                val adminBtn = createHoloButton("👑 ADMIN", Color(255, 185, 0), 78) { openAdminConsoleDialog() }
                val orbBtn = createHoloButton("◉ NÚCLEO", Color(0, 225, 255), 82) { setOrbMode(true) }
                val closeBtn = createHoloButton("✕", Color(255, 70, 90), 28) { System.exit(0) }
                add(accountBtn)
                add(adminBtn)
                add(orbBtn)
                add(closeBtn)
            }

            add(leftTitle, BorderLayout.WEST)
            add(rightControls, BorderLayout.EAST)
        }

        // 2. ALAS LATERALES DEL PANEL COMPLETO
        val centerDeck = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false

            val leftWing = JPanel(GridLayout(5, 1, 0, 7)).apply {
                isOpaque = false
                preferredSize = Dimension(150, 220)
                border = EmptyBorder(12, 2, 12, 2)

                val lbl = JLabel("◢ MODOS DE CUENTA").apply {
                    foreground = Color(0, 230, 255)
                    font = Font("Monospaced", Font.BOLD, 11)
                }
                add(lbl)
                add(createHoloButton("⚡ MODO TRABAJO", Color(0, 235, 255), 145) { runQuickCommand("vamos a trabajar") })
                add(createHoloButton("📝 MODO TAREA", Color(16, 235, 160), 145) { runQuickCommand("vamos a hacer tarea") })
                add(createHoloButton("📘 MODO ESTUDIO", Color(120, 180, 255), 145) { runQuickCommand("vamos a estudiar") })
                add(createHoloButton("🎮 MODO GAMING", Color(255, 185, 0), 145) { runQuickCommand("vamos a jugar") })
            }

            val rightWing = JPanel(GridLayout(5, 1, 0, 7)).apply {
                isOpaque = false
                preferredSize = Dimension(150, 220)
                border = EmptyBorder(12, 2, 12, 2)

                val lbl = JLabel("◣ SPOTIFY & CLOUD").apply {
                    foreground = Color(255, 195, 0)
                    font = Font("Monospaced", Font.BOLD, 11)
                    horizontalAlignment = SwingConstants.RIGHT
                }
                add(lbl)
                add(createHoloButton("▶ PLAY SPOTIFY", Color(16, 235, 160), 145) { runQuickCommand("dale play") })
                add(createHoloButton("⏸ PAUSA SPOTIFY", Color(255, 185, 0), 145) { runQuickCommand("pausa") })
                add(createHoloButton("⏭ SIGUIENTE", Color(0, 225, 255), 145) { runQuickCommand("siguiente cancion") })
                add(createHoloButton("☁ SYNC AHORA", Color(160, 140, 255), 145) { runQuickCommand("sincronizar nube") })
            }

            add(leftWing, BorderLayout.WEST)
            add(rightWing, BorderLayout.EAST)
        }

        // 3. CONSOLA INFERIOR
        val bottomDeck = JPanel(BorderLayout(0, 8)).apply {
            isOpaque = false
            border = EmptyBorder(4, 6, 2, 6)

            outputArea.apply {
                isOpaque = true
                background = Color(6, 15, 28)
                foreground = Color(215, 250, 255)
                font = Font("Monospaced", Font.PLAIN, 12)
                lineWrap = true
                wrapStyleWord = true
                isEditable = false
                border = EmptyBorder(8, 12, 8, 12)
                rows = 4
            }

            val scroll = JScrollPane(outputArea).apply {
                isOpaque = true
                background = Color(6, 15, 28)
                viewport.isOpaque = true
                viewport.background = Color(6, 15, 28)
                border = BorderFactory.createLineBorder(Color(0, 200, 240), 1)
                verticalScrollBar.ui = object : BasicScrollBarUI() {
                    override fun configureScrollBarColors() {
                        thumbColor = Color(0, 180, 220)
                        trackColor = Color(5, 12, 24)
                    }
                    override fun createDecreaseButton(orientation: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                    override fun createIncreaseButton(orientation: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                }
            }

            val commandRow = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false

                val micBtn = createHoloButton("🎤 HABLAR", Color(16, 235, 160), 90) {
                    brain.activateDirectVoiceListenWindow()
                }

                inputField.apply {
                    isOpaque = true
                    background = Color(8, 20, 38)
                    foreground = Color(0, 245, 255)
                    caretColor = Color(255, 195, 0)
                    font = Font("Monospaced", Font.PLAIN, 13)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(0, 200, 240), 1),
                        EmptyBorder(7, 10, 7, 10)
                    )
                    addActionListener { submitCommand() }
                }

                val sendBtn = createHoloButton("⚡ EJECUTAR", Color(0, 225, 255), 104) {
                    submitCommand()
                }

                add(micBtn, BorderLayout.WEST)
                add(inputField, BorderLayout.CENTER)
                add(sendBtn, BorderLayout.EAST)
            }

            add(scroll, BorderLayout.CENTER)
            add(commandRow, BorderLayout.SOUTH)
        }

        fullHudContent.isOpaque = false
        fullHudContent.add(headerBar, BorderLayout.NORTH)
        fullHudContent.add(centerDeck, BorderLayout.CENTER)
        fullHudContent.add(bottomDeck, BorderLayout.SOUTH)

        hudCanvas.add(fullHudContent, BorderLayout.CENTER)
        contentPane = hudCanvas

        setLocation(initialX, initialY)
        applyOrbWindowGeometry()

        hudCanvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                pressPoint = e.point
                startDragScreen = e.locationOnScreen
                wasDragged = false
            }

            override fun mouseReleased(e: MouseEvent) {
                if (wasDragged) return
                if (SwingUtilities.isRightMouseButton(e) || e.clickCount == 2) {
                    setOrbMode(!isMiniOrbMode)
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (isMiniOrbMode || (e.y in 90..310 && e.x in 190..510)) {
                        brain.activateDirectVoiceListenWindow()
                    }
                }
            }
        })

        hudCanvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val curr = e.locationOnScreen
                if (abs(curr.x - startDragScreen.x) > 4 || abs(curr.y - startDragScreen.y) > 4) {
                    wasDragged = true
                    setLocation(curr.x - pressPoint.x, curr.y - pressPoint.y)
                }
            }
        })

        javax.swing.Timer(45) {
            if (System.currentTimeMillis() < brain.directListenUntil && brain.uiState == "IDLE") {
                brain.uiState = "LISTENING"
            } else if (System.currentTimeMillis() >= brain.directListenUntil && brain.uiState == "LISTENING" && !brain.isSpeakingNow) {
                brain.uiState = "IDLE"
            }

            if (isMiniOrbMode) {
                hudCanvas.repaint()
            } else {
                hudCanvas.repaint(180, 62, 340, 275)
            }
        }.start()
    }

    fun refreshAccountBadges() {
        val acc = cloudVault.getCurrentAccount()
        val roleIcon = if (acc.role == "ADMIN") "👑 ADMIN" else "👤 USER"
        accountBadgeLabel.text = "${acc.username.uppercase()} [$roleIcon]"
        hudCanvas.repaint()
    }

    // Ventana rápida para Iniciar Sesión, Cambiar de Cuenta o Registrar Nuevo Usuario en la Nube
    private fun openAccountSwitcherDialog() {
        val acc = cloudVault.getCurrentAccount()
        val options = arrayOf("Iniciar Sesión / Cambiar Cuenta", "Registrar Nueva Cuenta", "Cancelar")
        val choice = JOptionPane.showOptionDialog(
            this,
            "Cuenta activa en este dispositivo: ${acc.username.uppercase()} (${acc.role})\nEstado de Nube: ${cloudVault.cloudSyncStatus}\nRecuerdos sincronizados: ${acc.personalFacts.size}",
            "DAEMON CLOUD // CUENTA DE USUARIO",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.INFORMATION_MESSAGE,
            null,
            options,
            options[0]
        )

        if (choice == 0) {
            val user = JOptionPane.showInputDialog(this, "Nombre de usuario:", acc.username) ?: return
            val pass = JOptionPane.showInputDialog(this, "Contraseña / PIN para '$user':") ?: return
            val msg = cloudVault.loginOrSwitchUser(user, pass)
            refreshAccountBadges()
            brain.speakAndDisplay("CAMBIO DE CUENTA", msg)
        } else if (choice == 1) {
            val user = JOptionPane.showInputDialog(this, "Nuevo nombre de usuario:") ?: return
            val pass = JOptionPane.showInputDialog(this, " Define una contraseña / PIN:") ?: return
            val msg = cloudVault.registerNewUser(user, pass)
            refreshAccountBadges()
            brain.speakAndDisplay("NUEVA CUENTA", msg)
        }
    }

    // Consola exclusiva para la Cuenta de Administrador de Cristian
    private fun openAdminConsoleDialog() {
        if (!cloudVault.isAdminLoggedIn()) {
            val pass = JOptionPane.showInputDialog(this, "🔒 Ingresa la clave de Administrador Maestro (cristian):") ?: return
            val loginRes = cloudVault.loginOrSwitchUser("cristian", pass)
            refreshAccountBadges()
            if (!cloudVault.isAdminLoggedIn()) {
                brain.speakAndDisplay("ADMIN", loginRes)
                return
            }
        }

        val options = arrayOf(
            "1. Conectar URL de Base de Datos Cloud",
            "2. Agregar Conocimiento Global (Todos los Usuarios)",
            "3. Ver Lista de Usuarios en la Nube",
            "4. Cerrar"
        )
        val sel = JOptionPane.showOptionDialog(
            this,
            "👑 CONSOLA MAESTRA DE ADMINISTRADOR (CRISTIAN)\nURL Cloud actual: ${cloudVault.deviceConfig.cloudDatabaseUrl.ifBlank { "Modo Caché Local (Sin URL externa)" }}\nNodos Globales: ${cloudVault.vault.globalKnowledge.size} | Cuentas Registradas: ${cloudVault.vault.accounts.size}",
            "👑 DAEMON // ADMIN CORE",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        )

        when (sel) {
            0 -> {
                val url = JOptionPane.showInputDialog(
                    this,
                    "Pega la URL de tu Firebase Realtime Database o Servidor Daemon Cloud\n(Ej: https://tu-daemon-default-rtdb.firebaseio.com):",
                    cloudVault.deviceConfig.cloudDatabaseUrl
                ) ?: return
                val msg = cloudVault.adminSetCloudDatabaseUrl(url)
                brain.speakAndDisplay("ADMIN CLOUD", msg)
            }
            1 -> {
                val patternsRaw = JOptionPane.showInputDialog(this, "Escribe las formas de preguntarlo separadas por '/' (Ej: que es escom / donde estudia cristian):") ?: return
                val answer = JOptionPane.showInputDialog(this, "Escribe la respuesta oficial que aprenderán todos los dispositivos:") ?: return
                val tag = "global_" + (System.currentTimeMillis() % 100000)
                val msg = cloudVault.adminAddGlobalKnowledge(tag, patternsRaw.split("/").map { it.trim() }, answer)
                brain.speakAndDisplay("ADMIN GLOBAL", msg)
            }
            2 -> {
                val report = cloudVault.adminListUsers()
                updateHologram("ADMIN USUARIOS", report)
            }
        }
    }

    fun setOrbMode(mini: Boolean) {
        SwingUtilities.invokeLater {
            val screenBounds = Toolkit.getDefaultToolkit().screenSize
            isMiniOrbMode = mini
            scanner.isFullPanelVisible = !mini
            applyOrbWindowGeometry()
            if (!mini) {
                val newX = location.x.coerceIn(10, (screenBounds.width - fullWidth - 10).coerceAtLeast(10))
                val newY = location.y.coerceIn(28, (screenBounds.height - fullHeight - 40).coerceAtLeast(28))
                setLocation(newX, newY)
                toFront()
                inputField.requestFocusInWindow()
            }
            revalidate()
            hudCanvas.repaint()
        }
    }

    private fun applyOrbWindowGeometry() {
        if (isMiniOrbMode) {
            fullHudContent.isVisible = false
            focusableWindowState = false
            setSize(orbSize, orbSize)
        } else {
            setSize(fullWidth, fullHeight)
            focusableWindowState = true
            fullHudContent.isVisible = true
        }
    }

    private fun runQuickCommand(cmd: String) {
        setStatusText("EJECUTANDO: ${cmd.uppercase()}...")
        thread {
            val reply = brain.processThought(cmd)
            brain.speakAndDisplay(cmd, reply)
        }
    }

    private fun submitCommand() {
        val text = inputField.text.trim()
        if (text.isBlank()) return
        inputField.text = ""
        runQuickCommand(text)
    }

    fun setStatusText(text: String) {
        SwingUtilities.invokeLater { statusLabel.text = text }
    }

    fun updateHologram(userQuery: String, daemonReply: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "ORDEN // ${userQuery.take(40).uppercase()}"
            outputArea.text = daemonReply
            outputArea.caretPosition = 0
        }
    }

    private fun createHoloButton(label: String, accent: Color, widthPx: Int, onClick: () -> Unit): JComponent {
        return object : JPanel(true) {
            private var hovered = false
            private val fillIdle = Color(7, 18, 34)
            private val fillHover = Color(
                (accent.red * 0.28 + 7).toInt().coerceIn(0, 255),
                (accent.green * 0.28 + 18).toInt().coerceIn(0, 255),
                (accent.blue * 0.28 + 34).toInt().coerceIn(0, 255)
            )
            private val borderIdle = Color(
                (accent.red * 0.65).toInt(),
                (accent.green * 0.65).toInt(),
                (accent.blue * 0.65).toInt()
            )
            private val btnFont = Font("Monospaced", Font.BOLD, 11)
            private val strokeIdle = BasicStroke(1.1f)
            private val strokeHover = BasicStroke(1.6f)

            init {
                isOpaque = true
                background = bgSpaceDark
                preferredSize = Dimension(widthPx, 32)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) { hovered = true; repaint() }
                    override fun mouseExited(e: MouseEvent?) { hovered = false; repaint() }
                    override fun mouseClicked(e: MouseEvent?) { onClick() }
                })
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val w = width.toDouble()
                val h = height.toDouble()
                val cut = 7.0

                val poly = GeneralPath().apply {
                    moveTo(cut, 0.0)
                    lineTo(w - 1, 0.0)
                    lineTo(w - 1, h - cut - 1)
                    lineTo(w - cut - 1, h - 1)
                    lineTo(0.0, h - 1)
                    lineTo(0.0, cut)
                    closePath()
                }

                g2.color = if (hovered) fillHover else fillIdle
                g2.fill(poly)

                g2.color = if (hovered) accent else borderIdle
                g2.stroke = if (hovered) strokeHover else strokeIdle
                g2.draw(poly)

                g2.color = if (hovered) Color.WHITE else Color(220, 250, 255)
                g2.font = btnFont
                val fm = g2.fontMetrics
                g2.drawString(label, ((w - fm.stringWidth(label)) / 2).toInt(), ((h + fm.ascent - fm.descent) / 2).toInt())
            }
        }
    }

    inner class HolographicChassisPanel : JPanel(true) {
        private var t = 0.0
        private val nodeCount = 36

        private val nodeTheta = DoubleArray(nodeCount)
        private val nodePhi = DoubleArray(nodeCount)
        private val nodeBaseR = DoubleArray(nodeCount)
        private val projX = DoubleArray(nodeCount)
        private val projY = DoubleArray(nodeCount)
        private val projZ = DoubleArray(nodeCount)

        private val strokeBorder = BasicStroke(1.5f)
        private val strokeCorner = BasicStroke(2.8f)
        private val strokeOuterFull = BasicStroke(2.2f)
        private val strokeOuterOrb = BasicStroke(1.2f)
        private val strokeGoldFull = BasicStroke(1.5f)
        private val strokeGoldOrb = BasicStroke(0.9f)
        private val strokePlexusFull = BasicStroke(0.8f)
        private val strokePlexusOrb = BasicStroke(0.5f)
        private val telemetryFont = Font("Monospaced", Font.BOLD, 10)

        private val colorGold = Color(255, 190, 0)
        private val colorTelemetryCyan = Color(0, 235, 255)
        private val colorSpotifyGreen = Color(16, 240, 165)

        init {
            val golden = (1.0 + sqrt(5.0)) / 2.0
            for (i in 0 until nodeCount) {
                nodeTheta[i] = 2.0 * PI * i / golden
                nodePhi[i] = acos(1.0 - 2.0 * (i + 0.5) / nodeCount)
                nodeBaseR[i] = 56.0 + (i % 4) * 5.5
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val mainColor = when (brain.uiState) {
                "LISTENING" -> Color(16, 245, 160)
                "THINKING" -> Color(255, 185, 0)
                "SPEAKING" -> Color(0, 255, 255)
                else -> Color(0, 215, 255)
            }
            val dimMainColor = Color(mainColor.red / 2, mainColor.green / 2, mainColor.blue / 2)
            val glowColor = Color(mainColor.red / 8 + 4, mainColor.green / 8 + 10, mainColor.blue / 8 + 22)

            val speed = when (brain.uiState) {
                "THINKING" -> 0.08
                "SPEAKING" -> 0.05
                "LISTENING" -> 0.045
                else -> 0.022
            }
            t += speed
            val pulse = if (brain.uiState == "SPEAKING" || brain.uiState == "LISTENING") {
                1.0 + abs(sin(t * 4.0)) * 0.12
            } else {
                1.0 + sin(t * 2.0) * 0.025
            }

            if (isMiniOrbMode) {
                val cx = width / 2.0
                val cy = height / 2.0
                g2.color = dimMainColor
                g2.stroke = strokeBorder
                g2.drawOval(2, 2, width - 5, height - 5)
                drawFloatingArcReactor(g2, cx, cy, 0.42, mainColor, dimMainColor, glowColor, pulse, true)
                return
            }

            val w = width.toDouble()
            val cut = 26

            g2.color = dimMainColor
            g2.stroke = strokeBorder
            g2.drawRect(2, 2, width - 5, height - 5)

            g2.color = colorGold
            g2.stroke = strokeCorner
            g2.drawLine(2, 2, 2 + cut, 2)
            g2.drawLine(2, 2, 2, 2 + cut)
            g2.drawLine(width - 3 - cut, 2, width - 3, 2)
            g2.drawLine(width - 3, 2, width - 3, 2 + cut)
            g2.drawLine(2, height - 3 - cut, 2, height - 3)
            g2.drawLine(2, height - 3, 2 + cut, height - 3)
            g2.drawLine(width - 3 - cut, height - 3, width - 3, height - 3)
            g2.drawLine(width - 3, height - 3 - cut, width - 3, height - 3)

            val cx = w / 2.0
            val cy = 195.0
            drawFloatingArcReactor(g2, cx, cy, 1.0, mainColor, dimMainColor, glowColor, pulse, false)

            g2.font = telemetryFont
            g2.color = colorTelemetryCyan
            g2.drawString("CPU:${scanner.cachedCpuLoad}% RAM:${scanner.cachedRamMb}MB", 184, 76)
            g2.drawString(cloudVault.cloudSyncStatus, 418, 76)

            val acc = cloudVault.getCurrentAccount()
            g2.color = colorGold
            val userBadge = "USER // ${acc.username.uppercase()} [${acc.role}]"
            val fm = g2.fontMetrics
            g2.drawString(userBadge, (cx - fm.stringWidth(userBadge) / 2).toInt(), 92)

            g2.color = colorSpotifyGreen
            val spText = "♫ SPOTIFY: ${scanner.currentSpotifyTrack}"
            g2.drawString(spText, (cx - fm.stringWidth(spText) / 2).toInt(), 328)
        }

        private fun drawFloatingArcReactor(
            g2: Graphics2D,
            cx: Double,
            cy: Double,
            scale: Double,
            mainColor: Color,
            dimMainColor: Color,
            glowColor: Color,
            pulse: Double,
            isOrb: Boolean
        ) {
            val glowRad = (82.0 * scale * pulse).toInt()
            g2.color = glowColor
            g2.fillOval((cx - glowRad).toInt(), (cy - glowRad).toInt(), glowRad * 2, glowRad * 2)

            val rOuter = (106.0 * scale).toInt()
            g2.color = mainColor
            g2.stroke = if (isOrb) strokeOuterOrb else strokeOuterFull
            val baseAngle1 = (t * 28.0).toInt()
            for (a in 0 until 360 step 60) {
                g2.drawArc((cx - rOuter).toInt(), (cy - rOuter).toInt(), rOuter * 2, rOuter * 2, a + baseAngle1, 34)
            }

            val rGold = (92.0 * scale).toInt()
            g2.color = colorGold
            g2.stroke = if (isOrb) strokeGoldOrb else strokeGoldFull
            val baseAngle2 = (-t * 38.0).toInt()
            for (a in 0 until 360 step 90) {
                g2.drawArc((cx - rGold).toInt(), (cy - rGold).toInt(), rGold * 2, rGold * 2, a + baseAngle2, 48)
            }

            val numBars = 24
            for (i in 0 until numBars) {
                val a = (i.toDouble() / numBars) * PI * 2.0 + t * 0.15
                val amp = if (brain.uiState == "IDLE") {
                    (2.5 + sin(a * 4.0 + t * 2.0) * 1.5) * scale
                } else {
                    (4.5 + abs(sin(a * 6.0 + t * 5.0)) * 16.0 * pulse) * scale
                }
                val rIn = 70.0 * scale * pulse
                val rOut = rIn + amp
                val cosA = cos(a)
                val sinA = sin(a)
                g2.color = mainColor
                g2.drawLine(
                    (cx + cosA * rIn).toInt(), (cy + sinA * rIn).toInt(),
                    (cx + cosA * rOut).toInt(), (cy + sinA * rOut).toInt()
                )
            }

            val wobble = sin(t * 0.5) * 0.08
            for (i in 0 until nodeCount) {
                val r = nodeBaseR[i] * scale * pulse
                val rotT = nodeTheta[i] + t
                val rotP = nodePhi[i] + wobble
                val sinP = sin(rotP)
                val x3 = r * sinP * cos(rotT)
                val y3 = r * cos(rotP)
                val z3 = r * sinP * sin(rotT)
                val fov = 220.0 / (220.0 + z3)
                projX[i] = cx + x3 * fov
                projY[i] = cy + y3 * fov
                projZ[i] = z3
            }

            g2.stroke = if (isOrb) strokePlexusOrb else strokePlexusFull
            g2.color = dimMainColor
            val maxDist = 34.0 * scale * pulse
            val maxDistSq = maxDist * maxDist
            for (i in 0 until nodeCount) {
                val xi = projX[i]
                val yi = projY[i]
                for (j in i + 1 until nodeCount) {
                    val dx = xi - projX[j]
                    val dy = yi - projY[j]
                    if (dx * dx + dy * dy < maxDistSq) {
                        g2.drawLine(xi.toInt(), yi.toInt(), projX[j].toInt(), projY[j].toInt())
                    }
                }
            }

            val dotSize = (4.0 * scale).toInt().coerceAtLeast(2)
            val halfDot = dotSize / 2
            for (i in 0 until nodeCount) {
                g2.color = if (i % 4 == 0) Color.WHITE else mainColor
                g2.fillOval(projX[i].toInt() - halfDot, projY[i].toInt() - halfDot, dotSize, dotSize)
            }
        }
    }
}

// ============================================================================
// 5. ENDPOINTS REST PARA SINCRONIZAR DISPOSITIVOS Y BÓVEDA EN LA NUBE
// ============================================================================
@RestController
@RequestMapping("/api/daemon")
@CrossOrigin(origins = ["*"])
class DaemonController(
    private val brain: DaemonBrain,
    private val cloudVault: CloudVaultService
) {
    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): String {
        if (request.username.isNotBlank() && request.password.isNotBlank()) {
            cloudVault.loginOrSwitchUser(request.username, request.password)
        }
        val reply = brain.processThought(request.prompt)
        brain.speakAndDisplay(request.prompt, reply)
        return reply
    }

    // Si alojas Daemon en un servidor (o conectas tu Mac a tu PC), este endpoint sirve de Bóveda Cloud maestra
    @GetMapping("/cloud-vault")
    fun getVault(): CloudVaultState = cloudVault.vault

    @PutMapping("/cloud-vault")
    fun updateVault(@RequestBody incomingVault: CloudVaultState): CloudVaultState {
        cloudVault.mergeCloudVault(incomingVault)
        return cloudVault.vault
    }
}