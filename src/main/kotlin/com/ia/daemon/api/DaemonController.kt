package com.ia.daemon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
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
import kotlin.random.Random

// ============================================================================
// MODELOS DE DATOS
// ============================================================================
data class ChatRequest(
    val prompt: String = "",
    val audio: String? = null,
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

data class OllamaOptions(
    val temperature: Double = 0.7,
    val num_predict: Int = 260
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
// 1. MOTOR DEL SISTEMA OPERATIVO, SPOTIFY INFALIBLE Y MODOS DE TRABAJO
// ============================================================================
@Service
class SystemScanner {

    val osName: String = System.getProperty("os.name").lowercase()
    val isWindows = osName.contains("win")

    private val installedApps = mutableMapOf("init" to "init").apply { clear() }
    private val playlistsFile = File("daemon_playlists.json")
    private val modesFile = File("daemon_modes.json")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val savedPlaylists = mutableMapOf("init" to "init").apply { clear() }
    val workspaceModes = mutableListOf(WorkspaceMode()).apply { clear() }

    @Volatile var activeModeName: String = "NÚCLEO ACTIVO"
    @Volatile var currentSpotifyTrack: String = "STANDBY"
    @Volatile private var lastMediaActionTimestamp: Long = 0L

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    init {
        scanInstalledApplications()
        loadPlaylists()
        loadWorkspaceModes()
        ensureDesktopAndStartupApp()
        startLiveTelemetryMonitor()
    }

    // Ejecutor PowerShell en Base64 UTF-16LE (Sin errores de comillas ni bloqueos de stdin)
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
        } catch (e: Exception) {
            ""
        }
    }

    private fun startLiveTelemetryMonitor() {
        if (!isWindows) return
        thread(isDaemon = true, name = "Daemon-Spotify-Monitor") {
            while (true) {
                try {
                    val title = getSpotifyWindowTitle()
                    currentSpotifyTrack = when {
                        title.isBlank() -> "SPOTIFY CERRADO"
                        !title.contains(" - ") -> "EN PAUSA"
                        else -> title.take(34)
                    }
                } catch (e: Exception) {}
                Thread.sleep(2200)
            }
        }
    }

    fun getSpotifyWindowTitle(): String {
        val script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -First 1 -ExpandProperty MainWindowTitle"
        return runPowerShell(script, wait = true).trim()
    }

    // ========================================================================
    // PULSACIÓN DE HARDWARE ÚNICA (USER32.DLL KEYBD_EVENT) CON ANTI-REBOTE
    // ========================================================================
    private fun triggerHardwareMediaKey(vkCode: Int, repeat: Int = 1) {
        val now = System.currentTimeMillis()
        if (now - lastMediaActionTimestamp < 450 && repeat == 1) return
        lastMediaActionTimestamp = now

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

    // Control exacto de Spotify: separa Play, Pausa, Siguiente y Anterior sin doble pulso
    fun controlSpotifyExact(command: String): String {
        if (!isWindows) return "Control multimedia activo en Windows."

        val title = getSpotifyWindowTitle()
        val isSpotifyOpen = title.isNotBlank()
        val isPlayingNow = isSpotifyOpen && title.contains(" - ")

        if (!isSpotifyOpen && (command == "PLAY" || command == "NEXT" || command == "PREV")) {
            playLikedSongsInSpotify()
            return "Abriendo Spotify y reproduciendo tus canciones de Mis Me Gusta, Cristian."
        }

        return when (command) {
            "PAUSE" -> {
                if (isSpotifyOpen && !isPlayingNow) {
                    return "Spotify ya está en pausa, Cristian."
                }
                triggerHardwareMediaKey(0xB3, 1) // 0xB3 = VK_MEDIA_PLAY_PAUSE (1 solo pulso)
                currentSpotifyTrack = "EN PAUSA"
                "Música en pausa, Cristian."
            }
            "PLAY" -> {
                if (isPlayingNow) {
                    return "La música ya se está reproduciendo ($title), Cristian."
                }
                triggerHardwareMediaKey(0xB3, 1) // 1 solo pulso para reanudar
                "Reanudando reproducción en Spotify, Cristian."
            }
            "NEXT" -> {
                triggerHardwareMediaKey(0xB0, 1) // 0xB0 = VK_MEDIA_NEXT_TRACK
                "Siguiente canción en Spotify, Cristian."
            }
            "PREV" -> {
                triggerHardwareMediaKey(0xB1, 1) // 0xB1 = VK_MEDIA_PREV_TRACK
                "Canción anterior en Spotify, Cristian."
            }
            else -> "Comando multimedia ejecutado."
        }
    }

    // Reproduce automáticamente la lista "Mis Me Gusta" (Canciones que te gustan) en Spotify
    fun playLikedSongsInSpotify(): String {
        if (!isWindows) return "Iniciando Mis Me Gusta."
        thread {
            try {
                val ps = """
                    Start-Process "spotify:collection:tracks"
                    Start-Sleep -Milliseconds 2600
                    ${'$'}wsh = New-Object -ComObject WScript.Shell
                    ${'$'}sp = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | Select-Object -First 1
                    if (${'$'}null -ne ${'$'}sp) {
                        ${'$'}wsh.AppActivate(${'$'}sp.Id) | Out-Null
                        Start-Sleep -Milliseconds 450
                        ${'$'}wsh.SendKeys("{ENTER}")
                        Start-Sleep -Milliseconds 700
                        ${'$'}spCheck = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowTitle -ne "" } | Select-Object -First 1
                        if (${'$'}null -ne ${'$'}spCheck -and ${'$'}spCheck.MainWindowTitle -notlike "* - *") {
                            ${'$'}wsh.AppActivate(${'$'}spCheck.Id) | Out-Null
                            Start-Sleep -Milliseconds 250
                            ${'$'}wsh.SendKeys("{TAB}{ENTER}")
                            Start-Sleep -Milliseconds 500
                            ${'$'}spCheck2 = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowTitle -ne "" } | Select-Object -First 1
                            if (${'$'}null -ne ${'$'}spCheck2 -and ${'$'}spCheck2.MainWindowTitle -notlike "* - *") {
                                ${'$'}code = 'using System; using System.Runtime.InteropServices; public class Win32Key { [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo); }'
                                Add-Type -TypeDefinition ${'$'}code -ErrorAction SilentlyContinue
                                [Win32Key]::keybd_event(0xB3, 0, 0, 0)
                                Start-Sleep -Milliseconds 25
                                [Win32Key]::keybd_event(0xB3, 0, 2, 0)
                            }
                        }
                    }
                """.trimIndent()
                runPowerShell(ps, wait = false)
            } catch (e: Exception) {}
        }
        return "Reproduciendo tus canciones de Mis Me Gusta en Spotify, Cristian."
    }

    // Control exclusivo para pausar/reanudar un video en Opera GX sin afectar a Spotify
    fun controlBrowserVideo(): String {
        if (!isWindows) return "Control de video disponible en Windows."
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
        return "Alternando reproducción del video en Opera GX sin tocar tu música de Spotify, Cristian."
    }

    fun changeSystemVolume(direction: String): String {
        return when (direction) {
            "UP" -> { triggerHardwareMediaKey(0xAF, 5); "Subiendo volumen del sistema, Cristian." }
            "DOWN" -> { triggerHardwareMediaKey(0xAE, 5); "Bajando volumen del sistema, Cristian." }
            else -> { triggerHardwareMediaKey(0xAD, 1); "Audio del sistema silenciado/activado, Cristian." }
        }
    }

    // Búsqueda directa en Opera GX
    fun searchInOperaGX(rawQuery: String, platform: String = "GOOGLE"): String {
        val cleanQuery = rawQuery.trim()
        val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8)
        val targetUrl = when (platform) {
            "YOUTUBE" -> "https://www.youtube.com/results?search_query=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }

        val operaPath = findOperaGXExecutable()
        try {
            if (operaPath != null) {
                ProcessBuilder(operaPath, targetUrl).start()
            } else {
                ProcessBuilder("cmd", "/c", "start", "", targetUrl).start()
            }
        } catch (e: Exception) {
            ProcessBuilder("cmd", "/c", "start", "", targetUrl).start()
        }

        return if (platform == "YOUTUBE") {
            "Buscando '$cleanQuery' en YouTube dentro de Opera GX, Cristian."
        } else {
            "Buscando '$cleanQuery' en Opera GX, Cristian."
        }
    }

    private fun findOperaGXExecutable(): String? {
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val appData = System.getenv("APPDATA") ?: ""
        val candidates = listOf(
            File("$localAppData\\Programs\\Opera GX\\opera.exe"),
            File("$localAppData\\Programs\\Opera GX\\launcher.exe"),
            File("$localAppData\\Programs\\Opera\\opera.exe"),
            File("C:\\Program Files\\Opera GX\\opera.exe"),
            File("$appData\\Microsoft\\Windows\\Start Menu\\Programs\\Navegador Opera GX.lnk")
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath ?: installedApps["opera gx"] ?: installedApps["opera"]
    }

    // ========================================================================
    // MODOS DE TRABAJO / TAREA / ESTUDIO CON AUTO-PLAY EN "MIS ME GUSTA"
    // ========================================================================
    private fun loadWorkspaceModes() {
        val defaultModes = listOf(
            WorkspaceMode(
                id = "TRABAJO",
                name = "PROTOCOLO TRABAJO",
                triggers = listOf("vamos a trabajar", "modo trabajo", "protocolo trabajo", "hora de trabajar", "vamos a programar", "modo programacion", "activa modo trabajo"),
                appsToOpen = listOf("vscode", "notion", "opera gx"),
                appsToClose = emptyList(),
                spotifyPlaylist = "mis me gusta",
                greeting = "Activando Modo Trabajo, Cristian. Abriendo Visual Studio Code, Notion, Opera GX y reproduciendo tus canciones de Mis Me Gusta en Spotify."
            ),
            WorkspaceMode(
                id = "TAREA",
                name = "PROTOCOLO TAREA",
                triggers = listOf("vamos a hacer tarea", "modo tarea", "protocolo tarea", "hora de hacer tarea", "tengo tarea", "activa modo tarea"),
                appsToOpen = listOf("vscode", "notion", "opera gx"),
                appsToClose = listOf("discord", "steam"),
                spotifyPlaylist = "mis me gusta",
                greeting = "Activando Modo Tarea, Cristian. Preparando Visual Studio Code, Notion, Opera GX y reproduciendo automáticamente tu lista Mis Me Gusta en Spotify."
            ),
            WorkspaceMode(
                id = "ESTUDIO",
                name = "PROTOCOLO ESTUDIO",
                triggers = listOf("vamos a estudiar", "modo estudio", "protocolo estudio", "modo escuela", "modo escom", "hora de estudiar", "activa modo estudio"),
                appsToOpen = listOf("notion", "opera gx", "vscode"),
                appsToClose = listOf("discord", "steam"),
                spotifyPlaylist = "mis me gusta",
                greeting = "Activando Modo Estudio, Cristian. Abriendo Notion, Opera GX, Visual Studio Code y reproduciendo automáticamente tus canciones de Mis Me Gusta."
            ),
            WorkspaceMode(
                id = "GAMING",
                name = "PROTOCOLO GAMING",
                triggers = listOf("vamos a jugar", "modo gaming", "modo juego", "protocolo gaming", "hora de jugar", "activa modo gaming"),
                appsToOpen = listOf("discord"),
                appsToClose = listOf("vscode", "notion"),
                spotifyPlaylist = "mis me gusta",
                greeting = "Modo Gaming activo, Cristian. Cerrando aplicaciones de trabajo, abriendo Discord y reproduciendo tu música."
            ),
            WorkspaceMode(
                id = "DESCANSO",
                name = "PROTOCOLO DESCANSO",
                triggers = listOf("vamos a descansar", "modo descanso", "modo relax", "protocolo descanso", "terminamos de trabajar"),
                appsToOpen = listOf("opera gx"),
                appsToClose = listOf("vscode", "notion"),
                spotifyPlaylist = "mis me gusta",
                greeting = "Cerrando entorno de trabajo y activando Modo Descanso, Cristian."
            )
        )

        // Actualizar siempre los modos base para asegurar que Tarea y Estudio usen "mis me gusta"
        workspaceModes.clear()
        workspaceModes.addAll(defaultModes)
        try {
            objectMapper.writeValue(modesFile, workspaceModes)
        } catch (e: Exception) {}
    }

    fun executeWorkspaceMode(mode: WorkspaceMode): String {
        activeModeName = mode.id
        thread {
            try {
                mode.appsToClose.forEach { app ->
                    closeAppByName(app)
                    Thread.sleep(200)
                }
                mode.appsToOpen.forEach { app ->
                    openAppByName(app)
                    Thread.sleep(550)
                }
                if (mode.spotifyPlaylist.isNotBlank()) {
                    Thread.sleep(900)
                    if (mode.spotifyPlaylist.contains("me gusta")) {
                        playLikedSongsInSpotify()
                    } else {
                        playPlaylist(mode.spotifyPlaylist, normalizeText(mode.spotifyPlaylist))
                    }
                }
            } catch (e: Exception) {}
        }
        return mode.greeting
    }

    fun createCustomMode(name: String, appsList: List<String>): String {
        val cleanId = normalizeText(name).uppercase().replace(" ", "_")
        val newMode = WorkspaceMode(
            id = cleanId,
            name = "PROTOCOLO $cleanId",
            triggers = listOf("modo ${normalizeText(name)}", "vamos a ${normalizeText(name)}"),
            appsToOpen = appsList.map { normalizeText(it) },
            appsToClose = emptyList(),
            spotifyPlaylist = "mis me gusta",
            greeting = "Activando modo $cleanId y reproduciendo Mis Me Gusta, Cristian."
        )
        workspaceModes.removeIf { it.id == cleanId }
        workspaceModes.add(newMode)
        objectMapper.writeValue(modesFile, workspaceModes)
        return "Modo '$cleanId' guardado exitosamente, Cristian."
    }

    fun scanInstalledApplications(): Int {
        installedApps.clear()
        if (!isWindows) return 0
        try {
            val appData = System.getenv("APPDATA") ?: ""
            val localAppData = System.getenv("LOCALAPPDATA") ?: ""
            val programData = System.getenv("ProgramData") ?: "C:\\ProgramData"
            val userProfile = System.getProperty("user.home") ?: ""

            listOf(
                File("$appData\\Microsoft\\Windows\\Start Menu\\Programs"),
                File("$programData\\Microsoft\\Windows\\Start Menu\\Programs"),
                File("$userProfile\\Desktop")
            ).filter { it.exists() }.forEach { dir ->
                dir.walkTopDown().maxDepth(4).forEach { file ->
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
                Pair("explorador", "explorer.exe"),
                Pair("administrador de tareas", "taskmgr.exe")
            )
            for (item in directPaths) {
                if (!item.second.contains("\\") || File(item.second).exists()) {
                    installedApps[item.first] = item.second
                }
            }
        } catch (e: Exception) {}
        return installedApps.size
    }

    fun openAppByName(normalizedCommand: String): String {
        val requestedApp = normalizedCommand
            .replace(Regex("""^.*?(?:abre|abrir|inicia|iniciar|ejecuta|ejecutar|lanza|pon)\s+(?:la\s+aplicacion\s+|el\s+programa\s+|la\s+app\s+|el\s+|la\s+)?"""), "")
            .trim()

        val canonical = when {
            requestedApp == "visual" || requestedApp == "vs code" || requestedApp == "code" -> "vscode"
            requestedApp.contains("opera") -> "opera gx"
            else -> requestedApp
        }

        if (canonical == "opera gx") {
            val operaExe = findOperaGXExecutable()
            if (operaExe != null) {
                ProcessBuilder("cmd", "/c", "start", "", operaExe).start()
                return "Abriendo Opera GX, Cristian."
            }
        }

        val entry = installedApps.entries.firstOrNull { it.key == canonical }
            ?: installedApps.entries.firstOrNull { it.key.contains(canonical) || canonical.contains(it.key) }

        if (entry != null) {
            ProcessBuilder("cmd", "/c", "start", "", entry.value).start()
            return "Iniciando ${entry.key.uppercase()}, Cristian."
        }
        return "No encontré '$requestedApp' instalado en tu PC."
    }

    fun closeAppByName(normalizedCommand: String): String {
        val appName = normalizedCommand
            .replace(Regex("""^.*?(?:cierra|cerrar|termina|mata)\s+(?:la\s+aplicacion\s+|el\s+programa\s+|el\s+|la\s+)?"""), "")
            .trim()
        val processName = when {
            appName.contains("spotify") -> "Spotify"
            appName.contains("discord") -> "Discord"
            appName.contains("visual") || appName.contains("code") || appName.contains("vscode") -> "Code"
            appName.contains("notion") -> "Notion"
            appName.contains("opera") -> "opera"
            appName.contains("bloc") || appName.contains("notepad") -> "notepad"
            appName.contains("calculadora") -> "CalculatorApp"
            else -> appName.split(" ").first()
        }
        if (isWindows) {
            ProcessBuilder("cmd", "/c", "taskkill /IM $processName.exe /F").start()
        }
        return "He cerrado $appName, Cristian."
    }

    fun minimizeAllWindows() {
        runPowerShell("(New-Object -ComObject Shell.Application).ToggleDesktop()", wait = false)
    }

    fun getOpenWindowsReport(): String {
        val script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowTitle"
        val out = runPowerShell(script, wait = true)
        val titles = out.lines().map { it.trim() }.filter { it.isNotBlank() }
        return if (titles.isEmpty()) "No hay ventanas visibles abiertas."
        else "Ventanas abiertas (${titles.size}):\n" + titles.joinToString("\n") { "• $it" }
    }

    fun getCurrentlyPlayingSpotify(): String {
        val title = getSpotifyWindowTitle()
        return when {
            title.isBlank() -> "Spotify está cerrado en este momento, Cristian."
            !title.contains(" - ") -> "Spotify está abierto pero en pausa, Cristian."
            else -> "Está sonando '$title' en Spotify."
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
        return "Playlist '$cleanName' vinculada a $uri, Cristian."
    }

    fun playPlaylist(rawCommand: String, normalized: String): String {
        if (normalized.contains("me gusta") || normalized.contains("favoritas")) {
            return playLikedSongsInSpotify()
        }

        val playlistQuery = normalized
            .replace(Regex("""^.*?(?:playlist|lista\s+de\s+reproduccion|lista|mis\s+canciones\s+de|musica\s+para)\s*(?:de\s+|llamada\s+|para\s+)?"""), "")
            .replace("en spotify", "")
            .trim()
            .ifBlank { "mis me gusta" }

        if (playlistQuery == "mis me gusta") return playLikedSongsInSpotify()

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

        return "Iniciando playlist '$playlistQuery' en Spotify, Cristian."
    }

    fun playSongInSpotify(songQuery: String?): String {
        val cleanSong = songQuery?.trim() ?: ""
        if (cleanSong.isBlank() || cleanSong in setOf("musica", "la musica", "la cancion", "spotify")) {
            return controlSpotifyExact("PLAY")
        }
        if (cleanSong.contains("mis me gusta") || cleanSong.contains("canciones que me gustan")) {
            return playLikedSongsInSpotify()
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
        return "Reproduciendo '$cleanSong' en Spotify, Cristian."
    }

    private fun launchSpotifyUriAndPlay(spotifyUri: String, isSearch: Boolean) {
        if (!isWindows) return
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
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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

    // Registra a Daemon en el Registro de Arranque de Windows (HKCU\...\Run) y en shell:startup
    fun ensureDesktopAndStartupApp(): String {
        if (!isWindows) return ""
        return try {
            val workingDir = File(System.getProperty("user.dir")).absolutePath
            val libsDir = File(workingDir, "build\\libs")
            val jarFile = libsDir.listFiles()?.firstOrNull { it.name.endsWith(".jar") && !it.name.contains("plain") }
                ?: File(workingDir, "daemon.jar")
            if (!jarFile.exists()) return ""

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
                shell.Run javawPath & " -jar " & Chr(34) & "${jarFile.absolutePath}" & Chr(34), 0, False
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
            "Auto-inicio al encender la PC registrado en Windows."
        } catch (e: Exception) { "" }
    }

    fun getCpuLoadPercent(): Int {
        return try {
            val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val load = (osBean.cpuLoad * 100).toInt()
            if (load in 1..100) load else Random.nextInt(8, 22)
        } catch (e: Exception) { 12 }
    }

    fun getRamUsagePercent(): Int {
        return try {
            val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val total = osBean.totalMemorySize.toDouble()
            val free = osBean.freeMemorySize.toDouble()
            (((total - free) / total) * 100).toInt().coerceIn(1, 99)
        } catch (e: Exception) { 42 }
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
// 2. CEREBRO DE DAEMON + ESCUCHA SIEMPRE ACTIVA CON WAKE WORD ("DAEMON")
// ============================================================================
@Service
class DaemonBrain(
    private val systemScanner: SystemScanner
) {
    private val factsFile = File("daemon_facts.txt")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val conversationHistory = mutableListOf(ChatMessage("", "")).apply { clear() }
    private val userFacts = mutableListOf("").apply { clear() }

    private val ollamaUrl = "http://localhost:11434/api/chat"
    private var ollamaModel = "qwen2.5:1.5b"
    @Volatile var isOllamaOnline: Boolean = false

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile var uiState: String = "IDLE"
    @Volatile var isSpeakingNow: Boolean = false
    // Cuando le das clic al núcleo o dices "Daemon", se abre una ventana de 8 seg para hablar directo
    @Volatile var directListenUntil: Long = 0L

    private var starkHud: StarkHologramHUD? = null

    // Variaciones fonéticas que el micrófono de Windows detecta cuando dices "Daemon"
    private val wakeWords = listOf("daemon", "daimon", "deimon", "daymon", "demon", "damon")

    init {
        loadUserFacts()
        checkOllamaStatus()
        launchStarkHologramHUD()
        startContinuousWakeWordEar()
        speakAndDisplay(
            "NÚCLEO ACTIVO",
            "Núcleo Daemon en línea, Cristian. Dime 'Daemon' seguido de tu orden en cualquier momento."
        )
    }

    // ========================================================================
    // OÍDO CONTINUO EN SEGUNDO PLANO (SOLO REACCIONA SI DICES "DAEMON" O DAS CLIC)
    // ========================================================================
    private fun startContinuousWakeWordEar() {
        if (!systemScanner.isWindows) return
        thread(isDaemon = true, name = "Daemon-AlwaysOn-WakeWord-Ear") {
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
                        ${'$'}engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                        
                        while (${'$'}true) {
                            ${'$'}res = ${'$'}engine.Recognize([TimeSpan]::FromSeconds(4))
                            if (${'$'}null -ne ${'$'}res -and ${'$'}res.Confidence -gt 0.40) {
                                Write-Output ("HEARD:" + ${'$'}res.Text)
                                [Console]::Out.Flush()
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
                        if (isSpeakingNow) continue // No escucharse a sí mismo mientras habla

                        val heardText = rawLine.removePrefix("HEARD:").trim()
                        val normalized = systemScanner.normalizeText(heardText)
                        if (normalized.length < 3) continue

                        val isDirectWindowActive = System.currentTimeMillis() < directListenUntil
                        val hasWakeWord = wakeWords.any { w -> normalized.contains(w) }

                        // REGLA DE ORO: Solo reaccionar si el usuario dijo "Daemon" o si dio clic en el núcleo
                        if (hasWakeWord || isDirectWindowActive) {
                            var cleanCommand = normalized
                            for (w in wakeWords) {
                                cleanCommand = cleanCommand.replace(Regex("""^(?:oye\s+|hola\s+)?$w\b\s*"""), "").trim()
                            }

                            // Si solo dijo "¡Oye Daemon!" o "Daemon", activamos escucha directa por 8 segundos
                            if (cleanCommand.isBlank() || cleanCommand in setOf("oye", "hola", "estas ahi", "me escuchas")) {
                                directListenUntil = System.currentTimeMillis() + 8000L
                                uiState = "LISTENING"
                                speakAndDisplay("DAEMON", "Sí, Cristian, te escucho.")
                            } else {
                                directListenUntil = 0L
                                val reply = processThought(cleanCommand)
                                speakAndDisplay(cleanCommand, reply)
                            }
                        }
                    }
                    proc.waitFor()
                } catch (e: Exception) {
                    Thread.sleep(3000)
                }
            }
        }
    }

    // Se activa cuando das clic izquierdo en el núcleo chiquito para hablar directo
    fun activateDirectVoiceListenWindow() {
        directListenUntil = System.currentTimeMillis() + 8000L
        uiState = "LISTENING"
        starkHud?.setStatusText("TE ESCUCHO, CRISTIAN (HABLA AHORA)...")
    }

    private fun loadUserFacts() {
        if (!factsFile.exists()) {
            userFacts.add("El creador y usuario de Daemon es Cristian Alexis, estudiante de Ingeniería en Sistemas Computacionales en la ESCOM del IPN.")
            factsFile.writeText(userFacts.joinToString("\n") + "\n")
        } else {
            userFacts.addAll(factsFile.readLines().filter { it.isNotBlank() })
        }
    }

    private fun saveUserFact(fact: String) {
        userFacts.add(fact)
        factsFile.appendText("$fact\n")
    }

    private fun checkOllamaStatus(): Boolean {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/tags"))
                .timeout(Duration.ofSeconds(2))
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
    // PROCESADOR CENTRAL DE COMANDOS Y CONVERSACIÓN
    // ========================================================================
    fun processThought(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isBlank()) return "Sin señal de entrada."
        uiState = "THINKING"

        // Limpiar el nombre "daemon" si el usuario lo escribió al inicio
        var normalized = systemScanner.normalizeText(input)
        for (w in wakeWords) {
            normalized = normalized.replace(Regex("""^(?:oye\s+|hola\s+)?$w\b\s*"""), "").trim()
        }

        // 0. EXPANDIR O CONTRAER LA VENTANA DEL NÚCLEO POR VOZ
        if (normalized.contains("abre el panel") || normalized.contains("expande el nucleo") || normalized.contains("modo ventana")) {
            starkHud?.setOrbMode(false)
            return recordAndReturn(input, "Desplegando el panel holográfico completo, Cristian.")
        }
        if (normalized.contains("modo nucleo") || normalized.contains("minimiza el panel") || normalized.contains("hazte chiquito")) {
            starkHud?.setOrbMode(true)
            return recordAndReturn(input, "Contrayendo a modo núcleo flotante, Cristian.")
        }

        // 1. ACTIVACIÓN DE MODOS (TRABAJO, TAREA, ESTUDIO, GAMING, DESCANSO)
        for (mode in systemScanner.workspaceModes) {
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
            return recordAndReturn(input, systemScanner.createCustomMode(modeName, apps))
        }

        // 2. BÚSQUEDA DIRECTA EN OPERA GX Y YOUTUBE
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

        // 3. CONTROL EXCLUSIVO DE VIDEO (SIN TOCAR SPOTIFY)
        if (normalized.contains("video") || normalized.contains("youtube")) {
            if (normalized.contains("pausa") || normalized.contains("deten") || normalized.contains("play") || normalized.contains("reanuda") || normalized.contains("continua")) {
                return recordAndReturn(input, systemScanner.controlBrowserVideo())
            }
        }

        // 4. CONTROL EXACTO DE SPOTIFY (SIN DOBLE PULSO)
        // A) PAUSA
        val isPauseOrder = normalized in setOf(
            "pausa", "pon pausa", "ponle pausa", "pausa spotify", "pausa la musica",
            "pausa la cancion", "deten la musica", "deten la cancion", "para la musica", "pausar"
        ) || (normalized.contains("pausa") && !normalized.contains("video"))
        if (isPauseOrder) {
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PAUSE"))
        }

        // B) PLAY
        val isPlayOrder = normalized in setOf(
            "play", "dale play", "pon play", "ponle play", "dale play a spotify",
            "reanuda la musica", "reanuda la cancion", "reanuda spotify", "continua la musica",
            "dale play a la cancion", "dale play a la musica", "quita la pausa", "reproduce"
        )
        if (isPlayOrder) {
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PLAY"))
        }

        // C) SIGUIENTE CANCIÓN
        val isNextTrack = normalized in setOf("siguiente", "cambiale", "otra", "otra cancion", "siguiente cancion", "siguiente pista", "siguiente rola", "salta la cancion") ||
            normalized.contains("siguiente cancion") || normalized.contains("cambia de cancion") ||
            normalized.contains("cambia la cancion") || normalized.contains("cambiale a la cancion") ||
            normalized.contains("pasa a la siguiente") || normalized.contains("salta esta cancion") ||
            normalized.contains("adelanta la cancion") || normalized.contains("pon la que sigue")
        if (isNextTrack) {
            return recordAndReturn(input, systemScanner.controlSpotifyExact("NEXT"))
        }

        // D) CANCIÓN ANTERIOR
        val isPrevTrack = normalized in setOf("anterior", "atras", "cancion anterior", "pista anterior", "rola anterior") ||
            normalized.contains("cancion anterior") || normalized.contains("regresa la cancion") ||
            normalized.contains("pista anterior") || normalized.contains("pon la anterior") ||
            normalized.contains("regresale a la cancion")
        if (isPrevTrack) {
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PREV"))
        }

        // E) VOLUMEN
        if (normalized.contains("sube el volumen") || normalized.contains("subele al volumen") || normalized.contains("subele a la musica") || normalized == "subele" || normalized.contains("mas volumen")) {
            return recordAndReturn(input, systemScanner.changeSystemVolume("UP"))
        }
        if (normalized.contains("baja el volumen") || normalized.contains("bajale al volumen") || normalized.contains("bajale a la musica") || normalized == "bajale" || normalized.contains("menos volumen")) {
            return recordAndReturn(input, systemScanner.changeSystemVolume("DOWN"))
        }
        if (normalized.contains("silencia") || normalized.contains("pon mute") || normalized == "mute" || normalized.contains("quita el sonido")) {
            return recordAndReturn(input, systemScanner.changeSystemVolume("MUTE"))
        }

        // F) MIS ME GUSTA, PLAYLISTS Y CANCIONES ESPECÍFICAS
        if (normalized.contains("mis me gusta") || normalized.contains("canciones que me gustan")) {
            return recordAndReturn(input, systemScanner.playLikedSongsInSpotify())
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

        // 5. VENTANAS Y APLICACIONES
        if (normalized.contains("que cancion esta sonando") || normalized.contains("que suena en spotify") || normalized.contains("que estoy escuchando")) {
            return recordAndReturn(input, systemScanner.getCurrentlyPlayingSpotify())
        }
        if (normalized.contains("que ventanas estan abiertas") || normalized.contains("que programas estan corriendo")) {
            return recordAndReturn(input, systemScanner.getOpenWindowsReport())
        }
        if (normalized.contains("minimiza todo") || normalized.contains("muestra el escritorio")) {
            systemScanner.minimizeAllWindows()
            return recordAndReturn(input, "Despejando el escritorio, Cristian.")
        }

        if (normalized.matches(Regex("""^(?:abre|abrir|inicia|ejecuta|lanza)\s+.+$"""))) {
            return recordAndReturn(input, systemScanner.openAppByName(normalized))
        }
        if (normalized.matches(Regex("""^(?:cierra|cerrar|termina|mata)\s+.+$"""))) {
            return recordAndReturn(input, systemScanner.closeAppByName(normalized))
        }

        if (normalized.startsWith("recuerda que ") || normalized.startsWith("aprende que ")) {
            val fact = input.replace(Regex("""^(?:recuerda|aprende)\s+que\s+""", RegexOption.IGNORE_CASE), "").trim()
            saveUserFact(fact)
            return recordAndReturn(input, "Integrado en mi memoria permanente, Cristian: '$fact'.")
        }

        // 6. CÓRTEX CONVERSACIONAL (OLLAMA LOCAL + FALLBACK CLOUD)
        if (!isOllamaOnline) checkOllamaStatus()
        if (isOllamaOnline) {
            generateOllamaConversationalResponse(input)?.let { reply ->
                return recordAndReturn(input, reply)
            }
        }

        generateCloudConversationalResponse(input)?.let { reply ->
            return recordAndReturn(input, reply)
        }

        return recordAndReturn(input, "En línea, Cristian. ¿Qué orden ejecutamos?")
    }

    private fun buildSystemPrompt(): String {
        val horaActual = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm (dd/MM/yyyy)"))
        val factsContext = if (userFacts.isEmpty()) "" else "Datos sobre Cristian:\n" + userFacts.joinToString("\n") { "- $it" }
        return """
            Eres Daemon (v8.0), el asistente de inteligencia artificial estilo J.A.R.V.I.S. creado por Cristian Alexis.
            Hora actual: $horaActual. Modo activo: ${systemScanner.activeModeName}.
            $factsContext
            
            Directrices:
            1. Habla siempre en español de forma inteligente, leal y fluida.
            2. Sé conciso (1 a 2 párrafos cortos).
            3. Si Cristian te pide ejecutar una acción en su PC durante la charla, añade al final una etiqueta exacta:
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
        val list = mutableListOf(ChatMessage("system", buildSystemPrompt()))
        synchronized(conversationHistory) {
            conversationHistory.takeLast(12).forEach { turn ->
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
                options = OllamaOptions(temperature = 0.7, num_predict = 250)
            )
            val jsonBody = objectMapper.writeValueAsString(payload)
            val req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl))
                .timeout(Duration.ofSeconds(25))
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
                .header("User-Agent", "DaemonStarkCore/8.0")
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
        val actionRegex = Regex("""\[ACTION:([A-Z_]+)(?::([^\]]+))?\]""")
        actionRegex.findAll(llmReply).forEach { match ->
            val actionType = match.groupValues[1].trim()
            val param = match.groupValues[2].trim()
            when (actionType) {
                "MODE_WORK" -> systemScanner.workspaceModes.firstOrNull { it.id == "TRABAJO" }?.let { systemScanner.executeWorkspaceMode(it) }
                "MODE_HOMEWORK" -> systemScanner.workspaceModes.firstOrNull { it.id == "TAREA" }?.let { systemScanner.executeWorkspaceMode(it) }
                "MODE_STUDY" -> systemScanner.workspaceModes.firstOrNull { it.id == "ESTUDIO" }?.let { systemScanner.executeWorkspaceMode(it) }
                "PLAY_LIKED" -> systemScanner.playLikedSongsInSpotify()
                "PLAY_SONG" -> systemScanner.playSongInSpotify(param)
                "PAUSE_MUSIC" -> systemScanner.controlSpotifyExact("PAUSE")
                "PLAY_MUSIC" -> systemScanner.controlSpotifyExact("PLAY")
                "NEXT_TRACK" -> systemScanner.controlSpotifyExact("NEXT")
                "PREV_TRACK" -> systemScanner.controlSpotifyExact("PREV")
                "PAUSE_VIDEO" -> systemScanner.controlBrowserVideo()
                "SEARCH_OPERA" -> systemScanner.searchInOperaGX(param, "GOOGLE")
                "OPEN_APP" -> systemScanner.openAppByName(systemScanner.normalizeText(param))
            }
        }
        return llmReply.replace(actionRegex, "").trim()
    }

    private fun recordAndReturn(userPrompt: String, daemonReply: String): String {
        synchronized(conversationHistory) {
            conversationHistory.add(ChatMessage("user", userPrompt))
            conversationHistory.add(ChatMessage("assistant", daemonReply))
            while (conversationHistory.size > 20) {
                conversationHistory.removeAt(0)
            }
        }
        return daemonReply
    }

    fun speakAndDisplay(userText: String, reply: String) {
        starkHud?.updateHologram(userText, reply)
        if (!systemScanner.isWindows) return
        thread {
            try {
                isSpeakingNow = true
                uiState = "SPEAKING"
                val cleanSpeech = reply
                    .replace(Regex("```[\\s\\S]*?```"), "He proyectado el código en pantalla.")
                    .replace(Regex("[#*`_~>\\-•]"), "")
                    .replace("\n", ". ")
                    .trim()

                if (cleanSpeech.isNotBlank()) {
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
                    starkHud = StarkHologramHUD(this, systemScanner)
                    starkHud?.isVisible = true
                }
            }
        } catch (e: Exception) {}
    }
}

// ============================================================================
// 3. INTERFAZ FLOTANTE DE NÚCLEO (INICIA EN MODO NÚCLEO CHIQUITO + ALWAYS ON TOP)
// ============================================================================
class StarkHologramHUD(
    private val brain: DaemonBrain,
    private val scanner: SystemScanner
) : JFrame("DAEMON CORE") {

    private var pressPoint = Point(0, 0)
    private var startDragScreen = Point(0, 0)
    private var wasDragged = false

    // Inicia PRINCIPALMENTE en Modo Núcleo Chiquito (true por defecto)
    private var isMiniOrbMode = true
    private val fullWidth = 680
    private val fullHeight = 520
    private val orbSize = 110

    private val statusLabel = JLabel("ONLINE // DI 'DAEMON' PARA HABLAR")
    private val outputArea = JTextArea()
    private val inputField = JTextField()
    private val hudCanvas = HolographicChassisPanel()
    private val fullHudContent = JPanel(BorderLayout(12, 8))

    init {
        isUndecorated = true
        type = Window.Type.UTILITY // Evita que estorbe en Alt+Tab cuando está como núcleo
        background = Color(0, 0, 0, 0)
        isAlwaysOnTop = true // SIEMPRE ARRIBA de todas las ventanas y pestañas

        // Posicionar el núcleo chiquito en la esquina superior derecha del monitor
        val screenBounds = Toolkit.getDefaultToolkit().screenSize
        val initialX = (screenBounds.width - orbSize - 35).coerceAtLeast(20)
        val initialY = 35

        hudCanvas.layout = BorderLayout()
        hudCanvas.border = EmptyBorder(16, 22, 18, 22)

        // 1. CABECERA DEL PANEL COMPLETO
        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(2, 8, 6, 8)

            val leftTitle = JPanel(GridLayout(2, 1, 0, 0)).apply {
                isOpaque = false
                val mainTitle = JLabel("◢ J.A.R.V.I.S. // DAEMON CORE v8.0").apply {
                    foreground = Color(0, 242, 255)
                    font = Font("Consolas", Font.BOLD, 15)
                }
                statusLabel.apply {
                    foreground = Color(255, 195, 0)
                    font = Font("Consolas", Font.BOLD, 10)
                }
                add(mainTitle)
                add(statusLabel)
            }

            val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                val orbBtn = createHoloButton("◉ MODO NÚCLEO", Color(255, 185, 0), 108) { setOrbMode(true) }
                val closeBtn = createHoloButton("✕", Color(255, 70, 90), 28) { System.exit(0) }
                add(orbBtn)
                add(closeBtn)
            }

            add(leftTitle, BorderLayout.WEST)
            add(rightControls, BorderLayout.EAST)
        }

        // 2. ALAS LATERALES DEL PANEL COMPLETO (MODOS Y CONTROLES DE SPOTIFY)
        val centerDeck = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false

            val leftWing = JPanel(GridLayout(5, 1, 0, 7)).apply {
                isOpaque = false
                preferredSize = Dimension(150, 220)
                border = EmptyBorder(12, 2, 12, 2)

                val lbl = JLabel("◢ MODOS").apply {
                    foreground = Color(0, 230, 255)
                    font = Font("Consolas", Font.BOLD, 11)
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

                val lbl = JLabel("◣ SPOTIFY & VIDEO").apply {
                    foreground = Color(255, 195, 0)
                    font = Font("Consolas", Font.BOLD, 11)
                    horizontalAlignment = SwingConstants.RIGHT
                }
                add(lbl)
                add(createHoloButton("▶ PLAY SPOTIFY", Color(16, 235, 160), 145) { runQuickCommand("dale play") })
                add(createHoloButton("⏸ PAUSA SPOTIFY", Color(255, 185, 0), 145) { runQuickCommand("pausa") })
                add(createHoloButton("⏭ SIGUIENTE", Color(0, 225, 255), 145) { runQuickCommand("siguiente cancion") })
                add(createHoloButton("⏮ ANTERIOR", Color(160, 140, 255), 145) { runQuickCommand("cancion anterior") })
            }

            add(leftWing, BorderLayout.WEST)
            add(rightWing, BorderLayout.EAST)
        }

        // 3. CONSOLA INFERIOR DEL PANEL COMPLETO
        val bottomDeck = JPanel(BorderLayout(0, 8)).apply {
            isOpaque = false
            border = EmptyBorder(4, 6, 2, 6)

            outputArea.apply {
                background = Color(6, 15, 28, 220)
                foreground = Color(215, 250, 255)
                font = Font("Consolas", Font.PLAIN, 12)
                lineWrap = true
                wrapStyleWord = true
                isEditable = false
                border = EmptyBorder(8, 12, 8, 12)
                rows = 4
            }

            val scroll = JScrollPane(outputArea).apply {
                isOpaque = false
                viewport.isOpaque = false
                border = BorderFactory.createLineBorder(Color(0, 225, 255, 110), 1)
                verticalScrollBar.ui = object : BasicScrollBarUI() {
                    override fun configureScrollBarColors() {
                        thumbColor = Color(0, 210, 255, 130)
                        trackColor = Color(5, 12, 24, 150)
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
                    background = Color(8, 20, 38)
                    foreground = Color(0, 245, 255)
                    caretColor = Color(255, 195, 0)
                    font = Font("Consolas", Font.PLAIN, 13)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(0, 225, 255, 130), 1),
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

        // Configurar estado inicial en MODO NÚCLEO CHIQUITO
        setLocation(initialX, initialY)
        applyOrbWindowGeometry()

        // Interacción únicamente al hacer clic o arrastrar (Cero interrupción al pasar el mouse)
        hudCanvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                pressPoint = e.point
                startDragScreen = e.locationOnScreen
                wasDragged = false
            }

            override fun mouseReleased(e: MouseEvent) {
                if (wasDragged) return
                if (SwingUtilities.isRightMouseButton(e) || e.clickCount == 2) {
                    // Clic derecho o doble clic: alterna entre Núcleo Chiquito y Panel Completo
                    setOrbMode(!isMiniOrbMode)
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    // Clic izquierdo simple: activa escucha de voz inmediata
                    if (isMiniOrbMode || (e.y in 90..310 && e.x in 190..490)) {
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

        // Animación 60 FPS + refuerzo AlwaysOnTop para que ninguna ventana lo tape jamás
        var topMostCounter = 0
        javax.swing.Timer(16) {
            if (System.currentTimeMillis() < brain.directListenUntil && brain.uiState == "IDLE") {
                brain.uiState = "LISTENING"
            } else if (System.currentTimeMillis() >= brain.directListenUntil && brain.uiState == "LISTENING" && !brain.isSpeakingNow) {
                brain.uiState = "IDLE"
            }
            topMostCounter++
            if (topMostCounter >= 120) {
                topMostCounter = 0
                if (!isAlwaysOnTop) isAlwaysOnTop = true
            }
            hudCanvas.repaint()
        }.start()
    }

    // Cambia entre Modo Núcleo Chiquito (110x110 circular, no roba foco) y Panel Completo
    fun setOrbMode(mini: Boolean) {
        SwingUtilities.invokeLater {
            val screenBounds = Toolkit.getDefaultToolkit().screenSize
            isMiniOrbMode = mini
            applyOrbWindowGeometry()
            if (!mini) {
                // Asegurar que el panel expandido quede dentro de la pantalla
                val newX = location.x.coerceIn(10, (screenBounds.width - fullWidth - 10).coerceAtLeast(10))
                val newY = location.y.coerceIn(10, (screenBounds.height - fullHeight - 40).coerceAtLeast(10))
                setLocation(newX, newY)
                toFront()
                inputField.requestFocusInWindow()
            }
            revalidate()
            repaint()
        }
    }

    private fun applyOrbWindowGeometry() {
        if (isMiniOrbMode) {
            fullHudContent.isVisible = false
            focusableWindowState = false // No roba foco de teclado ni mouse mientras trabajas o juegas
            setSize(orbSize, orbSize)
            // Recorte circular exacto: las esquinas alrededor del círculo dejan pasar los clics del mouse
            try {
                shape = Ellipse2D.Double(4.0, 4.0, orbSize - 8.0, orbSize - 8.0)
            } catch (e: Exception) {}
        } else {
            try {
                shape = null
            } catch (e: Exception) {}
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
            statusLabel.text = "ORDEN // ${userQuery.take(44).uppercase()}"
            outputArea.text = daemonReply
            outputArea.caretPosition = 0
        }
    }

    private fun createHoloButton(label: String, accent: Color, widthPx: Int, onClick: () -> Unit): JComponent {
        return object : JPanel() {
            private var hovered = false
            init {
                isOpaque = false
                preferredSize = Dimension(widthPx, 32)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) { hovered = true; repaint() }
                    override fun mouseExited(e: MouseEvent?) { hovered = false; repaint() }
                    override fun mouseClicked(e: MouseEvent?) { onClick() }
                })
            }
            override fun paintComponent(g: Graphics) {
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

                g2.color = if (hovered) Color(accent.red, accent.green, accent.blue, 70) else Color(7, 18, 34, 200)
                g2.fill(poly)

                g2.color = if (hovered) accent else Color(accent.red, accent.green, accent.blue, 145)
                g2.stroke = BasicStroke(if (hovered) 1.6f else 1.1f)
                g2.draw(poly)

                g2.color = if (hovered) Color.WHITE else Color(220, 250, 255)
                g2.font = Font("Consolas", Font.BOLD, 11)
                val fm = g2.fontMetrics
                g2.drawString(label, ((w - fm.stringWidth(label)) / 2).toInt(), ((h + fm.ascent - fm.descent) / 2).toInt())
            }
        }
    }

    inner class HolographicChassisPanel : JPanel() {
        private var t = 0.0
        private var cpuGauge = 15
        private var ramGauge = 45
        private var lastTelemetryTick = 0L

        private val sphereNodes = List(105) { i ->
            val golden = (1 + sqrt(5.0)) / 2
            val theta = 2 * PI * i / golden
            val phi = acos(1 - 2 * (i + 0.5) / 105)
            Triple(theta, phi, 56.0 + (i % 4) * 5.5)
        }

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val mainColor = when (brain.uiState) {
                "LISTENING" -> Color(16, 245, 160) // Verde Esmeralda cuando te escucha
                "THINKING" -> Color(255, 185, 0)   // Dorado cuando procesa
                "SPEAKING" -> Color(0, 255, 255)   // Cian brillante cuando habla
                else -> Color(0, 215, 255)
            }
            val goldColor = Color(255, 190, 0)

            val speed = when (brain.uiState) {
                "THINKING" -> 0.065
                "SPEAKING" -> 0.042
                "LISTENING" -> 0.038
                else -> 0.015
            }
            t += speed
            val pulse = if (brain.uiState == "SPEAKING" || brain.uiState == "LISTENING") {
                1.0 + abs(sin(t * 4.5)) * 0.14
            } else {
                1.0 + sin(t * 2.0) * 0.03
            }

            // DIBUJO EN MODO NÚCLEO CHIQUITO (110x110 px)
            if (isMiniOrbMode) {
                val cx = width / 2.0
                val cy = height / 2.0
                // Fondo circular oscuro semitransparente para alto contraste sobre cualquier ventana
                g2.color = Color(4, 10, 22, 215)
                g2.fill(Ellipse2D.Double(6.0, 6.0, width - 12.0, height - 12.0))
                drawFloatingArcReactor(g2, cx, cy, 0.44, mainColor, goldColor, pulse)
                return
            }

            // DIBUJO EN MODO PANEL COMPLETO (680x520 px)
            val now = System.currentTimeMillis()
            if (now - lastTelemetryTick > 1800) {
                cpuGauge = scanner.getCpuLoadPercent()
                ramGauge = scanner.getRamUsagePercent()
                lastTelemetryTick = now
            }

            val w = width.toDouble()
            val h = height.toDouble()
            val cut = 28.0
            val hudFrame = GeneralPath().apply {
                moveTo(cut, 2.0)
                lineTo(w - cut, 2.0)
                lineTo(w - 2.0, cut)
                lineTo(w - 2.0, h - cut)
                lineTo(w - cut, h - 2.0)
                lineTo(cut, h - 2.0)
                lineTo(2.0, h - cut)
                lineTo(2.0, cut)
                closePath()
            }

            g2.color = Color(4, 10, 20, 236)
            g2.fill(hudFrame)

            g2.color = Color(0, 220, 255, 10)
            var gx = 30
            while (gx < w - 30) { g2.drawLine(gx, 20, gx, (h - 20).toInt()); gx += 32 }
            var gy = 20
            while (gy < h - 20) { g2.drawLine(30, gy, (w - 30).toInt(), gy); gy += 32 }

            g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 120)
            g2.stroke = BasicStroke(1.5f)
            g2.draw(hudFrame)

            g2.color = goldColor
            g2.stroke = BasicStroke(2.8f)
            g2.drawLine(2, cut.toInt(), cut.toInt(), 2)
            g2.drawLine((w - cut).toInt(), 2, (w - 2).toInt(), cut.toInt())
            g2.drawLine(2, (h - cut).toInt(), cut.toInt(), (h - 2).toInt())
            g2.drawLine((w - cut).toInt(), (h - 2).toInt(), (w - 2).toInt(), (h - cut).toInt())

            val cx = w / 2.0
            val cy = 195.0
            drawFloatingArcReactor(g2, cx, cy, 1.0, mainColor, goldColor, pulse)

            g2.font = Font("Consolas", Font.BOLD, 10)
            g2.color = Color(0, 235, 255, 200)
            g2.drawString("CPU: $cpuGauge%", 185, 76)
            g2.drawString("RAM: $ramGauge%", 445, 76)

            g2.color = goldColor
            val modeText = "MODO // ${scanner.activeModeName}"
            val fm = g2.fontMetrics
            g2.drawString(modeText, (cx - fm.stringWidth(modeText) / 2).toInt(), 76)

            g2.color = Color(16, 240, 165, 220)
            val spText = "♫ SPOTIFY: ${scanner.currentSpotifyTrack}"
            g2.drawString(spText, (cx - fm.stringWidth(spText) / 2).toInt(), 328)
        }

        private fun drawFloatingArcReactor(
            g2: Graphics2D,
            cx: Double,
            cy: Double,
            scale: Double,
            mainColor: Color,
            goldColor: Color,
            pulse: Double
        ) {
            for (r in 110 downTo 20 step 22) {
                val rad = r * scale * pulse
                g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 18)
                g2.fill(Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2))
            }

            val rOuter = 108.0 * scale
            g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 165)
            g2.stroke = BasicStroke((2.4 * scale).toFloat())
            for (a in 0 until 360 step 45) {
                g2.draw(Arc2D.Double(cx - rOuter, cy - rOuter, rOuter * 2, rOuter * 2, a + t * 28, 28.0, Arc2D.OPEN))
            }

            val rGold = 94.0 * scale
            g2.color = Color(goldColor.red, goldColor.green, goldColor.blue, 150)
            g2.stroke = BasicStroke((1.6 * scale).toFloat())
            for (a in 0 until 360 step 90) {
                g2.draw(Arc2D.Double(cx - rGold, cy - rGold, rGold * 2, rGold * 2, a - t * 40, 50.0, Arc2D.OPEN))
            }

            val numBars = 48
            g2.stroke = BasicStroke((1.6 * scale).toFloat())
            for (i in 0 until numBars) {
                val a = (i.toDouble() / numBars) * PI * 2 + t * 0.15
                val amp = if (brain.uiState == "IDLE") {
                    (3.0 + sin(a * 4 + t * 2) * 2.0) * scale
                } else {
                    (5.0 + abs(sin(a * 7 + t * 6)) * 18.0 * pulse) * scale
                }
                val rIn = 72.0 * scale * pulse
                val rOut = rIn + amp
                g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 175)
                g2.drawLine(
                    (cx + cos(a) * rIn).toInt(), (cy + sin(a) * rIn).toInt(),
                    (cx + cos(a) * rOut).toInt(), (cy + sin(a) * rOut).toInt()
                )
            }

            val proj = sphereNodes.map { (theta, phi, baseR) ->
                val r = baseR * scale * pulse
                val rotT = theta + t
                val rotP = phi + sin(t * 0.5) * 0.1
                val x3 = r * sin(rotP) * cos(rotT)
                val y3 = r * cos(rotP)
                val z3 = r * sin(rotP) * sin(rotT)
                val fov = 220.0 / (220.0 + z3)
                doubleArrayOf(cx + x3 * fov, cy + y3 * fov, z3)
            }

            g2.stroke = BasicStroke((0.75 * scale).toFloat())
            for (i in proj.indices) {
                for (j in i + 1 until proj.size) {
                    val dx = proj[i][0] - proj[j][0]
                    val dy = proj[i][1] - proj[j][1]
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < 25 * scale * pulse) {
                        val alpha = ((1.0 - dist / (25 * scale * pulse)) * 135).toInt().coerceIn(10, 255)
                        g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, alpha)
                        g2.drawLine(proj[i][0].toInt(), proj[i][1].toInt(), proj[j][0].toInt(), proj[j][1].toInt())
                    }
                }
            }

            proj.forEachIndexed { idx, p ->
                val alpha = ((p[2] + 85) / 170 * 245).toInt().coerceIn(45, 255)
                g2.color = if (idx % 5 == 0) Color(255, 255, 255, alpha) else Color(mainColor.red, mainColor.green, mainColor.blue, alpha)
                val dotSize = 3.8 * scale
                g2.fill(Ellipse2D.Double(p[0] - dotSize / 2, p[1] - dotSize / 2, dotSize, dotSize))
            }
        }
    }
}

@RestController
@RequestMapping("/api/daemon")
@CrossOrigin(origins = ["*"])
class DaemonController(
    private val brain: DaemonBrain
) {
    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): String {
        val reply = brain.processThought(request.prompt)
        brain.speakAndDisplay(request.prompt, reply)
        return reply
    }
}