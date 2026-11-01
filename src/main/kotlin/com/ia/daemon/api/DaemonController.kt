package com.ia.daemon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Duration
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

data class ChatRequest(
    val prompt: String = "",
    val audio: String? = null,
    val fromBrowser: Boolean = true
)

// Estructura de cada Nodo de la Red Neuronal en daemon_brain.json
data class IntentNode(
    val tag: String = "",
    val patterns: List<String> = emptyList(),
    val responses: List<String> = emptyList(),
    val action: String = "NONE"
)

// ============================================================================
// 1. RED NEURONAL MULTICAPA (MLP CON BACKPROPAGATION EN KOTLIN PURO)
// ============================================================================
class SynapticNeuralNetwork {
    private var vocabulary = listOf<String>()
    private var intents = listOf<IntentNode>()

    private var hiddenSize = 48
    private var w1 = arrayOf<DoubleArray>()
    private var b1 = DoubleArray(0)
    private var w2 = arrayOf<DoubleArray>()
    private var b2 = DoubleArray(0)

    private val stopWords = setOf("el", "la", "los", "las", "un", "una", "de", "del", "en", "y", "a", "por", "para", "con", "mi", "tu", "daemon", "oye", "porfa", "favor")

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
        val t0 = System.currentTimeMillis()
        intents = dataset
        val vocabSet = linkedSetOf<String>()
        val trainingSamples = mutableListOf<Pair<List<String>, Int>>()

        intents.forEachIndexed { intentIdx, node ->
            node.patterns.forEach { pattern ->
                val stems = normalizeAndStem(pattern)
                if (stems.isNotEmpty()) {
                    vocabSet.addAll(stems)
                    trainingSamples.add(stems to intentIdx)
                }
            }
        }

        vocabulary = vocabSet.toList()
        val inputSize = vocabulary.size
        val outputSize = intents.size
        if (inputSize == 0 || outputSize == 0) return

        val rnd = Random(42)
        w1 = Array(inputSize) { DoubleArray(hiddenSize) { (rnd.nextDouble() - 0.5) * sqrt(2.0 / inputSize) } }
        b1 = DoubleArray(hiddenSize) { 0.01 }
        w2 = Array(hiddenSize) { DoubleArray(outputSize) { (rnd.nextDouble() - 0.5) * sqrt(2.0 / hiddenSize) } }
        b2 = DoubleArray(outputSize) { 0.0 }

        val xTrain = trainingSamples.map { (stems, _) ->
            DoubleArray(inputSize) { i -> if (vocabulary[i] in stems) 1.0 else 0.0 }
        }
        val yTrain = trainingSamples.map { (_, targetIdx) -> targetIdx }

        val lr = 0.08
        val epochs = 450
        for (epoch in 0 until epochs) {
            for (sampleIdx in xTrain.indices) {
                val x = xTrain[sampleIdx]
                val target = yTrain[sampleIdx]

                // Forward Pass
                val hidden = DoubleArray(hiddenSize)
                for (j in 0 until hiddenSize) {
                    var sum = b1[j]
                    for (i in 0 until inputSize) {
                        if (x[i] > 0.0) sum += x[i] * w1[i][j]
                    }
                    hidden[j] = if (sum > 0.0) sum else 0.0
                }

                val logits = DoubleArray(outputSize)
                var maxLogit = Double.NEGATIVE_INFINITY
                for (k in 0 until outputSize) {
                    var sum = b2[k]
                    for (j in 0 until hiddenSize) sum += hidden[j] * w2[j][k]
                    logits[k] = sum
                    if (sum > maxLogit) maxLogit = sum
                }

                val probs = DoubleArray(outputSize)
                var expSum = 0.0
                for (k in 0 until outputSize) {
                    probs[k] = exp(logits[k] - maxLogit)
                    expSum += probs[k]
                }
                for (k in 0 until outputSize) probs[k] /= expSum

                // Backward Pass
                val dOut = DoubleArray(outputSize) { k -> probs[k] - (if (k == target) 1.0 else 0.0) }
                val dHidden = DoubleArray(hiddenSize)
                for (j in 0 until hiddenSize) {
                    if (hidden[j] > 0.0) {
                        var grad = 0.0
                        for (k in 0 until outputSize) grad += dOut[k] * w2[j][k]
                        dHidden[j] = grad
                    }
                }

                for (k in 0 until outputSize) {
                    b2[k] -= lr * dOut[k]
                    for (j in 0 until hiddenSize) {
                        w2[j][k] -= lr * hidden[j] * dOut[k]
                    }
                }

                for (j in 0 until hiddenSize) {
                    if (dHidden[j] != 0.0) {
                        b1[j] -= lr * dHidden[j]
                        for (i in 0 until inputSize) {
                            if (x[i] > 0.0) w1[i][j] -= lr * x[i] * dHidden[j]
                        }
                    }
                }
            }
        }
        val ms = System.currentTimeMillis() - t0
        println("[DAEMON NEURAL NET] Red entrenada en ${ms}ms | Vocabulario: $inputSize raíces | Intenciones: $outputSize | Patrones: ${trainingSamples.size}")
    }

    fun predict(rawInput: String): Pair<IntentNode?, Double> {
        if (vocabulary.isEmpty() || intents.isEmpty()) return null to 0.0
        val stems = normalizeAndStem(rawInput)
        if (stems.isEmpty()) return null to 0.0

        val x = DoubleArray(vocabulary.size) { i -> if (vocabulary[i] in stems) 1.0 else 0.0 }
        if (x.all { it == 0.0 }) return null to 0.0

        val hidden = DoubleArray(hiddenSize)
        for (j in 0 until hiddenSize) {
            var sum = b1[j]
            for (i in vocabulary.indices) if (x[i] > 0.0) sum += x[i] * w1[i][j]
            hidden[j] = if (sum > 0.0) sum else 0.0
        }

        val logits = DoubleArray(intents.size)
        var maxLogit = Double.NEGATIVE_INFINITY
        for (k in intents.indices) {
            var sum = b2[k]
            for (j in 0 until hiddenSize) sum += hidden[j] * w2[j][k]
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
}

// ============================================================================
// 2. ESCÁNER DEL SISTEMA, PLAYLISTS DE SPOTIFY Y VISIÓN DE VENTANAS ABIERTAS
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

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    init {
        scanInstalledApplications()
        loadPlaylists()
    }

    fun getOSLabel(): String = when {
        isWindows -> "Windows (${System.getProperty("os.name")})"
        isMac -> "macOS"
        isLinux -> "Linux / Ubuntu"
        else -> System.getProperty("os.name")
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
            } catch (e: Exception) {
                println("[DAEMON PLAYLISTS] Error leyendo playlists: ${e.message}")
            }
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
        return "Playlist **$cleanName** vinculada exitosamente a `$uri`, Cristian."
    }

    fun getOpenWindowsReport(): String {
        if (!isWindows) return "Escaneo de ventanas activas disponible en Windows."
        return try {
            val ps = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowTitle"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", ps).start()
            val titles = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readLines().map { it.trim() }.filter { it.isNotBlank() }
            if (titles.isEmpty()) "No detecto ventanas visibles abiertas en este momento."
            else "Actualmente tienes abiertas **${titles.size} ventanas** en tu escritorio:\n" + titles.joinToString("\n") { "* $it" }
        } catch (e: Exception) {
            "No pude leer las ventanas activas: ${e.message}"
        }
    }

    fun getCurrentlyPlayingSpotify(): String {
        if (!isWindows) return "Lectura de pista activa disponible en Windows."
        return try {
            val ps = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowTitle"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", ps).start()
            val title = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
            if (title.isBlank()) "Spotify está cerrado en este momento, Cristian."
            else if (title.equals("Spotify Free", true) || title.equals("Spotify Premium", true) || title.equals("Spotify", true)) {
                "Spotify está abierto pero en pausa, Cristian."
            } else {
                "En este momento está sonando: **$title** en Spotify."
            }
        } catch (e: Exception) {
            "No pude consultar el proceso de Spotify."
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
        val playlistQuery = normalized
            .replace(Regex("""^.*?(?:playlist|lista\s+de\s+reproduccion|lista|mis\s+canciones\s+de|musica\s+para)\s*(?:de\s+|llamada\s+|para\s+)?"""), "")
            .replace("en spotify", "")
            .trim()
            .ifBlank { "mis me gusta" }

        val savedMatch = savedPlaylists.entries.firstOrNull {
            playlistQuery.contains(it.key) || it.key.contains(playlistQuery)
        }

        val targetUri = savedMatch?.value ?: findSpotifyUri("playlist", playlistQuery)

        thread {
            try {
                if (targetUri != null) {
                    openSystemTarget(targetUri)
                    Thread.sleep(2800)
                    triggerSpotifyPlaylistStart()
                } else {
                    val encoded = URLEncoder.encode(playlistQuery, StandardCharsets.UTF_8).replace("+", "%20")
                    openSystemTarget("spotify:search:$encoded")
                    Thread.sleep(2800)
                    triggerSpotifyPlaylistStart()
                }
            } catch (e: Exception) {}
        }

        return if (savedMatch != null) {
            "Iniciando tu playlist **\"${savedMatch.key.uppercase()}\"** en Spotify, Cristian."
        } else {
            "Cargando y reproduciendo la playlist **\"$playlistQuery\"** en Spotify, Cristian."
        }
    }

    fun playSongInSpotify(songQuery: String?): String {
        if (songQuery.isNullOrBlank()) {
            sendMediaKey("PLAY_PAUSE")
            return "Alternando reproducción en tu sistema, Cristian."
        }
        thread {
            try {
                val trackUri = findSpotifyUri("track", songQuery)
                if (trackUri != null) {
                    openSystemTarget(trackUri)
                    Thread.sleep(2600)
                    triggerSpotifyPlaylistStart()
                } else {
                    val encoded = URLEncoder.encode(songQuery, StandardCharsets.UTF_8).replace("+", "%20")
                    openSystemTarget("spotify:search:$encoded")
                    Thread.sleep(2800)
                    triggerSpotifyPlaylistStart()
                }
            } catch (e: Exception) {}
        }
        return "Reproduciendo **\"$songQuery\"** en Spotify, Cristian."
    }

    private fun findSpotifyUri(type: String, query: String): String? {
        return try {
            val q = URLEncoder.encode("site:open.spotify.com/$type $query", StandardCharsets.UTF_8)
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://html.duckduckgo.com/html/?q=$q"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            val match = Regex("""open\.spotify\.com/$type/([a-zA-Z0-9]{22})""").find(res.body())
            match?.groupValues?.get(1)?.let { "spotify:$type:$it" }
        } catch (e: Exception) {
            null
        }
    }

    private fun triggerSpotifyPlaylistStart() {
        if (isWindows) {
            val ps = "\$w = New-Object -ComObject WScript.Shell; \$w.AppActivate('Spotify'); Start-Sleep -Milliseconds 350; \$w.SendKeys('{ENTER}'); Start-Sleep -Milliseconds 350; \$w.SendKeys([char]179)"
            ProcessBuilder("powershell", "-NoProfile", "-Command", ps).start()
        } else if (isMac) {
            ProcessBuilder("osascript", "-e", "tell application \"Spotify\" to play").start()
        } else if (isLinux) {
            ProcessBuilder("sh", "-c", "playerctl play").start()
        }
    }

    fun openAppByName(normalizedCommand: String): String {
        val requestedApp = normalizedCommand
            .replace(Regex("""^.*?(?:abre|abrir|inicia|iniciar|ejecuta|ejecutar|lanza|pon)\s+(?:la\s+aplicacion\s+|el\s+programa\s+|la\s+app\s+|el\s+|la\s+)?"""), "")
            .trim()

        val entry = installedApps.entries.firstOrNull { it.key == requestedApp }
            ?: installedApps.entries.firstOrNull { it.key.contains(requestedApp) || requestedApp.contains(it.key) }

        if (entry != null) {
            when {
                isWindows -> ProcessBuilder("cmd", "/c", "start", "", entry.value).start()
                isMac -> ProcessBuilder("open", "-a", entry.value).start()
                isLinux -> ProcessBuilder("sh", "-c", "${entry.value} &").start()
            }
            return "Ejecutando **${entry.key.uppercase()}** en tu ${getOSLabel()}, Cristian."
        }
        return "No encontré una aplicación llamada **$requestedApp** en tu sistema."
    }

    fun closeAppByName(normalizedCommand: String): String {
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
        when {
            isWindows -> ProcessBuilder("cmd", "/c", "taskkill /IM $processName.exe /F").start()
            isMac -> ProcessBuilder("killall", processName).start()
            isLinux -> ProcessBuilder("pkill", "-f", processName).start()
        }
        return "Cerrando **$appName**, Cristian."
    }

    fun sendMediaKey(action: String) {
        if (!isWindows) return
        val psCommand = when (action) {
            "PLAY_PAUSE" -> "\$w = New-Object -ComObject WScript.Shell; \$w.SendKeys([char]179)"
            "NEXT" -> "\$w = New-Object -ComObject WScript.Shell; \$w.SendKeys([char]176)"
            "PREV" -> "\$w = New-Object -ComObject WScript.Shell; \$w.SendKeys([char]177)"
            "VOL_UP" -> "\$w = New-Object -ComObject WScript.Shell; 1..5 | ForEach-Object { \$w.SendKeys([char]175) }"
            "VOL_DOWN" -> "\$w = New-Object -ComObject WScript.Shell; 1..5 | ForEach-Object { \$w.SendKeys([char]174) }"
            "MUTE" -> "\$w = New-Object -ComObject WScript.Shell; \$w.SendKeys([char]173)"
            else -> return
        }
        ProcessBuilder("powershell", "-NoProfile", "-Command", psCommand).start()
    }

    private fun openSystemTarget(uriOrPath: String) {
        when {
            isWindows -> ProcessBuilder("cmd", "/c", "start", "", uriOrPath).start()
            isMac -> ProcessBuilder("open", uriOrPath).start()
            isLinux -> ProcessBuilder("xdg-open", uriOrPath).start()
        }
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
// 3. CEREBRO DE DAEMON + MINI-VENTANA NATIVA DE ESCRITORIO
// ============================================================================
@Service
class DaemonBrain(
    private val systemScanner: SystemScanner
) {
    private val brainFile = File("daemon_brain.json")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val neuralNet = SynapticNeuralNetwork()
    private val intents = mutableListOf<IntentNode>()

    @Volatile var uiState: String = "IDLE"
    private var miniWindow: DaemonMiniWindow? = null

    init {
        loadOrCreateNeuralDataset()
        launchNativeDesktopMiniWindow()
        startNativeVoiceRecognition()
    }

    private fun loadOrCreateNeuralDataset() {
        if (!brainFile.exists()) {
            val defaultDataset = listOf(
                IntentNode(
                    tag = "saludo",
                    patterns = listOf("hola", "buenos dias", "buenas tardes", "buenas noches", "que tal daemon", "estas en linea", "hola daemon como estas", "despierta daemon"),
                    responses = listOf("En línea y operando con mi red neuronal local, Cristian. ¿Qué ejecutamos hoy?")
                ),
                IntentNode(
                    tag = "identidad",
                    patterns = listOf("quien eres", "como te llamas", "que eres exactamente", "presentate", "cual es tu funcion", "quien te creo"),
                    responses = listOf("Soy Daemon, tu asistente autónomo en Kotlin. Opero mediante una red neuronal multicapa entrenada localmente y fui creado por ti, Cristian.")
                ),
                IntentNode(
                    tag = "play_pausa",
                    patterns = listOf("dale play", "pon play", "pausa la musica", "ponle pausa", "deten la cancion", "reanuda la musica", "continua reproduciendo", "dale play a la cancion", "pausa spotify"),
                    responses = listOf("Comando Play/Pausa ejecutado en tu sistema, Cristian."),
                    action = "MEDIA_PLAY_PAUSE"
                ),
                IntentNode(
                    tag = "siguiente_cancion",
                    patterns = listOf("siguiente cancion", "cambia de cancion", "salta esta rola", "pasa a la siguiente", "otra cancion", "siguiente pista"),
                    responses = listOf("Pasando a la siguiente pista, Cristian."),
                    action = "MEDIA_NEXT"
                ),
                IntentNode(
                    tag = "anterior_cancion",
                    patterns = listOf("cancion anterior", "regresa la cancion", "pista anterior", "pon la anterior", "atras en spotify"),
                    responses = listOf("Regresando a la pista anterior, Cristian."),
                    action = "MEDIA_PREV"
                ),
                IntentNode(
                    tag = "subir_volumen",
                    patterns = listOf("sube el volumen", "subele a la musica", "mas volumen", "no se escucha subele", "aumenta el volumen"),
                    responses = listOf("Subiendo el volumen del sistema, Cristian."),
                    action = "VOL_UP"
                ),
                IntentNode(
                    tag = "bajar_volumen",
                    patterns = listOf("baja el volumen", "bajale a la musica", "menos volumen", "esta muy fuerte bajale", "disminuye el volumen"),
                    responses = listOf("Bajando el volumen del sistema, Cristian."),
                    action = "VOL_DOWN"
                ),
                IntentNode(
                    tag = "que_suena",
                    patterns = listOf("que cancion esta sonando", "como se llama esta cancion", "que estoy escuchando", "que suena en spotify", "dime el nombre de la rola"),
                    responses = emptyList(),
                    action = "CHECK_SPOTIFY_SONG"
                ),
                IntentNode(
                    tag = "ventanas_abiertas",
                    patterns = listOf("que tengo abierto", "que ventanas estan abiertas", "que programas estan corriendo", "revisa mis ventanas activas", "que aplicaciones tengo abiertas ahorita"),
                    responses = emptyList(),
                    action = "CHECK_OPEN_WINDOWS"
                ),
                IntentNode(
                    tag = "concepto_red_neuronal",
                    patterns = listOf("que es una red neuronal", "como funciona una red neuronal", "explicame las redes neuronales", "definicion de red neuronal", "que son las neuronas artificiales"),
                    responses = listOf("Una red neuronal artificial es un modelo matemático inspirado en el cerebro biológico, compuesto por capas de nodos interconectados que ajustan sus pesos sinápticos mediante retropropagación.")
                ),
                IntentNode(
                    tag = "concepto_sistema_operativo",
                    patterns = listOf("que es un sistema operativo", "como funciona el sistema operativo", "para que sirve un so", "definicion de sistema operativo", "que hace el kernel"),
                    responses = listOf("Un sistema operativo es el software principal que gestiona los recursos de hardware y provee servicios a los procesos y aplicaciones del usuario.")
                ),
                IntentNode(
                    tag = "concepto_proceso",
                    patterns = listOf("que es un proceso en computacion", "definicion de proceso e hilo", "como funciona un proceso en el sistema", "que son los procesos y threads"),
                    responses = listOf("Un proceso es una instancia de un programa en ejecución con su propio espacio de memoria asignado, mientras que un hilo es la unidad básica de ejecución dentro de ese proceso.")
                ),
                IntentNode(
                    tag = "concepto_agujero_negro",
                    patterns = listOf("que es un agujero negro", "explicame los agujeros negros", "como funciona un agujero negro", "definicion de agujero negro"),
                    responses = listOf("Un agujero negro es una región finita del espacio-tiempo cuya concentración de masa genera un campo gravitatorio tan intenso que ni siquiera la luz puede escapar.")
                )
            )
            intents.addAll(defaultDataset)
            objectMapper.writeValue(brainFile, intents)
        } else {
            val loadedArray = objectMapper.readValue(brainFile, Array<IntentNode>::class.java)
            intents.addAll(loadedArray)
        }
        neuralNet.train(intents)
    }

    private fun learnNewIntent(tag: String, patterns: List<String>, response: String): String {
        val existing = intents.indexOfFirst { it.tag == tag }
        if (existing >= 0) {
            val old = intents[existing]
            intents[existing] = old.copy(patterns = (old.patterns + patterns).distinct(), responses = listOf(response))
        } else {
            intents.add(IntentNode(tag = tag, patterns = patterns, responses = listOf(response)))
        }
        objectMapper.writeValue(brainFile, intents)
        neuralNet.train(intents)
        return "Red neuronal reentrenada, Cristian. Agregué el nodo **$tag** con ${patterns.size} patrones de activación."
    }

    fun processThought(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isBlank()) return "Sin señal de entrada."
        uiState = "THINKING"

        val normalized = systemScanner.normalizeText(input)

        if (normalized.contains("reentrena") || normalized.contains("actualiza tu cerebro")) {
            intents.clear()
            loadOrCreateNeuralDataset()
            return "He recargado `daemon_brain.json` y reentrenado mis pesos sinápticos, Cristian. Total de intenciones activas: **${intents.size}**."
        }

        if (normalized.startsWith("vincula playlist ") || normalized.startsWith("guarda playlist ")) {
            val name = input.substringAfter("playlist ").substringBefore(" con ").trim()
            val link = input.substringAfter(" con ").trim()
            return systemScanner.registerPlaylist(name, link)
        }

        if (normalized.contains("playlist") || normalized.contains("lista de reproduccion") || normalized.contains("mis me gusta")) {
            return systemScanner.playPlaylist(input, normalized)
        }

        if (normalized.startsWith("reproduce ") || normalized.startsWith("pon la cancion ") || normalized.startsWith("pon musica de ")) {
            val song = normalized.replace(Regex("""^(?:reproduce|pon la cancion|pon musica de)\s+"""), "").replace("en spotify", "").trim()
            return systemScanner.playSongInSpotify(song)
        }

        if (normalized.matches(Regex("""^(?:daemon\s+)?(?:abre|abrir|inicia|ejecuta|lanza)\s+.+$"""))) {
            return systemScanner.openAppByName(normalized)
        }
        if (normalized.matches(Regex("""^(?:daemon\s+)?(?:cierra|cerrar|termina|mata)\s+.+$"""))) {
            return systemScanner.closeAppByName(normalized)
        }

        if (input.lowercase().startsWith("entrena:")) {
            val body = input.substringAfter(":")
            if (body.contains("=")) {
                val patternsPart = body.substringBefore("=").split("/").map { it.trim() }.filter { it.isNotBlank() }
                val answerPart = body.substringAfter("=").trim()
                val tag = "custom_" + System.currentTimeMillis() % 10000
                return learnNewIntent(tag, patternsPart, answerPart)
            }
        }

        val (matchedIntent, confidence) = neuralNet.predict(input)
        println("[DAEMON MLP] Input: '$input' -> Nodo ganador: '${matchedIntent?.tag}' (Confianza: ${(confidence * 100).toInt()}%)")

        if (matchedIntent != null && confidence >= 0.42) {
            return when (matchedIntent.action) {
                "MEDIA_PLAY_PAUSE" -> { systemScanner.sendMediaKey("PLAY_PAUSE"); matchedIntent.responses.first() }
                "MEDIA_NEXT" -> { systemScanner.sendMediaKey("NEXT"); matchedIntent.responses.first() }
                "MEDIA_PREV" -> { systemScanner.sendMediaKey("PREV"); matchedIntent.responses.first() }
                "VOL_UP" -> { systemScanner.sendMediaKey("VOL_UP"); matchedIntent.responses.first() }
                "VOL_DOWN" -> { systemScanner.sendMediaKey("VOL_DOWN"); matchedIntent.responses.first() }
                "CHECK_SPOTIFY_SONG" -> systemScanner.getCurrentlyPlayingSpotify()
                "CHECK_OPEN_WINDOWS" -> systemScanner.getOpenWindowsReport()
                else -> matchedIntent.responses.random()
            }
        }

        return "Mi red neuronal analizó tu frase (confianza: ${(confidence * 100).toInt()}%), pero aún no tengo ese nodo entrenado. Agrégalo en `daemon_brain.json` o dime: *'Entrena: pregunta 1 / pregunta 2 = respuesta'*."
    }

    fun speakAndDisplay(userText: String, reply: String) {
        miniWindow?.updateHologram(userText, reply)
        thread {
            try {
                uiState = "SPEAKING"
                val cleanSpeech = reply.replace(Regex("[#*`_~>\\-]"), "").replace("\n", ". ").replace("'", "").replace("\"", "").trim()
                if (systemScanner.isWindows && cleanSpeech.isNotBlank()) {
                    val ps = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Rate = 1; \$s.Speak('$cleanSpeech')"
                    ProcessBuilder("powershell", "-NoProfile", "-Command", ps).start().waitFor()
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
                miniWindow?.setStatusText("ESCUCHANDO TU VOZ // HABLA AHORA...")

                val grammarPhrases = intents.flatMap { it.patterns }
                    .map { it.replace("'", "").trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(120)
                    .joinToString(",") { "'$it'" }

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
                    
                    ${'$'}choices = New-Object System.Speech.Recognition.Choices
                    ${'$'}choices.Add(@($grammarPhrases, 'abre spotify', 'cierra spotify', 'abre calculadora', 'abre discord', 'pon mi playlist de reggaeton', 'pon mi playlist de rock en espanol', 'pon mi playlist para programar', 'pon mis me gusta'))
                    ${'$'}gb = New-Object System.Speech.Recognition.GrammarBuilder(${'$'}choices)
                    ${'$'}gb.Culture = ${'$'}esRec.Culture
                    ${'$'}engine.LoadGrammar((New-Object System.Speech.Recognition.Grammar(${'$'}gb)))
                    ${'$'}engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                    
                    ${'$'}result = ${'$'}engine.Recognize([TimeSpan]::FromSeconds(6))
                    if (${'$'}null -ne ${'$'}result) {
                        Write-Output ${'$'}result.Text
                    } else {
                        Write-Output "ERR_TIMEOUT"
                    }
                """.trimIndent()

                val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", psScript).start()
                val output = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).readText().trim()

                when {
                    output == "ERR_NO_RECOGNIZER" -> {
                        uiState = "IDLE"
                        miniWindow?.updateHologram("DIAGNÓSTICO DE VOZ", "Tu Windows no tiene instalado el paquete de Reconocimiento de Voz en Español. Ve a Configuración -> Hora e idioma -> Voz.")
                    }
                    output == "ERR_TIMEOUT" || output.isBlank() -> {
                        uiState = "IDLE"
                        miniWindow?.setStatusText("STANDBY // NO SE DETECTÓ VOZ")
                    }
                    else -> {
                        val reply = processThought(output)
                        speakAndDisplay(output, reply)
                    }
                }
            } catch (e: Exception) {
                uiState = "IDLE"
                miniWindow?.setStatusText("ERROR DE AUDIO: ${e.message}")
            }
        }
    }

    private fun startNativeVoiceRecognition() {
        speakAndDisplay("SISTEMA INICIADO", "Mini-ventana del núcleo y red neuronal activas, Cristian.")
    }

    private fun launchNativeDesktopMiniWindow() {
        try {
            System.setProperty("java.awt.headless", "false")
            if (!GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeLater {
                    miniWindow = DaemonMiniWindow(this)
                    miniWindow?.isVisible = true
                }
            }
        } catch (e: Exception) {
            println("[DAEMON GUI INFO] ${e.message}")
        }
    }
}

// ============================================================================
// 4. MINI-VENTANA NATIVA DEL NÚCLEO (420x540 px, NO SUPERPUESTA)
// ============================================================================
class DaemonMiniWindow(private val brain: DaemonBrain) : JFrame("DAEMON // STARK CORE") {

    private val statusLabel = JLabel("ONLINE // RED NEURONAL ACTIVA")
    private val outputArea = JTextArea("Núcleo inicializado. Escribe abajo o presiona [🎤 HABLAR].")
    private val inputField = JTextField()
    private val corePanel = CoreCanvasPanel()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(420, 540)
        isAlwaysOnTop = false
        location = Point(60, 60)
        contentPane.background = Color(4, 9, 18)
        layout = BorderLayout(8, 8)

        val header = JPanel(BorderLayout()).apply {
            background = Color(8, 16, 32)
            border = EmptyBorder(10, 14, 10, 14)
            val title = JLabel("DAEMON // NEURAL CORE").apply {
                foreground = Color(0, 235, 255)
                font = Font("Consolas", Font.BOLD, 14)
            }
            statusLabel.apply {
                foreground = Color(16, 185, 129)
                font = Font("Consolas", Font.PLAIN, 11)
            }
            add(title, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(corePanel, BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout(6, 6)).apply {
            background = Color(4, 9, 18)
            border = EmptyBorder(4, 12, 12, 12)

            outputArea.apply {
                background = Color(10, 22, 42)
                foreground = Color(215, 250, 255)
                font = Font("Consolas", Font.PLAIN, 12)
                lineWrap = true
                wrapStyleWord = true
                isEditable = false
                border = EmptyBorder(8, 10, 8, 10)
                rows = 4
            }
            val scroll = JScrollPane(outputArea).apply {
                border = BorderFactory.createLineBorder(Color(0, 180, 215), 1)
            }

            val controls = JPanel(BorderLayout(6, 0)).apply {
                background = Color(4, 9, 18)

                val micBtn = JButton("🎤 HABLAR").apply {
                    background = Color(6, 95, 70)
                    foreground = Color.WHITE
                    font = Font("Consolas", Font.BOLD, 12)
                    isFocusPainted = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener { brain.triggerSingleVoiceListen() }
                }

                inputField.apply {
                    background = Color(12, 20, 35)
                    foreground = Color(0, 240, 255)
                    caretColor = Color.CYAN
                    font = Font("Consolas", Font.PLAIN, 13)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(0, 150, 190)),
                        EmptyBorder(6, 8, 6, 8)
                    )
                    addActionListener { sendTextCommand() }
                }

                val sendBtn = JButton("ENVIAR").apply {
                    background = Color(0, 120, 160)
                    foreground = Color.WHITE
                    font = Font("Consolas", Font.BOLD, 12)
                    isFocusPainted = false
                    addActionListener { sendTextCommand() }
                }

                add(micBtn, BorderLayout.WEST)
                add(inputField, BorderLayout.CENTER)
                add(sendBtn, BorderLayout.EAST)
            }

            add(scroll, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        add(bottomPanel, BorderLayout.SOUTH)

        javax.swing.Timer(16) { corePanel.repaint() }.start()
    }

    private fun sendTextCommand() {
        val text = inputField.text.trim()
        if (text.isBlank()) return
        inputField.text = ""
        thread {
            val reply = brain.processThought(text)
            brain.speakAndDisplay(text, reply)
        }
    }

    fun setStatusText(text: String) {
        SwingUtilities.invokeLater { statusLabel.text = text }
    }

    fun updateHologram(userQuery: String, daemonReply: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "ORDEN: ${userQuery.take(32).uppercase()}"
            outputArea.text = daemonReply.replace("**", "").replace("### ", "")
            outputArea.caretPosition = 0
        }
    }

    inner class CoreCanvasPanel : JPanel() {
        private var angle = 0.0
        private val nodes = List(95) { i ->
            val golden = (1 + sqrt(5.0)) / 2
            val theta = 2 * PI * i / golden
            val phi = acos(1 - 2 * (i + 0.5) / 95)
            Triple(theta, phi, 68.0 + (i % 4) * 6.0)
        }

        init {
            background = Color(4, 9, 18)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Haz clic en el núcleo para hablar por micrófono"
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

            val cx = width / 2.0
            val cy = height / 2.0

            val mainColor = when (brain.uiState) {
                "LISTENING" -> Color(16, 235, 150)
                "THINKING" -> Color(255, 175, 0)
                "SPEAKING" -> Color(0, 255, 255)
                else -> Color(0, 200, 255)
            }

            val speed = if (brain.uiState == "THINKING") 0.06 else if (brain.uiState == "SPEAKING") 0.035 else 0.015
            angle += speed
            val pulse = if (brain.uiState == "SPEAKING" || brain.uiState == "LISTENING") 1.0 + sin(angle * 5) * 0.14 else 1.0 + sin(angle * 2) * 0.03

            g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 90)
            g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f, floatArrayOf(18f, 12f), (angle * 40).toFloat())
            val r1 = 115 * pulse
            g2.draw(Ellipse2D.Double(cx - r1, cy - r1, r1 * 2, r1 * 2))

            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f, floatArrayOf(32f, 18f), (-angle * 60).toFloat())
            val r2 = 96 * pulse
            g2.draw(Ellipse2D.Double(cx - r2, cy - r2, r2 * 2, r2 * 2))

            val proj = nodes.map { (theta, phi, baseR) ->
                val r = baseR * pulse
                val rotT = theta + angle
                val x3 = r * sin(phi) * cos(rotT)
                val y3 = r * cos(phi)
                val z3 = r * sin(phi) * sin(rotT)
                val fov = 240.0 / (240.0 + z3)
                doubleArrayOf(cx + x3 * fov, cy + y3 * fov, z3)
            }

            g2.stroke = BasicStroke(0.8f)
            for (i in proj.indices) {
                for (j in i + 1 until proj.size) {
                    val dx = proj[i][0] - proj[j][0]
                    val dy = proj[i][1] - proj[j][1]
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < 32 * pulse) {
                        val alpha = ((1.0 - dist / (32 * pulse)) * 110).toInt().coerceIn(10, 255)
                        g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, alpha)
                        g2.drawLine(proj[i][0].toInt(), proj[i][1].toInt(), proj[j][0].toInt(), proj[j][1].toInt())
                    }
                }
            }

            proj.forEach { p ->
                val alpha = ((p[2] + 90) / 180 * 220).toInt().coerceIn(40, 255)
                g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, alpha)
                g2.fill(Ellipse2D.Double(p[0] - 2.2, p[1] - 2.2, 4.4, 4.4))
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