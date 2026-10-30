package gt.umg.agua.dispatcher.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que el consumer lee del application.yml, con tipos.
 *
 * @param equipo     codigo del equipo en los dos contratos (EQUIPO_D)
 * @param colaPagos  cola de la que lee
 * @param colaDlq    cola a la que copia lo que no se pudo entregar
 * @param tablaLog   tabla con un registro por intento
 * @param tablaEstado tabla con el veredicto por destino
 */
@ConfigurationProperties("app")
public record ConsumerProps(
        String equipo,
        String colaPagos,
        String colaDlq,
        String tablaLog,
        String tablaEstado,
        Reintentos reintentos,
        Simulacion simulacion) {

    public record Reintentos(
            int enMemoria,
            long backoffInicialMs,
            long retryAfterMaxMs,
            int maxRecepciones) {}

    public record Simulacion(String escenarioBanco, String escenarioErp) {}
}