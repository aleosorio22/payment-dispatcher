package gt.umg.agua.dispatcher.consumer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Armado de los atributos de DynamoDB.
 *
 * <p>Las dos tablas son chicas y de esquema fijo, asi que se escriben con el
 * cliente de bajo nivel y un mapa. Lo que queda guardado esta a la vista en el
 * repositorio, sin anotaciones ni beans mutables de por medio.
 */
final class Atributos {

    /**
     * La marca de tiempo de la clave de rango: UTC y nueve digitos de nanos,
     * siempre.
     *
     * <p>El ancho fijo no es decoracion. DynamoDB ordena la clave de rango
     * como texto y un ISO normal omite los ceros de la fraccion: asi
     * {@code ...:01.1Z} quedaria antes de {@code ...:01Z}, porque el punto
     * pesa menos que la Z, y el historial de un pago se leeria desordenado.
     */
    static final DateTimeFormatter MARCA = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Atributos() {
    }

    static AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    static AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }

    /** Un atributo vacio no dice nada y estorba al leer la tabla a mano. */
    static void ponerSiHay(Map<String, AttributeValue> atributos, String nombre, String valor) {
        if (valor != null && !valor.isBlank()) {
            atributos.put(nombre, texto(valor));
        }
    }

    static String marcaDe(Instant instante) {
        return MARCA.format(instante);
    }
}