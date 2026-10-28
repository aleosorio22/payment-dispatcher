package gt.umg.agua.dispatcher.producer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que el producer lee del application.yml, con tipos.
 *
 * <p>Si falta una propiedad o trae un valor que no convierte, la aplicacion
 * no arranca y dice cual. Es mejor que descubrirlo a las tres de la manana
 * porque los pagos dejaron de encolarse.
 *
 * @param laravel    como alcanzar la API de integracion del monolito
 * @param colaPagos  nombre de la cola SQS donde se publica
 * @param reclamo    cada cuanto preguntar y cuantos pagos pedir por vez
 */
@ConfigurationProperties("app")
public record ProducerProps(Laravel laravel, String colaPagos, Reclamo reclamo) {

    public record Laravel(String baseUrl, String token) {}

    public record Reclamo(long intervaloMs, int limite) {}
}