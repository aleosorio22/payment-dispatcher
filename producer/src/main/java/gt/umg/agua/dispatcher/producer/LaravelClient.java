package gt.umg.agua.dispatcher.producer;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Habla con la API de integracion del monolito.
 *
 * <p>Reclamar no es una consulta: el monolito marca los pagos como ENCOLADO
 * en la misma transaccion en que los entrega, asi que una segunda llamada ya
 * no los trae. Por eso el verbo es POST y no GET.
 */
@Component
public class LaravelClient {

    private final RestClient rest;

    public LaravelClient(RestClient.Builder builder, ProducerProps props) {
        this.rest = builder
                .baseUrl(props.laravel().baseUrl())
                // La cerradura que revisa el middleware TokenIntegracion.
                .defaultHeader("X-Integracion-Token", props.laravel().token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * @return los pagos que el monolito acaba de entregar, o una lista vacia
     */
    public List<PagoMensaje> reclamar(int limite) {
        RespuestaReclamo respuesta = rest.post()
                .uri(uri -> uri.path("/pagos/reclamar").queryParam("limite", limite).build())
                .retrieve()
                .body(RespuestaReclamo.class);

        return respuesta == null || respuesta.data() == null
                ? List.of()
                : respuesta.data();
    }

    /** Laravel envuelve toda coleccion en `data`; este record es solo ese sobre. */
    record RespuestaReclamo(List<PagoMensaje> data) {}
}