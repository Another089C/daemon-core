package com.ia.daemon.skills

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Description
import java.util.function.Function

@Configuration
class FacturacionSkill {
    data class Request(val idCliente: String, val monto: Double, val concepto: String)

    @Bean
    @Description("Genera una factura en el sistema ERP para un cliente específico.")
    fun facturarCliente(): Function<Request, String> {
        return Function { request ->
            println("[DAEMON] Ejecutando facturación de $${request.monto} para ${request.idCliente}")
            "Factura de $${request.monto} por '${request.concepto}' generada con éxito."
        }
    }
}