package gt.umg.agua.dispatcher.consumer;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Como le fue al pago en esta vuelta, con un resultado por destino.
 *
 * <p>Es lo que el listener necesita para decidir entre tres cosas: borrar el
 * mensaje, devolverlo a la cola o mandarlo a la DLQ.
 */
public record Despacho(List<Resultado> resultados) {

    public boolean todoEntregado() {
        return resultados.stream().allMatch(Resultado::fueEntregado);
    }

    /** Al menos un destino fallo por algo que puede componerse solo. */
    public boolean hayQueVolverAIntentar() {
        return resultados.stream()
                .anyMatch(resultado -> resultado.clasificacion() == Clasificacion.REINTENTABLE);
    }

    /** Una linea para el log y para el mensaje que se copia a la DLQ. */
    public String resumen() {
        return resultados.stream()
                .map(resultado -> "%s=%s(%d%s)".formatted(
                        resultado.destino(),
                        resultado.clasificacion(),
                        resultado.httpStatus(),
                        resultado.codigo().isEmpty() ? "" : " " + resultado.codigo()))
                .collect(Collectors.joining(" "));
    }
}