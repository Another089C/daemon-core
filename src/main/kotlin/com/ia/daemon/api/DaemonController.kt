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
// 1. MOTOR MULTIPLATAFORMA NATIVO (WINDOWS + MACOS + UBUNTU LINUX)
// ============================================================================
@Service
class SystemScanner {

    val osName: String = System.getProperty("os.name").lowercase()
    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac") || osName.contains("darwin")
    val isLinux = osName.contains("nix") || osName.contains("nux") || osName.contains("aix")

    private val installedApps = mutableMapOf("init" to "init").apply { clear() }
    private val playlistsFile = File("daemon_playlists.json")
    private val modesFile = File("daemon_modes.json")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val savedPlaylists = mutableMapOf("init" to "init").apply { clear() }
    val workspaceModes = mutableListOf(WorkspaceMode()).apply { clear() }

    @Volatile var activeModeName: String = "NÚCLEO ACTIVO"
    @Volatile var currentSpotifyTrack: String = "STANDBY"
    @Volatile var cachedCpuLoad: Int = 12
    @Volatile var cachedRamUsage: Int = 42
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

    fun getOSLabel(): String = when {
        isWindows -> "WINDOWS"
        isMac -> "MACOS"
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

    // Actualiza Spotify, CPU y RAM en un hilo en segundo plano para NUNCA trabar la interfaz gráfica
    private fun startLiveTelemetryMonitor() {
        thread(isDaemon = true, name = "Daemon-Background-Telemetry") {
            while (true) {
                try {
                    cachedCpuLoad = readCpuLoadInternal()
                    cachedRamUsage = readRamUsageInternal()
                    val status = getSpotifyStatusAndTrack()
                    currentSpotifyTrack = when {
                        status == "CLOSED" -> "SPOTIFY CERRADO"
                        status == "PAUSED" -> "EN PAUSA"
                        else -> status.removePrefix("PLAYING:").take(34)
                    }
                } catch (e: Exception) {}
                Thread.sleep(2500)
            }
        }
    }

    private fun readCpuLoadInternal(): Int {
        return try {
            val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val load = (osBean.cpuLoad * 100).toInt()
            if (load in 1..100) load else Random.nextInt(8, 20)
        } catch (e: Exception) { 12 }
    }

    private fun readRamUsageInternal(): Int {
        return try {
            val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val total = osBean.totalMemorySize.toDouble()
            val free = osBean.freeMemorySize.toDouble()
            (((total - free) / total) * 100).toInt().coerceIn(1, 99)
        } catch (e: Exception) { 42 }
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

    fun controlSpotifyExact(command: String): String {
        val now = System.currentTimeMillis()
        if (now - lastMediaActionTimestamp < 450) return "Comando en proceso, Cristian."
        lastMediaActionTimestamp = now

        val state = getSpotifyStatusAndTrack()
        val isOpen = state != "CLOSED"
        val isPlaying = state.startsWith("PLAYING:")

        if (!isOpen && (command == "PLAY" || command == "NEXT" || command == "PREV")) {
            playLikedSongsInSpotify()
            return "Abriendo Spotify e iniciando tus canciones de Mis Me Gusta, Cristian."
        }

        return when {
            isWindows -> {
                when (command) {
                    "PAUSE" -> {
                        if (!isPlaying) return "Spotify ya está en pausa, Cristian."
                        triggerWindowsMediaKey(0xB3, 1)
                        currentSpotifyTrack = "EN PAUSA"
                        "Música en pausa, Cristian."
                    }
                    "PLAY" -> {
                        if (isPlaying) return "La música ya se está reproduciendo, Cristian."
                        triggerWindowsMediaKey(0xB3, 1)
                        "Reanudando reproducción en Spotify, Cristian."
                    }
                    "NEXT" -> {
                        triggerWindowsMediaKey(0xB0, 1)
                        "Siguiente canción en Spotify, Cristian."
                    }
                    "PREV" -> {
                        triggerWindowsMediaKey(0xB1, 1)
                        "Canción anterior en Spotify, Cristian."
                    }
                    else -> "Comando ejecutado."
                }
            }
            isMac -> {
                when (command) {
                    "PAUSE" -> {
                        runAppleScript("tell application \"Spotify\" to pause")
                        currentSpotifyTrack = "EN PAUSA"
                        "Música en pausa en tu Mac, Cristian."
                    }
                    "PLAY" -> {
                        runAppleScript("tell application \"Spotify\" to play")
                        "Reanudando reproducción en Spotify, Cristian."
                    }
                    "NEXT" -> {
                        runAppleScript("tell application \"Spotify\" to next track")
                        "Siguiente canción en Spotify, Cristian."
                    }
                    "PREV" -> {
                        runAppleScript("tell application \"Spotify\" to previous track")
                        "Canción anterior en Spotify, Cristian."
                    }
                    else -> "Comando ejecutado."
                }
            }
            isLinux -> {
                when (command) {
                    "PAUSE" -> {
                        runLinuxShell("playerctl -p spotify pause || dbus-send --print-reply --dest=org.mpris.MediaPlayer2.spotify /org/mpris/MediaPlayer2 org.mpris.MediaPlayer2.Player.Pause")
                        "Música en pausa, Cristian."
                    }
                    "PLAY" -> {
                        runLinuxShell("playerctl -p spotify play || dbus-send --print-reply --dest=org.mpris.MediaPlayer2.spotify /org/mpris/MediaPlayer2 org.mpris.MediaPlayer2.Player.Play")
                        "Reanudando reproducción en Spotify, Cristian."
                    }
                    "NEXT" -> {
                        runLinuxShell("playerctl -p spotify next || dbus-send --print-reply --dest=org.mpris.MediaPlayer2.spotify /org/mpris/MediaPlayer2 org.mpris.MediaPlayer2.Player.Next")
                        "Siguiente canción, Cristian."
                    }
                    "PREV" -> {
                        runLinuxShell("playerctl -p spotify previous || dbus-send --print-reply --dest=org.mpris.MediaPlayer2.spotify /org/mpris/MediaPlayer2 org.mpris.MediaPlayer2.Player.Previous")
                        "Canción anterior, Cristian."
                    }
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

    fun playLikedSongsInSpotify(): String {
        thread {
            try {
                when {
                    isWindows -> {
                        val ps = """
                            Start-Process "spotify:collection:tracks"
                            Start-Sleep -Milliseconds 2600
                            ${'$'}wsh = New-Object -ComObject WScript.Shell
                            ${'$'}sp = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | Select-Object -First 1
                            if (${'$'}null -ne ${'$'}sp) {
                                ${'$'}wsh.AppActivate(${'$'}sp.Id) | Out-Null
                                Start-Sleep -Milliseconds 450
                                ${'$'}wsh.SendKeys("{ENTER}")
                                Start-Sleep -Milliseconds 650
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
                                activate
                                open location "spotify:collection:tracks"
                                delay 2.0
                                play
                            end tell
                            tell application "System Events"
                                key code 36
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
        return "Reproduciendo tus canciones de Mis Me Gusta en Spotify, Cristian."
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
        return "Alternando reproducción del video en tu navegador sin alterar Spotify, Cristian."
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
            "UP" -> "Subiendo volumen del sistema, Cristian."
            "DOWN" -> "Bajando volumen del sistema, Cristian."
            else -> "Audio del sistema alternado, Cristian."
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

        return if (platform == "YOUTUBE") {
            "Buscando '$cleanQuery' en YouTube dentro de Opera GX, Cristian."
        } else {
            "Buscando '$cleanQuery' en Opera GX, Cristian."
        }
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

    private fun loadWorkspaceModes() {
        val defaultModes = listOf(
            WorkspaceMode(
                id = "TRABAJO",
                name = "PROTOCOLO TRABAJO",
                triggers = listOf("vamos a trabajar", "modo trabajo", "protocolo trabajo", "hora de trabajar", "vamos a programar", "modo programacion", "activa modo trabajo"),
                appsToOpen = listOf("vscode", "notion", "opera gx"),
                appsToClose = emptyList(),
                spotifyPlaylist = "mis me gusta",
                greeting = "Activando Modo Trabajo en tu ${getOSLabel()}, Cristian. Abriendo Visual Studio Code, Notion, Opera GX y reproduciendo tus canciones de Mis Me Gusta."
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
            )
        )

        workspaceModes.clear()
        workspaceModes.addAll(defaultModes)
        try { objectMapper.writeValue(modesFile, workspaceModes) } catch (e: Exception) {}
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
        val home = System.getProperty("user.home") ?: ""
        listOf(
            File("/usr/share/applications"),
            File("/var/lib/snapd/desktop/applications"),
            File("$home/.local/share/applications")
        ).filter { it.exists() }.forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".desktop") }?.forEach { file ->
                var name: String? = null
                var exec: String? = null
                file.readLines().forEach { line ->
                    if (line.startsWith("Name=") && name == null) name = line.removePrefix("Name=").trim()
                    if (line.startsWith("Exec=") && exec == null) exec = line.removePrefix("Exec=").replace(Regex("%[a-zA-Z]"), "").trim()
                }
                if (name != null && exec != null) {
                    installedApps[normalizeText(name!!)] = exec!!
                }
            }
        }
        installedApps["vscode"] = "code"
        installedApps["visual"] = "code"
        installedApps["spotify"] = "spotify"
        installedApps["opera gx"] = "opera"
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

        val entry = installedApps.entries.firstOrNull { it.key == canonical }
            ?: installedApps.entries.firstOrNull { it.key.contains(canonical) || canonical.contains(it.key) }

        return try {
            when {
                isWindows -> {
                    if (canonical == "opera gx") {
                        val operaExe = findWindowsOperaGX()
                        if (operaExe != null) {
                            ProcessBuilder("cmd", "/c", "start", "", operaExe).start()
                            return "Abriendo Opera GX, Cristian."
                        }
                    }
                    if (entry != null) {
                        ProcessBuilder("cmd", "/c", "start", "", entry.value).start()
                        "Iniciando ${entry.key.uppercase()}, Cristian."
                    } else "No encontré '$requestedApp' instalado."
                }
                isMac -> {
                    val macTarget = entry?.value ?: requestedApp
                    ProcessBuilder("open", "-a", macTarget).start()
                    "Abriendo ${macTarget.uppercase()} en tu Mac, Cristian."
                }
                isLinux -> {
                    val linuxTarget = entry?.value ?: requestedApp
                    runLinuxShell("$linuxTarget &")
                    "Iniciando ${linuxTarget.uppercase()} en Linux, Cristian."
                }
                else -> "Sistema no soportado."
            }
        } catch (e: Exception) {
            "No pude iniciar '$requestedApp': ${e.message}"
        }
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
        return "He cerrado $appName, Cristian."
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
                val out = runLinuxShell("wmctrl -l | awk '{{\$1=\$2=\$3=\"\"; print \$0}}'", wait = true)
                "Ventanas activas en Linux:\n$out"
            }
        }
    }

    fun getCurrentlyPlayingSpotify(): String {
        val state = getSpotifyStatusAndTrack()
        return when {
            state == "CLOSED" -> "Spotify está cerrado en este momento, Cristian."
            state == "PAUSED" -> "Spotify está abierto pero en pausa, Cristian."
            else -> "Está sonando '${state.removePrefix("PLAYING:")}' en Spotify."
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
                        activate
                        open location "$spotifyUri"
                        delay 1.8
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
                    "Auto-inicio registrado en Windows."
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
                    cmdFile.writeText("#!/bin/bash\ncd \"$workingDir\"\n\"$javaBin\" -jar \"${jarFile.absolutePath}\" &\n", StandardCharsets.UTF_8)
                    cmdFile.setExecutable(true)
                    "Auto-inicio registrado en macOS LaunchAgents."
                }
                isLinux -> {
                    val home = System.getProperty("user.home")
                    val javaBin = System.getProperty("java.home") + "/bin/java"
                    val autostartDir = File("$home/.config/autostart")
                    if (!autostartDir.exists()) autostartDir.mkdirs()

                    val desktopEntry = File(autostartDir, "daemon-ai.desktop")
                    val content = """
                        [Desktop Entry]
                        Type=Application
                        Name=Daemon AI
                        Exec="$javaBin" -jar "${jarFile.absolutePath}"
                        Path=$workingDir
                        Terminal=false
                        X-GNOME-Autostart-enabled=true
                    """.trimIndent()
                    desktopEntry.writeText(content, StandardCharsets.UTF_8)
                    "Auto-inicio registrado en Ubuntu/Linux."
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
// 2. CEREBRO DE DAEMON + VOZ MULTIPLATAFORMA + WAKE WORD ("DAEMON")
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
    @Volatile var directListenUntil: Long = 0L

    private var starkHud: StarkHologramHUD? = null
    private val wakeWords = listOf("daemon", "daimon", "deimon", "daymon", "demon", "damon")

    init {
        loadUserFacts()
        checkOllamaStatus()
        launchStarkHologramHUD()
        startContinuousWakeWordEar()
        speakAndDisplay(
            "NÚCLEO ACTIVO",
            "Daemon v9.5 en línea sobre ${systemScanner.getOSLabel()}, Cristian."
        )
    }

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

    fun activateDirectVoiceListenWindow() {
        directListenUntil = System.currentTimeMillis() + 8000L
        uiState = "LISTENING"
        starkHud?.setStatusText("TE ESCUCHO, CRISTIAN...")
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

    fun processThought(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isBlank()) return "Sin señal de entrada."
        uiState = "THINKING"

        var normalized = systemScanner.normalizeText(input)
        for (w in wakeWords) {
            normalized = normalized.replace(Regex("""^(?:oye\s+|hola\s+)?$w\b\s*"""), "").trim()
        }

        if (normalized.contains("abre el panel") || normalized.contains("expande el nucleo") || normalized.contains("modo ventana")) {
            starkHud?.setOrbMode(false)
            return recordAndReturn(input, "Desplegando el panel holográfico completo, Cristian.")
        }
        if (normalized.contains("modo nucleo") || normalized.contains("minimiza el panel") || normalized.contains("hazte chiquito")) {
            starkHud?.setOrbMode(true)
            return recordAndReturn(input, "Contrayendo a modo núcleo flotante, Cristian.")
        }

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

        if (!isOllamaOnline) checkOllamaStatus()
        if (isOllamaOnline) {
            generateOllamaConversationalResponse(input)?.let { reply ->
                return recordAndReturn(input, reply)
            }
        }

        generateCloudConversationalResponse(input)?.let { reply ->
            return recordAndReturn(input, reply)
        }

        return recordAndReturn(input, "En línea sobre ${systemScanner.getOSLabel()}, Cristian. ¿Qué orden ejecutamos?")
    }

    private fun buildSystemPrompt(): String {
        val horaActual = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm (dd/MM/yyyy)"))
        val factsContext = if (userFacts.isEmpty()) "" else "Datos sobre Cristian:\n" + userFacts.joinToString("\n") { "- $it" }
        return """
            Eres Daemon (v9.5), el asistente de inteligencia artificial multiplataforma estilo J.A.R.V.I.S. creado por Cristian Alexis.
            Estás ejecutándote sobre ${systemScanner.getOSLabel()}. Hora actual: $horaActual. Modo activo: ${systemScanner.activeModeName}.
            $factsContext
            
            Directrices:
            1. Habla siempre en español de forma inteligente, leal y fluida.
            2. Sé conciso (1 a 2 párrafos cortos).
            3. Si Cristian te pide ejecutar una acción durante la charla, añade al final una etiqueta exacta:
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
                .header("User-Agent", "DaemonStarkCore/9.5")
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
                    starkHud = StarkHologramHUD(this, systemScanner)
                    starkHud?.isVisible = true
                }
            }
        } catch (e: Exception) {}
    }
}

// ============================================================================
// 3. INTERFAZ ACELERADA CERO PARPADEO Y CERO LAG (MACOS / WINDOWS / LINUX)
// ============================================================================
class StarkHologramHUD(
    private val brain: DaemonBrain,
    private val scanner: SystemScanner
) : JFrame("DAEMON CORE") {

    private var pressPoint = Point(0, 0)
    private var startDragScreen = Point(0, 0)
    private var wasDragged = false

    private var isMiniOrbMode = true
    private val fullWidth = 680
    private val fullHeight = 520
    private val orbSize = 110

    private val bgSpaceDark = Color(4, 10, 20)
    private val statusLabel = JLabel("ONLINE // CLIC DERECHO PARA EXPANDIR")
    private val outputArea = JTextArea()
    private val inputField = JTextField()
    private val hudCanvas = HolographicChassisPanel()
    private val fullHudContent = JPanel(BorderLayout(12, 8))

    init {
        isUndecorated = true
        if (scanner.isWindows) {
            type = Window.Type.UTILITY
        }
        // Superficie 100% opaca acelerada por hardware: elimina el parpadeo al mover el mouse en macOS y Windows
        background = bgSpaceDark
        isAlwaysOnTop = true

        rootPane.putClientProperty("Window.shadow", false)
        rootPane.isDoubleBuffered = true

        val screenBounds = Toolkit.getDefaultToolkit().screenSize
        val initialX = (screenBounds.width - orbSize - 35).coerceAtLeast(20)
        val initialY = 38

        hudCanvas.layout = BorderLayout()
        hudCanvas.border = EmptyBorder(16, 22, 18, 22)
        hudCanvas.isOpaque = true
        hudCanvas.background = bgSpaceDark

        // 1. CABECERA DEL PANEL COMPLETO
        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(2, 8, 6, 8)

            val leftTitle = JPanel(GridLayout(2, 1, 0, 0)).apply {
                isOpaque = false
                val mainTitle = JLabel("◢ J.A.R.V.I.S. // DAEMON CORE v9.5 (${scanner.getOSLabel()})").apply {
                    foreground = Color(0, 242, 255)
                    font = Font("Monospaced", Font.BOLD, 14)
                }
                statusLabel.apply {
                    foreground = Color(255, 195, 0)
                    font = Font("Monospaced", Font.BOLD, 10)
                }
                add(mainTitle)
                add(statusLabel)
            }

            val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                val orbBtn = createHoloButton("◉ MODO NÚCLEO", Color(255, 185, 0), 112) { setOrbMode(true) }
                val closeBtn = createHoloButton("✕", Color(255, 70, 90), 28) { System.exit(0) }
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

                val lbl = JLabel("◢ MODOS").apply {
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

                val lbl = JLabel("◣ SPOTIFY & VIDEO").apply {
                    foreground = Color(255, 195, 0)
                    font = Font("Monospaced", Font.BOLD, 11)
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

        // 3. CONSOLA INFERIOR DEL PANEL COMPLETO (100% OPACA PARA EVITAR PARPADEO EN SWING)
        val bottomDeck = JPanel(BorderLayout(0, 8)).apply {
            isOpaque = false
            border = EmptyBorder(4, 6, 2, 6)

            outputArea.apply {
                isOpaque = true
                background = Color(6, 15, 28) // Sin canal alfa para evitar bug de repintado en Swing
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

        // Un único reloj maestro a 36 FPS: fluido, sin saturar la CPU ni competir con eventos del mouse
        javax.swing.Timer(28) {
            if (System.currentTimeMillis() < brain.directListenUntil && brain.uiState == "IDLE") {
                brain.uiState = "LISTENING"
            } else if (System.currentTimeMillis() >= brain.directListenUntil && brain.uiState == "LISTENING" && !brain.isSpeakingNow) {
                brain.uiState = "IDLE"
            }
            hudCanvas.repaint()
        }.start()
    }

    fun setOrbMode(mini: Boolean) {
        SwingUtilities.invokeLater {
            val screenBounds = Toolkit.getDefaultToolkit().screenSize
            isMiniOrbMode = mini
            applyOrbWindowGeometry()
            if (!mini) {
                val newX = location.x.coerceIn(10, (screenBounds.width - fullWidth - 10).coerceAtLeast(10))
                val newY = location.y.coerceIn(28, (screenBounds.height - fullHeight - 40).coerceAtLeast(28))
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
            statusLabel.text = "ORDEN // ${userQuery.take(44).uppercase()}"
            outputArea.text = daemonReply
            outputArea.caretPosition = 0
        }
    }

    // Los botones NO llaman a repaint() en mouseEntered/mouseExited; el ciclo maestro los actualiza sin parpadear
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
                isOpaque = false
                preferredSize = Dimension(widthPx, 32)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) { hovered = true }
                    override fun mouseExited(e: MouseEvent?) { hovered = false }
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

    // ========================================================================
    // MOTOR GRÁFICO PRE-ALOJADO EN MEMORIA (CERO GARBAGE COLLECTION POR FRAME)
    // ========================================================================
    inner class HolographicChassisPanel : JPanel(true) {
        private var t = 0.0
        private val nodeCount = 68

        // Coordenadas esféricas y proyecciones pre-alojadas (0 allocations por fotograma)
        private val nodeTheta = DoubleArray(nodeCount)
        private val nodePhi = DoubleArray(nodeCount)
        private val nodeBaseR = DoubleArray(nodeCount)
        private val projX = DoubleArray(nodeCount)
        private val projY = DoubleArray(nodeCount)
        private val projZ = DoubleArray(nodeCount)

        // Trazos y fuentes reutilizables en caché
        private val strokeGrid = BasicStroke(1.0f)
        private val strokeBorder = BasicStroke(1.5f)
        private val strokeCorner = BasicStroke(2.8f)
        private val strokeOuterFull = BasicStroke(2.4f)
        private val strokeOuterOrb = BasicStroke(1.2f)
        private val strokeGoldFull = BasicStroke(1.6f)
        private val strokeGoldOrb = BasicStroke(0.9f)
        private val strokePlexusFull = BasicStroke(0.8f)
        private val strokePlexusOrb = BasicStroke(0.5f)
        private val telemetryFont = Font("Monospaced", Font.BOLD, 10)

        private val colorGrid = Color(0, 50, 75)
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

            // Relleno sólido rápido sin AlphaComposite.Clear
            g2.color = bgSpaceDark
            g2.fillRect(0, 0, width, height)

            val mainColor = when (brain.uiState) {
                "LISTENING" -> Color(16, 245, 160)
                "THINKING" -> Color(255, 185, 0)
                "SPEAKING" -> Color(0, 255, 255)
                else -> Color(0, 215, 255)
            }
            val dimMainColor = Color(mainColor.red / 2, mainColor.green / 2, mainColor.blue / 2)
            val glowColor = Color(mainColor.red / 8 + 4, mainColor.green / 8 + 10, mainColor.blue / 8 + 22)

            val speed = when (brain.uiState) {
                "THINKING" -> 0.065
                "SPEAKING" -> 0.042
                "LISTENING" -> 0.038
                else -> 0.018
            }
            t += speed
            val pulse = if (brain.uiState == "SPEAKING" || brain.uiState == "LISTENING") {
                1.0 + abs(sin(t * 4.5)) * 0.14
            } else {
                1.0 + sin(t * 2.0) * 0.03
            }

            if (isMiniOrbMode) {
                val cx = width / 2.0
                val cy = height / 2.0
                g2.color = dimMainColor
                g2.stroke = strokeBorder
                g2.drawOval(2, 2, width - 5, height - 5)
                drawFloatingArcReactor(g2, cx, cy, 0.44, mainColor, dimMainColor, glowColor, pulse, true)
                return
            }

            val w = width.toDouble()
            val h = height.toDouble()
            val cut = 26

            // Rejilla táctica interna
            g2.color = colorGrid
            g2.stroke = strokeGrid
            var gx = 30
            while (gx < w - 30) { g2.drawLine(gx, 20, gx, (h - 20).toInt()); gx += 36 }
            var gy = 20
            while (gy < h - 20) { g2.drawLine(30, gy, (w - 30).toInt(), gy); gy += 36 }

            // Borde del chasis
            g2.color = dimMainColor
            g2.stroke = strokeBorder
            g2.drawRect(2, 2, width - 5, height - 5)

            // Esquinas reforzadas en Oro Stark
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

            // Telemetría instantánea leída de memoria caché (0 ms de espera)
            g2.font = telemetryFont
            g2.color = colorTelemetryCyan
            g2.drawString("CPU: ${scanner.cachedCpuLoad}%", 185, 76)
            g2.drawString("RAM: ${scanner.cachedRamUsage}%", 445, 76)

            g2.color = colorGold
            val modeText = "MODO // ${scanner.activeModeName}"
            val fm = g2.fontMetrics
            g2.drawString(modeText, (cx - fm.stringWidth(modeText) / 2).toInt(), 76)

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
            // Resplandor base opaco
            val glowRad = (84.0 * scale * pulse).toInt()
            g2.color = glowColor
            g2.fillOval((cx - glowRad).toInt(), (cy - glowRad).toInt(), glowRad * 2, glowRad * 2)

            // Anillo exterior segmentado
            val rOuter = (108.0 * scale).toInt()
            g2.color = mainColor
            g2.stroke = if (isOrb) strokeOuterOrb else strokeOuterFull
            val baseAngle1 = (t * 28.0).toInt()
            for (a in 0 until 360 step 45) {
                g2.drawArc((cx - rOuter).toInt(), (cy - rOuter).toInt(), rOuter * 2, rOuter * 2, a + baseAngle1, 28)
            }

            // Segundo anillo en Oro Stark
            val rGold = (94.0 * scale).toInt()
            g2.color = colorGold
            g2.stroke = if (isOrb) strokeGoldOrb else strokeGoldFull
            val baseAngle2 = (-t * 40.0).toInt()
            for (a in 0 until 360 step 90) {
                g2.drawArc((cx - rGold).toInt(), (cy - rGold).toInt(), rGold * 2, rGold * 2, a + baseAngle2, 50)
            }

            // Analizador de espectro radial
            val numBars = 36
            for (i in 0 until numBars) {
                val a = (i.toDouble() / numBars) * PI * 2.0 + t * 0.15
                val amp = if (brain.uiState == "IDLE") {
                    (3.0 + sin(a * 4.0 + t * 2.0) * 2.0) * scale
                } else {
                    (5.0 + abs(sin(a * 7.0 + t * 6.0)) * 18.0 * pulse) * scale
                }
                val rIn = 72.0 * scale * pulse
                val rOut = rIn + amp
                val cosA = cos(a)
                val sinA = sin(a)
                g2.color = mainColor
                g2.drawLine(
                    (cx + cosA * rIn).toInt(), (cy + sinA * rIn).toInt(),
                    (cx + cosA * rOut).toInt(), (cy + sinA * rOut).toInt()
                )
            }

            // Proyección 3D sin crear objetos en memoria
            val wobble = sin(t * 0.5) * 0.1
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

            // Conexiones neuronales usando distancia al cuadrado (Evita llamar a sqrt miles de veces)
            g2.stroke = if (isOrb) strokePlexusOrb else strokePlexusFull
            g2.color = dimMainColor
            val maxDist = 26.0 * scale * pulse
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

            // Nodos
            val dotSize = (3.8 * scale).toInt().coerceAtLeast(2)
            val halfDot = dotSize / 2
            for (i in 0 until nodeCount) {
                g2.color = if (i % 5 == 0) Color.WHITE else mainColor
                g2.fillOval(projX[i].toInt() - halfDot, projY[i].toInt() - halfDot, dotSize, dotSize)
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