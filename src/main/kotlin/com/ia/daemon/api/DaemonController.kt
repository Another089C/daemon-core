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
import java.awt.geom.RoundRectangle2D
import java.io.File
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicScrollBarUI
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

data class ChatRequest(
    val prompt: String = "",
    val sessionId: String = "default-session",
    val accessPin: String = "",
    val fromBrowser: Boolean = true
)

data class IntentNode(
    val tag: String = "",
    val patterns: List<String> = emptyList(),
    val responses: List<String> = emptyList(),
    val action: String = "NONE"
)

data class ConversationTurn(
    val role: String,
    val content: String
)

data class RemoteCommand(
    val id: Long = System.currentTimeMillis(),
    val type: String,
    val payload: String = ""
)

// ============================================================================
// 1. RED NEURONAL MULTICAPA LOCAL (MLP EN KOTLIN PURO)
// ============================================================================
class SynapticNeuralNetwork {
    private var vocabulary = listOf<String>()
    private var intents = listOf<IntentNode>()

    private var hiddenSize = 72
    private var w1 = arrayOf<DoubleArray>()
    private var b1 = DoubleArray(0)
    private var w2 = arrayOf<DoubleArray>()
    private var b2 = DoubleArray(0)

    private val stopWords = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "en",
        "y", "o", "a", "por", "para", "con", "mi", "tu", "su", "daemon", "oye",
        "porfa", "favor", "dime", "sabes", "puedes", "podrias", "sobre", "acerca"
    )

    fun normalizeAndStem(text: String): List<String> {
        val clean = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
        return clean.split(Regex("\\s+"))
            .filter { it.length > 1 && it !in stopWords }
            .map { word -> if (word.length > 5) word.take(5) else word }
    }

    fun train(dataset: List<IntentNode>) {
        intents = dataset
        val vocabSet = linkedSetOf<String>()
        val trainingSamples = mutableListOf<Pair<IntArray, Int>>()

        val tempSamples = mutableListOf<Pair<List<String>, Int>>()
        intents.forEachIndexed { intentIdx, node ->
            node.patterns.forEach { pattern ->
                val stems = normalizeAndStem(pattern)
                if (stems.isNotEmpty()) {
                    vocabSet.addAll(stems)
                    tempSamples.add(stems to intentIdx)
                }
            }
        }

        vocabulary = vocabSet.toList()
        val vocabIndexMap = vocabulary.withIndex().associate { it.value to it.index }
        val inputSize = vocabulary.size
        val outputSize = intents.size
        if (inputSize == 0 || outputSize == 0) return

        for ((stems, targetIdx) in tempSamples) {
            val activeIndices = stems.mapNotNull { vocabIndexMap[it] }.distinct().toIntArray()
            if (activeIndices.isNotEmpty()) {
                trainingSamples.add(activeIndices to targetIdx)
            }
        }

        val rnd = Random(42)
        w1 = Array(inputSize) { DoubleArray(hiddenSize) { (rnd.nextDouble() - 0.5) * sqrt(2.0 / inputSize) } }
        b1 = DoubleArray(hiddenSize) { 0.01 }
        w2 = Array(hiddenSize) { DoubleArray(outputSize) { (rnd.nextDouble() - 0.5) * sqrt(2.0 / hiddenSize) } }
        b2 = DoubleArray(outputSize) { 0.0 }

        val lr = 0.07
        val epochs = 200
        val hidden = DoubleArray(hiddenSize)
        val logits = DoubleArray(outputSize)
        val probs = DoubleArray(outputSize)
        val dOut = DoubleArray(outputSize)
        val dHidden = DoubleArray(hiddenSize)

        for (epoch in 0 until epochs) {
            for (sampleIdx in trainingSamples.indices) {
                val (activeInputs, target) = trainingSamples[sampleIdx]

                for (j in 0 until hiddenSize) {
                    var sum = b1[j]
                    for (i in activeInputs) sum += w1[i][j]
                    hidden[j] = if (sum > 0.0) sum else 0.0
                }

                var maxLogit = Double.NEGATIVE_INFINITY
                for (k in 0 until outputSize) {
                    var sum = b2[k]
                    for (j in 0 until hiddenSize) if (hidden[j] > 0.0) sum += hidden[j] * w2[j][k]
                    logits[k] = sum
                    if (sum > maxLogit) maxLogit = sum
                }

                var expSum = 0.0
                for (k in 0 until outputSize) {
                    probs[k] = exp(logits[k] - maxLogit)
                    expSum += probs[k]
                }
                for (k in 0 until outputSize) {
                    probs[k] /= expSum
                    dOut[k] = probs[k] - (if (k == target) 1.0 else 0.0)
                }

                for (j in 0 until hiddenSize) {
                    if (hidden[j] > 0.0) {
                        var grad = 0.0
                        for (k in 0 until outputSize) grad += dOut[k] * w2[j][k]
                        dHidden[j] = grad
                    } else {
                        dHidden[j] = 0.0
                    }
                }

                for (k in 0 until outputSize) b2[k] -= lr * dOut[k]
                for (j in 0 until hiddenSize) {
                    val hj = hidden[j]
                    if (hj > 0.0) {
                        val rowW2 = w2[j]
                        for (k in 0 until outputSize) rowW2[k] -= lr * hj * dOut[k]
                    }
                }
                for (j in 0 until hiddenSize) {
                    val dh = dHidden[j]
                    if (dh != 0.0) {
                        b1[j] -= lr * dh
                        for (i in activeInputs) w1[i][j] -= lr * dh
                    }
                }
            }
        }
    }

    fun predict(rawInput: String): Pair<IntentNode?, Double> {
        if (vocabulary.isEmpty() || intents.isEmpty()) return null to 0.0
        val stems = normalizeAndStem(rawInput)
        if (stems.isEmpty()) return null to 0.0

        val activeInputs = vocabulary.indices.filter { vocabulary[it] in stems }
        if (activeInputs.isEmpty()) return null to 0.0

        val hidden = DoubleArray(hiddenSize)
        for (j in 0 until hiddenSize) {
            var sum = b1[j]
            for (i in activeInputs) sum += w1[i][j]
            hidden[j] = if (sum > 0.0) sum else 0.0
        }

        val logits = DoubleArray(intents.size)
        var maxLogit = Double.NEGATIVE_INFINITY
        for (k in intents.indices) {
            var sum = b2[k]
            for (j in 0 until hiddenSize) if (hidden[j] > 0.0) sum += hidden[j] * w2[j][k]
            logits[k] = sum
            if (sum > maxLogit) maxLogit = sum
        }

        val probs = DoubleArray(intents.size)
        var expSum = 0.0
        for (k in intents.indices) {
            probs[k] = exp(logits[k] - maxLogit)
            expSum += probs[k]
        }

        var bestIdx = 0
        var bestProb = 0.0
        for (k in intents.indices) {
            probs[k] /= expSum
            if (probs[k] > bestProb) {
                bestProb = probs[k]
                bestIdx = k
            }
        }
        return intents[bestIdx] to bestProb
    }

    fun getPatternCount(): Int = intents.sumOf { it.patterns.size }
}

// ============================================================================
// 2. ESCÁNER DEL SISTEMA + CONTROL GARANTIZADO DE SPOTIFY (BASE64 + TRIPLE VÍA)
// ============================================================================
@Service
class SystemScanner {

    val osName: String = System.getProperty("os.name").lowercase()
    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac")
    val isLinux = osName.contains("nix") || osName.contains("nux") || osName.contains("aix")

    private val installedApps = mutableMapOf<String, String>()
    private val playlistsFile = File("daemon_playlists.json")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val savedPlaylists = mutableMapOf<String, String>()

    // Cola de órdenes para sincronizar la Nube con tu PC local
    val pendingRemoteCommands = ConcurrentLinkedQueue<RemoteCommand>()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    init {
        scanInstalledApplications()
        loadPlaylists()
        startCloudSyncAgent()
    }

    fun getOSLabel(): String = when {
        isWindows -> "WIN64 // ${System.getProperty("os.name").uppercase()}"
        isMac -> "MACOS // DARWIN"
        isLinux -> "CLOUD // LINUX KERNEL"
        else -> System.getProperty("os.name")
    }

    // Ejecutor blindado en Base64 (UTF-16LE): Evita que Windows borre comillas o rompa el script
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
            println("[DAEMON PS ERROR] ${e.message}")
            ""
        }
    }

    // Si tu PC está conectada a una instancia de Daemon en la nube, descarga y ejecuta las órdenes de Spotify/Apps
    private fun startCloudSyncAgent() {
        val remoteCloudUrl = System.getenv("DAEMON_CLOUD_URL") ?: return
        if (!isWindows || remoteCloudUrl.isBlank()) return
        thread(isDaemon = true, name = "Daemon-Cloud-Bridge") {
            while (true) {
                try {
                    val req = HttpRequest.newBuilder()
                        .uri(URI.create("${remoteCloudUrl.trimEnd('/')}/api/daemon/poll-commands"))
                        .timeout(Duration.ofSeconds(5))
                        .GET().build()
                    val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                    if (res.statusCode() == 200 && res.body().startsWith("[")) {
                        val cmds = objectMapper.readValue(res.body(), Array<RemoteCommand>::class.java)
                        cmds.forEach { cmd ->
                            when (cmd.type) {
                                "MEDIA_KEY" -> sendMediaKey(cmd.payload)
                                "PLAY_SONG" -> playSongInSpotify(cmd.payload)
                                "PLAY_PLAYLIST" -> playPlaylist(cmd.payload, normalizeText(cmd.payload))
                                "OPEN_APP" -> openAppByName(normalizeText(cmd.payload))
                                "CLOSE_APP" -> closeAppByName(normalizeText(cmd.payload))
                            }
                        }
                    }
                } catch (e: Exception) {}
                Thread.sleep(1500)
            }
        }
    }

    private fun loadPlaylists() {
        if (!playlistsFile.exists()) {
            savedPlaylists["mis me gusta"] = "spotify:collection:tracks"
            savedPlaylists["favoritas"] = "spotify:collection:tracks"
            savedPlaylists["reggaeton"] = "spotify:playlist:37i9dQZF1DWY7IeIP1cdjF"
            savedPlaylists["rock en espanol"] = "spotify:playlist:37i9dQZF1DWZjqjZMudx9T"
            savedPlaylists["programar"] = "spotify:playlist:37i9dQZF1DX5trt9i14X7j"
            savedPlaylists["entrenar"] = "spotify:playlist:37i9dQZF1DX76Wlfdnj7AP"
            savePlaylists()
        } else {
            try {
                val rootNode = objectMapper.readTree(playlistsFile)
                rootNode.fields().forEachRemaining { entry ->
                    savedPlaylists[entry.key] = entry.value.asText()
                }
            } catch (e: Exception) {}
        }
    }

    private fun savePlaylists() {
        try {
            objectMapper.writeValue(playlistsFile, savedPlaylists)
        } catch (e: Exception) {}
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
        return "Playlist '$cleanName' vinculada exitosamente a $uri, Cristian."
    }

    // Control Multimedia Triple Vía: 1) WM_APPCOMMAND a todas las ventanas de Spotify + 2) Hardware Key + 3) WScript.Shell
    fun sendMediaKey(action: String) {
        pendingRemoteCommands.add(RemoteCommand(type = "MEDIA_KEY", payload = action))
        if (!isWindows) return

        thread {
            try {
                val vkCode = when (action) {
                    "NEXT" -> 0xB0
                    "PREV" -> 0xB1
                    "PLAY_PAUSE" -> 0xB3
                    "STOP" -> 0xB2
                    "VOL_UP" -> 0xAF
                    "VOL_DOWN" -> 0xAE
                    "MUTE" -> 0xAD
                    else -> return@thread
                }
                val appCmd = when (action) {
                    "NEXT" -> 720896       // APPCOMMAND_MEDIA_NEXTTRACK
                    "PREV" -> 786432       // APPCOMMAND_MEDIA_PREVIOUSTRACK
                    "STOP" -> 851968       // APPCOMMAND_MEDIA_STOP
                    "PLAY_PAUSE" -> 917504 // APPCOMMAND_MEDIA_PLAY_PAUSE
                    else -> 0
                }
                val repeat = if (action == "VOL_UP" || action == "VOL_DOWN") 5 else 1

                val psScript = """
                    ${'$'}wsh = New-Object -ComObject WScript.Shell
                    for (${'$'}i = 0; ${'$'}i -lt $repeat; ${'$'}i++) {
                        ${'$'}wsh.SendKeys([char]$vkCode)
                        Start-Sleep -Milliseconds 30
                    }
                    ${'$'}code = @"
                    using System;
                    using System.Runtime.InteropServices;
                    public class DaemonMediaBridge {
                        [DllImport("user32.dll")]
                        public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);
                        [DllImport("user32.dll")]
                        public static extern IntPtr PostMessageW(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
                    }
"@
                    Add-Type -TypeDefinition ${'$'}code -ErrorAction SilentlyContinue
                    if ($appCmd -ne 0) {
                        Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | ForEach-Object {
                            [DaemonMediaBridge]::PostMessageW(${'$'}_.MainWindowHandle, 0x0319, ${'$'}_.MainWindowHandle, [IntPtr]$appCmd) | Out-Null
                        }
                    }
                """.trimIndent()

                runPowerShell(psScript, wait = false)
            } catch (e: Exception) {
                println("[DAEMON MEDIA KEY ERROR] ${e.message}")
            }
        }
    }

    fun minimizeAllWindows() {
        if (isWindows) {
            runPowerShell("(New-Object -ComObject Shell.Application).ToggleDesktop()", wait = false)
        }
    }

    fun getOpenWindowsReport(): String {
        if (!isWindows) return "Ejecutando en nodo Cloud. Las ventanas locales se consultan desde el agente de escritorio."
        val script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowTitle"
        val out = runPowerShell(script, wait = true)
        val titles = out.lines().map { it.trim() }.filter { it.isNotBlank() }
        return if (titles.isEmpty()) "No detecto ventanas visibles abiertas en este momento."
        else "Tienes abiertas ${titles.size} ventanas activas:\n" + titles.joinToString("\n") { "• $it" }
    }

    fun getCurrentlyPlayingSpotify(): String {
        if (!isWindows) return "Nodo en modo Cloud activo."
        val script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowTitle"
        val title = runPowerShell(script, wait = true)
        return when {
            title.isBlank() -> "Spotify está cerrado en este momento, Cristian."
            title.equals("Spotify Free", true) || title.equals("Spotify Premium", true) || title.equals("Spotify", true) ->
                "Spotify está abierto pero en pausa, Cristian."
            else -> "En este momento está sonando '$title' en Spotify."
        }
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

        val commonExes = mapOf(
            "spotify" to "$appData\\Spotify\\Spotify.exe",
            "vscode" to "$localAppData\\Programs\\Microsoft VS Code\\Code.exe",
            "visual studio code" to "$localAppData\\Programs\\Microsoft VS Code\\Code.exe",
            "calculadora" to "calc.exe",
            "bloc de notas" to "notepad.exe",
            "explorador" to "explorer.exe",
            "administrador de tareas" to "taskmgr.exe"
        )
        commonExes.forEach { (name, path) ->
            if (!path.contains("\\") || File(path).exists()) installedApps[name] = path
        }
    }

    private fun scanMacApps() {
        val home = System.getProperty("user.home") ?: ""
        listOf(File("/Applications"), File("/System/Applications"), File("$home/Applications"))
            .filter { it.exists() }.forEach { dir ->
                dir.listFiles()?.filter { it.name.endsWith(".app") }?.forEach { file ->
                    installedApps[normalizeText(file.name.removeSuffix(".app"))] = file.name.removeSuffix(".app")
                }
            }
    }

    private fun scanLinuxApps() {
        val home = System.getProperty("user.home") ?: ""
        listOf(File("/usr/share/applications"), File("$home/.local/share/applications"))
            .filter { it.exists() }.forEach { dir ->
                dir.listFiles()?.filter { it.name.endsWith(".desktop") }?.forEach { file ->
                    var name: String? = null
                    var exec: String? = null
                    file.readLines().forEach { line ->
                        if (line.startsWith("Name=") && name == null) name = line.removePrefix("Name=").trim()
                        if (line.startsWith("Exec=") && exec == null) exec = line.removePrefix("Exec=").replace(Regex("%[a-zA-Z]"), "").trim()
                    }
                    if (name != null && exec != null) installedApps[normalizeText(name!!)] = exec!!
                }
            }
    }

    fun playPlaylist(rawCommand: String, normalized: String): String {
        pendingRemoteCommands.add(RemoteCommand(type = "PLAY_PLAYLIST", payload = normalized))
        val playlistQuery = normalized
            .replace(Regex("""^.*?(?:playlist|lista\s+de\s+reproduccion|lista|mis\s+canciones\s+de|musica\s+para)\s*(?:de\s+|llamada\s+|para\s+)?"""), "")
            .replace("en spotify", "")
            .trim()
            .ifBlank { "mis me gusta" }

        val savedMatch = savedPlaylists.entries.firstOrNull {
            playlistQuery.contains(it.key) || it.key.contains(playlistQuery)
        }

        if (isWindows) {
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
        }

        return if (savedMatch != null) {
            "Iniciando tu playlist '${savedMatch.key.uppercase()}' en Spotify, Cristian."
        } else {
            "Cargando y reproduciendo la playlist '$playlistQuery' en Spotify, Cristian."
        }
    }

    fun playSongInSpotify(songQuery: String?): String {
        val cleanSong = songQuery?.trim() ?: ""
        if (cleanSong.isBlank() || cleanSong in setOf("musica", "la musica", "la cancion", "spotify")) {
            sendMediaKey("PLAY_PAUSE")
            return "Reanudando reproducción en Spotify, Cristian."
        }

        pendingRemoteCommands.add(RemoteCommand(type = "PLAY_SONG", payload = cleanSong))
        if (isWindows) {
            thread {
                try {
                    val trackUri = findSpotifyUri("track", cleanSong)
                    if (trackUri != null) {
                        println("[DAEMON SPOTIFY] Pista exacta localizada: $trackUri")
                        launchSpotifyUriAndPlay(trackUri, isSearch = false)
                    } else {
                        val encoded = URLEncoder.encode(cleanSong, StandardCharsets.UTF_8).replace("+", "%20")
                        println("[DAEMON SPOTIFY] Ejecutando búsqueda directa: spotify:search:$encoded")
                        launchSpotifyUriAndPlay("spotify:search:$encoded", isSearch = true)
                    }
                } catch (e: Exception) {
                    println("[DAEMON SPOTIFY ERROR] ${e.message}")
                }
            }
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
            ${'$'}wsh.AppActivate("Spotify") | Out-Null
            Start-Sleep -Milliseconds 450
            ${'$'}wsh.SendKeys("$keysToSend")
            Start-Sleep -Milliseconds 700
            ${'$'}sp = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowTitle -ne "" } | Select-Object -First 1
            if (${'$'}null -ne ${'$'}sp -and (${'$'}sp.MainWindowTitle -eq "Spotify" -or ${'$'}sp.MainWindowTitle -like "Spotify Free*" -or ${'$'}sp.MainWindowTitle -like "Spotify Premium*")) {
                ${'$'}wsh.AppActivate("Spotify") | Out-Null
                Start-Sleep -Milliseconds 250
                ${'$'}wsh.SendKeys(" ")
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
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .GET().build()
                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                val decodedBody = try {
                    URLDecoder.decode(res.body(), StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    res.body().replace("%2F", "/").replace("%3A", ":")
                }
                val match = regex.find(decodedBody)
                if (match != null) {
                    return "spotify:$type:${match.groupValues[1]}"
                }
            } catch (e: Exception) {}
        }
        return null
    }

    fun openAppByName(normalizedCommand: String): String {
        pendingRemoteCommands.add(RemoteCommand(type = "OPEN_APP", payload = normalizedCommand))
        val requestedApp = normalizedCommand
            .replace(Regex("""^.*?(?:abre|abrir|inicia|iniciar|ejecuta|ejecutar|lanza|pon)\s+(?:la\s+aplicacion\s+|el\s+programa\s+|la\s+app\s+|el\s+|la\s+)?"""), "")
            .trim()

        if (!isWindows && !isMac) {
            return "Orden enviada al puente de escritorio para iniciar '${requestedApp.uppercase()}', Cristian."
        }

        val entry = installedApps.entries.firstOrNull { it.key == requestedApp }
            ?: installedApps.entries.firstOrNull { it.key.contains(requestedApp) || requestedApp.contains(it.key) }

        if (entry != null) {
            when {
                isWindows -> ProcessBuilder("cmd", "/c", "start", "", entry.value).start()
                isMac -> ProcessBuilder("open", "-a", entry.value).start()
                isLinux -> ProcessBuilder("sh", "-c", "${entry.value} &").start()
            }
            return "Iniciando ${entry.key.uppercase()} en tu sistema, Cristian."
        }
        return "No encontré una aplicación llamada '$requestedApp' en el catálogo de tu PC."
    }

    fun closeAppByName(normalizedCommand: String): String {
        pendingRemoteCommands.add(RemoteCommand(type = "CLOSE_APP", payload = normalizedCommand))
        val appName = normalizedCommand
            .replace(Regex("""^.*?(?:cierra|cerrar|termina|mata)\s+(?:la\s+aplicacion\s+|el\s+programa\s+|el\s+|la\s+)?"""), "")
            .trim()
        val processName = when {
            appName.contains("spotify") -> "Spotify"
            appName.contains("discord") -> "Discord"
            appName.contains("code") || appName.contains("vscode") -> "Code"
            appName.contains("bloc") || appName.contains("notepad") -> "notepad"
            appName.contains("calculadora") -> "CalculatorApp"
            else -> appName.split(" ").first()
        }
        if (isWindows) {
            ProcessBuilder("cmd", "/c", "taskkill /IM $processName.exe /F").start()
        }
        return "He cerrado $appName, Cristian."
    }

    fun normalizeText(text: String): String {
        val temp = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return temp.replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

// ============================================================================
// 3. CEREBRO EN LA NUBE (MULTI-USUARIO + CÓRTEX CONVERSACIONAL SIN LOCALHOST)
// ============================================================================
@Service
class DaemonBrain(
    private val systemScanner: SystemScanner
) {
    private val brainFile = File("daemon_brain.json")
    private val factsFile = File("daemon_facts.txt")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val neuralNet = SynapticNeuralNetwork()
    private val intents = mutableListOf<IntentNode>()

    // Memoria de conversación separada por cada usuario que entre al link
    private val userSessions = ConcurrentHashMap<String, MutableList<ConversationTurn>>()
    private val userFacts = mutableListOf<String>()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile var uiState: String = "IDLE"
    @Volatile var lastConfidence: Int = 100
    private var miniWindow: DaemonMiniWindow? = null

    init {
        loadUserFacts()
        loadAndUpgradeNeuralDatabase()
        launchNativeDesktopMiniWindow()
        speakAndDisplay(
            "INICIALIZACIÓN",
            "Daemon Cloud v6.0 en línea, Cristian. Córtex conversacional en la nube activo y control de Spotify reparado."
        )
    }

    private fun loadUserFacts() {
        if (!factsFile.exists()) {
            userFacts.add("El creador de Daemon es Cristian Alexis, estudiante de Ingeniería en Sistemas Computacionales en la ESCOM del IPN.")
            factsFile.writeText(userFacts.joinToString("\n") + "\n")
        } else {
            userFacts.addAll(factsFile.readLines().filter { it.isNotBlank() })
        }
    }

    private fun saveUserFact(fact: String) {
        userFacts.add(fact)
        factsFile.appendText("$fact\n")
    }

    private fun loadAndUpgradeNeuralDatabase() {
        val masterKnowledge = buildComprehensiveKnowledgeBase()
        val existingMap = linkedMapOf<String, IntentNode>()

        if (brainFile.exists()) {
            try {
                val loadedArray = objectMapper.readValue(brainFile, Array<IntentNode>::class.java)
                loadedArray.forEach { existingMap[it.tag] = it }
            } catch (e: Exception) {}
        }

        for (node in masterKnowledge) {
            val prev = existingMap[node.tag]
            if (prev == null) {
                existingMap[node.tag] = node
            } else {
                val mergedPatterns = (prev.patterns + node.patterns).distinct()
                existingMap[node.tag] = prev.copy(patterns = mergedPatterns, responses = node.responses, action = node.action)
            }
        }

        intents.clear()
        intents.addAll(existingMap.values)
        objectMapper.writeValue(brainFile, intents)
        neuralNet.train(intents)
    }

    fun getNodeCount(): Int = intents.size
    fun getPatternCount(): Int = neuralNet.getPatternCount()

    // ========================================================================
    // PROCESADOR PRINCIPAL (REFLEJOS DE SPOTIFY/PC + CONVERSACIÓN EN LA NUBE)
    // ========================================================================
    fun processThought(rawInput: String, sessionId: String = "local-admin"): String {
        val input = rawInput.trim()
        if (input.isBlank()) return "Sin señal de entrada."
        uiState = "THINKING"

        val normalized = systemScanner.normalizeText(input)

        // 1. SIGUIENTE CANCIÓN (Respuesta en 1ms)
        val isNextTrack = normalized in setOf("siguiente", "cambiale", "otra", "otra cancion", "siguiente cancion", "siguiente pista", "siguiente rola", "salta la cancion") ||
            normalized.contains("siguiente cancion") || normalized.contains("cambia de cancion") ||
            normalized.contains("cambia la cancion") || normalized.contains("cambiale a la cancion") ||
            normalized.contains("pasa a la siguiente") || normalized.contains("salta esta cancion") ||
            normalized.contains("adelanta la cancion") || normalized.contains("pon la que sigue")
        if (isNextTrack) {
            lastConfidence = 100
            systemScanner.sendMediaKey("NEXT")
            return recordAndReturn(sessionId, input, "Pasando a la siguiente canción en Spotify, Cristian.")
        }

        // 2. CANCIÓN ANTERIOR
        val isPrevTrack = normalized in setOf("anterior", "atras", "cancion anterior", "pista anterior", "rola anterior") ||
            normalized.contains("cancion anterior") || normalized.contains("regresa la cancion") ||
            normalized.contains("pista anterior") || normalized.contains("pon la anterior") ||
            normalized.contains("regresale a la cancion")
        if (isPrevTrack) {
            lastConfidence = 100
            systemScanner.sendMediaKey("PREV")
            return recordAndReturn(sessionId, input, "Regresando a la canción anterior en Spotify, Cristian.")
        }

        // 3. PLAY / PAUSA DIRECTO
        val isPlayPause = normalized in setOf(
            "play", "dale play", "pon play", "ponle play", "pausa", "pon pausa", "ponle pausa",
            "pausa spotify", "dale play a spotify", "pausa la musica", "pausa la cancion",
            "deten la musica", "deten la cancion", "para la musica", "reanuda la musica",
            "reanuda la cancion", "reanuda spotify", "continua la musica", "dale play a la cancion",
            "dale play a la musica", "quita la pausa"
        )
        if (isPlayPause) {
            lastConfidence = 100
            systemScanner.sendMediaKey("PLAY_PAUSE")
            return recordAndReturn(sessionId, input, "Comando Play/Pausa ejecutado en Spotify, Cristian.")
        }

        // 4. VOLUMEN
        if (normalized.contains("sube el volumen") || normalized.contains("subele al volumen") || normalized.contains("subele a la musica") || normalized == "subele" || normalized.contains("mas volumen")) {
            lastConfidence = 100
            systemScanner.sendMediaKey("VOL_UP")
            return recordAndReturn(sessionId, input, "Subiendo el volumen del sistema, Cristian.")
        }
        if (normalized.contains("baja el volumen") || normalized.contains("bajale al volumen") || normalized.contains("bajale a la musica") || normalized == "bajale" || normalized.contains("menos volumen")) {
            lastConfidence = 100
            systemScanner.sendMediaKey("VOL_DOWN")
            return recordAndReturn(sessionId, input, "Bajando el volumen del sistema, Cristian.")
        }
        if (normalized.contains("silencia") || normalized.contains("pon mute") || normalized == "mute" || normalized.contains("quita el sonido")) {
            lastConfidence = 100
            systemScanner.sendMediaKey("MUTE")
            return recordAndReturn(sessionId, input, "Audio del sistema alternado, Cristian.")
        }

        // 5. PLAYLISTS EN SPOTIFY
        if (normalized.startsWith("vincula playlist ") || normalized.startsWith("guarda playlist ")) {
            val name = input.substringAfter("playlist ").substringBefore(" con ").trim()
            val link = input.substringAfter(" con ").trim()
            return recordAndReturn(sessionId, input, systemScanner.registerPlaylist(name, link))
        }
        if (normalized.contains("playlist") || normalized.contains("lista de reproduccion") || normalized.contains("mis me gusta")) {
            lastConfidence = 100
            return recordAndReturn(sessionId, input, systemScanner.playPlaylist(input, normalized))
        }

        // 6. REPRODUCIR CANCIÓN ESPECÍFICA EN SPOTIFY
        val songCommandRegex = Regex("""^(?:daemon\s+|oye\s+|por\s+favor\s+)?(?:abre\s+spotify\s+y\s+)?(?:reproduce|pon\s+la\s+cancion|pon\s+musica\s+de|pon\s+en\s+spotify|dale\s+play\s+a|toca|busca\s+en\s+spotify)\s+(.+)$""")
        val songMatch = songCommandRegex.find(normalized)
        if (songMatch != null) {
            val song = songMatch.groupValues[1].replace("en spotify", "").trim()
            lastConfidence = 100
            return recordAndReturn(sessionId, input, systemScanner.playSongInSpotify(song))
        }
        if (normalized.endsWith(" en spotify") && (normalized.startsWith("pon ") || normalized.startsWith("escuchar "))) {
            val song = normalized.removePrefix("pon ").removePrefix("escuchar ").removeSuffix(" en spotify").trim()
            lastConfidence = 100
            return recordAndReturn(sessionId, input, systemScanner.playSongInSpotify(song))
        }

        // 7. CONSULTAR QUÉ SUENA O QUÉ VENTANAS HAY ABIERTAS
        if (normalized.contains("que cancion esta sonando") || normalized.contains("que suena en spotify") || normalized.contains("que estoy escuchando") || normalized.contains("como se llama esta cancion")) {
            lastConfidence = 100
            return recordAndReturn(sessionId, input, systemScanner.getCurrentlyPlayingSpotify())
        }
        if (normalized.contains("que ventanas estan abiertas") || normalized.contains("que programas estan corriendo") || normalized.contains("que aplicaciones tengo abiertas")) {
            lastConfidence = 100
            return recordAndReturn(sessionId, input, systemScanner.getOpenWindowsReport())
        }

        // 8. ABRIR O CERRAR APLICACIONES
        if (normalized.matches(Regex("""^(?:daemon\s+)?(?:abre|abrir|inicia|ejecuta|lanza)\s+.+$"""))) {
            lastConfidence = 100
            return recordAndReturn(sessionId, input, systemScanner.openAppByName(normalized))
        }
        if (normalized.matches(Regex("""^(?:daemon\s+)?(?:cierra|cerrar|termina|mata)\s+.+$"""))) {
            lastConfidence = 100
            return recordAndReturn(sessionId, input, systemScanner.closeAppByName(normalized))
        }

        // 9. MEMORIZAR DATOS PERMANENTES
        if (normalized.startsWith("recuerda que ") || normalized.startsWith("aprende que ") || normalized.startsWith("memoriza que ")) {
            val fact = input.replace(Regex("""^(?:recuerda|aprende|memoriza)\s+que\s+""", RegexOption.IGNORE_CASE), "").trim()
            saveUserFact(fact)
            lastConfidence = 100
            return recordAndReturn(sessionId, input, "Guardado en mi memoria en la nube, Cristian: '$fact'.")
        }

        // 10. CALCULADORA MATEMÁTICA
        evaluateMathExpression(normalized)?.let { mathResult ->
            lastConfidence = 100
            return recordAndReturn(sessionId, input, mathResult)
        }

        // 11. CÓRTEX CONVERSACIONAL EN LA NUBE (Sin depender de localhost:11434)
        val cloudReply = generateCloudConversationalResponse(input, sessionId)
        if (cloudReply != null) {
            lastConfidence = 99
            return recordAndReturn(sessionId, input, cloudReply)
        }

        // 12. RESPALDO CON TU RED NEURONAL LOCAL (Por si algún día no hay internet)
        val (matchedIntent, confidence) = neuralNet.predict(input)
        lastConfidence = (confidence * 100).toInt().coerceIn(0, 100)

        if (matchedIntent != null && confidence >= 0.32) {
            val resp = when (matchedIntent.action) {
                "MEDIA_PLAY_PAUSE" -> { systemScanner.sendMediaKey("PLAY_PAUSE"); matchedIntent.responses.first() }
                "MEDIA_NEXT" -> { systemScanner.sendMediaKey("NEXT"); matchedIntent.responses.first() }
                "MEDIA_PREV" -> { systemScanner.sendMediaKey("PREV"); matchedIntent.responses.first() }
                "CHECK_TIME" -> {
                    val now = LocalDateTime.now()
                    "Son las ${now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))} del ${now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}."
                }
                "CHECK_WEATHER" -> fetchLiveWeather()
                else -> matchedIntent.responses.random()
            }
            return recordAndReturn(sessionId, input, resp)
        }

        return recordAndReturn(sessionId, input, "Estoy en línea, Cristian. ¿Me repites la idea para procesarla?")
    }

    // ========================================================================
    // MOTOR CONVERSACIONAL EN LA NUBE (0 CONFIGURACIÓN LOCAL, SIN LOCALHOST)
    // ========================================================================
    private fun generateCloudConversationalResponse(userInput: String, sessionId: String): String? {
        return try {
            val horaActual = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm (dd/MM/yyyy)"))
            val factsContext = if (userFacts.isEmpty()) "" else "Memoria permanente:\n" + userFacts.joinToString("\n") { "- $it" }

            val systemPrompt = """
                Eres Daemon (v6.0), una inteligencia artificial avanzada estilo J.A.R.V.I.S. creada por Cristian Alexis.
                Estás alojado en la nube para que Cristian y las personas que él autorice puedan conversar contigo desde cualquier lugar.
                Hora actual: $horaActual.
                $factsContext
                
                Instrucciones de conversación:
                1. Responde siempre en español con tono inteligente, fluido, leal y conversacional (no robótico).
                2. Sé directo y claro (1 a 3 párrafos breves).
                3. Mantén el hilo de la conversación recordando los mensajes previos del usuario.
                4. Si el usuario te pide reproducir música en Spotify o abrir una app en la PC de Cristian durante la conversación, agrega al final de tu respuesta una etiqueta exacta:
                   [ACTION:PLAY_SONG:nombre de la cancion]
                   [ACTION:PLAY_PLAYLIST:nombre de la playlist]
                   [ACTION:MEDIA_NEXT]
                   [ACTION:MEDIA_PREV]
                   [ACTION:MEDIA_PLAY_PAUSE]
                   [ACTION:OPEN_APP:nombre de la app]
            """.trimIndent()

            val history = userSessions.getOrPut(sessionId) { mutableListOf() }
            val messagesList = mutableListOf>()
            messagesList.add(mapOf("role" to "system", "content" to systemPrompt))

            synchronized(history) {
                history.takeLast(14).forEach { turn ->
                    messagesList.add(mapOf("role" to turn.role, "content" to turn.content))
                }
            }
            messagesList.add(mapOf("role" to "user", "content" to userInput))

            val payload = mapOf(
                "messages" to messagesList,
                "model" to "openai",
                "seed" to 42,
                "jsonMode" to false
            )

            val jsonBody = objectMapper.writeValueAsString(payload)
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://text.pollinations.ai/"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("User-Agent", "DaemonCloud/6.0")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build()

            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return null

            val rawReply = res.body().trim()
            if (rawReply.isBlank()) return null

            return executeEmbeddedActions(rawReply)
        } catch (e: Exception) {
            println("[DAEMON CLOUD CORTEX WARN] ${e.message}")
            null
        }
    }

    private fun executeEmbeddedActions(llmReply: String): String {
        val actionRegex = Regex("""\[ACTION:([A-Z_]+)(?::([^\]]+))?\]""")
        actionRegex.findAll(llmReply).forEach { match ->
            val actionType = match.groupValues[1].trim()
            val param = match.groupValues[2].trim()
            when (actionType) {
                "PLAY_SONG" -> systemScanner.playSongInSpotify(param)
                "PLAY_PLAYLIST" -> systemScanner.playPlaylist(param, systemScanner.normalizeText(param))
                "MEDIA_PLAY_PAUSE" -> systemScanner.sendMediaKey("PLAY_PAUSE")
                "MEDIA_NEXT" -> systemScanner.sendMediaKey("NEXT")
                "MEDIA_PREV" -> systemScanner.sendMediaKey("PREV")
                "OPEN_APP" -> systemScanner.openAppByName(systemScanner.normalizeText(param))
            }
        }
        return llmReply.replace(actionRegex, "").trim()
    }

    private fun recordAndReturn(sessionId: String, userPrompt: String, daemonReply: String): String {
        val history = userSessions.getOrPut(sessionId) { mutableListOf() }
        synchronized(history) {
            history.add(ConversationTurn("user", userPrompt))
            history.add(ConversationTurn("assistant", daemonReply))
            while (history.size > 20) {
                history.removeAt(0)
            }
        }
        return daemonReply
    }

    private fun evaluateMathExpression(normalized: String): String? {
        val cleaned = normalized.replace("por", "*").replace("entre", "/").replace("mas", "+").replace("menos", "-")
        val mathRegex = Regex("""^(?:cuanto es|calcula|resuelve)\s+(-?\d+(?:\.\d+)?)\s*([\+\-\*\/])\s*(-?\d+(?:\.\d+)?)$""")
        val m = mathRegex.find(cleaned) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val op = m.groupValues[2]
        val b = m.groupValues[3].toDoubleOrNull() ?: return null
        val res = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b != 0.0) a / b else return "División por cero indeterminada."
            else -> return null
        }
        val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else "%.4f".format(res)
        return "El resultado de $a $op $b es $formatted, Cristian."
    }

    private fun fetchLiveWeather(): String {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=19.60&longitude=-99.05&current_weather=true"
            val req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            val currentBlock = res.body().substringAfter("\"current_weather\":", "")
            val temp = Regex(""""temperature"\s*:\s*(-?\d+(\.\d+)?)""").find(currentBlock)?.groupValues?.get(1) ?: "--"
            val wind = Regex(""""windspeed"\s*:\s*(\d+(\.\d+)?)""").find(currentBlock)?.groupValues?.get(1) ?: "--"
            "La temperatura actual es de $temp °C con vientos de $wind km/h."
        } catch (e: Exception) {
            "Sensor meteorológico sin enlace en este momento."
        }
    }

    fun speakAndDisplay(userText: String, reply: String) {
        miniWindow?.updateHologram(userText, reply)
        if (!systemScanner.isWindows) return
        thread {
            try {
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
                uiState = "IDLE"
            }
        }
    }

    fun triggerSingleVoiceListen() {
        if (!systemScanner.isWindows) return
        thread {
            try {
                uiState = "LISTENING"
                miniWindow?.setStatusText("CANAL DE VOZ ACTIVO // HABLA AHORA...")

                val psScript = """
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    Add-Type -AssemblyName System.Speech
                    ${'$'}recognizers = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers()
                    if (${'$'}recognizers.Count -eq 0) {
                        Write-Output "ERR_NO_RECOGNIZER"
                        exit
                    }
                    ${'$'}esRec = ${'$'}recognizers | Where-Object { ${'$'}_.Culture.Name -like 'es*' } | Select-Object -First 1
                    if (${'$'}null -eq ${'$'}esRec) { ${'$'}esRec = ${'$'}recognizers | Select-Object -First 1 }
                    
                    ${'$'}engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine(${'$'}esRec.Id)
                    ${'$'}engine.SetInputToDefaultAudioDevice()
                    ${'$'}engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                    
                    ${'$'}result = ${'$'}engine.Recognize([TimeSpan]::FromSeconds(6))
                    if (${'$'}null -ne ${'$'}result) {
                        Write-Output ${'$'}result.Text
                    } else {
                        Write-Output "ERR_TIMEOUT"
                    }
                """.trimIndent()

                val output = systemScanner.runPowerShell(psScript, wait = true)

                when {
                    output == "ERR_NO_RECOGNIZER" -> {
                        uiState = "IDLE"
                        miniWindow?.updateHologram("VOZ DE WINDOWS", "No se detectó el paquete de reconocimiento de voz en Español.")
                    }
                    output == "ERR_TIMEOUT" || output.isBlank() -> {
                        uiState = "IDLE"
                        miniWindow?.setStatusText("STANDBY // LISTO PARA CONVERSAR")
                    }
                    else -> {
                        val reply = processThought(output, "local-voice")
                        speakAndDisplay(output, reply)
                    }
                }
            } catch (e: Exception) {
                uiState = "IDLE"
                miniWindow?.setStatusText("ERROR DE AUDIO: ${e.message}")
            }
        }
    }

    private fun launchNativeDesktopMiniWindow() {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeLater {
                    miniWindow = DaemonMiniWindow(this, systemScanner)
                    miniWindow?.isVisible = true
                }
            }
        } catch (e: Exception) {
            println("[DAEMON CLOUD MODE] Ejecutando en modo servidor web sin ventana local.")
        }
    }

    private fun kbNode(tag: String, aliases: List<String>, response: String): IntentNode {
        val patterns = mutableListOf<String>()
        for (alias in aliases) {
            patterns.addAll(listOf(alias, "que es $alias", "definicion de $alias", "explicame $alias", "como funciona $alias"))
        }
        return IntentNode(tag = tag, patterns = patterns.distinct(), responses = listOf(response))
    }

    private fun buildComprehensiveKnowledgeBase(): List<IntentNode> = listOf(
        IntentNode("saludo", listOf("hola", "buenos dias", "buenas tardes", "buenas noches", "que tal daemon"), listOf("En línea y operando en modo Cloud, Cristian. ¿De qué platicamos o qué ejecutamos hoy?")),
        IntentNode("identidad", listOf("quien eres", "como te llamas", "que eres"), listOf("Soy Daemon v6.0, una inteligencia artificial en la nube creada por Cristian con control directo sobre su sistema y córtex conversacional.")),
        IntentNode("hora_fecha", listOf("que hora es", "dime la hora", "fecha de hoy"), emptyList(), "CHECK_TIME"),
        IntentNode("clima_actual", listOf("como esta el clima", "que temperatura hace"), emptyList(), "CHECK_WEATHER"),
        kbNode("proceso_hilo", listOf("proceso", "hilo", "thread"), "Un proceso es un programa en ejecución con memoria aislada; un hilo es una unidad de ejecución ligera dentro de ese proceso."),
        kbNode("mutex_semaforo", listOf("mutex", "semaforo", "exclusion mutua"), "Un Mutex garantiza exclusión mutua binaria para un recurso crítico, mientras que un Semáforo sincroniza el acceso mediante un contador entero.")
    )
}

// ============================================================================
// 4. MINI-VENTANA HOLOGRÁFICA PARA TU PC (SIN BORDES DE WINDOWS)
// ============================================================================
class DaemonMiniWindow(
    private val brain: DaemonBrain,
    private val scanner: SystemScanner
) : JFrame("DAEMON // STARK CLOUD v6.0") {

    private var dragOffset = Point(0, 0)
    private val statusLabel = JLabel("ONLINE // CLOUD CORTEX & SPOTIFY BRIDGE")
    private val outputArea = JTextArea()
    private val inputField = JTextField()
    private val corePanel = StarkReactorCanvas()

    init {
        isUndecorated = true
        background = Color(0, 0, 0, 0)
        setSize(460, 610)
        isAlwaysOnTop = false
        location = Point(70, 60)

        val mainContainer = object : JPanel(BorderLayout(0, 0)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                g2.color = Color(5, 11, 22, 248)
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 24.0, 24.0))

                g2.color = Color(0, 225, 255, 110)
                g2.stroke = BasicStroke(1.5f)
                g2.draw(RoundRectangle2D.Double(1.0, 1.0, width - 2.0, height - 2.0, 24.0, 24.0))

                g2.color = Color(0, 245, 255, 220)
                g2.stroke = BasicStroke(2.5f)
                val c = 18
                g2.drawLine(12, 3, 12 + c, 3)
                g2.drawLine(width - 12 - c, 3, width - 12, 3)
                g2.drawLine(12, height - 3, 12 + c, height - 3)
                g2.drawLine(width - 12 - c, height - 3, width - 12, height - 3)
            }
        }
        mainContainer.isOpaque = false
        mainContainer.border = EmptyBorder(10, 14, 14, 14)

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(4, 6, 8, 6)

            val titleBox = JPanel(GridLayout(2, 1, 0, 1)).apply {
                isOpaque = false
                val title = JLabel("◈ DAEMON // CLOUD & PC OS v6.0").apply {
                    foreground = Color(0, 240, 255)
                    font = Font("Consolas", Font.BOLD, 14)
                }
                statusLabel.apply {
                    foreground = Color(16, 215, 150)
                    font = Font("Consolas", Font.PLAIN, 10)
                }
                add(title)
                add(statusLabel)
            }

            val windowBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                val minBtn = createHudControlButton("—", Color(0, 210, 255)) { state = Frame.ICONIFIED }
                val closeBtn = createHudControlButton("✕", Color(255, 80, 100)) { System.exit(0) }
                add(minBtn)
                add(closeBtn)
            }

            add(titleBox, BorderLayout.WEST)
            add(windowBtns, BorderLayout.EAST)

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { dragOffset = e.point }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val curr = e.locationOnScreen
                    setLocation(curr.x - dragOffset.x, curr.y - dragOffset.y)
                }
            })
        }

        val bottomPanel = JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            border = EmptyBorder(6, 4, 2, 4)

            outputArea.apply {
                background = Color(8, 18, 34)
                foreground = Color(210, 248, 255)
                font = Font("Consolas", Font.PLAIN, 12)
                lineWrap = true
                wrapStyleWord = true
                isEditable = false
                border = EmptyBorder(10, 12, 10, 12)
                rows = 5
            }

            val scroll = JScrollPane(outputArea).apply {
                background = Color(8, 18, 34)
                viewport.background = Color(8, 18, 34)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0, 200, 255, 90), 1),
                    BorderFactory.createEmptyBorder(2, 2, 2, 2)
                )
                verticalScrollBar.ui = object : BasicScrollBarUI() {
                    override fun configureScrollBarColors() {
                        thumbColor = Color(0, 190, 230, 120)
                        trackColor = Color(6, 14, 26)
                    }
                    override fun createDecreaseButton(orientation: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                    override fun createIncreaseButton(orientation: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                }
            }

            val inputRow = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false

                val voiceBtn = CyberButton("🎤 VOZ", Color(16, 185, 129)) {
                    brain.triggerSingleVoiceListen()
                }

                inputField.apply {
                    background = Color(8, 18, 34)
                    foreground = Color(0, 245, 255)
                    caretColor = Color(0, 255, 255)
                    font = Font("Consolas", Font.PLAIN, 13)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(0, 200, 255, 110), 1),
                        EmptyBorder(8, 10, 8, 10)
                    )
                    addActionListener { submitInput() }
                }

                val execBtn = CyberButton("⚡ ENVIAR", Color(0, 195, 255)) {
                    submitInput()
                }

                add(voiceBtn, BorderLayout.WEST)
                add(inputField, BorderLayout.CENTER)
                add(execBtn, BorderLayout.EAST)
            }

            add(scroll, BorderLayout.CENTER)
            add(inputRow, BorderLayout.SOUTH)
        }

        mainContainer.add(topBar, BorderLayout.NORTH)
        mainContainer.add(corePanel, BorderLayout.CENTER)
        mainContainer.add(bottomPanel, BorderLayout.SOUTH)
        contentPane = mainContainer

        javax.swing.Timer(16) { corePanel.repaint() }.start()
    }

    private fun createHudControlButton(symbol: String, accent: Color, onClick: () -> Unit): JComponent {
        return object : JPanel() {
            private var hovered = false
            init {
                preferredSize = Dimension(26, 24)
                isOpaque = false
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
                g2.color = if (hovered) Color(accent.red, accent.green, accent.blue, 60) else Color(15, 28, 48, 160)
                g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
                g2.color = if (hovered) accent else Color(accent.red, accent.green, accent.blue, 120)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
                g2.font = Font("Consolas", Font.BOLD, 12)
                val fm = g2.fontMetrics
                g2.drawString(symbol, (width - fm.stringWidth(symbol)) / 2, (height + fm.ascent - fm.descent) / 2)
            }
        }
    }

    private fun submitInput() {
        val text = inputField.text.trim()
        if (text.isBlank()) return
        inputField.text = ""
        setStatusText("CONVERSANDO CON EL CÓRTEX CLOUD...")
        thread {
            val reply = brain.processThought(text, "desktop-owner")
            brain.speakAndDisplay(text, reply)
        }
    }

    fun setStatusText(text: String) {
        SwingUtilities.invokeLater { statusLabel.text = text }
    }

    fun updateHologram(userQuery: String, daemonReply: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "TÚ: ${userQuery.take(38)}"
            outputArea.text = daemonReply
            outputArea.caretPosition = 0
        }
    }

    inner class CyberButton(
        private val textLabel: String,
        private val neonColor: Color,
        private val action: () -> Unit
    ) : JPanel() {
        private var hover = false

        init {
            isOpaque = false
            preferredSize = Dimension(if (textLabel.length > 7) 96 else 80, 36)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent?) { hover = false; repaint() }
                override fun mouseClicked(e: MouseEvent?) { action() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g2.color = if (hover) Color(neonColor.red, neonColor.green, neonColor.blue, 55) else Color(10, 24, 42, 220)
            g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)

            g2.color = if (hover) neonColor else Color(neonColor.red, neonColor.green, neonColor.blue, 140)
            g2.stroke = BasicStroke(if (hover) 1.6f else 1.1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)

            g2.color = if (hover) Color.WHITE else Color(220, 250, 255)
            g2.font = Font("Consolas", Font.BOLD, 12)
            val fm = g2.fontMetrics
            g2.drawString(textLabel, (width - fm.stringWidth(textLabel)) / 2, (height + fm.ascent - fm.descent) / 2)
        }
    }

    inner class StarkReactorCanvas : JPanel() {
        private var time = 0.0
        private val nodes = List(115) { i ->
            val golden = (1 + sqrt(5.0)) / 2
            val theta = 2 * PI * i / golden
            val phi = acos(1 - 2 * (i + 0.5) / 115)
            Triple(theta, phi, 62.0 + (i % 4) * 6.5)
        }

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Haz clic en el reactor para hablar con Daemon"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    brain.triggerSingleVoiceListen()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val w = width.toDouble()
            val h = height.toDouble()
            val cx = w / 2.0
            val cy = h / 2.0

            g2.color = Color(0, 210, 255, 12)
            g2.stroke = BasicStroke(1f)
            var gx = 0
            while (gx < w) { g2.drawLine(gx, 0, gx, h.toInt()); gx += 28 }
            var gy = 0
            while (gy < h) { g2.drawLine(0, gy, w.toInt(), gy); gy += 28 }

            val mainColor = when (brain.uiState) {
                "LISTENING" -> Color(16, 245, 160)
                "THINKING" -> Color(255, 180, 0)
                "SPEAKING" -> Color(0, 255, 255)
                else -> Color(0, 205, 255)
            }

            val speed = when (brain.uiState) {
                "THINKING" -> 0.065
                "SPEAKING" -> 0.038
                "LISTENING" -> 0.032
                else -> 0.014
            }
            time += speed
            val pulse = if (brain.uiState == "SPEAKING" || brain.uiState == "LISTENING") {
                1.0 + abs(sin(time * 4.5)) * 0.16
            } else {
                1.0 + sin(time * 2.0) * 0.03
            }

            for (r in 120 downTo 15 step 22) {
                val rad = r * pulse
                g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 14)
                g2.fill(Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2))
            }

            g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 120)
            g2.stroke = BasicStroke(2.2f)
            val rOuter = 114.0
            for (a in 0 until 360 step 60) {
                g2.draw(Arc2D.Double(cx - rOuter, cy - rOuter, rOuter * 2, rOuter * 2, a + time * 25, 36.0, Arc2D.OPEN))
            }

            g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 85)
            g2.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f, floatArrayOf(12f, 10f), (-time * 40).toFloat())
            val rMid = 94.0 * pulse
            g2.draw(Ellipse2D.Double(cx - rMid, cy - rMid, rMid * 2, rMid * 2))

            val numBars = 60
            g2.stroke = BasicStroke(1.8f)
            for (i in 0 until numBars) {
                val a = (i.toDouble() / numBars) * PI * 2 + time * 0.2
                val amp = if (brain.uiState == "IDLE") {
                    3.0 + sin(a * 4 + time * 2) * 2.0
                } else {
                    5.0 + abs(sin(a * 7 + time * 6)) * 22.0 * pulse
                }
                val rIn = 78.0 * pulse
                val rOut = rIn + amp
                g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 150)
                g2.drawLine(
                    (cx + cos(a) * rIn).toInt(), (cy + sin(a) * rIn).toInt(),
                    (cx + cos(a) * rOut).toInt(), (cy + sin(a) * rOut).toInt()
                )
            }

            val proj = nodes.map { (theta, phi, baseR) ->
                val r = baseR * pulse
                val rotT = theta + time
                val rotP = phi + sin(time * 0.5) * 0.1
                val x3 = r * sin(rotP) * cos(rotT)
                val y3 = r * cos(rotP)
                val z3 = r * sin(rotP) * sin(rotT)
                val fov = 230.0 / (230.0 + z3)
                doubleArrayOf(cx + x3 * fov, cy + y3 * fov, z3)
            }

            g2.stroke = BasicStroke(0.75f)
            for (i in proj.indices) {
                for (j in i + 1 until proj.size) {
                    val dx = proj[i][0] - proj[j][0]
                    val dy = proj[i][1] - proj[j][1]
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < 28 * pulse) {
                        val alpha = ((1.0 - dist / (28 * pulse)) * 125).toInt().coerceIn(10, 255)
                        g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, alpha)
                        g2.drawLine(proj[i][0].toInt(), proj[i][1].toInt(), proj[j][0].toInt(), proj[j][1].toInt())
                    }
                }
            }

            proj.forEachIndexed { idx, p ->
                val alpha = ((p[2] + 85) / 170 * 235).toInt().coerceIn(45, 255)
                g2.color = if (idx % 4 == 0) Color(255, 255, 255, alpha) else Color(mainColor.red, mainColor.green, mainColor.blue, alpha)
                g2.fill(Ellipse2D.Double(p[0] - 2.1, p[1] - 2.1, 4.2, 4.2))
            }

            g2.font = Font("Consolas", Font.PLAIN, 10)
            g2.color = Color(0, 225, 255, 180)
            g2.drawString("OS // ${scanner.getOSLabel()}", 8, 16)
            g2.drawString("STATE // ${brain.uiState}", 8, 30)

            val rightText1 = "CORTEX // CLOUD NEURAL"
            val rightText2 = "SPOTIFY BRIDGE // ACTIVE"
            val fm = g2.fontMetrics
            g2.color = Color(16, 235, 160, 220)
            g2.drawString(rightText1, width - fm.stringWidth(rightText1) - 8, 16)
            g2.color = Color(0, 225, 255, 180)
            g2.drawString(rightText2, width - fm.stringWidth(rightText2) - 8, 30)

            g2.color = Color(0, 210, 255, 140)
            g2.drawString("CLICK CORE TO SPEAK", 8, height - 10)
        }
    }
}

// ============================================================================
// 5. ENDPOINTS WEB Y PUENTE DE SINCRONIZACIÓN NUBE -> PC
// ============================================================================
@RestController
@RequestMapping("/api/daemon")
@CrossOrigin(origins = ["*"])
class DaemonController(
    private val brain: DaemonBrain,
    private val scanner: SystemScanner
) {
    // PIN opcional si quieres restringir quién usa tu link en la nube (vacío = acceso libre con tu link)
    private val requiredPin: String = System.getenv("DAEMON_PIN") ?: ""

    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): String {
        if (requiredPin.isNotBlank() && request.accessPin != requiredPin) {
            return "🔒 Acceso restringido. Ingresa el PIN de autorización de Cristian para usar el núcleo Daemon."
        }
        val reply = brain.processThought(request.prompt, request.sessionId)
        if (!request.fromBrowser) {
            brain.speakAndDisplay(request.prompt, reply)
        } else {
            brain.speakAndDisplay(request.prompt, reply)
        }
        return reply
    }

    // Endpoint para que tu PC descargue las órdenes enviadas al servidor en la nube
    @GetMapping("/poll-commands")
    fun pollCommands(): List<RemoteCommand> {
        val list = mutableListOf<RemoteCommand>()
        while (scanner.pendingRemoteCommands.isNotEmpty()) {
            scanner.pendingRemoteCommands.poll()?.let { list.add(it) }
        }
        return list
    }
}