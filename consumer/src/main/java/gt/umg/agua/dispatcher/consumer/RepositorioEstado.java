package gt.umg.agua.dispatcher.consumer;

import static gt.umg.agua.dispatcher.consumer.Atributos.numero;
import static gt.umg.agua.dispatcher.consumer.Atributos.ponerSiHay;
import static gt.umg.agua.dispatcher.consumer.Atributos.texto;

import gt.umg.agua.dispatcher.common.Destino;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * El veredicto actual de cada destino: una fila por par (llave, destino).
 *
 * <p>A diferencia del historial, aqui cada fila se sobreescribe. Lo que
 * interesa es en que quedo el envio, no cuantas vueltas dio.
 *
 * <p>Esta tabla tiene el stream encendido. La Lambda de la fase siguiente lo
 * lee y le avisa al monolito, y asi el pago pasa de ENCOLADO a ENVIADO sin que
 * el consumer tenga que hablarle a Laravel.
 */
@Component
public class RepositorioEstado {

    private static final Logger log = LoggerFactory.getLogger(RepositorioEstado.class);

    /** La hora que se guarda es la de la oficina, que es la que se va a leer. */
    private static final ZoneId ZONA = ZoneId.of("America/Guatemala");

    private static final String ENTREGADO = "ENTREGADO";

    private final DynamoDbClient dynamo;
    private final ConsumerProps props;

    public RepositorioEstado(DynamoDbClient dynamo, ConsumerProps props) {
        this.dynamo = dynamo;
        this.props = props;
    }

    /**
     * Deja asentado como quedo este destino.
     *
     * <p>A proposito no atrapa el fallo de DynamoDB. Si el veredicto no se
     * puede guardar, el mensaje tiene que volver a la cola: dar por entregado
     * un pago cuyo estado nadie anoto es como no haberlo entregado. Reintentar
     * es inofensivo porque el destino responde 409 y eso tambien es entregado.
     */
    public void guardar(PagoMensaje pago, Resultado resultado, int recepcion) {
        Map<String, AttributeValue> fila = new HashMap<>();
        fila.put("llave", texto(pago.llave()));
        fila.put("destino", texto(resultado.destino().name()));
        fila.put("estado", texto(estadoDe(resultado.clasificacion())));
        fila.put("http_status", numero(resultado.httpStatus()));
        fila.put("recepciones", numero(recepcion));
        fila.put("ronda", numero(pago.ronda()));
        fila.put("pago_id", numero(pago.pagoId()));
        fila.put("actualizado_en", texto(OffsetDateTime.now(ZONA).toString()));
        ponerSiHay(fila, "codigo", resultado.codigo());
        ponerSiHay(fila, "mensaje", Textos.recortar(resultado.mensaje(), 500));
        ponerSiHay(fila, "referencia", resultado.referencia());
        ponerSiHay(fila, "correlation_id", pago.correlationId());
        // Para poder buscar en la consola sin cruzar contra la base de datos.
        ponerSiHay(fila, "folio", pago.folio());
        ponerSiHay(fila, "cliente", pago.cliente().codigo());

        dynamo.putItem(PutItemRequest.builder()
                .tableName(props.tablaEstado())
                .item(fila)
                .build());
    }

    /**
     * Si un destino ya dijo que lo tiene, no hay para que volver a molestarlo.
     *
     * <p>Pasa de verdad: el banco acepta, el ERP devuelve 503, el mensaje
     * vuelve a la cola y en la siguiente vuelta habria que reenviarle al banco
     * un pago que ya registro. El 409 lo resolveria igual, pero esta consulta
     * sale mas barata que una vuelta a internet.
     */
    public boolean yaEntregado(String llave, Destino destino) {
        try {
            GetItemResponse respuesta = dynamo.getItem(GetItemRequest.builder()
                    .tableName(props.tablaEstado())
                    .key(Map.of("llave", texto(llave), "destino", texto(destino.name())))
                    .projectionExpression("estado")
                    // Lectura consistente: lo que interesa es lo que se acabo
                    // de escribir en la vuelta anterior, no una copia atrasada.
                    .consistentRead(true)
                    .build());

            return respuesta.hasItem()
                    && ENTREGADO.equals(respuesta.item().getOrDefault("estado", texto("")).s());
        } catch (RuntimeException e) {
            // Si no se puede consultar, se asume que falta entregar. Un
            // reenvio es inofensivo gracias a la llave de idempotencia; darlo
            // por entregado sin saberlo, no.
            log.warn("No se pudo leer el estado llave={} destino={}: {}", llave, destino, e.getMessage());
            return false;
        }
    }

    /**
     * El vocabulario de la tabla no es el de la clasificacion: aqui interesa en
     * que quedo el envio, no que se decidio en el momento.
     */
    private String estadoDe(Clasificacion clasificacion) {
        return switch (clasificacion) {
            case ENTREGADO -> ENTREGADO;
            case REINTENTABLE -> "REINTENTANDO";
            case DEFINITIVO -> "ERROR";
        };
    }
}