package com.ia.daemon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.GeneralPath
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.management.ManagementFactory
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicScrollBarUI
import kotlin.concurrent.thread
import kotlin.math.*

// ============================================================================
// MODELOS DE DATOS DE CUENTAS, CALENDARIO (GOOGLE/APPLE), OTA Y NUBE
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

data class CalendarEventItem(
    val id: String = "evt_" + System.currentTimeMillis(),
    val title: String = "",
    val startEpochMillis: Long = 0L,
    val endEpochMillis: Long = 0L,
    val formattedDate: String = "",
    var syncedToGoogle: Boolean = false,
    var syncedToApple: Boolean = false,
    var voiceAlertTriggered: Boolean = false
)

data class UserAccount(
    val username: String = "",
    var passwordHash: String = "",
    var role: String = "USER",
    var calendarProvider: String = "BOTH", // "GOOGLE", "APPLE" o "BOTH"
    var googleAccountEmail: String = "",
    var googleWebhookOrToken: String = "",
    var appleAccountEmail: String = "",
    val personalFacts: MutableList<String> = mutableListOf(),
    val personalKnowledge: MutableList<IntentNode> = mutableListOf(),
    val personalModes: MutableList<WorkspaceMode> = mutableListOf(),
    val calendarEvents: MutableList<CalendarEventItem> = mutableListOf(),
    val recentHistory: MutableList<ChatMessage> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class CloudVaultState(
    var lastUpdated: Long = System.currentTimeMillis(),
    val globalKnowledge: MutableList<IntentNode> = mutableListOf(),
    val accounts: MutableList<UserAccount> = mutableListOf()
)

data class OtaManifest(
    var appVersion: Int = 130,
    var versionName: String = "13.0.0",
    var githubRawBaseUrl: String = "",
    var sharedCloudDbUrl: String = "",
    var jarRelativePath: String = "build/libs/daemon-0.0.1-SNAPSHOT.jar",
    var publishedAt: Long = System.currentTimeMillis()
)

data class LocalMachineSession(
    var loggedUsername: String = "invitado",
    var loggedPasswordHash: String = "",
    var installedAppVersion: Int = 130
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

data class GoogleCalendarWebhookPayload(
    val title: String = "",
    val startIso: String = "",
    val endIso: String = "",
    val email: String = "",
    val description: String = "Agendado automáticamente por Daemon AI"
)

// ============================================================================
// 1. PARSER INTELIGENTE DE FECHAS Y HORAS EN ESPAÑOL PARA RECORDATORIOS
// ============================================================================
object SpanishCalendarParser {

    private val monthMap = mapOf(
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4,
        "mayo" to 5, "junio" to 6, "julio" to 7, "agosto" to 8,
        "septiembre" to 9, "setiembre" to 9, "octubre" to 10,
        "noviembre" to 11, "diciembre" to 12
    )

    private val dayOfWeekMap = mapOf(
        "lunes" to DayOfWeek.MONDAY,
        "martes" to DayOfWeek.TUESDAY,
        "miercoles" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY,
        "viernes" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY
    )

    fun isCalendarReminderRequest(normalized: String): Boolean {
        val hasTriggerVerb = normalized.startsWith("recuerdame ") ||
            normalized.startsWith("agenda ") ||
            normalized.startsWith("agendar ") ||
            normalized.startsWith("programa ") ||
            normalized.contains("agrega al calendario") ||
            normalized.contains("agregar al calendario") ||
            normalized.contains("pon en mi calendario") ||
            normalized.contains("pon un recordatorio") ||
            normalized.contains("crea un recordatorio") ||
            normalized.contains("crear recordatorio")

        if (hasTriggerVerb) return true

        // Si empieza con "recuerda " pero incluye una referencia temporal ("mañana", "el viernes", "a las 5")
        if (normalized.startsWith("recuerda ")) {
            val hasTimeCue = normalized.contains(" manana") ||
                normalized.contains(" hoy") ||
                normalized.contains(" a las ") ||
                normalized.contains(" en ") && (normalized.contains(" minutos") || normalized.contains(" horas")) ||
                dayOfWeekMap.keys.any { normalized.contains(" el $it") || normalized.contains(" este $it") }
            return hasTimeCue
        }
        return false
    }

    fun parseEvent(rawInput: String, normalized: String): CalendarEventItem {
        val now = LocalDateTime.now()
        var targetDate: LocalDate = now.toLocalDate()
        var targetHour = 9
        var targetMinute = 0
        var timeExplicitlyFound = false

        // 1. Detectar tiempos relativos ("en 20 minutos", "en 2 horas")
        val inMinutesMatch = Regex("""\ben\s+(\d{1,3})\s+minutos?\b""").find(normalized)
        val inHoursMatch = Regex("""\ben\s+(\d{1,2})\s+horas?\b""").find(normalized)

        if (inMinutesMatch != null) {
            val mins = inMinutesMatch.groupValues[1].toLongOrNull() ?: 15L
            val future = now.plusMinutes(mins)
            targetDate = future.toLocalDate()
            targetHour = future.hour
            targetMinute = future.minute
            timeExplicitlyFound = true
        } else if (inHoursMatch != null) {
            val hrs = inHoursMatch.groupValues[1].toLongOrNull() ?: 1L
            val future = now.plusHours(hrs)
            targetDate = future.toLocalDate()
            targetHour = future.hour
            targetMinute = future.minute
            timeExplicitlyFound = true
        } else {
            // 2. Detectar día ("pasado mañana", "mañana", "hoy")
            when {
                normalized.contains("pasado manana") -> targetDate = now.toLocalDate().plusDays(2)
                normalized.contains("manana") && !normalized.contains("de la manana") -> targetDate = now.toLocalDate().plusDays(1)
                normalized.contains("hoy") -> targetDate = now.toLocalDate()
            }

            // 3. Detectar día de la semana ("el viernes", "este lunes", "proximo martes")
            for ((dayName, dayEnum) in dayOfWeekMap) {
                if (Regex("""\b(?:el|este|proximo)?\s*$dayName\b""").containsMatchIn(normalized)) {
                    targetDate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(dayEnum))
                    if (targetDate == now.toLocalDate() && now.hour >= 20) {
                        targetDate = now.toLocalDate().with(TemporalAdjusters.next(dayEnum))
                    }
                    break
                }
            }

            // 4. Detectar fecha explícita ("el 15 de noviembre", "3 de diciembre")
            val explicitDateMatch = Regex("""\b(\d{1,2})\s+de\s+([a-z]+)(?:\s+de\s+(\d{4}))?\b""").find(normalized)
            if (explicitDateMatch != null) {
                val dayNum = explicitDateMatch.groupValues[1].toIntOrNull() ?: 1
                val monthName = explicitDateMatch.groupValues[2]
                val yearNum = explicitDateMatch.groupValues[3].toIntOrNull() ?: now.year
                val monthNum = monthMap[monthName]
                if (monthNum != null) {
                    try {
                        var candidate = LocalDate.of(yearNum, monthNum, dayNum.coerceIn(1, 28))
                        try { candidate = LocalDate.of(yearNum, monthNum, dayNum) } catch (e: Exception) {}
                        if (candidate.isBefore(now.toLocalDate()) && explicitDateMatch.groupValues[3].isBlank()) {
                            candidate = candidate.plusYears(1)
                        }
                        targetDate = candidate
                    } catch (e: Exception) {}
                }
            }

            // 5. Detectar hora ("a las 5:30 pm", "a las 6 de la tarde", "a las 14:00", "a la 1 pm")
            val timeRegex = Regex("""\ba\s+las?\s+(\d{1,2})(?:[:\s](\d{2}))?\s*(am|pm|de\s+la\s+manana|de\s+la\s+tarde|de\s+la\s+noche|hrs|horas)?\b""")
            val timeMatch = timeRegex.find(normalized)
            if (timeMatch != null) {
                var hr = timeMatch.groupValues[1].toIntOrNull() ?: 9
                val mn = timeMatch.groupValues[2].toIntOrNull() ?: 0
                val meridiem = timeMatch.groupValues[3].trim()

                if (meridiem == "pm" || meridiem.contains("tarde") || meridiem.contains("noche")) {
                    if (hr in 1..11) hr += 12
                } else if (meridiem == "am" || meridiem.contains("manana")) {
                    if (hr == 12) hr = 0
                } else if (hr in 1..6) {
                    // Si dice "a las 5" sin especificar am/pm, suele ser 5:00 PM
                    hr += 12
                }
                targetHour = hr.coerceIn(0, 23)
                targetMinute = mn.coerceIn(0, 59)
                timeExplicitlyFound = true
            }
        }

        if (!timeExplicitlyFound && targetDate == now.toLocalDate()) {
            // Si es para hoy y no dijo hora, programarlo 1 hora después de la hora actual
            val plusOne = now.plusHours(1)
            targetDate = plusOne.toLocalDate()
            targetHour = plusOne.hour
            targetMinute = 0
        }

        val startDt = LocalDateTime.of(targetDate, LocalTime.of(targetHour, targetMinute, 0))
        val endDt = startDt.plusHours(1)

        // Extraer el título limpio del evento quitando las palabras de comando y fecha
        var cleanTitle = rawInput.trim()
            .replace(Regex("""^(?:daemon\s+|oye\s+daemon\s+|hola\s+daemon\s+)?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:recuerdame|recuérdame|recuerda|agenda|agendar|programa|agrega\s+al\s+calendario|agregar\s+al\s+calendario|pon\s+en\s+mi\s+calendario|pon\s+un\s+recordatorio|crea\s+un\s+recordatorio)\s+(?:que\s+|para\s+)?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:pasado\s+manana|pasado\s+mañana|manana|mañana|hoy)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:el|este|proximo|próximo)\s+(?:lunes|martes|miercoles|miércoles|jueves|viernes|sabado|sábado|domingo)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b\d{1,2}\s+de\s+[a-zA-Z]+(?:\s+de\s+\d{4})?\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\ba\s+las?\s+\d{1,2}(?:[:\s]\d{2})?\s*(?:am|pm|de\s+la\s+manana|de\s+la\s+mañana|de\s+la\s+tarde|de\s+la\s+noche|hrs|horas)?\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\ben\s+\d{1,3}\s+(?:minutos?|horas?)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (cleanTitle.isBlank()) cleanTitle = "Recordatorio de Daemon AI"
        cleanTitle = cleanTitle.replaceFirstChar { it.uppercase() }

        val zone = ZoneId.systemDefault()
        val startMillis = startDt.atZone(zone).toInstant().toEpochMilli()
        val endMillis = endDt.atZone(zone).toInstant().toEpochMilli()
        val fmt = startDt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

        return CalendarEventItem(
            id = "evt_${startMillis}_${abs(cleanTitle.hashCode() % 10000)}",
            title = cleanTitle,
            startEpochMillis = startMillis,
            endEpochMillis = endMillis,
            formattedDate = fmt
        )
    }
}

// ============================================================================
// 2. SERVICIO DE BÓVEDA EN LA NUBE + SINCRONIZACIÓN DE CALENDARIO MULTI-OS
// ============================================================================
@Service
class CloudVaultService {

    private val vaultCacheFile = File("daemon_cloud_vault.json")
    private val otaManifestFile = File("daemon_ota_manifest.json")
    private val localSessionFile = File(System.getProperty("user.home"), ".daemon_session.json")

    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    var otaManifest = OtaManifest()
    var localSession = LocalMachineSession()
    var vault = CloudVaultState()

    private val discoveredPeers = CopyOnWriteArraySet<String>()
    private val udpDiscoveryPort = 19840

    @Volatile var cloudSyncStatus: String = "LIVE SYNC ☁"
    @Volatile var otaStatusText: String = "v13.0.0 LIVE"
    var onSessionOrVaultChanged: (() -> Unit)? = null
    var onDueCalendarAlarm: ((CalendarEventItem) -> Unit)? = null

    val isMac = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
    val isWindows = System.getProperty("os.name").lowercase().contains("win")

    init {
        loadOrDetectOtaManifest()
        loadPrivateMachineSession()
        loadOrInitializeVault()
        startLanPeerToPeerSyncServer()
        startAutomaticOtaAndCloudDaemon()
        startCalendarAlarmAndCrossDeviceSync()
    }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.trim().toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ========================================================================
    // MOTOR AUTOMÁTICO GOOGLE CALENDAR & APPLE CALENDAR (iCLOUD)
    // ========================================================================
    fun scheduleEventInConnectedCalendars(rawInput: String, normalized: String): String {
        val event = SpanishCalendarParser.parseEvent(rawInput, normalized)
        val acc = getCurrentAccount()

        val provider = acc.calendarProvider.uppercase()
        val syncResults = mutableListOf<String>()

        // 1. CONEXIÓN CON APPLE CALENDAR / iCLOUD
        if (provider == "APPLE" || provider == "BOTH") {
            if (isMac) {
                val okApple = insertIntoMacAppleCalendar(event, acc.appleAccountEmail)
                if (okApple) {
                    event.syncedToApple = true
                    syncResults.add("Apple Calendar (iCloud)")
                }
            } else {
                // Si está en Windows, se marca pendiente para que la Mac lo inyecte en iCloud automáticamente al sincronizar
                syncResults.add("Cola iCloud (Se inyectará en tu Mac automáticamente)")
            }
        }

        // 2. CONEXIÓN CON GOOGLE CALENDAR
        if (provider == "GOOGLE" || provider == "BOTH") {
            val okGoogle = insertIntoGoogleCalendar(event, acc)
            if (okGoogle) {
                event.syncedToGoogle = true
                syncResults.add("Google Calendar (${acc.googleAccountEmail.ifBlank { "Cuenta Activa" }})")
            }
        }

        // Guardar en la bóveda en la nube de la cuenta activa
        acc.calendarEvents.removeIf { it.id == event.id }
        acc.calendarEvents.add(event)
        acc.updatedAt = System.currentTimeMillis()
        pushVaultEverywhereAsync("feat(calendar): nuevo recordatorio ${event.title}")
        onSessionOrVaultChanged?.invoke()

        val channelsText = if (syncResults.isEmpty()) "Alarma Interna de Daemon" else syncResults.joinToString(" + ")
        return "📅 Recordatorio agendado para el ${event.formattedDate}: '${event.title}'. Sincronizado automáticamente con: $channelsText."
    }

    // Inserta el evento de forma 100% silenciosa en Apple Calendar (macOS Calendar.app -> iCloud / Google)
    private fun insertIntoMacAppleCalendar(event: CalendarEventItem, preferredEmail: String): Boolean {
        if (!isMac) return false
        return try {
            val nowMillis = System.currentTimeMillis()
            val secondsFromNow = ((event.startEpochMillis - nowMillis) / 1000L).toInt()
            val safeTitle = event.title.replace("\"", "'").replace("\\", "")

            // Usa aritmética de segundos sobre (current date) para no fallar jamás por el idioma de macOS
            val appleScript = """
                tell application "Calendar"
                    set targetCal to missing value
                    repeat with c in calendars
                        if writable of c is true then
                            set targetCal to c
                            exit repeat
                        end if
                    end repeat
                    if targetCal is not missing value then
                        set startD to (current date) + ($secondsFromNow)
                        set endD to startD + 3600
                        tell targetCal
                            set newEv to make new event at end with properties {summary:"$safeTitle", start date:startD, end date:endD, description:"Agendado automáticamente por Daemon AI"}
                            make new display alarm at end of display alarms of newEv with properties {trigger interval:-10}
                        end tell
                        return "OK"
                    end if
                end tell
            """.trimIndent()

            val proc = ProcessBuilder("osascript", "-e", appleScript).start()
            val out = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
            proc.waitFor()
            out.contains("OK")
        } catch (e: Exception) {
            false
        }
    }

    // Inserta el evento en Google Calendar (Vía Puente Silencioso en 2do plano, Token OAuth, Mac Calendar o Auto-Web)
    private fun insertIntoGoogleCalendar(event: CalendarEventItem, acc: UserAccount): Boolean {
        val bridgeOrToken = acc.googleWebhookOrToken.trim()
        val startIso = Instant.ofEpochMilli(event.startEpochMillis).atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
        val endIso = Instant.ofEpochMilli(event.endEpochMillis).atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))

        // Vía A: Si el usuario conectó su Puente Google Apps Script (100% silencioso en la nube sin abrir navegador)
        if (bridgeOrToken.startsWith("https://script.google.com/")) {
            try {
                val payload = GoogleCalendarWebhookPayload(
                    title = event.title,
                    startIso = startIso,
                    endIso = endIso,
                    email = acc.googleAccountEmail
                )
                val bodyJson = objectMapper.writeValueAsString(payload)
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(bridgeOrToken))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build()
                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() in 200..399) return true
            } catch (e: Exception) {}
        }

        // Vía B: Si el usuario puso un OAuth Access Token directo de Google Calendar API
        if (bridgeOrToken.startsWith("ya29.")) {
            try {
                val apiJson = """{"summary":"${event.title.replace("\"", "'")}","start":{"dateTime":"$startIso"},"end":{"dateTime":"$endIso"}}"""
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/primary/events"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer $bridgeOrToken")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(apiJson, StandardCharsets.UTF_8))
                    .build()
                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() in 200..299) return true
            } catch (e: Exception) {}
        }

        // Vía C: Si está en macOS y tiene su cuenta de Google vinculada en Calendar.app, inyectarlo en silencio
        if (isMac && insertIntoMacAppleCalendar(event, acc.googleAccountEmail)) {
            return true
        }

        // Vía D: Inserción automática vía sesión activa de Google Calendar en el navegador
        return try {
            val gcalFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            val d1 = Instant.ofEpochMilli(event.startEpochMillis).atZone(ZoneOffset.UTC).format(gcalFmt)
            val d2 = Instant.ofEpochMilli(event.endEpochMillis).atZone(ZoneOffset.UTC).format(gcalFmt)
            val encTitle = URLEncoder.encode(event.title, StandardCharsets.UTF_8)
            val encDetails = URLEncoder.encode("Recordatorio creado automáticamente por Daemon AI", StandardCharsets.UTF_8)
            val authUserParam = if (acc.googleAccountEmail.isNotBlank()) "&authuser=${URLEncoder.encode(acc.googleAccountEmail, StandardCharsets.UTF_8)}" else ""
            val gcalUrl = "https://calendar.google.com/calendar/render?action=TEMPLATE&text=$encTitle&dates=$d1/$d2&details=$encDetails$authUserParam"

            if (isWindows) {
                ProcessBuilder("cmd", "/c", "start", "", gcalUrl).start()
            } else if (isMac) {
                ProcessBuilder("open", gcalUrl).start()
            } else {
                ProcessBuilder("xdg-open", gcalUrl).start()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // Hilo que dispara las alarmas por voz en la fecha/hora exacta y sincroniza eventos pendientes de Windows en Mac
    private fun startCalendarAlarmAndCrossDeviceSync() {
        thread(isDaemon = true, name = "Daemon-Calendar-Guardian") {
            Thread.sleep(4000)
            while (true) {
                try {
                    val acc = getCurrentAccount()
                    val now = System.currentTimeMillis()
                    var vaultModified = false

                    for (ev in acc.calendarEvents) {
                        // Si estamos en Mac y hay un evento creado en Windows que aún no se inyecta en Apple Calendar:
                        if (isMac && !ev.syncedToApple && (acc.calendarProvider == "APPLE" || acc.calendarProvider == "BOTH")) {
                            if (insertIntoMacAppleCalendar(ev, acc.appleAccountEmail)) {
                                ev.syncedToApple = true
                                vaultModified = true
                            }
                        }

                        // Si llegó la hora del evento (dentro de una ventana de 2 minutos), avisar por voz
                        if (!ev.voiceAlertTriggered && now >= ev.startEpochMillis && (now - ev.startEpochMillis) <= 180000L) {
                            ev.voiceAlertTriggered = true
                            vaultModified = true
                            onDueCalendarAlarm?.invoke(ev)
                        }
                    }

                    if (vaultModified) {
                        acc.updatedAt = System.currentTimeMillis()
                        saveLocalVaultCache()
                        pushToAllPeerDevicesAsync()
                    }
                } catch (e: Exception) {}
                Thread.sleep(15000)
            }
        }
    }

    fun configureUserCalendarSettings(
        provider: String,
        googleEmail: String,
        googleWebhookOrToken: String,
        appleEmail: String
    ): String {
        val acc = getCurrentAccount()
        acc.calendarProvider = provider.uppercase()
        acc.googleAccountEmail = googleEmail.trim()
        acc.googleWebhookOrToken = googleWebhookOrToken.trim()
        acc.appleAccountEmail = appleEmail.trim()
        acc.updatedAt = System.currentTimeMillis()

        pushVaultEverywhereAsync("feat(calendar): configuracion de calendario de ${acc.username}")
        onSessionOrVaultChanged?.invoke()

        return "Calendario de '${acc.username.uppercase()}' conectado en modo [${acc.calendarProvider}]. Google: ${acc.googleAccountEmail.ifBlank { "No asignado" }} | Apple: ${acc.appleAccountEmail.ifBlank { "Nativo iCloud" }}."
    }

    fun getUpcomingEventsReport(): String {
        val acc = getCurrentAccount()
        if (acc.calendarEvents.isEmpty()) {
            return "No tienes recordatorios programados en tu cuenta (${acc.username.uppercase()}). Dime por ejemplo: 'Recuérdame entregar el proyecto mañana a las 5 pm'."
        }
        val sorted = acc.calendarEvents.sortedBy { it.startEpochMillis }.takeLast(8)
        val lines = sorted.joinToString("\n") { ev ->
            val gBadge = if (ev.syncedToGoogle) "[G✓]" else ""
            val aBadge = if (ev.syncedToApple) "[✓]" else ""
            "• ${ev.formattedDate} -> ${ev.title} $gBadge$aBadge"
        }
        return "📅 Recordatorios en la cuenta de ${acc.username.uppercase()} (${acc.calendarProvider}):\n$lines"
    }

    // ========================================================================
    // GESTIÓN DE BÓVEDA, RED P2P Y OTA AUTO-UPDATER
    // ========================================================================
    private fun loadOrDetectOtaManifest() {
        if (otaManifestFile.exists()) {
            try {
                otaManifest = objectMapper.readValue(otaManifestFile, OtaManifest::class.java)
            } catch (e: Exception) {}
        }
        if (otaManifest.githubRawBaseUrl.isBlank()) {
            val detectedRaw = detectGitHubRawBaseUrl()
            if (detectedRaw != null) {
                otaManifest.githubRawBaseUrl = detectedRaw
                saveOtaManifest()
            }
        }
    }

    private fun detectGitHubRawBaseUrl(): String? {
        return try {
            val gitConfig = sequenceOf(File(".git/config"), File("../.git/config")).firstOrNull { it.exists() } ?: return null
            val content = gitConfig.readText(StandardCharsets.UTF_8)
            val m = Regex("""github\.com[:/]([^/\s]+)/([^/\s\.]+?)(?:\.git)?\s""").find(content + " ") ?: return null
            val owner = m.groupValues[1].trim()
            val repo = m.groupValues[2].trim()
            "https://raw.githubusercontent.com/$owner/$repo/main"
        } catch (e: Exception) {
            null
        }
    }

    fun saveOtaManifest() {
        try {
            objectMapper.writeValue(otaManifestFile, otaManifest)
        } catch (e: Exception) {}
    }

    private fun loadPrivateMachineSession() {
        if (localSessionFile.exists()) {
            try {
                localSession = objectMapper.readValue(localSessionFile, LocalMachineSession::class.java)
            } catch (e: Exception) {}
        } else {
            localSession = LocalMachineSession(
                loggedUsername = "invitado",
                loggedPasswordHash = sha256("invitado"),
                installedAppVersion = otaManifest.appVersion
            )
            savePrivateMachineSession()
        }
    }

    fun savePrivateMachineSession() {
        try {
            objectMapper.writeValue(localSessionFile, localSession)
        } catch (e: Exception) {}
    }

    @Synchronized
    private fun reloadVaultFromDiskIfNewer() {
        if (!vaultCacheFile.exists()) return
        try {
            val diskVault = objectMapper.readValue(vaultCacheFile, CloudVaultState::class.java)
            if (diskVault.accounts.size != vault.accounts.size || diskVault.lastUpdated > vault.lastUpdated) {
                mergeCloudVault(diskVault, saveToDisk = false)
            }
        } catch (e: Exception) {}
    }

    private fun loadOrInitializeVault() {
        if (vaultCacheFile.exists()) {
            try {
                vault = objectMapper.readValue(vaultCacheFile, CloudVaultState::class.java)
            } catch (e: Exception) {}
        }

        if (vault.globalKnowledge.isEmpty()) {
            vault.globalKnowledge.addAll(buildMasterGlobalKnowledge())
        }

        val adminHash = sha256("admin2026")
        val existingAdmin = vault.accounts.firstOrNull { it.username.equals("cristian", true) }
        if (existingAdmin == null) {
            vault.accounts.add(
                UserAccount(
                    username = "cristian",
                    passwordHash = adminHash,
                    role = "ADMIN",
                    calendarProvider = "BOTH",
                    personalFacts = mutableListOf(
                        "Es Cristian Alexis, creador y Administrador Maestro de Daemon.",
                        "Estudia Ingeniería en Sistemas Computacionales en la ESCOM del IPN."
                    ),
                    personalKnowledge = mutableListOf(),
                    personalModes = buildDefaultModesForUser("Cristian"),
                    calendarEvents = mutableListOf(),
                    recentHistory = mutableListOf(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            existingAdmin.role = "ADMIN"
            if (existingAdmin.passwordHash.isBlank()) existingAdmin.passwordHash = adminHash
        }

        if (vault.accounts.none { it.username.equals("invitado", true) }) {
            vault.accounts.add(
                UserAccount(
                    username = "invitado",
                    passwordHash = sha256("invitado"),
                    role = "USER",
                    calendarProvider = "BOTH",
                    personalFacts = mutableListOf("Sesión estándar de usuario en Daemon."),
                    personalKnowledge = mutableListOf(),
                    personalModes = buildDefaultModesForUser("Usuario"),
                    calendarEvents = mutableListOf(),
                    recentHistory = mutableListOf(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        vault.accounts.forEach { acc ->
            if (!acc.username.equals("cristian", true)) acc.role = "USER"
        }

        saveLocalVaultCache()
    }

    fun saveLocalVaultCache() {
        try {
            vault.lastUpdated = System.currentTimeMillis()
            objectMapper.writeValue(vaultCacheFile, vault)
        } catch (e: Exception) {}
    }

    private fun startLanPeerToPeerSyncServer() {
        thread(isDaemon = true, name = "Daemon-UDP-Peer-Listener") {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(udpDiscoveryPort))
                val buffer = ByteArray(512)
                val localIps = getLocalIpAddresses()

                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                    val senderIp = packet.address.hostAddress
                    if (msg.startsWith("DAEMON_SYNC") && senderIp !in localIps) {
                        val peerBaseUrl = "http://$senderIp:8080"
                        discoveredPeers.add(peerBaseUrl)
                        pullFromPeerDevice(peerBaseUrl)
                    }
                }
            } catch (e: Exception) {}
        }

        thread(isDaemon = true, name = "Daemon-UDP-Broadcaster") {
            Thread.sleep(1500)
            while (true) {
                broadcastSyncPingToLan()
                for (peer in discoveredPeers) {
                    pullFromPeerDevice(peer)
                }
                Thread.sleep(6000)
            }
        }
    }

    private fun getLocalIpAddresses(): Set<String> {
        val ips = mutableSetOf("127.0.0.1", "0:0:0:0:0:0:0:1")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    ips.add(addrs.nextElement().hostAddress)
                }
            }
        } catch (e: Exception) {}
        return ips
    }

    private fun broadcastSyncPingToLan() {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val data = "DAEMON_SYNC:${vault.lastUpdated}".toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), udpDiscoveryPort))
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    for (ifaceAddr in iface.interfaceAddresses) {
                        val broadcast = ifaceAddr.broadcast ?: continue
                        try {
                            socket.send(DatagramPacket(data, data.size, broadcast, udpDiscoveryPort))
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun pullFromPeerDevice(peerBaseUrl: String): Boolean {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$peerBaseUrl/api/daemon/cloud-vault"))
                .timeout(Duration.ofSeconds(2))
                .GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200 && res.body().contains("accounts")) {
                val peerVault = objectMapper.readValue(res.body(), CloudVaultState::class.java)
                val changed = mergeCloudVault(peerVault)
                if (changed) {
                    cloudSyncStatus = "P2P & CLOUD SYNC ☁"
                    onSessionOrVaultChanged?.invoke()
                }
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private fun pushToAllPeerDevicesAsync() {
        broadcastSyncPingToLan()
        if (discoveredPeers.isEmpty()) return
        thread(isDaemon = true) {
            val json = objectMapper.writeValueAsString(vault)
            for (peer in discoveredPeers) {
                try {
                    val req = HttpRequest.newBuilder()
                        .uri(URI.create("$peer/api/daemon/cloud-vault"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build()
                    httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                } catch (e: Exception) {}
            }
        }
    }

    private fun startAutomaticOtaAndCloudDaemon() {
        thread(isDaemon = true, name = "Daemon-OTA-AutoUpdater") {
            Thread.sleep(2000)
            syncAllChannelsNow(silent = true)
            while (true) {
                Thread.sleep(20000)
                syncAllChannelsNow(silent = true)
            }
        }
    }

    fun syncAllChannelsNow(silent: Boolean = false): String {
        reloadVaultFromDiskIfNewer()
        broadcastSyncPingToLan()
        for (peer in discoveredPeers) {
            pullFromPeerDevice(peer)
        }
        pullFromRealtimeCloudDb()
        pullLatestVaultFromGitAndGitHub()
        checkOtaBinaryUpdate()

        onSessionOrVaultChanged?.invoke()
        return "Sincronización completada: ${vault.accounts.size} cuentas activas."
    }

    private fun pullLatestVaultFromGitAndGitHub() {
        try {
            val workingDir = File(System.getProperty("user.dir"))
            if (File(workingDir, ".git").exists() || File(workingDir.parentFile, ".git").exists()) {
                val fetchProc = ProcessBuilder("git", "fetch", "origin", "main").directory(workingDir).start()
                fetchProc.waitFor()
                val showProc = ProcessBuilder("git", "show", "origin/main:daemon_cloud_vault.json").directory(workingDir).start()
                val gitJson = showProc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                showProc.waitFor()
                if (gitJson.startsWith("{") && gitJson.contains("accounts")) {
                    val gitVault = objectMapper.readValue(gitJson, CloudVaultState::class.java)
                    mergeCloudVault(gitVault)
                    cloudSyncStatus = "GIT CLOUD SYNC ☁"
                }
            }
        } catch (e: Exception) {}

        val rawBase = otaManifest.githubRawBaseUrl.trimEnd('/')
        if (rawBase.isNotBlank()) {
            try {
                val apiOrRawUrl = "$rawBase/daemon_cloud_vault.json?nocache=${System.currentTimeMillis()}"
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(apiOrRawUrl))
                    .timeout(Duration.ofSeconds(4))
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .GET().build()
                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() == 200 && res.body().contains("accounts")) {
                    val remoteVault = objectMapper.readValue(res.body(), CloudVaultState::class.java)
                    mergeCloudVault(remoteVault)
                    cloudSyncStatus = "CLOUD SYNCED ☁"
                }
            } catch (e: Exception) {}
        }
    }

    fun pushVaultEverywhereAsync(commitReason: String = "sync: actualizacion de cuenta en la nube") {
        saveLocalVaultCache()
        pushToAllPeerDevicesAsync()
        pushToRealtimeCloudDbAsync()

        thread(isDaemon = true) {
            try {
                val workingDir = File(System.getProperty("user.dir"))
                if (File(workingDir, ".git").exists() || File(workingDir.parentFile, ".git").exists()) {
                    ProcessBuilder("git", "add", "daemon_cloud_vault.json").directory(workingDir).start().waitFor()
                    ProcessBuilder("git", "commit", "-m", commitReason).directory(workingDir).start().waitFor()
                    ProcessBuilder("git", "push", "origin", "main").directory(workingDir).start().waitFor()
                }
            } catch (e: Exception) {}
        }
    }

    private fun checkOtaBinaryUpdate() {
        val rawBase = otaManifest.githubRawBaseUrl.trimEnd('/')
        if (rawBase.isBlank()) return
        try {
            val manifestUrl = "$rawBase/daemon_ota_manifest.json?nocache=${System.currentTimeMillis()}"
            val req = HttpRequest.newBuilder()
                .uri(URI.create(manifestUrl))
                .timeout(Duration.ofSeconds(4))
                .header("Cache-Control", "no-cache")
                .GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200 && res.body().contains("appVersion")) {
                val remoteManifest = objectMapper.readValue(res.body(), OtaManifest::class.java)
                if (remoteManifest.sharedCloudDbUrl.isNotBlank() && remoteManifest.sharedCloudDbUrl != otaManifest.sharedCloudDbUrl) {
                    otaManifest.sharedCloudDbUrl = remoteManifest.sharedCloudDbUrl
                    saveOtaManifest()
                    pullFromRealtimeCloudDb()
                }
                if (remoteManifest.appVersion > otaManifest.appVersion) {
                    otaStatusText = "⬇ ACTUALIZANDO A v${remoteManifest.versionName}..."
                    onSessionOrVaultChanged?.invoke()
                    downloadAndApplyNewJarUpdate(rawBase, remoteManifest)
                } else {
                    otaStatusText = "v${otaManifest.versionName} AL DÍA ✓"
                }
            }
        } catch (e: Exception) {}
    }

    private fun downloadAndApplyNewJarUpdate(rawBase: String, remoteManifest: OtaManifest): Boolean {
        return try {
            val jarUrl = "$rawBase/${remoteManifest.jarRelativePath.trimStart('/')}?t=${System.currentTimeMillis()}"
            val workingDir = File(System.getProperty("user.dir"))
            val currentJar = File(workingDir, remoteManifest.jarRelativePath).let {
                if (it.exists()) it else File(workingDir, "daemon.jar")
            }
            val tempJar = File(workingDir, "daemon_update_temp.jar")

            val req = HttpRequest.newBuilder().uri(URI.create(jarUrl)).timeout(Duration.ofSeconds(60)).GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())

            if (res.statusCode() == 200 && res.body().size > 100000) {
                tempJar.writeBytes(res.body())
                otaManifest = remoteManifest
                saveOtaManifest()
                localSession.installedAppVersion = remoteManifest.appVersion
                savePrivateMachineSession()

                val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + (if (isWindows) "javaw.exe" else "java")

                if (isWindows) {
                    val updaterBat = File(workingDir, "daemon_swapper.bat")
                    val batContent = """
                        @echo off
                        timeout /t 2 /nobreak >nul
                        move /y "${tempJar.absolutePath}" "${currentJar.absolutePath}" >nul
                        start "" "$javaBin" -Xms32m -Xmx128m -XX:+UseSerialGC -jar "${currentJar.absolutePath}"
                        del "%~f0"
                    """.trimIndent()
                    updaterBat.writeText(batContent, StandardCharsets.ISO_8859_1)

                    val vbsSwapper = File(workingDir, "run_swapper.vbs")
                    vbsSwapper.writeText(
                        "CreateObject(\"WScript.Shell\").Run \"\"\"${updaterBat.absolutePath}\"\"\", 0, False",
                        StandardCharsets.ISO_8859_1
                    )
                    ProcessBuilder("wscript.exe", vbsSwapper.absolutePath).directory(workingDir).start()
                } else {
                    val updaterSh = File(workingDir, "daemon_swapper.sh")
                    val shContent = """
                        #!/bin/bash
                        sleep 2
                        mv -f "${tempJar.absolutePath}" "${currentJar.absolutePath}"
                        "$javaBin" -Xms32m -Xmx128m -XX:+UseSerialGC -jar "${currentJar.absolutePath}" &
                        rm -- "$0"
                    """.trimIndent()
                    updaterSh.writeText(shContent, StandardCharsets.UTF_8)
                    updaterSh.setExecutable(true)
                    ProcessBuilder("/bin/bash", updaterSh.absolutePath).directory(workingDir).start()
                }

                Thread.sleep(300)
                System.exit(0)
                true
            } else {
                otaManifest = remoteManifest
                saveOtaManifest()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveRealtimeCloudUrl(): String? {
        val raw = otaManifest.sharedCloudDbUrl.trim()
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

    private fun pushToRealtimeCloudDbAsync() {
        val url = resolveRealtimeCloudUrl() ?: return
        thread(isDaemon = true) {
            try {
                val json = objectMapper.writeValueAsString(vault)
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build()
                val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() in 200..299) {
                    cloudSyncStatus = "LIVE CLOUD ☁"
                }
            } catch (e: Exception) {}
        }
    }

    fun pullFromRealtimeCloudDb(): Boolean {
        val url = resolveRealtimeCloudUrl() ?: return false
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$url?nocache=${System.currentTimeMillis()}"))
                .timeout(Duration.ofSeconds(4))
                .header("Cache-Control", "no-cache")
                .GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200 && res.body().isNotBlank() && res.body().trim() != "null") {
                val remoteVault = objectMapper.readValue(res.body(), CloudVaultState::class.java)
                mergeCloudVault(remoteVault)
                cloudSyncStatus = "LIVE CLOUD ☁"
                true
            } else {
                pushToRealtimeCloudDbAsync()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun mergeCloudVault(remote: CloudVaultState, saveToDisk: Boolean = true): Boolean {
        var changed = false

        for (rNode in remote.globalKnowledge) {
            val idx = vault.globalKnowledge.indexOfFirst { it.tag.equals(rNode.tag, true) }
            if (idx < 0) {
                vault.globalKnowledge.add(rNode)
                changed = true
            } else if (remote.lastUpdated > vault.lastUpdated) {
                vault.globalKnowledge[idx] = rNode
                changed = true
            }
        }

        for (rAcc in remote.accounts) {
            val localIdx = vault.accounts.indexOfFirst { it.username.equals(rAcc.username, true) }
            if (localIdx < 0) {
                rAcc.role = if (rAcc.username.equals("cristian", true)) "ADMIN" else "USER"
                vault.accounts.add(rAcc)
                changed = true
            } else {
                val localAcc = vault.accounts[localIdx]
                val mergedFacts = (localAcc.personalFacts + rAcc.personalFacts).distinct().toMutableList()
                val mergedKnow = (localAcc.personalKnowledge + rAcc.personalKnowledge).distinctBy { it.tag }.toMutableList()
                val mergedEvents = (localAcc.calendarEvents + rAcc.calendarEvents).distinctBy { it.id }.toMutableList()
                val mergedModes = if (rAcc.updatedAt > localAcc.updatedAt && rAcc.personalModes.isNotEmpty()) rAcc.personalModes else localAcc.personalModes
                val newPassHash = if (rAcc.updatedAt >= localAcc.updatedAt && rAcc.passwordHash.isNotBlank()) rAcc.passwordHash else localAcc.passwordHash

                if (mergedFacts.size != localAcc.personalFacts.size ||
                    mergedKnow.size != localAcc.personalKnowledge.size ||
                    mergedEvents.size != localAcc.calendarEvents.size ||
                    newPassHash != localAcc.passwordHash
                ) {
                    changed = true
                }

                vault.accounts[localIdx] = localAcc.copy(
                    passwordHash = newPassHash,
                    role = if (rAcc.username.equals("cristian", true)) "ADMIN" else "USER",
                    calendarProvider = if (rAcc.updatedAt >= localAcc.updatedAt) rAcc.calendarProvider else localAcc.calendarProvider,
                    googleAccountEmail = if (rAcc.googleAccountEmail.isNotBlank()) rAcc.googleAccountEmail else localAcc.googleAccountEmail,
                    googleWebhookOrToken = if (rAcc.googleWebhookOrToken.isNotBlank()) rAcc.googleWebhookOrToken else localAcc.googleWebhookOrToken,
                    appleAccountEmail = if (rAcc.appleAccountEmail.isNotBlank()) rAcc.appleAccountEmail else localAcc.appleAccountEmail,
                    personalFacts = mergedFacts,
                    personalKnowledge = mergedKnow,
                    personalModes = mergedModes,
                    calendarEvents = mergedEvents,
                    updatedAt = maxOf(localAcc.updatedAt, rAcc.updatedAt)
                )
            }
        }

        if (changed && saveToDisk) {
            objectMapper.writeValue(vaultCacheFile, vault)
        }
        return changed
    }

    fun getCurrentAccount(): UserAccount {
        reloadVaultFromDiskIfNewer()
        val acc = vault.accounts.firstOrNull { it.username.equals(localSession.loggedUsername, true) }
        if (acc != null && acc.passwordHash == localSession.loggedPasswordHash) {
            return acc
        }
        return vault.accounts.firstOrNull { it.username.equals("invitado", true) } ?: vault.accounts.last()
    }

    fun isAdminLoggedIn(): Boolean {
        val acc = getCurrentAccount()
        return acc.username.equals("cristian", true) && acc.role == "ADMIN"
    }

    fun loginUser(username: String, plainPassword: String): Pair<Boolean, String> {
        syncAllChannelsNow(silent = true)

        val cleanUser = username.trim().lowercase()
        val acc = vault.accounts.firstOrNull { it.username.equals(cleanUser, true) }
            ?: return Pair(false, "No se encontró '$cleanUser'. Cuentas detectadas: ${vault.accounts.joinToString(", ") { it.username }}.")

        val inputHash = sha256(plainPassword)
        if (acc.passwordHash != inputHash && acc.passwordHash != plainPassword.trim()) {
            return Pair(false, "Clave de acceso incorrecta para '$cleanUser'.")
        }

        acc.passwordHash = inputHash
        localSession.loggedUsername = acc.username
        localSession.loggedPasswordHash = inputHash
        savePrivateMachineSession()
        onSessionOrVaultChanged?.invoke()

        val badge = if (acc.role == "ADMIN") "👑 ADMINISTRADOR MAESTRO" else "👤 CUENTA CLOUD"
        return Pair(true, "Bienvenido, ${acc.username.uppercase()} [$badge]. Recuerdos: ${acc.personalFacts.size} | Eventos: ${acc.calendarEvents.size}.")
    }

    fun logoutToGuest(): String {
        localSession.loggedUsername = "invitado"
        localSession.loggedPasswordHash = sha256("invitado")
        savePrivateMachineSession()
        onSessionOrVaultChanged?.invoke()
        return "Sesión cerrada. Modo Invitado activo (Panel de Administrador oculto)."
    }

    fun registerNewUser(username: String, plainPassword: String): Pair<Boolean, String> {
        syncAllChannelsNow(silent = true)

        val cleanUser = username.trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
        if (cleanUser.length < 3) return Pair(false, "El usuario debe tener mínimo 3 letras o números.")
        if (cleanUser == "cristian") return Pair(false, "El usuario 'cristian' está reservado para el Administrador.")
        if (vault.accounts.any { it.username.equals(cleanUser, true) }) {
            return Pair(false, "El usuario '$cleanUser' ya existe. Solo inicia sesión con su clave.")
        }
        if (plainPassword.trim().length < 3) return Pair(false, "La clave debe tener al menos 3 caracteres.")

        val passHash = sha256(plainPassword)
        val newAcc = UserAccount(
            username = cleanUser,
            passwordHash = passHash,
            role = "USER",
            calendarProvider = "BOTH",
            personalFacts = mutableListOf("Cuenta personal en la nube de $cleanUser."),
            personalKnowledge = mutableListOf(),
            personalModes = buildDefaultModesForUser(cleanUser),
            calendarEvents = mutableListOf(),
            recentHistory = mutableListOf(),
            updatedAt = System.currentTimeMillis()
        )
        vault.accounts.add(newAcc)
        localSession.loggedUsername = newAcc.username
        localSession.loggedPasswordHash = passHash
        savePrivateMachineSession()

        pushVaultEverywhereAsync("feat(cloud): nueva cuenta registrada ($cleanUser)")
        onSessionOrVaultChanged?.invoke()

        return Pair(true, "Cuenta '${newAcc.username.uppercase()}' creada y transmitida en vivo a todos tus dispositivos.")
    }

    fun addPersonalFactToCurrentUser(fact: String): String {
        val acc = getCurrentAccount()
        if (!acc.personalFacts.contains(fact)) {
            acc.personalFacts.add(fact)
            acc.updatedAt = System.currentTimeMillis()
            pushVaultEverywhereAsync("sync: nuevo recuerdo de ${acc.username}")
        }
        return "Guardado y sincronizado en la cuenta de '${acc.username.uppercase()}': '$fact'."
    }

    fun addPersonalKnowledgeToCurrentUser(tag: String, patterns: List<String>, response: String): String {
        val acc = getCurrentAccount()
        acc.personalKnowledge.removeIf { it.tag.equals(tag, true) }
        acc.personalKnowledge.add(IntentNode(tag = tag, patterns = patterns, responses = listOf(response)))
        acc.updatedAt = System.currentTimeMillis()
        pushVaultEverywhereAsync("sync: nuevo nodo de conocimiento de ${acc.username}")
        return "Conocimiento sincronizado en la cuenta de '${acc.username.uppercase()}'."
    }

    fun adminAddGlobalKnowledge(tag: String, patterns: List<String>, response: String): String {
        if (!isAdminLoggedIn()) return "⛔ Acceso denegado. Exclusivo para el Administrador (Cristian)."
        vault.globalKnowledge.removeIf { it.tag.equals(tag, true) }
        vault.globalKnowledge.add(IntentNode(tag = tag, patterns = patterns, responses = listOf(response)))
        pushVaultEverywhereAsync("feat(admin): nuevo conocimiento global $tag")
        return "👑 [ADMIN] Conocimiento global '$tag' inyectado y transmitido en vivo a todos los dispositivos."
    }

    fun adminConfigureCloudAndRepo(cloudDbUrl: String, githubRawUrl: String): String {
        if (!isAdminLoggedIn()) return "⛔ Acceso denegado."
        if (cloudDbUrl.isNotBlank()) otaManifest.sharedCloudDbUrl = cloudDbUrl.trim()
        if (githubRawUrl.isNotBlank()) otaManifest.githubRawBaseUrl = githubRawUrl.trim().trimEnd('/')
        saveOtaManifest()
        syncAllChannelsNow(silent = true)
        pushVaultEverywhereAsync("admin: actualizacion de enlaces cloud")
        return "👑 [ADMIN] Configuración sincronizada en todos tus equipos."
    }

    fun adminPublishOtaUpdateToAllDevices(): String {
        if (!isAdminLoggedIn()) return "⛔ Solo Cristian puede publicar actualizaciones globales."

        otaManifest.appVersion += 1
        val major = otaManifest.appVersion / 10
        val minor = otaManifest.appVersion % 10
        otaManifest.versionName = "13.$major.$minor"
        otaManifest.publishedAt = System.currentTimeMillis()

        saveOtaManifest()
        saveLocalVaultCache()
        pushToAllPeerDevicesAsync()
        pushToRealtimeCloudDbAsync()

        otaStatusText = "⚙ COMPILANDO UPDATE v${otaManifest.versionName}..."
        onSessionOrVaultChanged?.invoke()

        thread {
            try {
                val workingDir = File(System.getProperty("user.dir"))
                val gradleWrapper = File(workingDir, if (isWindows) "gradlew.bat" else "gradlew")
                if (gradleWrapper.exists()) {
                    val buildCmd = if (isWindows) listOf("cmd", "/c", "gradlew.bat", "bootJar") else listOf("./gradlew", "bootJar")
                    ProcessBuilder(buildCmd).directory(workingDir).start().waitFor()
                }

                val builtJar = File(workingDir, otaManifest.jarRelativePath)
                if (builtJar.exists()) {
                    builtJar.copyTo(File(workingDir, "daemon.jar"), overwrite = true)
                }

                otaStatusText = "☁ SUBIENDO UPDATE v${otaManifest.versionName}..."
                onSessionOrVaultChanged?.invoke()

                ProcessBuilder("git", "add", "daemon_ota_manifest.json", "daemon_cloud_vault.json")
                    .directory(workingDir).start().waitFor()
                ProcessBuilder("git", "add", "-f", otaManifest.jarRelativePath, "daemon.jar")
                    .directory(workingDir).start().waitFor()
                ProcessBuilder("git", "commit", "-m", "ota: release automatico v${otaManifest.versionName}")
                    .directory(workingDir).start().waitFor()
                ProcessBuilder("git", "push", "origin", "main")
                    .directory(workingDir).start().waitFor()

                otaStatusText = "PUBLICADO v${otaManifest.versionName} 🚀"
                onSessionOrVaultChanged?.invoke()
            } catch (e: Exception) {
                otaStatusText = "ERROR AL SUBIR OTA"
                onSessionOrVaultChanged?.invoke()
            }
        }

        return "👑 [DESPLIEGUE OTA v${otaManifest.versionName}] Compilando tu código nuevo y subiendo el binario actualizado a GitHub."
    }

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
            greeting = "Modo Trabajo activo, $userLabel. Enfocando Visual Studio Code, Notion, Opera GX y tu música en Mis Me Gusta."
        ),
        WorkspaceMode(
            id = "TAREA",
            name = "PROTOCOLO TAREA",
            triggers = listOf("vamos a hacer tarea", "modo tarea", "protocolo tarea", "hora de hacer tarea", "tengo tarea"),
            appsToOpen = listOf("vscode", "notion", "opera gx"),
            appsToClose = listOf("discord", "steam"),
            spotifyPlaylist = "mis me gusta",
            greeting = "Modo Tarea activo, $userLabel. Enfocando Visual Studio Code, Notion, Opera GX y reproduciendo Mis Me Gusta."
        ),
        WorkspaceMode(
            id = "ESTUDIO",
            name = "PROTOCOLO ESTUDIO",
            triggers = listOf("vamos a estudiar", "modo estudio", "protocolo estudio", "modo escuela", "modo escom"),
            appsToOpen = listOf("notion", "opera gx", "vscode"),
            appsToClose = listOf("discord", "steam"),
            spotifyPlaylist = "mis me gusta",
            greeting = "Modo Estudio activo, $userLabel. Enfocando Notion, Opera GX, Visual Studio Code y tus canciones de Mis Me Gusta."
        ),
        WorkspaceMode(
            id = "GAMING",
            name = "PROTOCOLO GAMING",
            triggers = listOf("vamos a jugar", "modo gaming", "modo juego", "protocolo gaming", "hora de jugar"),
            appsToOpen = listOf("discord"),
            appsToClose = listOf("vscode", "notion"),
            spotifyPlaylist = "mis me gusta",
            greeting = "Modo Gaming activo, $userLabel. Cerrando aplicaciones de trabajo, enfocando Discord y tu música."
        )
    )

    private fun buildMasterGlobalKnowledge(): List<IntentNode> = listOf(
        IntentNode(
            tag = "global_identidad",
            patterns = listOf("quien eres", "que es daemon", "como funcionas"),
            responses = listOf("Soy Daemon v13.0, una aplicación nativa de inteligencia artificial con integración automática de Google Calendar y Apple Calendar, creada por Cristian Alexis.")
        ),
        IntentNode(
            tag = "global_proceso_hilo",
            patterns = listOf("que es un proceso", "diferencia entre proceso e hilo", "que es un thread"),
            responses = listOf("Un proceso es un programa en ejecución con su propio espacio de memoria aislado; un hilo (thread) es una unidad ligera de ejecución dentro de ese proceso.")
        ),
        IntentNode(
            tag = "global_mutex_semaforo",
            patterns = listOf("que es un mutex", "diferencia entre mutex y semaforo"),
            responses = listOf("Un Mutex es un cerrojo binario de exclusión mutua para proteger secciones críticas, mientras que un Semáforo emplea un contador entero para sincronizar múltiples hilos o procesos.")
        )
    )
}

// ============================================================================
// 3. MOTOR MULTIPLATAFORMA + INSTALADOR DE APLICACIÓN NATIVA (CERO ESCRITORIO)
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
    @Volatile var cachedCpuLoad: Int = 3
    @Volatile var cachedRamMb: Int = 52
    @Volatile var isFullPanelVisible: Boolean = false
    @Volatile private var lastMediaActionTimestamp: Long = 0L

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    init {
        scanInstalledApplications()
        loadPlaylists()
        installAsSystemApplicationAndAutoStart()
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

    fun installAsSystemApplicationAndAutoStart(): String {
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

                    val psInstallApp = """
                        ${'$'}wsh = New-Object -ComObject WScript.Shell
                        ${'$'}desktop = [Environment]::GetFolderPath('Desktop')
                        ${'$'}programsMenu = [Environment]::GetFolderPath('Programs')
                        ${'$'}startup = [Environment]::GetFolderPath('Startup')

                        Remove-Item -Path "${'$'}desktop\Daemon AI.lnk" -Force -ErrorAction SilentlyContinue
                        Remove-Item -Path "${'$'}desktop\Daemon.lnk" -Force -ErrorAction SilentlyContinue

                        ${'$'}sApp = ${'$'}wsh.CreateShortcut("${'$'}programsMenu\Daemon AI.lnk")
                        ${'$'}sApp.TargetPath = "wscript.exe"
                        ${'$'}sApp.Arguments = '"${launcherVbs.absolutePath}"'
                        ${'$'}sApp.WorkingDirectory = "$workingDir"
                        ${'$'}sApp.IconLocation = "shell32.dll,13"
                        ${'$'}sApp.Description = "Daemon AI - Sistema Operativo J.A.R.V.I.S."
                        ${'$'}sApp.Save()

                        ${'$'}sBoot = ${'$'}wsh.CreateShortcut("${'$'}startup\Daemon AI.lnk")
                        ${'$'}sBoot.TargetPath = "wscript.exe"
                        ${'$'}sBoot.Arguments = '"${launcherVbs.absolutePath}"'
                        ${'$'}sBoot.WorkingDirectory = "$workingDir"
                        ${'$'}sBoot.IconLocation = "shell32.dll,13"
                        ${'$'}sBoot.Save()

                        Set-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "DaemonAI" -Value "wscript.exe `"${launcherVbs.absolutePath}`"" -Force
                    """.trimIndent()
                    runPowerShell(psInstallApp, wait = false)
                    "Aplicación registrada en el Menú Inicio y Auto-Arranque de Windows."
                }
                isMac -> {
                    val home = System.getProperty("user.home")
                    val javaBin = System.getProperty("java.home") + "/bin/java"

                    File("$home/Desktop/Daemon AI.command").delete()
                    File("$home/Desktop/Daemon_Mac.command").delete()

                    val sysApps = File("/Applications")
                    val targetAppsDir = if (sysApps.exists() && sysApps.canWrite()) sysApps else File("$home/Applications").apply { mkdirs() }
                    val appBundle = File(targetAppsDir, "Daemon AI.app")
                    val contentsDir = File(appBundle, "Contents")
                    val macOsDir = File(contentsDir, "MacOS")
                    macOsDir.mkdirs()

                    val infoPlist = File(contentsDir, "Info.plist")
                    val infoPlistXml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                        <dict>
                            <key>CFBundleExecutable</key>
                            <string>DaemonAI</string>
                            <key>CFBundleIdentifier</key>
                            <string>com.cristian.daemon.ai</string>
                            <key>CFBundleName</key>
                            <string>Daemon AI</string>
                            <key>CFBundleDisplayName</key>
                            <string>Daemon AI</string>
                            <key>CFBundlePackageType</key>
                            <string>APPL</string>
                            <key>CFBundleShortVersionString</key>
                            <string>13.0</string>
                            <key>LSUIElement</key>
                            <true/>
                        </dict>
                        </plist>
                    """.trimIndent()
                    infoPlist.writeText(infoPlistXml, StandardCharsets.UTF_8)

                    val macExec = File(macOsDir, "DaemonAI")
                    val macExecScript = """
                        #!/bin/bash
                        cd "$workingDir"
                        if [ -f "build/libs/daemon-0.0.1-SNAPSHOT.jar" ]; then
                            JAR_TARGET="$workingDir/build/libs/daemon-0.0.1-SNAPSHOT.jar"
                        else
                            JAR_TARGET="$workingDir/daemon.jar"
                        fi
                        exec "$javaBin" -Xms32m -Xmx128m -XX:+UseSerialGC -jar "${'$'}JAR_TARGET"
                    """.trimIndent()
                    macExec.writeText(macExecScript, StandardCharsets.UTF_8)
                    macExec.setExecutable(true)

                    val launchAgentsDir = File("$home/Library/LaunchAgents").apply { mkdirs() }
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
                                <string>${macExec.absolutePath}</string>
                            </array>
                            <key>WorkingDirectory</key>
                            <string>$workingDir</string>
                            <key>RunAtLoad</key>
                            <true/>
                        </dict>
                        </plist>
                    """.trimIndent()
                    plistFile.writeText(plistXml, StandardCharsets.UTF_8)
                    "Aplicación nativa Daemon AI.app instalada en Aplicaciones y Auto-Arranque de macOS."
                }
                else -> ""
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
        cloudVault.pushVaultEverywhereAsync("sync: nuevo modo personalizado de ${acc.username}")
        return "Modo '$cleanId' guardado y sincronizado en la cuenta de '${acc.username.uppercase()}'."
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

    fun normalizeText(text: String): String {
        val temp = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return temp.replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .replace(Regex("[^a-z0-9\\s\\.:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

// ============================================================================
// 4. CEREBRO DE DAEMON + CONEXIÓN DE RECORDATORIOS CON CALENDARIO
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

        // Cuando llega la hora de un evento del calendario, Daemon avisa por voz automáticamente
        cloudVault.onDueCalendarAlarm = { ev ->
            speakAndDisplay(
                "⏰ ALARMA DE CALENDARIO",
                "Atención ${cloudVault.getCurrentAccount().username}: Tienes programado ahora '${ev.title}' (${ev.formattedDate})."
            )
        }

        speakAndDisplay(
            "NÚCLEO CALENDAR v13.0",
            "Daemon activo en ${systemScanner.getOSLabel()}. Sincronización automática con Google y Apple Calendar lista."
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
                            'daemon mis recordatorios', 'daemon que tengo en mi calendario',
                            'pausa', 'dale play', 'siguiente cancion', 'cancion anterior',
                            'vamos a trabajar', 'vamos a hacer tarea', 'vamos a estudiar'
                        ))
                        ${'$'}gb = New-Object System.Speech.Recognition.GrammarBuilder(${'$'}choices)
                        ${'$'}gb.Culture = ${'$'}esRec.Culture
                        ${'$'}engine.LoadGrammar((New-Object System.Speech.Recognition.Grammar(${'$'}gb)))
                        ${'$'}engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                        
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

    fun processThought(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isBlank()) return "Sin señal de entrada."
        uiState = "THINKING"

        var normalized = systemScanner.normalizeText(input)
        for (w in wakeWords) {
            normalized = normalized.replace(Regex("""^(?:oye\s+|hola\s+)?$w\b\s*"""), "").trim()
        }

        // --------------------------------------------------------------------
        // 1. RECORDATORIOS Y EVENTOS EN GOOGLE CALENDAR / APPLE CALENDAR
        // --------------------------------------------------------------------
        if (normalized in setOf("mis recordatorios", "ver recordatorios", "que tengo en mi calendario", "mi calendario", "eventos programados")) {
            return recordAndReturn(input, cloudVault.getUpcomingEventsReport())
        }

        if (SpanishCalendarParser.isCalendarReminderRequest(normalized)) {
            val calReply = cloudVault.scheduleEventInConnectedCalendars(input, normalized)
            return recordAndReturn(input, calReply)
        }

        // --------------------------------------------------------------------
        // 2. GESTIÓN DE CUENTAS Y SINCRONIZACIÓN
        // --------------------------------------------------------------------
        val loginRegex = Regex("""^(?:iniciar\s+sesion|inicia\s+sesion|login|entrar\s+como)\s+(?:como\s+)?([a-zA-Z0-9_]+)\s+(?:clave|password|contrasena|pin)\s+(.+)$""", RegexOption.IGNORE_CASE)
        loginRegex.find(input)?.let { m ->
            val res = cloudVault.loginUser(m.groupValues[1], m.groupValues[2])
            return res.second
        }

        val registerRegex = Regex("""^(?:registrar\s+usuario|crear\s+cuenta|nuevo\s+usuario)\s+([a-zA-Z0-9_]+)\s+(?:clave|password|contrasena|pin)\s+(.+)$""", RegexOption.IGNORE_CASE)
        registerRegex.find(input)?.let { m ->
            val res = cloudVault.registerNewUser(m.groupValues[1], m.groupValues[2])
            return res.second
        }

        if (normalized in setOf("cerrar sesion", "cierra sesion", "salir de mi cuenta", "logout")) {
            return cloudVault.logoutToGuest()
        }

        if (normalized in setOf("buscar actualizaciones", "actualiza daemon", "sincronizar nube", "sync", "sincronizar cuentas")) {
            return cloudVault.syncAllChannelsNow(silent = false)
        }

        if (normalized in setOf("publicar actualizacion", "subir update", "admin publicar update")) {
            return cloudVault.adminPublishOtaUpdateToAllDevices()
        }

        // --------------------------------------------------------------------
        // 3. MEMORIA PERMANENTE DE DATOS SIN FECHA ("recuerda que mi carrera es...")
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
        // 4. MODOS DE TRABAJO DEL USUARIO ACTIVO
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
        // 5. CONTROL DE VENTANAS, OPERA GX, VIDEO Y SPOTIFY
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

        cloudVault.findKnowledgeMatch(normalized)?.let { exactNodeReply ->
            return recordAndReturn(input, exactNodeReply)
        }

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
        val eventsContext = if (acc.calendarEvents.isEmpty()) "" else "Próximos eventos en el calendario de ${acc.username}:\n" +
            acc.calendarEvents.takeLast(5).joinToString("\n") { "- ${it.formattedDate}: ${it.title}" }
        val globalContext = if (cloudVault.vault.globalKnowledge.isEmpty()) "" else "Base de conocimiento global:\n" +
            cloudVault.vault.globalKnowledge.take(15).joinToString("\n") { "- ${it.tag}: ${it.responses.firstOrNull() ?: ""}" }

        return """
            Eres Daemon (v13.0), una aplicación nativa de inteligencia artificial estilo J.A.R.V.I.S. con conexión directa a Google Calendar y Apple Calendar, creada por Cristian Alexis.
            Estás atendiendo a la cuenta: ${acc.username.uppercase()} (Rol: ${acc.role}) en un dispositivo ${systemScanner.getOSLabel()}.
            Hora actual: $horaActual. Modo activo: ${systemScanner.activeModeName}.
            $factsContext
            $eventsContext
            $globalContext
            
            Directrices:
            1. Habla siempre en español de forma inteligente, leal y fluida, dirigiéndote a ${acc.username}.
            2. Sé conciso (1 a 2 párrafos cortos).
            3. Si ${acc.username} te pide agendar o recordar algo para una fecha/hora durante la charla, o ejecutar una acción, añade al final una etiqueta exacta:
               [ACTION:CALENDAR_ADD:texto completo con fecha y hora]
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
                .header("User-Agent", "DaemonStarkCalendar/13.0")
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
                "CALENDAR_ADD" -> cloudVault.scheduleEventInConnectedCalendars(param, systemScanner.normalizeText(param))
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
        cloudVault.pushVaultEverywhereAsync("sync: historial de conversacion")
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
                    .replace(Regex("[#*`_~>\\-•👑⛔☁👤🚀✓⬇📅⏰]"), "")
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
// 5. INTERFAZ PRINCIPAL + PORTAL HOLOGRÁFICO DE GOOGLE & APPLE CALENDAR
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
    private val fullWidth = 735
    private val fullHeight = 540
    private val orbSize = 104

    val bgSpaceDark = Color(4, 10, 20)
    val neonCyan = Color(0, 242, 255)
    val starkGold = Color(255, 195, 0)
    val neonEmerald = Color(16, 235, 160)
    val neonCrimson = Color(255, 70, 95)

    private val statusLabel = JLabel("ONLINE // CLIC DERECHO PARA EXPANDIR")
    private val outputArea = JTextArea()
    private val inputField = JTextField()
    private val hudCanvas = HolographicChassisPanel()
    private val fullHudContent = JPanel(BorderLayout(12, 8))

    private lateinit var adminHoloBtn: JComponent

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

        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(2, 6, 6, 6)

            val leftTitle = JPanel(GridLayout(2, 1, 0, 0)).apply {
                isOpaque = false
                val mainTitle = JLabel("◢ J.A.R.V.I.S. // DAEMON v13.0").apply {
                    foreground = neonCyan
                    font = Font("Monospaced", Font.BOLD, 13)
                }
                statusLabel.apply {
                    foreground = starkGold
                    font = Font("Monospaced", Font.BOLD, 10)
                }
                add(mainTitle)
                add(statusLabel)
            }

            val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                isOpaque = false
                val calBtn = createHoloButton("📅 CALENDARIO", neonCyan, 106) { openAnimatedCalendarHoloModal() }
                val accountBtn = createHoloButton("👤 CUENTA", neonEmerald, 82) { openAnimatedAccountHoloModal() }
                adminHoloBtn = createHoloButton("👑 ADMIN", starkGold, 78) { openAnimatedAdminHoloModal() }
                val orbBtn = createHoloButton("◉ NÚCLEO", neonCyan, 78) { setOrbMode(true) }
                val closeBtn = createHoloButton("✕", neonCrimson, 28) { System.exit(0) }

                adminHoloBtn.isVisible = cloudVault.isAdminLoggedIn()

                add(calBtn)
                add(accountBtn)
                add(adminHoloBtn)
                add(orbBtn)
                add(closeBtn)
            }

            add(leftTitle, BorderLayout.WEST)
            add(rightControls, BorderLayout.EAST)
        }

        cloudVault.onSessionOrVaultChanged = {
            SwingUtilities.invokeLater {
                adminHoloBtn.isVisible = cloudVault.isAdminLoggedIn()
                headerBar.revalidate()
                hudCanvas.repaint()
            }
        }

        val centerDeck = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false

            val leftWing = JPanel(GridLayout(5, 1, 0, 7)).apply {
                isOpaque = false
                preferredSize = Dimension(150, 220)
                border = EmptyBorder(12, 2, 12, 2)

                val lbl = JLabel("◢ MODOS DE CUENTA").apply {
                    foreground = neonCyan
                    font = Font("Monospaced", Font.BOLD, 11)
                }
                add(lbl)
                add(createHoloButton("⚡ MODO TRABAJO", neonCyan, 145) { runQuickCommand("vamos a trabajar") })
                add(createHoloButton("📝 MODO TAREA", neonEmerald, 145) { runQuickCommand("vamos a hacer tarea") })
                add(createHoloButton("📘 MODO ESTUDIO", Color(120, 180, 255), 145) { runQuickCommand("vamos a estudiar") })
                add(createHoloButton("🎮 MODO GAMING", starkGold, 145) { runQuickCommand("vamos a jugar") })
            }

            val rightWing = JPanel(GridLayout(5, 1, 0, 7)).apply {
                isOpaque = false
                preferredSize = Dimension(150, 220)
                border = EmptyBorder(12, 2, 12, 2)

                val lbl = JLabel("◣ SPOTIFY & AGENDA").apply {
                    foreground = starkGold
                    font = Font("Monospaced", Font.BOLD, 11)
                    horizontalAlignment = SwingConstants.RIGHT
                }
                add(lbl)
                add(createHoloButton("▶ PLAY SPOTIFY", neonEmerald, 145) { runQuickCommand("dale play") })
                add(createHoloButton("⏸ PAUSA SPOTIFY", starkGold, 145) { runQuickCommand("pausa") })
                add(createHoloButton("⏭ SIGUIENTE", neonCyan, 145) { runQuickCommand("siguiente cancion") })
                add(createHoloButton("📅 VER AGENDA", Color(160, 140, 255), 145) { runQuickCommand("mis recordatorios") })
            }

            add(leftWing, BorderLayout.WEST)
            add(rightWing, BorderLayout.EAST)
        }

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

                val micBtn = createHoloButton("🎤 HABLAR", neonEmerald, 90) {
                    brain.activateDirectVoiceListenWindow()
                }

                inputField.apply {
                    isOpaque = true
                    background = Color(8, 20, 38)
                    foreground = neonCyan
                    caretColor = starkGold
                    font = Font("Monospaced", Font.PLAIN, 13)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(0, 200, 240), 1),
                        EmptyBorder(7, 10, 7, 10)
                    )
                    addActionListener { submitCommand() }
                }

                val sendBtn = createHoloButton("⚡ EJECUTAR", neonCyan, 104) {
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
                    if (isMiniOrbMode || (e.y in 90..310 && e.x in 190..540)) {
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
                hudCanvas.repaint(180, 62, 370, 275)
            }
        }.start()
    }

    // ========================================================================
    // VENTANA HOLOGRÁFICA ANIMADA: CONEXIÓN CON GOOGLE CALENDAR & APPLE CALENDAR
    // ========================================================================
    private fun openAnimatedCalendarHoloModal() {
        val acc = cloudVault.getCurrentAccount()
        val modal = StarkAnimatedHoloModal(
            parent = this,
            title = "📅 STARK CHRONOS // CONEXIÓN GOOGLE & APPLE CALENDAR",
            subtitle = "SINCRONIZACIÓN AUTOMÁTICA DE RECORDATORIOS PARA LA CUENTA: ${acc.username.uppercase()}",
            accentColor = neonCyan,
            widthPx = 610,
            heightPx = 500
        )

        val container = JPanel(BorderLayout(10, 10)).apply {
            isOpaque = false
            border = EmptyBorder(6, 18, 10, 18)
        }

        val formsGrid = JPanel(GridLayout(4, 1, 0, 7)).apply {
            isOpaque = false
        }

        var selectedProvider = acc.calendarProvider
        val providerStatusLbl = JLabel("PROVEEDOR ACTIVO: [$selectedProvider] (GOOGLE + APPLE iCLOUD)").apply {
            foreground = neonEmerald
            font = Font("Monospaced", Font.BOLD, 12)
        }

        val providerButtonsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            val btnGoogle = createHoloButton("🌐 SOLO GOOGLE", neonCyan, 130) {
                selectedProvider = "GOOGLE"
                providerStatusLbl.text = "PROVEEDOR SELECCIONADO: [GOOGLE CALENDAR]"
            }
            val btnApple = createHoloButton("🍎 SOLO APPLE", starkGold, 130) {
                selectedProvider = "APPLE"
                providerStatusLbl.text = "PROVEEDOR SELECCIONADO: [APPLE CALENDAR / iCLOUD]"
            }
            val btnBoth = createHoloButton("⚡ AMBOS (GOOGLE + APPLE)", neonEmerald, 195) {
                selectedProvider = "BOTH"
                providerStatusLbl.text = "PROVEEDOR SELECCIONADO: [DUAL: GOOGLE + APPLE]"
            }
            add(btnGoogle)
            add(btnApple)
            add(btnBoth)
        }

        val providerBox = JPanel(GridLayout(2, 1, 0, 2)).apply {
            isOpaque = false
            add(providerStatusLbl)
            add(providerButtonsRow)
        }

        val googleEmailField = createStarkTextField(acc.googleAccountEmail)
        val googleBridgeField = createStarkTextField(acc.googleWebhookOrToken)
        val appleEmailField = createStarkTextField(acc.appleAccountEmail)

        formsGrid.add(providerBox)
        formsGrid.add(createLabeledInputBox("🌐 CORREO DE CUENTA GOOGLE (EJ: TU_CORREO@GMAIL.COM):", googleEmailField, neonCyan))
        formsGrid.add(createLabeledInputBox("🔑 PUENTE SILENCIOSO GOOGLE SCRIPT URL / TOKEN OAUTH (OPCIONAL):", googleBridgeField, neonCyan))
        formsGrid.add(createLabeledInputBox("🍎 CORREO DE CUENTA APPLE / iCLOUD (SINCRONIZACIÓN NATIVA MAC):", appleEmailField, starkGold))

        val eventsArea = JTextArea().apply {
            isOpaque = true
            background = Color(6, 15, 28)
            foreground = Color(200, 245, 255)
            font = Font("Monospaced", Font.PLAIN, 11)
            isEditable = false
            border = EmptyBorder(6, 10, 6, 10)
            rows = 4
            text = cloudVault.getUpcomingEventsReport()
        }

        val bottomBtns = JPanel(FlowLayout(FlowLayout.CENTER, 8, 4)).apply {
            isOpaque = false

            val saveBtn = createHoloButton("💾 GUARDAR Y CONECTAR", neonEmerald, 175) {
                val msg = cloudVault.configureUserCalendarSettings(
                    provider = selectedProvider,
                    googleEmail = googleEmailField.text,
                    googleWebhookOrToken = googleBridgeField.text,
                    appleEmail = appleEmailField.text
                )
                eventsArea.text = "$msg\n\n" + cloudVault.getUpcomingEventsReport()
                brain.speakAndDisplay("CALENDARIO CONECTADO", msg)
            }

            val testBtn = createHoloButton("➕ PROBAR RECORDATORIO", neonCyan, 175) {
                cloudVault.configureUserCalendarSettings(
                    provider = selectedProvider,
                    googleEmail = googleEmailField.text,
                    googleWebhookOrToken = googleBridgeField.text,
                    appleEmail = appleEmailField.text
                )
                val reply = brain.processThought("recuerdame revisar sistemas de Daemon mañana a las 5 pm")
                eventsArea.text = reply + "\n\n" + cloudVault.getUpcomingEventsReport()
                brain.speakAndDisplay("PRUEBA DE CALENDARIO", reply)
            }

            val copyScriptBtn = createHoloButton("📋 COPIAR SCRIPT GOOGLE", starkGold, 185) {
                val gasCode = """
                    function doPost(e) {
                      var data = JSON.parse(e.postData.contents);
                      CalendarApp.getDefaultCalendar().createEvent(data.title, new Date(data.startIso), new Date(data.endIso), {description: data.description});
                      return ContentService.createTextOutput("OK");
                    }
                """.trimIndent()
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(gasCode), null)
                eventsArea.text = "✓ Código copiado al portapapeles.\nSi deseas inserción 100% silenciosa en Google sin abrir pestañas: entra a script.google.com -> Nuevo proyecto -> pega el código -> Implementar como Aplicación Web (Cualquier persona) y pega la URL arriba."
            }

            add(saveBtn)
            add(testBtn)
            add(copyScriptBtn)
        }

        container.add(formsGrid, BorderLayout.NORTH)
        container.add(JScrollPane(eventsArea).apply {
            border = BorderFactory.createLineBorder(neonCyan, 1)
        }, BorderLayout.CENTER)
        container.add(bottomBtns, BorderLayout.SOUTH)

        modal.setBodyContent(container)
        modal.isVisible = true
    }

    private fun openAnimatedAccountHoloModal() {
        val modal = StarkAnimatedHoloModal(
            parent = this,
            title = "◢ STARK IDENTITY // PORTAL DE CUENTAS CLOUD",
            subtitle = "SINCRONIZACIÓN EN TIEMPO REAL WINDOWS <-> MAC <-> CLOUD",
            accentColor = neonCyan,
            widthPx = 540,
            heightPx = 435
        )

        val currentAcc = cloudVault.getCurrentAccount()
        val body = JPanel(GridLayout(6, 1, 0, 8)).apply {
            isOpaque = false
            border = EmptyBorder(8, 18, 8, 18)
        }

        val l1 = JLabel("SESIÓN ACTIVA: ${currentAcc.username.uppercase()} [${currentAcc.role}]").apply {
            foreground = neonEmerald
            font = Font("Monospaced", Font.BOLD, 12)
        }
        val l2 = JLabel("CUENTAS SINCRONIZADAS: ${cloudVault.vault.accounts.joinToString(" | ") { it.username.uppercase() }}").apply {
            foreground = Color(180, 235, 255)
            font = Font("Monospaced", Font.PLAIN, 10)
        }

        val badgePanel = JPanel(GridLayout(2, 1)).apply {
            isOpaque = true
            background = Color(7, 19, 36)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(neonEmerald, 1),
                EmptyBorder(6, 12, 6, 12)
            )
            add(l1)
            add(l2)
        }

        val userField = createStarkTextField(if (currentAcc.username == "invitado") "" else currentAcc.username)
        val passField = createStarkPasswordField()

        val feedbackLabel = JLabel("Sincronizando cuentas con tus otros dispositivos...").apply {
            foreground = starkGold
            font = Font("Monospaced", Font.BOLD, 11)
        }

        thread {
            cloudVault.syncAllChannelsNow(silent = true)
            SwingUtilities.invokeLater {
                l2.text = "CUENTAS SINCRONIZADAS: ${cloudVault.vault.accounts.joinToString(" | ") { it.username.uppercase() }}"
                feedbackLabel.text = "Bóveda actualizada en vivo (${cloudVault.vault.accounts.size} cuentas disponibles)."
            }
        }

        val actionsRow = JPanel(FlowLayout(FlowLayout.CENTER, 8, 2)).apply {
            isOpaque = false
            val loginBtn = createHoloButton("⚡ INICIAR SESIÓN", neonCyan, 134) {
                val u = userField.text.trim()
                val p = String(passField.password)
                val (ok, msg) = cloudVault.loginUser(u, p)
                l2.text = "CUENTAS SINCRONIZADAS: ${cloudVault.vault.accounts.joinToString(" | ") { it.username.uppercase() }}"
                feedbackLabel.foreground = if (ok) neonEmerald else neonCrimson
                feedbackLabel.text = msg.take(66)
                brain.speakAndDisplay("CUENTA CLOUD", msg)
                if (ok) {
                    adminHoloBtn.isVisible = cloudVault.isAdminLoggedIn()
                    modal.dispose()
                }
            }
            val regBtn = createHoloButton("✨ CREAR CUENTA", neonEmerald, 128) {
                val u = userField.text.trim()
                val p = String(passField.password)
                val (ok, msg) = cloudVault.registerNewUser(u, p)
                l2.text = "CUENTAS SINCRONIZADAS: ${cloudVault.vault.accounts.joinToString(" | ") { it.username.uppercase() }}"
                feedbackLabel.foreground = if (ok) neonEmerald else neonCrimson
                feedbackLabel.text = msg.take(66)
                brain.speakAndDisplay("NUEVA CUENTA", msg)
                if (ok) modal.dispose()
            }
            val syncBtn = createHoloButton("🔄 SYNC", starkGold, 76) {
                feedbackLabel.text = "Sincronizando con Windows / Mac / Cloud..."
                thread {
                    val msg = cloudVault.syncAllChannelsNow(silent = false)
                    SwingUtilities.invokeLater {
                        l2.text = "CUENTAS SINCRONIZADAS: ${cloudVault.vault.accounts.joinToString(" | ") { it.username.uppercase() }}"
                        feedbackLabel.foreground = neonEmerald
                        feedbackLabel.text = msg.take(66)
                    }
                }
            }
            val logoutBtn = createHoloButton("🚪 SALIR", neonCrimson, 76) {
                val msg = cloudVault.logoutToGuest()
                adminHoloBtn.isVisible = false
                brain.speakAndDisplay("SESIÓN CERRADA", msg)
                modal.dispose()
            }
            add(loginBtn)
            add(regBtn)
            add(syncBtn)
            add(logoutBtn)
        }

        body.add(badgePanel)
        body.add(createLabeledInputBox("IDENTIFICADOR DE USUARIO (EJ: CRISTIAN / NUEVO USUARIO):", userField, neonCyan))
        body.add(createLabeledInputBox("CLAVE CRIPTOGRÁFICA DE ACCESO (SHA-256):", passField, neonCyan))
        body.add(feedbackLabel)
        body.add(actionsRow)

        modal.setBodyContent(body)
        modal.isVisible = true
    }

    private fun openAnimatedAdminHoloModal() {
        if (!cloudVault.isAdminLoggedIn()) {
            brain.speakAndDisplay("SEGURIDAD", "Acceso restringido. Inicia sesión como Administrador primero.")
            return
        }

        val modal = StarkAnimatedHoloModal(
            parent = this,
            title = "👑 STARK COMMAND CENTER // ADMINISTRADOR MAESTRO",
            subtitle = "CONTROL GLOBAL DE CONOCIMIENTO Y DESPLIEGUE OTA (v${cloudVault.otaManifest.versionName})",
            accentColor = starkGold,
            widthPx = 610,
            heightPx = 490
        )

        val container = JPanel(BorderLayout(10, 10)).apply {
            isOpaque = false
            border = EmptyBorder(6, 18, 10, 18)
        }

        val formsGrid = JPanel(GridLayout(4, 1, 0, 7)).apply {
            isOpaque = false
        }

        val patternsField = createStarkTextField("que es daemon / quien creo a daemon")
        val responseField = createStarkTextField("Daemon es un sistema operativo de IA creado por Cristian Alexis.")
        val cloudUrlField = createStarkTextField(cloudVault.otaManifest.sharedCloudDbUrl)
        val repoRawField = createStarkTextField(cloudVault.otaManifest.githubRawBaseUrl)

        formsGrid.add(createLabeledInputBox("1. PATRONES GLOBALES (SEPARADOS POR '/'):", patternsField, starkGold))
        formsGrid.add(createLabeledInputBox("2. RESPUESTA OFICIAL PARA TODOS LOS USUARIOS:", responseField, starkGold))
        formsGrid.add(createLabeledInputBox("3. URL FIREBASE / CLOUD DB COMPARTIDA (OPCIONAL):", cloudUrlField, neonCyan))
        formsGrid.add(createLabeledInputBox("4. REPOSITORIO OTA RAW (AUTO-DETECTADO DE TU GITHUB):", repoRawField, neonCyan))

        val telemetryArea = JTextArea().apply {
            isOpaque = true
            background = Color(6, 15, 28)
            foreground = neonEmerald
            font = Font("Monospaced", Font.PLAIN, 11)
            isEditable = false
            border = EmptyBorder(6, 10, 6, 10)
            rows = 4
            val usersList = cloudVault.vault.accounts.joinToString(" | ") { "${it.username.uppercase()}(${it.role})" }
            text = "NODOS GLOBALES ACTIVOS: ${cloudVault.vault.globalKnowledge.size}  |  VERSIÓN OTA: v${cloudVault.otaManifest.versionName}\nCUENTAS EN LA BÓVEDA: $usersList"
        }

        val adminBtns = JPanel(FlowLayout(FlowLayout.CENTER, 8, 4)).apply {
            isOpaque = false

            val addKnowBtn = createHoloButton("🧠 INYECTAR NODO GLOBAL", neonEmerald, 178) {
                val pats = patternsField.text.split("/").map { it.trim() }.filter { it.isNotBlank() }
                val resp = responseField.text.trim()
                if (pats.isNotEmpty() && resp.isNotBlank()) {
                    val tag = "global_" + (System.currentTimeMillis() % 100000)
                    val msg = cloudVault.adminAddGlobalKnowledge(tag, pats, resp)
                    telemetryArea.text = "$msg\nTotal de nodos globales: ${cloudVault.vault.globalKnowledge.size}"
                    brain.speakAndDisplay("ADMIN GLOBAL", msg)
                }
            }

            val saveLinksBtn = createHoloButton("☁ GUARDAR LINKS CLOUD", neonCyan, 172) {
                val msg = cloudVault.adminConfigureCloudAndRepo(cloudUrlField.text, repoRawField.text)
                telemetryArea.text = msg
                brain.speakAndDisplay("ADMIN CLOUD", msg)
            }

            val publishOtaBtn = createHoloButton("🚀 PUBLICAR UPDATE OTA A TODOS", starkGold, 215) {
                cloudVault.adminConfigureCloudAndRepo(cloudUrlField.text, repoRawField.text)
                val msg = cloudVault.adminPublishOtaUpdateToAllDevices()
                telemetryArea.text = msg
                brain.speakAndDisplay("DESPLIEGUE OTA", msg)
            }

            add(addKnowBtn)
            add(saveLinksBtn)
            add(publishOtaBtn)
        }

        container.add(formsGrid, BorderLayout.NORTH)
        container.add(JScrollPane(telemetryArea).apply {
            border = BorderFactory.createLineBorder(starkGold, 1)
        }, BorderLayout.CENTER)
        container.add(adminBtns, BorderLayout.SOUTH)

        modal.setBodyContent(container)
        modal.isVisible = true
    }

    private fun createLabeledInputBox(labelTitle: String, field: JComponent, accent: Color): JPanel {
        return JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            val lbl = JLabel(labelTitle).apply {
                foreground = accent
                font = Font("Monospaced", Font.BOLD, 10)
            }
            add(lbl, BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }
    }

    private fun createStarkTextField(initialText: String = ""): JTextField {
        return JTextField(initialText).apply {
            isOpaque = true
            background = Color(8, 20, 38)
            foreground = neonCyan
            caretColor = starkGold
            font = Font("Monospaced", Font.PLAIN, 12)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0, 195, 240), 1),
                EmptyBorder(5, 8, 5, 8)
            )
        }
    }

    private fun createStarkPasswordField(): JPasswordField {
        return JPasswordField().apply {
            isOpaque = true
            background = Color(8, 20, 38)
            foreground = starkGold
            caretColor = neonCyan
            font = Font("Monospaced", Font.BOLD, 13)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0, 195, 240), 1),
                EmptyBorder(5, 8, 5, 8)
            )
        }
    }

    inner class StarkAnimatedHoloModal(
        parent: JFrame,
        private val title: String,
        private val subtitle: String,
        private val accentColor: Color,
        widthPx: Int,
        heightPx: Int
    ) : JDialog(parent, false) {

        private var dragPt = Point(0, 0)
        private var animTick = 0.0
        private val rootCanvas = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val w = width
                val h = height
                val cut = 22

                g2.color = Color(0, 40, 65)
                var gx = 24
                while (gx < w - 24) { g2.drawLine(gx, 16, gx, h - 16); gx += 32 }
                var gy = 16
                while (gy < h - 16) { g2.drawLine(24, gy, w - 24, gy); gy += 32 }

                val scanY = ((sin(animTick) + 1.0) * 0.5 * (h - 30) + 15).toInt()
                g2.color = Color(accentColor.red / 4, accentColor.green / 4, accentColor.blue / 4)
                g2.drawLine(10, scanY, w - 10, scanY)

                val rx = w - 75
                val ry = 14
                g2.color = accentColor
                g2.stroke = BasicStroke(1.5f)
                val a1 = (animTick * 45).toInt()
                g2.drawArc(rx, ry, 26, 26, a1, 110)
                g2.drawArc(rx, ry, 26, 26, a1 + 180, 110)

                g2.color = Color(accentColor.red / 2, accentColor.green / 2, accentColor.blue / 2)
                g2.stroke = BasicStroke(1.4f)
                g2.drawRect(2, 2, w - 5, h - 5)

                g2.color = accentColor
                g2.stroke = BasicStroke(2.6f)
                g2.drawLine(2, 2, 2 + cut, 2)
                g2.drawLine(2, 2, 2, 2 + cut)
                g2.drawLine(w - 3 - cut, 2, w - 3, 2)
                g2.drawLine(w - 3, 2, w - 3, 2 + cut)
                g2.drawLine(2, h - 3 - cut, 2, h - 3)
                g2.drawLine(2, h - 3, 2 + cut, h - 3)
                g2.drawLine(w - 3 - cut, h - 3, w - 3, h - 3)
                g2.drawLine(w - 3, h - 3 - cut, w - 3, h - 3)
            }
        }

        private val animTimer = javax.swing.Timer(40) {
            animTick += 0.08
            rootCanvas.repaint()
        }

        init {
            isUndecorated = true
            isAlwaysOnTop = true
            setSize(widthPx, heightPx)
            setLocationRelativeTo(parent)
            rootPane.putClientProperty("Window.shadow", false)

            rootCanvas.isOpaque = true
            rootCanvas.background = Color(4, 11, 22)
            rootCanvas.border = EmptyBorder(14, 16, 14, 16)

            val topHeader = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = EmptyBorder(2, 8, 8, 8)
                val titleStack = JPanel(GridLayout(2, 1)).apply {
                    isOpaque = false
                    add(JLabel(title).apply {
                        foreground = accentColor
                        font = Font("Monospaced", Font.BOLD, 13)
                    })
                    add(JLabel(subtitle).apply {
                        foreground = Color(160, 225, 245)
                        font = Font("Monospaced", Font.PLAIN, 9)
                    })
                }
                val closeBtn = createHoloButton("✕", neonCrimson, 28) {
                    animTimer.stop()
                    dispose()
                }
                add(titleStack, BorderLayout.WEST)
                add(closeBtn, BorderLayout.EAST)
            }

            rootCanvas.add(topHeader, BorderLayout.NORTH)
            contentPane = rootCanvas

            rootCanvas.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { dragPt = e.point }
            })
            rootCanvas.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val sc = e.locationOnScreen
                    setLocation(sc.x - dragPt.x, sc.y - dragPt.y)
                }
            })

            animTimer.start()
        }

        fun setBodyContent(panel: JComponent) {
            rootCanvas.add(panel, BorderLayout.CENTER)
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

    fun createHoloButton(label: String, accent: Color, widthPx: Int, onClick: () -> Unit): JComponent {
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
                "LISTENING" -> neonEmerald
                "THINKING" -> starkGold
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

            g2.color = starkGold
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
            g2.color = neonCyan
            g2.drawString("CPU:${scanner.cachedCpuLoad}% RAM:${scanner.cachedRamMb}MB", 184, 76)
            g2.drawString(cloudVault.cloudSyncStatus, 430, 76)

            val acc = cloudVault.getCurrentAccount()
            g2.color = if (acc.role == "ADMIN") starkGold else neonEmerald
            val userBadge = "CUENTA // ${acc.username.uppercase()} [${acc.calendarProvider}]"
            val fm = g2.fontMetrics
            g2.drawString(userBadge, (cx - fm.stringWidth(userBadge) / 2).toInt(), 92)

            g2.color = neonEmerald
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
            g2.color = starkGold
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
            cloudVault.loginUser(request.username, request.password)
        }
        val reply = brain.processThought(request.prompt)
        brain.speakAndDisplay(request.prompt, reply)
        return reply
    }

    @GetMapping("/cloud-vault")
    fun getVault(): CloudVaultState = cloudVault.vault

    @PutMapping("/cloud-vault")
    fun updateVault(@RequestBody incomingVault: CloudVaultState): CloudVaultState {
        val changed = cloudVault.mergeCloudVault(incomingVault)
        if (changed) {
            cloudVault.onSessionOrVaultChanged?.invoke()
        }
        return cloudVault.vault
    }
}