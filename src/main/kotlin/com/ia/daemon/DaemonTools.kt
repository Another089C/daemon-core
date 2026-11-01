package com.ia.daemon

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
class DaemonTools {
    @Tool(description = "Genera una factura para un cliente específico.")
    fun facturarCliente(
        @ToolParam(description = "Identificador del cliente") idCliente: String,
        @ToolParam(description = "Monto de la factura") monto: Double,
        @ToolParam(description = "Concepto de la factura") concepto: String
    ): String {
        println("[DAEMON] Ejecutando facturación de $$monto para $idCliente")
        return "Factura de $$monto por '$concepto' generada con éxito."
    }
}
