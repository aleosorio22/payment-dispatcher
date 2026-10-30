package gt.umg.agua.dispatcher.consumer;

import static gt.umg.agua.dispatcher.consumer.Atributos.numero;
import static gt.umg.agua.dispatcher.consumer.Atributos.ponerSiHay;
import static gt.umg.agua.dispatcher.consumer.Atributos.texto;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * El historial: una fila por cada vez que se le hablo a un destino.
 *
 * <p>Es lo que contesta "que paso con el pago del vecino" sin tener que leer
 * logs. Se consulta por {@code llave} y sale la conversacion completa en orden
 * cronologico, con el codigo que devolvio cada destino en cada intento.
 *
 * <p>Nunca se lee desde el consumer y nunca se modifica una fila existente:
 * esta tabla solo crece.
 */
@Component
public class RepositorioLog {

    private static final Logger log = LoggerFactory.getLogger(RepositorioLog.class);

    /** Noventa dias alcanzan para cualquier reclamo y la tabla no crece sin fin. */
    private static final Duration RETENCION = Duration.ofDays(90);

    private final DynamoDbClient dynamo;
    private final ConsumerProps props;

    public RepositorioLog(DynamoDbClient dynamo, ConsumerProps props) {
        this.dynamo = dynamo;
        this.props = props;
    }

    /**
     * Anota un intento.
     *
     * @param intento   numero de intento dentro de esta recepcion del mensaje
     * @param recepcion cuantas veces SQS ha entregado este mensaje
     */
    public void registrar(PagoMensaje pago, Resultado resultado, int intento, int recepcion) {
        Instant ahora = Instant.now();

        Map<String, AttributeValue> fila = new HashMap<>();
        fila.put("llave", texto(pago.llave()));
        // La clave de rango lleva el destino pegado: el banco y el ERP se
        // atienden en la misma vuelta y podrian caer en el mismo nanosegundo.
        fila.put("ts", texto(Atributos.marcaDe(ahora) + "#" + resultado.destino().name()));
        fila.put("destino", texto(resultado.destino().name()));
        fila.put("clasificacion", texto(resultado.clasificacion().name()));
        fila.put("http_status", numero(resultado.httpStatus()));
        fila.put("intento", numero(intento));
        fila.put("recepcion", numero(recepcion));
        fila.put("ronda", numero(pago.ronda()));
        fila.put("pago_id", numero(pago.pagoId()));
        fila.put("ttl", numero(ahora.plus(RETENCION).getEpochSecond()));
        ponerSiHay(fila, "codigo", resultado.codigo());
        ponerSiHay(fila, "mensaje", Textos.recortar(resultado.mensaje(), 500));
        ponerSiHay(fila, "referencia", resultado.referencia());
        ponerSiHay(fila, "correlation_id", pago.correlationId());

        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(props.tablaLog())
                    .item(fila)
                    .build());
        } catch (RuntimeException e) {
            // El historial sirve para auditar, no para decidir. Si DynamoDB no
            // responde, el pago se entrega igual: perder una linea de bitacora
            // es mucho menos grave que dejar de cobrar.
            log.warn("No se pudo anotar el intento llave={} destino={}: {}",
                    pago.llave(), resultado.destino(), e.getMessage());
        }
    }
}