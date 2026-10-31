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
        ponerSiHay(fila, "partida", resultado.partida());
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
     * Si este destino ya tenia el pago, reescribe su fila tal cual y devuelve
     * true para que no se le vuelva a enviar.
     *
     * <p>La reescritura no es decorativa y por eso el metodo no se llama
     * "yaEntregado": el monolito se entera por el stream de esta tabla, y si
     * el consumer se saltara el envio sin escribir nada, el stream no
     * disparara. El pago quedaria entregado de verdad pero ENCOLADO para
     * siempre del otro lado, y el producer lo reclamaria cada media hora sin
     * que nadie rompiera el circulo.
     *
     * <p>Se reescribe la fila entera, no una version recortada: asi no se
     * pierde el numero de recibo ni la partida contable que trajo el envio
     * original.
     */
    public boolean reconfirmar(String llave, Destino destino) {
        try {
            GetItemResponse respuesta = dynamo.getItem(GetItemRequest.builder()
                    .tableName(props.tablaEstado())
                    .key(Map.of("llave", texto(llave), "destino", texto(destino.name())))
                    // Lectura consistente: lo que interesa es lo que se acabo
                    // de escribir en la vuelta anterior, no una copia atrasada.
                    .consistentRead(true)
                    .build());

            if (!respuesta.hasItem()
                    || !ENTREGADO.equals(respuesta.item().getOrDefault("estado", texto("")).s())) {
                return false;
            }

            Map<String, AttributeValue> fila = new HashMap<>(respuesta.item());
            fila.put("actualizado_en", texto(OffsetDateTime.now(ZONA).toString()));
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(props.tablaEstado())
                    .item(fila)
                    .build());

            return true;
        } catch (RuntimeException e) {
            // Ante cualquier tropiezo se responde que no, y el pago se vuelve
            // a enviar. Es la salida segura: el destino contestara 409, eso
            // cuenta como entregado y la fila se escribe por el camino normal.
            log.warn("No se pudo reconfirmar llave={} destino={}: {}", llave, destino, e.getMessage());
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