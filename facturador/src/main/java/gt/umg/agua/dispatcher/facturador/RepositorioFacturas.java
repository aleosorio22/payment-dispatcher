package gt.umg.agua.dispatcher.facturador;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Lo que se emitio, una fila por pago.
 *
 * <p>No se guarda el PDF ni el XML: con el numero de autorizacion, Digifact
 * los devuelve cuando se le pidan por su operacion GET DOCUMENT. Guardar aqui
 * ciento setenta kilobytes por factura seria pagar por almacenar algo que ya
 * esta almacenado del otro lado.
 */
@Component
public class RepositorioFacturas {

    /** La hora que se guarda es la de la oficina, que es la que se va a leer. */
    private static final ZoneId ZONA = ZoneId.of("America/Guatemala");

    private static final String CERTIFICADA = "CERTIFICADA";

    private final DynamoDbClient dynamo;
    private final FacturadorProps props;

    public RepositorioFacturas(DynamoDbClient dynamo, FacturadorProps props) {
        this.dynamo = dynamo;
        this.props = props;
    }

    /**
     * Si este pago ya tiene factura.
     *
     * <p>Es la primera linea de defensa contra el doble cobro fiscal: SQS
     * puede entregar el mismo mensaje dos veces, y una factura emitida dos
     * veces no se borra, hay que anularla con otro documento.
     *
     * <p>Ante cualquier tropiezo responde que no, y eso es deliberado: si la
     * consulta falla, el riesgo de no facturar es peor que el de revisar dos
     * veces, porque Digifact tambien rechaza los duplicados por su lado.
     */
    public boolean yaFacturado(String llave) {
        try {
            GetItemResponse respuesta = dynamo.getItem(GetItemRequest.builder()
                    .tableName(props.tablaFacturas())
                    .key(Map.of("llave", texto(llave)))
                    .projectionExpression("estado")
                    .consistentRead(true)
                    .build());

            return respuesta.hasItem()
                    && CERTIFICADA.equals(respuesta.item().getOrDefault("estado", texto("")).s());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Deja escrito el resultado.
     *
     * <p>Si esto falla, se propaga: el mensaje vuelve a la cola y se vuelve a
     * intentar. Una factura certificada cuyo numero de autorizacion nadie
     * anoto es, para la oficina, una factura perdida.
     */
    public void guardar(PagoMensaje pago, Certificacion certificacion) {
        Map<String, AttributeValue> fila = new HashMap<>();
        fila.put("llave", texto(pago.llave()));
        fila.put("estado", texto(certificacion.certificada() ? CERTIFICADA : "RECHAZADA"));
        fila.put("pago_id", numero(pago.pagoId()));
        fila.put("folio", texto(pago.folio()));
        fila.put("cliente", texto(pago.cliente().codigo()));
        fila.put("nit_receptor", texto(pago.cliente().nit()));
        fila.put("total", texto(pago.total().toPlainString()));
        fila.put("periodo", texto("%04d-%02d".formatted(pago.periodo().anio(), pago.periodo().mes())));
        fila.put("actualizado_en", texto(OffsetDateTime.now(ZONA).toString()));

        if (certificacion.certificada()) {
            fila.put("numero_autorizacion", texto(certificacion.numeroAutorizacion()));
            ponerSiHay(fila, "serie", certificacion.serie());
            ponerSiHay(fila, "correlativo", certificacion.correlativo());
            ponerSiHay(fila, "certificada_en", certificacion.certificadaEn());
        } else {
            fila.put("codigo_error", numero(certificacion.codigo()));
            ponerSiHay(fila, "mensaje_error", recortar(certificacion.mensaje()));
        }

        dynamo.putItem(PutItemRequest.builder()
                .tableName(props.tablaFacturas())
                .item(fila)
                .build());
    }

    private static String recortar(String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= 500 ? texto : texto.substring(0, 500);
    }

    private static void ponerSiHay(Map<String, AttributeValue> fila, String nombre, String valor) {
        if (valor != null && !valor.isBlank()) {
            fila.put(nombre, texto(valor));
        }
    }

    private static AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor == null ? "" : valor).build();
    }

    private static AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }
}
