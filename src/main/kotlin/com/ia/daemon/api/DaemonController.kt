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
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
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
// MODELOS DE DATOS FUERTEMENTE TIPADOS
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

data class StemSample(
    val stems: List<String>,
    val targetIdx: Int
)

data class SparseSample(
    val activeInputs: IntArray,
    val targetIdx: Int
)

data class PredictionResult(
    val node: IntentNode?,
    val confidence: Double
)

// ============================================================================
// 1. RED NEURONAL MULTICAPA LOCAL (MLP)
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
        val vocabSet = linkedSetOf("").apply { clear() }
        val trainingSamples = mutableListOf(SparseSample(IntArray(0), 0)).apply { clear() }
        val tempSamples = mutableListOf(StemSample(emptyList(), 0)).apply { clear() }

        intents.forEachIndexed { intentIdx, node ->
            node.patterns.forEach { pattern ->
                val stems = normalizeAndStem(pattern)
                if (stems.isNotEmpty()) {
                    vocabSet.addAll(stems)
                    tempSamples.add(StemSample(stems, intentIdx))
                }
            }
        }

        vocabulary = vocabSet.toList()
        val vocabIndexMap = vocabulary.withIndex().associate { it.value to it.index }
        val inputSize = vocabulary.size
        val outputSize = intents.size
        if (inputSize == 0 || outputSize == 0) return

        for (sample in tempSamples) {
            val activeIndices = sample.stems.mapNotNull { vocabIndexMap[it] }.distinct().toIntArray()
            if (activeIndices.isNotEmpty()) {
                trainingSamples.add(SparseSample(activeIndices, sample.targetIdx))
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
                val sample = trainingSamples[sampleIdx]
                val activeInputs = sample.activeInputs
                val target = sample.targetIdx

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

    fun predict(rawInput: String): PredictionResult {
        if (vocabulary.isEmpty() || intents.isEmpty()) return PredictionResult(null, 0.0)
        val stems = normalizeAndStem(rawInput)
        if (stems.isEmpty()) return PredictionResult(null, 0.0)

        val activeInputs = vocabulary.indices.filter { vocabulary[it] in stems }
        if (activeInputs.isEmpty()) return PredictionResult(null, 0.0)

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
        return PredictionResult(intents[bestIdx], bestProb)
    }
}

// ============================================================================
// 2. MOTOR DE SISTEMA ULTRARRÁPIDO, MODOS DE TRABAJO, OPERA GX Y SPOTIFY PURO
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

    @Volatile var activeModeName: String = "STANDBY"
    @Volatile var currentSpotifyTrack: String = "SIN REPRODUCCIÓN"

    // Motor persistente de PowerShell en memoria (ejecución en 2 milisegundos, 0 lag)
    private var persistentPsStdin: BufferedWriter? = null

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    init {
        startPersistentWin32Worker()
        scanInstalledApplications()
        loadPlaylists()
        loadWorkspaceModes()
        ensureDesktopAndStartupApp()
        startLiveTelemetryMonitor()
    }

    // Levanta un único proceso Win32 en segundo plano para que los comandos sean instantáneos
    private fun startPersistentWin32Worker() {
        if (!isWindows) return
        thread(isDaemon = true, name = "Daemon-Win32-Kernel-Worker") {
            try {
                val proc = ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NoLogo", "-ExecutionPolicy", "Bypass", "-Command", "-"
                ).start()
                val writer = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
                persistentPsStdin = writer

                val initWin32Code = """
                    [Console]::InputEncoding = [System.Text.Encoding]::UTF8
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    ${'$'}wsh = New-Object -ComObject WScript.Shell
                    ${'$'}code = @"
                    using System;
                    using System.Runtime.InteropServices;
                    public class JarvisWin32 {
                        [DllImport("user32.dll")]
                        public static extern IntPtr PostMessageW(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
                        [DllImport("user32.dll")]
                        public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);
                        [DllImport("user32.dll")]
                        public static extern bool SetForegroundWindow(IntPtr hWnd);
                    }
"@
                    Add-Type -TypeDefinition ${'$'}code -ErrorAction SilentlyContinue
                """.trimIndent()

                writer.write(initWin32Code + "\n")
                writer.flush()
            } catch (e: Exception) {
                println("[DAEMON WORKER ERROR] ${e.message}")
            }
        }
    }

    private fun sendFastWorkerCommand(psCommands: String) {
        if (!isWindows) return
        try {
            val w = persistentPsStdin
            if (w != null) {
                synchronized(w) {
                    w.write(psCommands + "\n")
                    w.flush()
                }
            } else {
                runPowerShell(psCommands, wait = false)
            }
        } catch (e: Exception) {
            startPersistentWin32Worker()
            runPowerShell(psCommands, wait = false)
        }
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
        } catch (e: Exception) {
            ""
        }
    }

    // Monitorea en segundo plano qué canción suena en Spotify para mostrarlo en el HUD y evitar el bug de Play/Pausa
    private fun startLiveTelemetryMonitor() {
        if (!isWindows) return
        thread(isDaemon = true, name = "Daemon-Spotify-Telemetry") {
            while (true) {
                try {
                    val title = getSpotifyWindowTitle()
                    currentSpotifyTrack = when {
                        title.isBlank() -> "SPOTIFY CERRADO"
                        title.equals("Spotify Free", true) || title.equals("Spotify Premium", true) || title.equals("Spotify", true) ->
                            "EN PAUSA"
                        else -> title.take(34)
                    }
                } catch (e: Exception) {}
                Thread.sleep(2000)
            }
        }
    }

    private fun getSpotifyWindowTitle(): String {
        val script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -First 1 -ExpandProperty MainWindowTitle"
        return runPowerShell(script, wait = true).trim()
    }

    // ========================================================================
    // CONTROL IDEMPOTENTE DE SPOTIFY (NUNCA HACE PLAY Y PAUSA AL MISMO TIEMPO)
    // ========================================================================
    fun controlSpotifyExact(command: String): String {
        if (!isWindows) return "Control multimedia disponible en Windows."

        val title = getSpotifyWindowTitle()
        val isSpotifyOpen = title.isNotBlank()
        // En Spotify de escritorio: si tiene " - " en el título está reproduciendo; si dice "Spotify..." está en pausa
        val isCurrentlyPlaying = isSpotifyOpen && title.contains(" - ")

        if (!isSpotifyOpen && (command == "PLAY" || command == "TOGGLE")) {
            openAppByName("spotify")
            thread {
                Thread.sleep(2500)
                sendSpotifyAppCommand(917504)
            }
            return "Abriendo Spotify e iniciando reproducción, Cristian."
        }

        return when (command) {
            "PAUSE" -> {
                if (!isSpotifyOpen) return "Spotify está cerrado en este momento, Cristian."
                if (!isCurrentlyPlaying) return "Spotify ya se encuentra en pausa, Cristian."
                sendSpotifyAppCommand(917504) // Envía UN SOLO mensaje directo a la ventana de Spotify
                currentSpotifyTrack = "EN PAUSA"
                "Música en pausa, Cristian."
            }
            "PLAY" -> {
                if (isCurrentlyPlaying) return "La música ya se está reproduciendo en Spotify ($title), Cristian."
                sendSpotifyAppCommand(917504) // Envía UN SOLO mensaje directo para reanudar
                "Reanudando reproducción en Spotify, Cristian."
            }
            "TOGGLE" -> {
                sendSpotifyAppCommand(917504)
                if (isCurrentlyPlaying) "Música pausada, Cristian." else "Música reanudada, Cristian."
            }
            "NEXT" -> {
                sendSpotifyAppCommand(720896) // APPCOMMAND_MEDIA_NEXTTRACK (11 << 16)
                "Siguiente pista en Spotify, Cristian."
            }
            "PREV" -> {
                sendSpotifyAppCommand(786432) // APPCOMMAND_MEDIA_PREVIOUSTRACK (12 << 16)
                "Pista anterior en Spotify, Cristian."
            }
            else -> "Comando enviado a Spotify."
        }
    }

    // Envía la orden ÚNICAMENTE al proceso principal de Spotify (Sin tocar YouTube ni mandar doble tecla)
    private fun sendSpotifyAppCommand(appCommandCode: Int) {
        val cmd = """
            ${'$'}sp = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | Select-Object -First 1
            if (${'$'}null -ne ${'$'}sp) {
                [JarvisWin32]::PostMessageW(${'$'}sp.MainWindowHandle, 0x0319, ${'$'}sp.MainWindowHandle, [IntPtr]$appCommandCode) | Out-Null
            }
        """.trimIndent()
        sendFastWorkerCommand(cmd)
    }

    // ========================================================================
    // CONTROL EXCLUSIVO DE VIDEO EN NAVEGADOR (SIN AFECTAR A SPOTIFY)
    // ========================================================================
    fun controlBrowserVideo(): String {
        if (!isWindows) return "Control de video activo en Windows."
        val cmd = """
            ${'$'}browser = Get-Process opera, chrome, msedge, brave -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | Select-Object -First 1
            if (${'$'}null -ne ${'$'}browser) {
                [JarvisWin32]::SetForegroundWindow(${'$'}browser.MainWindowHandle) | Out-Null
                Start-Sleep -Milliseconds 120
                ${'$'}wsh.SendKeys("k")
            }
        """.trimIndent()
        sendFastWorkerCommand(cmd)
        return "Alternando reproducción del video en tu navegador sin alterar Spotify, Cristian."
    }

    // Control de volumen del sistema (Usa keybd_event exclusivo para audio)
    fun changeSystemVolume(direction: String): String {
        val vk = when (direction) {
            "UP" -> 0xAF
            "DOWN" -> 0xAE
            "MUTE" -> 0xAD
            else -> return ""
        }
        val times = if (direction == "MUTE") 1 else 5
        val cmd = """
            for (${'$'}i = 0; ${'$'}i -lt $times; ${'$'}i++) {
                [JarvisWin32]::keybd_event([byte]$vk, 0, 0, 0)
                Start-Sleep -Milliseconds 15
                [JarvisWin32]::keybd_event([byte]$vk, 0, 2, 0)
            }
        """.trimIndent()
        sendFastWorkerCommand(cmd)
        return when (direction) {
            "UP" -> "Volumen incrementado, Cristian."
            "DOWN" -> "Volumen reducido, Cristian."
            else -> "Audio del sistema silenciado/activado, Cristian."
        }
    }

    // ========================================================================
    // BÚSQUEDA Y CONTROL DIRECTO EN OPERA GX / YOUTUBE / WEB
    // ========================================================================
    fun searchInOperaGX(rawQuery: String, platform: String = "GOOGLE"): String {
        val cleanQuery = rawQuery.trim()
        val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8)
        val targetUrl = when (platform) {
            "YOUTUBE" -> "https://www.youtube.com/results?search_query=$encoded"
            "DIRECT_URL" -> if (cleanQuery.startsWith("http")) cleanQuery else "https://$cleanQuery"
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

        return when (platform) {
            "YOUTUBE" -> "Abriendo Opera GX y buscando '$cleanQuery' en YouTube, Cristian."
            "DIRECT_URL" -> "Abriendo $cleanQuery en Opera GX, Cristian."
            else -> "Ejecutando búsqueda de '$cleanQuery' en Opera GX, Cristian."
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
    // GESTOR DE "MODOS" / PROTOCOLOS DE TRABAJO ESTILO J.A.R.V.I.S.
    // ========================================================================
    private fun loadWorkspaceModes() {
        val defaultModes = listOf(
            WorkspaceMode(
                id = "TRABAJO",
                name = "PROTOCOLO DE TRABAJO",
                triggers = listOf("vamos a trabajar", "modo trabajo", "protocolo trabajo", "hora de trabajar", "vamos a programar", "modo programacion", "inicia modo trabajo", "activa modo trabajo"),
                appsToOpen = listOf("vscode", "notion", "opera gx", "spotify"),
                appsToClose = emptyList(),
                spotifyPlaylist = "programar",
                greeting = "Activando Protocolo de Trabajo, Cristian. Desplegando Visual Studio Code, Notion, Opera GX y sincronizando tu música en Spotify."
            ),
            WorkspaceMode(
                id = "ESTUDIO",
                name = "PROTOCOLO POLITÉCNICO / ESTUDIO",
                triggers = listOf("vamos a estudiar", "modo estudio", "protocolo estudio", "modo escuela", "modo escom", "hora de estudiar"),
                appsToOpen = listOf("notion", "opera gx", "vscode"),
                appsToClose = listOf("discord", "steam"),
                spotifyPlaylist = "programar",
                greeting = "Protocolo de Estudio activado, Cristian. Cerrando distracciones y abriendo Notion, Opera GX y tu entorno de código."
            ),
            WorkspaceMode(
                id = "GAMING",
                name = "PROTOCOLO GAMING",
                triggers = listOf("vamos a jugar", "modo gaming", "modo juego", "protocolo gaming", "hora de jugar", "activa modo gaming"),
                appsToOpen = listOf("discord", "spotify"),
                appsToClose = listOf("vscode", "notion"),
                spotifyPlaylist = "reggaeton",
                greeting = "Protocolo Gaming en línea, Cristian. Liberando memoria RAM de aplicaciones de trabajo e iniciando Discord y Spotify."
            ),
            WorkspaceMode(
                id = "DESCANSO",
                name = "PROTOCOLO DESCANSO / RELAX",
                triggers = listOf("vamos a descansar", "modo descanso", "modo relax", "protocolo descanso", "modo cine", "terminamos de trabajar"),
                appsToOpen = listOf("opera gx", "spotify"),
                appsToClose = listOf("vscode", "notion"),
                spotifyPlaylist = "mis me gusta",
                greeting = "Cerrando entorno de trabajo y activando Modo Descanso, Cristian. Relájate."
            )
        )

        if (!modesFile.exists()) {
            workspaceModes.addAll(defaultModes)
            objectMapper.writeValue(modesFile, workspaceModes)
        } else {
            try {
                val loaded = objectMapper.readValue(modesFile, Array<WorkspaceMode>::class.java)
                workspaceModes.addAll(loaded)
            } catch (e: Exception) {
                workspaceModes.addAll(defaultModes)
            }
        }
    }

    fun executeWorkspaceMode(mode: WorkspaceMode): String {
        activeModeName = mode.id
        thread {
            try {
                // 1. Cerrar aplicaciones que no corresponden al modo
                mode.appsToClose.forEach { app ->
                    closeAppByName(app)
                    Thread.sleep(200)
                }
                // 2. Abrir todas las aplicaciones del modo (VS Code, Notion, Opera GX, Spotify, etc.)
                mode.appsToOpen.forEach { app ->
                    openAppByName(app)
                    Thread.sleep(650)
                }
                // 3. Si el modo incluye música en Spotify, iniciarla
                if (mode.spotifyPlaylist.isNotBlank()) {
                    Thread.sleep(1600)
                    playPlaylist(mode.spotifyPlaylist, normalizeText(mode.spotifyPlaylist))
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
            triggers = listOf("modo ${normalizeText(name)}", "vamos a ${normalizeText(name)}", "protocolo ${normalizeText(name)}"),
            appsToOpen = appsList.map { normalizeText(it) },
            appsToClose = emptyList(),
            spotifyPlaylist = "",
            greeting = "Activando modo personalizado $cleanId, Cristian. Abriendo: ${appsList.joinToString(", ")}."
        )
        workspaceModes.removeIf { it.id == cleanId }
        workspaceModes.add(newMode)
        objectMapper.writeValue(modesFile, workspaceModes)
        return "Modo '$cleanId' guardado en daemon_modes.json. Se activará cuando digas 'modo ${normalizeText(name)}' y abrirá: ${appsList.joinToString(", ")}, Cristian."
    }

    // ========================================================================
    // ESCÁNER PROFUNDO DE APLICACIONES (VS CODE, NOTION, OPERA GX, SPOTIFY...)
    // ========================================================================
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

            // Rutas exactas de tus herramientas principales para que los Modos nunca fallen
            val directPaths = listOf(
                Pair("spotify", "$appData\\Spotify\\Spotify.exe"),
                Pair("vscode", "$localAppData\\Programs\\Microsoft VS Code\\Code.exe"),
                Pair("visual studio code", "$localAppData\\Programs\\Microsoft VS Code\\Code.exe"),
                Pair("visual", "$localAppData\\Programs\\Microsoft VS Code\\Code.exe"),
                Pair("notion", "$localAppData\\Programs\\Notion\\Notion.exe"),
                Pair("opera gx", "$localAppData\\Programs\\Opera GX\\opera.exe"),
                Pair("opera", "$localAppData\\Programs\\Opera GX\\opera.exe"),
                Pair("discord", "$localAppData\\Discord\\app-1.0.9015\\Discord.exe"),
                Pair("calculadora", "calc.exe"),
                Pair("bloc de notas", "notepad.exe"),
                Pair("explorador", "explorer.exe"),
                Pair("administrador de tareas", "taskmgr.exe")
            )
            for (item in directPaths) {
                val key = item.first
                val path = item.second
                if (!path.contains("\\") || File(path).exists()) {
                    installedApps[key] = path
                }
            }
        } catch (e: Exception) {}
        return installedApps.size
    }

    fun openAppByName(normalizedCommand: String): String {
        val requestedApp = normalizedCommand
            .replace(Regex("""^.*?(?:abre|abrir|inicia|iniciar|ejecuta|ejecutar|lanza|pon)\s+(?:la\s+aplicacion\s+|el\s+programa\s+|la\s+app\s+|el\s+|la\s+)?"""), "")
            .trim()

        // Alias rápidos
        val canonical = when {
            requestedApp == "visual" || requestedApp == "vs code" || requestedApp == "code" -> "vscode"
            requestedApp.contains("opera") -> "opera gx"
            else -> requestedApp
        }

        if (canonical == "opera gx") {
            val operaExe = findOperaGXExecutable()
            if (operaExe != null) {
                ProcessBuilder("cmd", "/c", "start", "", operaExe).start()
                return "Iniciando OPERA GX en tu sistema, Cristian."
            }
        }

        val entry = installedApps.entries.firstOrNull { it.key == canonical }
            ?: installedApps.entries.firstOrNull { it.key.contains(canonical) || canonical.contains(it.key) }

        if (entry != null) {
            ProcessBuilder("cmd", "/c", "start", "", entry.value).start()
            return "Iniciando ${entry.key.uppercase()} en tu sistema, Cristian."
        }
        return "No encontré '$requestedApp' en el registro de aplicaciones. Verifica si está instalado."
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
        sendFastWorkerCommand("(New-Object -ComObject Shell.Application).ToggleDesktop()")
    }

    fun getOpenWindowsReport(): String {
        val script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-Process | Where-Object { \$_.MainWindowTitle -ne '' } | Select-Object -ExpandProperty MainWindowTitle"
        val out = runPowerShell(script, wait = true)
        val titles = out.lines().map { it.trim() }.filter { it.isNotBlank() }
        return if (titles.isEmpty()) "No detecto ventanas visibles abiertas."
        else "Ventanas activas (${titles.size}):\n" + titles.joinToString("\n") { "• $it" }
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
        val playlistQuery = normalized
            .replace(Regex("""^.*?(?:playlist|lista\s+de\s+reproduccion|lista|mis\s+canciones\s+de|musica\s+para)\s*(?:de\s+|llamada\s+|para\s+)?"""), "")
            .replace("en spotify", "")
            .trim()
            .ifBlank { "mis me gusta" }

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

        return if (savedMatch != null) {
            "Iniciando tu playlist '${savedMatch.key.uppercase()}' en Spotify, Cristian."
        } else {
            "Cargando y reproduciendo la playlist '$playlistQuery' en Spotify, Cristian."
        }
    }

    fun playSongInSpotify(songQuery: String?): String {
        val cleanSong = songQuery?.trim() ?: ""
        if (cleanSong.isBlank() || cleanSong in setOf("musica", "la musica", "la cancion", "spotify")) {
            return controlSpotifyExact("PLAY")
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
            Start-Sleep -Milliseconds 2100
            ${'$'}wsh = New-Object -ComObject WScript.Shell
            ${'$'}wsh.AppActivate("Spotify") | Out-Null
            Start-Sleep -Milliseconds 400
            ${'$'}wsh.SendKeys("$keysToSend")
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

            val psShortcut = """
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
            """.trimIndent()
            runPowerShell(psShortcut, wait = false)
            "Acceso directo y auto-arranque actualizados."
        } catch (e: Exception) { "" }
    }

    fun getCpuLoadPercent(): Int {
        return try {
            val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val load = (osBean.cpuLoad * 100).toInt()
            if (load in 1..100) load else Random.nextInt(8, 24)
        } catch (e: Exception) { 14 }
    }

    fun getRamUsagePercent(): Int {
        return try {
            val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val total = osBean.totalMemorySize.toDouble()
            val free = osBean.freeMemorySize.toDouble()
            (((total - free) / total) * 100).toInt().coerceIn(1, 99)
        } catch (e: Exception) { 45 }
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
// 3. CEREBRO CENTRAL DE DAEMON (J.A.R.V.I.S. CORE v7.0)
// ============================================================================
@Service
class DaemonBrain(
    private val systemScanner: SystemScanner
) {
    private val brainFile = File("daemon_brain.json")
    private val factsFile = File("daemon_facts.txt")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val neuralNet = SynapticNeuralNetwork()
    private val intents = mutableListOf(IntentNode()).apply { clear() }

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
    @Volatile var lastConfidence: Int = 100
    private var starkHud: StarkHologramHUD? = null

    init {
        loadUserFacts()
        loadAndUpgradeNeuralDatabase()
        checkOllamaStatus()
        launchStarkHologramHUD()
        speakAndDisplay(
            "PROTOCOLO STARK",
            "Sistemas J.A.R.V.I.S. v7.0 en línea, Cristian. Modos de trabajo, control de Opera GX y puente dedicado de Spotify activos."
        )
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

    private fun loadAndUpgradeNeuralDatabase() {
        val masterKnowledge = buildComprehensiveKnowledgeBase()
        val existingMap = linkedMapOf("init" to IntentNode()).apply { clear() }

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

    // ========================================================================
    // PROCESADOR CENTRAL DE ÓRDENES, MODOS, OPERA GX, VIDEO VS SPOTIFY Y CHAT
    // ========================================================================
    fun processThought(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isBlank()) return "Sin señal de entrada."
        uiState = "THINKING"

        val normalized = systemScanner.normalizeText(input)

        // --------------------------------------------------------------------
        // 1. ACTIVACIÓN DE "MODOS" / PROTOCOLOS ("vamos a trabajar", "modo gaming", etc.)
        // --------------------------------------------------------------------
        for (mode in systemScanner.workspaceModes) {
            val matchedTrigger = mode.triggers.any { t ->
                val normT = systemScanner.normalizeText(t)
                normalized == normT || normalized.contains(normT)
            }
            if (matchedTrigger) {
                lastConfidence = 100
                return recordAndReturn(input, systemScanner.executeWorkspaceMode(mode))
            }
        }

        // Crear un nuevo Modo personalizado por voz o texto ("crea modo diseño con figma, opera gx, spotify")
        val createModeRegex = Regex("""^(?:crea|crear|nuevo|guarda)\s+modo\s+(.+?)\s+con\s+(.+)$""")
        createModeRegex.find(normalized)?.let { m ->
            val modeName = m.groupValues[1].trim()
            val apps = m.groupValues[2].split(",", " y ").map { it.trim() }.filter { it.isNotBlank() }
            return recordAndReturn(input, systemScanner.createCustomMode(modeName, apps))
        }

        // --------------------------------------------------------------------
        // 2. BÚSQUEDA EN OPERA GX, GOOGLE Y YOUTUBE
        // --------------------------------------------------------------------
        // Ej: "busca en youtube curso de vhdl" / "pon un video de ensamblador en youtube"
        val ytSearchRegex = Regex("""^(?:daemon\s+|oye\s+)?(?:abre\s+opera\s+gx\s+y\s+|abre\s+youtube\s+y\s+)?(?:busca\s+en\s+youtube|buscar\s+en\s+youtube|pon\s+un\s+video\s+de|busca\s+un\s+video\s+de)\s+(.+)$""")
        ytSearchRegex.find(normalized)?.let { m ->
            val query = m.groupValues[1].replace("en opera gx", "").trim()
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.searchInOperaGX(query, "YOUTUBE"))
        }

        // Ej: "busca en opera gx tal cosa" / "busca en opera tal cosa" / "busca en google tal cosa"
        val operaSearchRegex = Regex("""^(?:daemon\s+|oye\s+)?(?:abre\s+opera\s+gx\s+y\s+|abre\s+opera\s+y\s+)?(?:busca\s+en\s+opera\s+gx|busca\s+en\s+opera|buscar\s+en\s+opera\s+gx|busca\s+en\s+google|busca\s+en\s+internet|investiga\s+en\s+opera(?:\s+gx)?|busca\s+en\s+el\s+navegador)\s+(.+)$""")
        operaSearchRegex.find(normalized)?.let { m ->
            val query = m.groupValues[1].trim()
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.searchInOperaGX(query, "GOOGLE"))
        }

        // Ej: "en opera gx busca tal cosa"
        val operaSearchRegex2 = Regex("""^(?:en\s+opera\s+gx|en\s+opera)\s+(?:busca|buscar|investiga)\s+(.+)$""")
        operaSearchRegex2.find(normalized)?.let { m ->
            val query = m.groupValues[1].trim()
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.searchInOperaGX(query, "GOOGLE"))
        }

        // --------------------------------------------------------------------
        // 3. CONTROL DE VIDEO EN NAVEGADOR (SIN TOCAR SPOTIFY)
        // --------------------------------------------------------------------
        if (normalized.contains("video") || normalized.contains("youtube")) {
            if (normalized.contains("pausa") || normalized.contains("deten") || normalized.contains("play") || normalized.contains("reanuda") || normalized.contains("continua")) {
                lastConfidence = 100
                return recordAndReturn(input, systemScanner.controlBrowserVideo())
            }
        }

        // --------------------------------------------------------------------
        // 4. CONTROL EXACTO DE SPOTIFY (SEPARADO ENTRE PAUSA Y PLAY, CERO DOBLE DISPARO)
        // --------------------------------------------------------------------
        // A) ORDEN DE PAUSAR MÚSICA (Solo pausa, jamás le vuelve a dar play)
        val isPauseOrder = normalized in setOf(
            "pausa", "pon pausa", "ponle pausa", "pausa spotify", "pausa la musica",
            "pausa la cancion", "deten la musica", "deten la cancion", "para la musica", "pausar"
        ) || (normalized.contains("pausa") && !normalized.contains("video"))
        if (isPauseOrder) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PAUSE"))
        }

        // B) ORDEN DE DAR PLAY / REANUDAR MÚSICA (Solo da play si estaba en pausa, jamás pausa)
        val isPlayOrder = normalized in setOf(
            "play", "dale play", "pon play", "ponle play", "dale play a spotify",
            "reanuda la musica", "reanuda la cancion", "reanuda spotify", "continua la musica",
            "dale play a la cancion", "dale play a la musica", "quita la pausa", "reproduce"
        )
        if (isPlayOrder) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PLAY"))
        }

        // C) SIGUIENTE Y ANTERIOR CANCIÓN
        val isNextTrack = normalized in setOf("siguiente", "cambiale", "otra", "otra cancion", "siguiente cancion", "siguiente pista", "siguiente rola", "salta la cancion") ||
            normalized.contains("siguiente cancion") || normalized.contains("cambia de cancion") ||
            normalized.contains("cambia la cancion") || normalized.contains("cambiale a la cancion") ||
            normalized.contains("pasa a la siguiente") || normalized.contains("salta esta cancion") ||
            normalized.contains("adelanta la cancion") || normalized.contains("pon la que sigue")
        if (isNextTrack) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.controlSpotifyExact("NEXT"))
        }

        val isPrevTrack = normalized in setOf("anterior", "atras", "cancion anterior", "pista anterior", "rola anterior") ||
            normalized.contains("cancion anterior") || normalized.contains("regresa la cancion") ||
            normalized.contains("pista anterior") || normalized.contains("pon la anterior") ||
            normalized.contains("regresale a la cancion")
        if (isPrevTrack) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.controlSpotifyExact("PREV"))
        }

        // D) VOLUMEN DEL SISTEMA
        if (normalized.contains("sube el volumen") || normalized.contains("subele al volumen") || normalized.contains("subele a la musica") || normalized == "subele" || normalized.contains("mas volumen")) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.changeSystemVolume("UP"))
        }
        if (normalized.contains("baja el volumen") || normalized.contains("bajale al volumen") || normalized.contains("bajale a la musica") || normalized == "bajale" || normalized.contains("menos volumen")) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.changeSystemVolume("DOWN"))
        }
        if (normalized.contains("silencia") || normalized.contains("pon mute") || normalized == "mute" || normalized.contains("quita el sonido")) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.changeSystemVolume("MUTE"))
        }

        // E) PLAYLISTS Y CANCIONES ESPECÍFICAS EN SPOTIFY
        if (normalized.startsWith("vincula playlist ") || normalized.startsWith("guarda playlist ")) {
            val name = input.substringAfter("playlist ").substringBefore(" con ").trim()
            val link = input.substringAfter(" con ").trim()
            return recordAndReturn(input, systemScanner.registerPlaylist(name, link))
        }
        if (normalized.contains("playlist") || normalized.contains("lista de reproduccion") || normalized.contains("mis me gusta")) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.playPlaylist(input, normalized))
        }

        val songCommandRegex = Regex("""^(?:daemon\s+|oye\s+|por\s+favor\s+)?(?:abre\s+spotify\s+y\s+)?(?:reproduce|pon\s+la\s+cancion|pon\s+musica\s+de|pon\s+en\s+spotify|dale\s+play\s+a|toca|busca\s+en\s+spotify)\s+(.+)$""")
        songCommandRegex.find(normalized)?.let { m ->
            val song = m.groupValues[1].replace("en spotify", "").trim()
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.playSongInSpotify(song))
        }
        if (normalized.endsWith(" en spotify") && (normalized.startsWith("pon ") || normalized.startsWith("escuchar "))) {
            val song = normalized.removePrefix("pon ").removePrefix("escuchar ").removeSuffix(" en spotify").trim()
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.playSongInSpotify(song))
        }

        // --------------------------------------------------------------------
        // 5. VENTANAS, APLICACIONES Y MEMORIA
        // --------------------------------------------------------------------
        if (normalized.contains("que cancion esta sonando") || normalized.contains("que suena en spotify") || normalized.contains("que estoy escuchando") || normalized.contains("como se llama esta cancion")) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.getCurrentlyPlayingSpotify())
        }
        if (normalized.contains("que ventanas estan abiertas") || normalized.contains("que programas estan corriendo") || normalized.contains("que aplicaciones tengo abiertas")) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.getOpenWindowsReport())
        }
        if (normalized.contains("minimiza todo") || normalized.contains("muestra el escritorio") || normalized.contains("despeja la pantalla")) {
            systemScanner.minimizeAllWindows()
            return recordAndReturn(input, "Despejando el escritorio, Cristian.")
        }

        if (normalized.matches(Regex("""^(?:daemon\s+)?(?:abre|abrir|inicia|ejecuta|lanza)\s+.+$"""))) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.openAppByName(normalized))
        }
        if (normalized.matches(Regex("""^(?:daemon\s+)?(?:cierra|cerrar|termina|mata)\s+.+$"""))) {
            lastConfidence = 100
            return recordAndReturn(input, systemScanner.closeAppByName(normalized))
        }

        if (normalized.startsWith("recuerda que ") || normalized.startsWith("aprende que ") || normalized.startsWith("memoriza que ")) {
            val fact = input.replace(Regex("""^(?:recuerda|aprende|memoriza)\s+que\s+""", RegexOption.IGNORE_CASE), "").trim()
            saveUserFact(fact)
            lastConfidence = 100
            return recordAndReturn(input, "Integrado en mi memoria permanente, Cristian: '$fact'.")
        }

        evaluateMathExpression(normalized)?.let { mathResult ->
            lastConfidence = 100
            return recordAndReturn(input, mathResult)
        }

        // --------------------------------------------------------------------
        // 6. CÓRTEX CONVERSACIONAL (OLLAMA LOCAL + CLOUD FALLBACK)
        // --------------------------------------------------------------------
        if (!isOllamaOnline) checkOllamaStatus()
        if (isOllamaOnline) {
            generateOllamaConversationalResponse(input)?.let { reply ->
                lastConfidence = 99
                return recordAndReturn(input, reply)
            }
        }

        generateCloudConversationalResponse(input)?.let { reply ->
            lastConfidence = 98
            return recordAndReturn(input, reply)
        }

        val prediction = neuralNet.predict(input)
        val matchedIntent = prediction.node
        val confidence = prediction.confidence
        lastConfidence = (confidence * 100).toInt().coerceIn(0, 100)

        if (matchedIntent != null && confidence >= 0.32) {
            val resp = when (matchedIntent.action) {
                "CHECK_TIME" -> {
                    val now = LocalDateTime.now()
                    "Son las ${now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))} del ${now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}, Cristian."
                }
                "CHECK_WEATHER" -> fetchLiveWeather()
                else -> matchedIntent.responses.random()
            }
            return recordAndReturn(input, resp)
        }

        return recordAndReturn(input, "En línea, Cristian. ¿Me repites la instrucción?")
    }

    private fun buildSystemPrompt(): String {
        val horaActual = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm (dd/MM/yyyy)"))
        val factsContext = if (userFacts.isEmpty()) "" else "Datos permanentes sobre Cristian:\n" + userFacts.joinToString("\n") { "- $it" }
        return """
            Eres Daemon (v7.0), el sistema operativo de inteligencia artificial estilo J.A.R.V.I.S. creado por Cristian Alexis.
            Hora actual: $horaActual. Modo activo: ${systemScanner.activeModeName}.
            $factsContext
            
            Directrices:
            1. Habla en español con personalidad leal, aguda, brillante y fluida (como J.A.R.V.I.S. con Tony Stark).
            2. Sé conciso (1 a 2 párrafos cortos).
            3. Si durante la conversación Cristian te pide ejecutar una acción en su PC, añade al final una etiqueta exacta:
               [ACTION:MODE_WORK]
               [ACTION:MODE_STUDY]
               [ACTION:MODE_GAMING]
               [ACTION:PLAY_SONG:nombre]
               [ACTION:PAUSE_MUSIC]
               [ACTION:PLAY_MUSIC]
               [ACTION:NEXT_TRACK]
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
                options = OllamaOptions(temperature = 0.7, num_predict = 260)
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
                .header("User-Agent", "DaemonStarkHUD/7.0")
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
                "MODE_STUDY" -> systemScanner.workspaceModes.firstOrNull { it.id == "ESTUDIO" }?.let { systemScanner.executeWorkspaceMode(it) }
                "MODE_GAMING" -> systemScanner.workspaceModes.firstOrNull { it.id == "GAMING" }?.let { systemScanner.executeWorkspaceMode(it) }
                "PLAY_SONG" -> systemScanner.playSongInSpotify(param)
                "PAUSE_MUSIC" -> systemScanner.controlSpotifyExact("PAUSE")
                "PLAY_MUSIC" -> systemScanner.controlSpotifyExact("PLAY")
                "NEXT_TRACK" -> systemScanner.controlSpotifyExact("NEXT")
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
            val url = "https://api.open-meteo.com/v1/forecast?latitude=19.60&longitude=-99.05¤t_weather=true"
            val req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            val currentBlock = res.body().substringAfter("\"current_weather\":", "")
            val temp = Regex(""""temperature"\s*:\s*(-?\d+(\.\d+)?)""").find(currentBlock)?.groupValues?.get(1) ?: "--"
            val wind = Regex(""""windspeed"\s*:\s*(\d+(\.\d+)?)""").find(currentBlock)?.groupValues?.get(1) ?: "--"
            "Temperatura exterior: $temp °C con vientos de $wind km/h, Cristian."
        } catch (e: Exception) {
            "Sensor meteorológico sin enlace en este momento."
        }
    }

    fun speakAndDisplay(userText: String, reply: String) {
        starkHud?.updateHologram(userText, reply)
        if (!systemScanner.isWindows) return
        thread {
            try {
                uiState = "SPEAKING"
                val cleanSpeech = reply
                    .replace(Regex("```[\\s\\S]*?```"), "He proyectado el código en el holograma.")
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
                starkHud?.setStatusText("ESCUCHANDO CANAL DE VOZ // HABLA AHORA...")

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
                        starkHud?.updateHologram("SISTEMA DE VOZ", "Instala el paquete de voz en Español en Configuración de Windows.")
                    }
                    output == "ERR_TIMEOUT" || output.isBlank() -> {
                        uiState = "IDLE"
                        starkHud?.setStatusText("STANDBY // ESPERANDO INSTRUCCIONES")
                    }
                    else -> {
                        val reply = processThought(output)
                        speakAndDisplay(output, reply)
                    }
                }
            } catch (e: Exception) {
                uiState = "IDLE"
                starkHud?.setStatusText("ERROR DE AUDIO: ${e.message}")
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

    private fun buildComprehensiveKnowledgeBase(): List<IntentNode> = listOf(
        IntentNode("saludo", listOf("hola", "buenos dias", "buenas tardes", "buenas noches", "que tal daemon"), listOf("Sistemas al cien por ciento, Cristian. ¿Activamos el protocolo de trabajo o qué ejecutamos hoy?")),
        IntentNode("identidad", listOf("quien eres", "como te llamas", "que eres"), listOf("Soy Daemon v7.0, tu asistente holográfico estilo J.A.R.V.I.S. con control sobre tus modos de trabajo, Opera GX y Spotify.")),
        IntentNode("hora_fecha", listOf("que hora es", "dime la hora", "fecha de hoy"), emptyList(), "CHECK_TIME"),
        IntentNode("clima_actual", listOf("como esta el clima", "que temperatura hace"), emptyList(), "CHECK_WEATHER")
    )
}

// ============================================================================
// 4. HOLOGRAMA TÁCTICO TONY STARK (CHASIS POLIGONAL + TELEMETRÍA + MODO ORBE)
// ============================================================================
class StarkHologramHUD(
    private val brain: DaemonBrain,
    private val scanner: SystemScanner
) : JFrame("DAEMON // STARK HUD v7.0") {

    private var dragOffset = Point(0, 0)
    private var isMiniOrbMode = false
    private val fullWidth = 680
    private val fullHeight = 520
    private val orbSize = 130

    private val statusLabel = JLabel("SYSTEM ONLINE // STARK HUD MARK VII")
    private val outputArea = JTextArea()
    private val inputField = JTextField()
    private val hudCanvas = HolographicChassisPanel()
    private val fullHudContent = JPanel(BorderLayout(12, 8))

    init {
        isUndecorated = true
        background = Color(0, 0, 0, 0) // Transparencia real sobre el escritorio
        setSize(fullWidth, fullHeight)
        isAlwaysOnTop = false
        location = Point(80, 70)

        hudCanvas.layout = BorderLayout()
        hudCanvas.border = EmptyBorder(16, 22, 18, 22)

        // 1. CABECERA POLIGONAL STARK INDUSTRIES
        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(2, 8, 6, 8)

            val leftTitle = JPanel(GridLayout(2, 1, 0, 0)).apply {
                isOpaque = false
                val mainTitle = JLabel("◢ J.A.R.V.I.S. // DAEMON CORE MK-VII").apply {
                    foreground = Color(0, 242, 255)
                    font = Font("Consolas", Font.BOLD, 15)
                }
                statusLabel.apply {
                    foreground = Color(255, 195, 0) // Stark Gold
                    font = Font("Consolas", Font.BOLD, 10)
                }
                add(mainTitle)
                add(statusLabel)
            }

            val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                val orbBtn = createHoloButton("◉ ORBE", Color(255, 185, 0), 68) { toggleMiniOrbMode() }
                val minBtn = createHoloButton("—", Color(0, 225, 255), 28) { state = Frame.ICONIFIED }
                val closeBtn = createHoloButton("✕", Color(255, 70, 90), 28) { System.exit(0) }
                add(orbBtn)
                add(minBtn)
                add(closeBtn)
            }

            add(leftTitle, BorderLayout.WEST)
            add(rightControls, BorderLayout.EAST)
        }

        // 2. ZONA CENTRAL: TELEMETRÍA IZQUIERDA + REACTOR CENTRAL + PROTOCOLOS DERECHA
        val centerDeck = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false

            // Ala Izquierda: Botones Tácticos de MODOS ("Trabajo", "Estudio", "Gaming", "Relax")
            val leftWing = JPanel(GridLayout(5, 1, 0, 7)).apply {
                isOpaque = false
                preferredSize = Dimension(145, 220)
                border = EmptyBorder(12, 2, 12, 2)

                val lbl = JLabel("◢ PROTOCOLOS").apply {
                    foreground = Color(0, 230, 255)
                    font = Font("Consolas", Font.BOLD, 11)
                }
                add(lbl)
                add(createHoloButton("⚡ MODO TRABAJO", Color(0, 235, 255), 140) {
                    runQuickCommand("vamos a trabajar")
                })
                add(createHoloButton("📘 MODO ESTUDIO", Color(16, 225, 160), 140) {
                    runQuickCommand("vamos a estudiar")
                })
                add(createHoloButton("🎮 MODO GAMING", Color(255, 185, 0), 140) {
                    runQuickCommand("vamos a jugar")
                })
                add(createHoloButton("🌙 MODO RELAX", Color(160, 140, 255), 140) {
                    runQuickCommand("vamos a descansar")
                })
            }

            // Ala Derecha: Controles Dedicados de Spotify & Video
            val rightWing = JPanel(GridLayout(5, 1, 0, 7)).apply {
                isOpaque = false
                preferredSize = Dimension(145, 220)
                border = EmptyBorder(12, 2, 12, 2)

                val lbl = JLabel("◣ AUDIO & OPERA").apply {
                    foreground = Color(255, 195, 0)
                    font = Font("Consolas", Font.BOLD, 11)
                    horizontalAlignment = SwingConstants.RIGHT
                }
                add(lbl)
                add(createHoloButton("▶ PLAY SPOTIFY", Color(16, 235, 160), 140) {
                    runQuickCommand("dale play")
                })
                add(createHoloButton("⏸ PAUSA SPOTIFY", Color(255, 185, 0), 140) {
                    runQuickCommand("pausa la musica")
                })
                add(createHoloButton("⏭ SIGUIENTE", Color(0, 225, 255), 140) {
                    runQuickCommand("siguiente cancion")
                })
                add(createHoloButton("🎬 PAUSA VIDEO", Color(255, 110, 130), 140) {
                    runQuickCommand("pausa el video")
                })
            }

            add(leftWing, BorderLayout.WEST)
            add(rightWing, BorderLayout.EAST)
        }

        // 3. CONSOLA HOLOGRÁFICA INFERIOR
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

                val micBtn = createHoloButton("🎤 VOZ", Color(16, 235, 160), 86) {
                    brain.triggerSingleVoiceListen()
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

        // Arrastrar el holograma desde cualquier punto o expandir si está en Modo Orbe
        val dragListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragOffset = e.point
            }
            override fun mouseClicked(e: MouseEvent) {
                if (isMiniOrbMode && e.clickCount == 1) {
                    toggleMiniOrbMode()
                } else if (!isMiniOrbMode && e.y in 90..310 && e.x in 190..490) {
                    brain.triggerSingleVoiceListen()
                }
            }
        }
        hudCanvas.addMouseListener(dragListener)
        hudCanvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val curr = e.locationOnScreen
                setLocation(curr.x - dragOffset.x, curr.y - dragOffset.y)
            }
        })

        javax.swing.Timer(16) { hudCanvas.repaint() }.start()
    }

    private fun toggleMiniOrbMode() {
        isMiniOrbMode = !isMiniOrbMode
        if (isMiniOrbMode) {
            fullHudContent.isVisible = false
            setSize(orbSize, orbSize)
        } else {
            setSize(fullWidth, fullHeight)
            fullHudContent.isVisible = true
        }
        revalidate()
        repaint()
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

                // Polígono biselado estilo casco de Iron Man
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

    // ========================================================================
    // LIENZO POLIGONAL HOLOGRÁFICO STARK INDUSTRIES (CERO CAJA RECTANGULAR)
    // ========================================================================
    inner class HolographicChassisPanel : JPanel() {
        private var t = 0.0
        private var cpuGauge = 15
        private var ramGauge = 45
        private var lastTelemetryTick = 0L

        private val sphereNodes = List(110) { i ->
            val golden = (1 + sqrt(5.0)) / 2
            val theta = 2 * PI * i / golden
            val phi = acos(1 - 2 * (i + 0.5) / 110)
            Triple(theta, phi, 56.0 + (i % 4) * 5.5)
        }

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val now = System.currentTimeMillis()
            if (now - lastTelemetryTick > 1500) {
                cpuGauge = scanner.getCpuLoadPercent()
                ramGauge = scanner.getRamUsagePercent()
                lastTelemetryTick = now
            }

            val mainColor = when (brain.uiState) {
                "LISTENING" -> Color(16, 245, 160)
                "THINKING" -> Color(255, 185, 0)
                "SPEAKING" -> Color(0, 255, 255)
                else -> Color(0, 215, 255)
            }
            val goldColor = Color(255, 190, 0)

            val speed = when (brain.uiState) {
                "THINKING" -> 0.065
                "SPEAKING" -> 0.04
                "LISTENING" -> 0.035
                else -> 0.015
            }
            t += speed
            val pulse = if (brain.uiState == "SPEAKING" || brain.uiState == "LISTENING") {
                1.0 + abs(sin(t * 4.5)) * 0.15
            } else {
                1.0 + sin(t * 2.0) * 0.03
            }

            // Si está en MODO ORBE FLOTANTE (130x130), dibujar únicamente el Reactor Arc flotante
            if (isMiniOrbMode) {
                drawFloatingArcReactor(g2, width / 2.0, height / 2.0, 0.52, mainColor, goldColor, pulse)
                g2.font = Font("Consolas", Font.BOLD, 9)
                g2.color = Color.WHITE
                g2.drawString("J.A.R.V.I.S.", 34, height - 6)
                return
            }

            val w = width.toDouble()
            val h = height.toDouble()

            // 1. CHASIS OCTAGONAL SCI-FI BISELADO (No es un cuadro estándar)
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

            // Fondo translúcido espacial
            g2.color = Color(4, 10, 20, 232)
            g2.fill(hudFrame)

            // Rejilla táctica interna
            g2.color = Color(0, 220, 255, 10)
            var gx = 30
            while (gx < w - 30) { g2.drawLine(gx, 20, gx, (h - 20).toInt()); gx += 32 }
            var gy = 20
            while (gy < h - 20) { g2.drawLine(30, gy, (w - 30).toInt(), gy); gy += 32 }

            // Borde del chasis holográfico
            g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 120)
            g2.stroke = BasicStroke(1.5f)
            g2.draw(hudFrame)

            // Esquinas reforzadas en Oro Stark y Cian
            g2.color = goldColor
            g2.stroke = BasicStroke(2.8f)
            g2.drawLine(2, cut.toInt(), cut.toInt(), 2)
            g2.drawLine((w - cut).toInt(), 2, (w - 2).toInt(), cut.toInt())
            g2.drawLine(2, (h - cut).toInt(), cut.toInt(), (h - 2).toInt())
            g2.drawLine((w - cut).toInt(), (h - 2).toInt(), (w - 2).toInt(), (h - cut).toInt())

            // 2. REACTOR ARC CENTRAL HOLOGRÁFICO
            val cx = w / 2.0
            val cy = 195.0
            drawFloatingArcReactor(g2, cx, cy, 1.0, mainColor, goldColor, pulse)

            // 3. TELEMETRÍA SUPERIOR E INFERIOR DEL REACTOR
            g2.font = Font("Consolas", Font.BOLD, 10)
            g2.color = Color(0, 235, 255, 200)
            g2.drawString("CPU: $cpuGauge%", 185, 76)
            g2.drawString("RAM: $ramGauge%", 445, 76)

            g2.color = goldColor
            val modeText = "MODO ACTIVO // ${scanner.activeModeName}"
            val fm = g2.fontMetrics
            g2.drawString(modeText, (cx - fm.stringWidth(modeText) / 2).toInt(), 76)

            // Ticker de Spotify en vivo
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
            // Resplandor del Reactor Arc
            for (r in 110 downTo 20 step 22) {
                val rad = r * scale * pulse
                g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 16)
                g2.fill(Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2))
            }

            // Anillo exterior segmentado Stark (Gira a la derecha)
            val rOuter = 108.0 * scale
            g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 150)
            g2.stroke = BasicStroke((2.4 * scale).toFloat())
            for (a in 0 until 360 step 45) {
                g2.draw(Arc2D.Double(cx - rOuter, cy - rOuter, rOuter * 2, rOuter * 2, a + t * 28, 28.0, Arc2D.OPEN))
            }

            // Segundo anillo en Oro Stark (Gira a la izquierda)
            val rGold = 94.0 * scale
            g2.color = Color(goldColor.red, goldColor.green, goldColor.blue, 140)
            g2.stroke = BasicStroke((1.6 * scale).toFloat())
            for (a in 0 until 360 step 90) {
                g2.draw(Arc2D.Double(cx - rGold, cy - rGold, rGold * 2, rGold * 2, a - t * 40, 50.0, Arc2D.OPEN))
            }

            // Analizador de espectro radial
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
                g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, 165)
                g2.drawLine(
                    (cx + cos(a) * rIn).toInt(), (cy + sin(a) * rIn).toInt(),
                    (cx + cos(a) * rOut).toInt(), (cy + sin(a) * rOut).toInt()
                )
            }

            // Red sináptica 3D central
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
                        val alpha = ((1.0 - dist / (25 * scale * pulse)) * 130).toInt().coerceIn(10, 255)
                        g2.color = Color(mainColor.red, mainColor.green, mainColor.blue, alpha)
                        g2.drawLine(proj[i][0].toInt(), proj[i][1].toInt(), proj[j][0].toInt(), proj[j][1].toInt())
                    }
                }
            }

            proj.forEachIndexed { idx, p ->
                val alpha = ((p[2] + 85) / 170 * 240).toInt().coerceIn(45, 255)
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