[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$brainFile = "daemon_brain.json"

$existing = @()
if (Test-Path $brainFile) {
    $existing = Get-Content $brainFile -Raw -Encoding UTF8 | ConvertFrom-Json
}

# Catálogo masivo de conceptos que se descargarán y convertirán en neuronas con múltiples patrones
$topics = @(
    "Inteligencia artificial", "Aprendizaje automático", "Red neuronal artificial", "Procesamiento de lenguaje natural",
    "Visión artificial", "Algoritmo", "Estructura de datos", " Árbol binario", "Tabla hash", "Grafo",
    "Sistema operativo", "Núcleo (informática)", "Linux", "Microsoft Windows", "macOS", "Android",
    "Memoria de acceso aleatorio", "Memoria virtual", "Unidad central de procesamiento", "Unidad aritmético lógica",
    "Lenguaje ensamblador", "Arquitectura x86-64", "FPGA", "VHDL", "Microcontrolador", "Arduino", "Raspberry Pi",
    "Lenguaje de programación C", "C++", "Java (lenguaje de programación)", "Kotlin", "Python", "JavaScript", "SQL",
    "Modelo OSI", "Familia de protocolos de internet", "Protocolo de control de transmisión", "Protocolo de datagramas de usuario",
    "Dirección IP", "Dirección MAC", "Ethernet", "Protocolo de resolución de direcciones", "DNS", "HTTP",
    "Criptografía", "Cifrado simétrico", "Criptografía asimétrica", "SHA-2", "Cortafuegos (informática)", "Ingeniería inversa",
    "Docker (software)", "Git", "Computación en la nube", "Servidor", "Base de datos relacional", "JSON",
    "Agujero negro", "Agujero de gusano", "Big Bang", "Vía Láctea", "Sistema solar", "Estrella de neutrones", "Supernova",
    "Materia oscura", "Energía oscura", "Exoplaneta", "Gravedad", "Velocidad de la luz",
    "Mecánica cuántica", "Teoría de la relatividad", "Termodinámica", "Electromagnetismo", "Física de partículas",
    "Átomo", "Electrón", "Fotón", "Tabla periódica de los elementos", "Reacción química",
    "ADN", "Célula", "Evolución biológica", "Neurona", "Cerebro humano", "Sistema inmunitario", "Fotosíntesis",
    "Cálculo infinitesimal", "Álgebra lineal", "Teorema de Pitágoras", "Número primo", "Sucesión de Fibonacci", "Probabilidad",
    "Segunda Guerra Mundial", "Primera Guerra Mundial", "Guerra Fría", "Revolución Industrial", "Imperio romano",
    "Revolución mexicana", "Independencia de México", "Cultura mexica", "Cultura maya",
    "Filosofía", "Ética", "Lógica", "Psicología", "Economía", "Inflación", "Interés compuesto", "Cadena de bloques"
)

$newNodes = [System.Collections.Generic.List[Object]]::new()
foreach ($item in $existing) { $newNodes.Add($item) }

Write-Host "Iniciando cosecha sináptica masiva para Daemon..." -ForegroundColor Cyan

foreach ($topic in $topics) {
    $cleanTopic = $topic.Split("(")[0].Trim().ToLower()
    $tag = "kb_" + ($cleanTopic -replace "[^a-z0-9]", "_")

    if ($newNodes | Where-Object { $_.tag -eq $tag }) { continue }

    try {
        $encoded = [uri]::EscapeDataString($topic.Replace(" ", "_"))
        $url = "https://es.wikipedia.org/api/rest_v1/page/summary/$encoded"
        $res = Invoke-RestMethod -Uri $url -Headers @{"User-Agent"="DaemonSeeder/4.0"} -TimeoutSec 5

        if ($res.extract) {
            # Tomar las primeras 2 oraciones limpias
            $sentences = ($res.extract -split "(?<=\.)\s+") | Select-Object -First 2
            $summary = ($sentences -join " ").Trim()

            # Generar automáticamente múltiples formas naturales de preguntarlo
            $patterns = @(
                "que es $cleanTopic",
                "que son $cleanTopic",
                "definicion de $cleanTopic",
                "explicame $cleanTopic",
                "hablame sobre $cleanTopic",
                "como funciona $cleanTopic",
                "para que sirve $cleanTopic",
                "sabes que es $cleanTopic",
                "concepto de $cleanTopic",
                "$cleanTopic"
            )

            $node = [PSCustomObject]@{
                tag       = $tag
                patterns  = $patterns
                responses = @($summary)
                action    = "NONE"
            }
            $newNodes.Add($node)
            Write-Host "[OK] Asimilado nodo: $cleanTopic (+10 patrones)" -ForegroundColor Green
        }
    } catch {
        Write-Host "[SKIP] $topic" -ForegroundColor DarkGray
    }
}

$jsonOut = $newNodes | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText((Resolve-Path $brainFile), $jsonOut, [System.Text.Encoding]::UTF8)
Write-Host "¡Listo! Base neuronal expandida a $($newNodes.Count) nodos totales." -ForegroundColor Yellow
