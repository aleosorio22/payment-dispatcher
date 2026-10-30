package gt.umg.agua.dispatcher.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * La tabla de decision de la integracion, en un solo lugar.
 *
 * <p>Los dos contratos tienen la misma semantica y solo cambian los nombres de
 * los campos, asi que la tabla sirve para ambos. Si manana se agrega un tercer
 * destino, esto no se toca.
 */
@Component
public class Clasificador {

    /** Mismo pago con los mismos datos: ya esta alla, se da por entregado. */
    private static final Set<String> DUPLICADOS = Set.of("DUPLICATE_PAYMENT", "DOCUMENTO_DUPLICADO");

    private final ObjectMapper mapper;

    public Clasificador(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Clasificacion clasificar(int httpStatus, String codigoDeNegocio) {
        // Status 0: no hubo respuesta. Puede salir bien al siguiente intento.
        if (httpStatus < 200) {
            return Clasificacion.REINTENTABLE;
        }
        if (httpStatus < 300) {
            return Clasificacion.ENTREGADO;
        }
        if (httpStatus == 409) {
            return DUPLICADOS.contains(codigoDeNegocio)
                    ? Clasificacion.ENTREGADO
                    : Clasificacion.DEFINITIVO;
        }
        if (httpStatus == 429 || httpStatus >= 500) {
            return Clasificacion.REINTENTABLE;
        }
        // 400, 403, 404, 422: el pago esta mal armado o el equipo no tiene
        // permiso. Mandarlo de nuevo solo gasta cuota.
        return Clasificacion.DEFINITIVO;
    }

    /**
     * Un campo de texto del cuerpo de error, o cadena vacia.
     *
     * <p>No siempre viene: cuando el 429 o el 502 los genera API Gateway y no
     * el simulador, el cuerpo es {@code {"message":"..."}} y no se parece al
     * contrato. Por eso esto nunca lanza.
     */
    public String campoDeTexto(FeignException e, String campo) {
        try {
            JsonNode valor = mapper.readTree(e.contentUTF8()).path(campo);
            return valor.isTextual() ? valor.asText() : "";
        } catch (Exception noEraElContrato) {
            return "";
        }
    }

    /**
     * Lo que el destino pidio esperar en {@code Retry-After}, acotado al techo.
     *
     * <p>El techo importa: el mensaje esta invisible en la cola mientras tanto
     * y un Retry-After de media hora bloquearia el hilo sin ganar nada. Pasado
     * el techo es mejor soltar el mensaje y dejar que la cola lo devuelva.
     */
    public Duration esperaPedida(FeignException e, Duration techo) {
        for (Map.Entry<String, Collection<String>> cabecera : e.responseHeaders().entrySet()) {
            if (!"retry-after".equalsIgnoreCase(cabecera.getKey())) {
                continue;
            }
            for (String valor : cabecera.getValue()) {
                try {
                    Duration pedida = Duration.ofSeconds(Long.parseLong(valor.trim()));
                    if (pedida.isNegative()) {
                        return Duration.ZERO;
                    }
                    return pedida.compareTo(techo) > 0 ? techo : pedida;
                } catch (NumberFormatException noEranSegundos) {
                    // El contrato lo declara en segundos. Una fecha HTTP se ignora.
                }
            }
        }
        return Duration.ZERO;
    }
}