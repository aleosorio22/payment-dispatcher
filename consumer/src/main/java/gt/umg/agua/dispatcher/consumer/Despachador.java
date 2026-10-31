package gt.umg.agua.dispatcher.consumer;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Le entrega el pago a cada destino y deja todo anotado.
 *
 * <p>Los reintentos de aqui son los de una misma recepcion del mensaje: tres
 * intentos seguidos con espera creciente. Si despues de eso el destino sigue
 * sin responder bien, el mensaje vuelve a la cola y lo intenta otra vuelta mas
 * tarde. Son dos niveles a proposito: el de aqui resuelve el bache de unos
 * segundos sin devolver el mensaje, y el de la cola resuelve la caida larga
 * sin tener un hilo esperando.
 *
 * <p>El peor caso de una vuelta cabe comodo en el visibility timeout de 120 s
 * de la cola: por destino son tres lecturas que cortan a los 3 s mas dos
 * esperas que no pasan de 10 s cada una.
 */
@Component
public class Despachador {

    private static final Logger log = LoggerFactory.getLogger(Despachador.class);

    private final List<Gateway> gateways;
    private final RepositorioLog bitacora;
    private final RepositorioEstado estados;
    private final ConsumerProps props;

    public Despachador(List<Gateway> gateways, RepositorioLog bitacora,
                       RepositorioEstado estados, ConsumerProps props) {
        // Orden fijo para que el log de una demo se lea siempre igual. Los
        // destinos son independientes: el orden no cambia el resultado.
        this.gateways = gateways.stream()
                .sorted(Comparator.comparing(gateway -> gateway.destino().name()))
                .toList();
        this.bitacora = bitacora;
        this.estados = estados;
        this.props = props;
    }

    public Despacho despachar(PagoMensaje pago, int recepcion) {
        List<Resultado> resultados = new ArrayList<>();

        for (Gateway gateway : gateways) {
            if (estados.reconfirmar(pago.llave(), gateway.destino())) {
                // reconfirmar reescribe la fila que ya estaba, con su recibo
                // intacto. Hace falta: el monolito se entera por el stream, y
                // saltarse el envio sin escribir nada lo dejaria esperando una
                // noticia que ya nadie le iba a dar.
                log.debug("{} ya lo tenia, no se le vuelve a enviar", gateway.destino());
                resultados.add(Resultado.yaEstaba(gateway.destino()));
                continue;
            }

            Resultado resultado = entregarConReintentos(pago, gateway, recepcion);
            estados.guardar(pago, resultado, recepcion);
            resultados.add(resultado);
        }

        return new Despacho(resultados);
    }

    /**
     * Cierra en ERROR lo que no se entrego.
     *
     * <p>Sin esto una fila quedaria diciendo REINTENTANDO para siempre, y la
     * Lambda nunca le avisaria al monolito que ese pago ya no va a salir.
     */
    public void darPorPerdido(PagoMensaje pago, Despacho despacho, int recepcion, String motivo) {
        for (Resultado resultado : despacho.resultados()) {
            if (resultado.fueEntregado()) {
                continue;
            }
            estados.guardar(pago, Resultado.definitivo(
                    resultado.destino(), resultado.httpStatus(), motivo, resultado.mensaje()), recepcion);
        }
    }

    private Resultado entregarConReintentos(PagoMensaje pago, Gateway gateway, int recepcion) {
        int maximo = props.reintentos().enMemoria();
        Resultado resultado = null;

        for (int intento = 1; intento <= maximo; intento++) {
            resultado = gateway.enviar(pago);
            bitacora.registrar(pago, resultado, intento, recepcion);

            if (resultado.clasificacion() != Clasificacion.REINTENTABLE) {
                // Entregado o rechazado de plano: insistir no cambia nada.
                return resultado;
            }

            log.warn("{} fallo en el intento {}/{}: {} {}",
                    gateway.destino(), intento, maximo, resultado.httpStatus(), resultado.mensaje());

            if (intento < maximo && !esperar(resultado, intento)) {
                break;
            }
        }

        return resultado;
    }

    /**
     * Espera lo que pidio el destino, o el backoff si no pidio nada.
     *
     * @return false si se interrumpio la espera, para soltar el pago en vez de
     *         seguir insistiendo
     */
    private boolean esperar(Resultado resultado, int intento) {
        long backoff = props.reintentos().backoffInicialMs() * (1L << (intento - 1));
        long milis = resultado.espera().isZero() ? backoff : resultado.espera().toMillis();

        try {
            Thread.sleep(milis);
            return true;
        } catch (InterruptedException apagando) {
            // El servicio se esta deteniendo. El pago sigue en la cola y lo
            // atiende la siguiente instancia: nada se pierde.
            Thread.currentThread().interrupt();
            return false;
        }
    }
}