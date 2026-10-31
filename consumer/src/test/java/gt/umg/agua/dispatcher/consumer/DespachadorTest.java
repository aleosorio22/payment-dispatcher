package gt.umg.agua.dispatcher.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gt.umg.agua.dispatcher.common.Destino;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DespachadorTest {

    @Mock
    private Gateway banco;

    @Mock
    private Gateway erp;

    @Mock
    private RepositorioLog bitacora;

    @Mock
    private RepositorioEstado estados;

    @Captor
    private ArgumentCaptor<Resultado> guardado;

    private final PagoMensaje pago = PagosDePrueba.unPago();

    /** Con el backoff en un milisegundo, tres intentos no demoran la prueba. */
    private static final ConsumerProps PROPS = new ConsumerProps(
            "EQUIPO_D", "pagos-envio", "pagos-envio-dlq", "pago_envio_log", "pago_envio_estado",
            new ConsumerProps.Reintentos(3, 1, 10, 5),
            new ConsumerProps.Simulacion("AUTO", "AUTO"));

    private Despachador despachador;

    @BeforeEach
    void prepararLosDestinos() {
        when(banco.destino()).thenReturn(Destino.BANCO);
        when(erp.destino()).thenReturn(Destino.ERP);
        despachador = new Despachador(List.of(erp, banco), bitacora, estados, PROPS);
    }

    @Test
    @DisplayName("entrega en los dos destinos y anota cada uno")
    void entregaEnLosDosDestinos() {
        when(banco.enviar(pago)).thenReturn(Resultado.entregado(Destino.BANCO, 201, "BNK-1"));
        when(erp.enviar(pago)).thenReturn(Resultado.entregado(Destino.ERP, 201, "ERP-1"));

        Despacho despacho = despachador.despachar(pago, 1);

        assertThat(despacho.todoEntregado()).isTrue();
        assertThat(despacho.hayQueVolverAIntentar()).isFalse();
        verify(bitacora, times(2)).registrar(eq(pago), any(), anyInt(), eq(1));
        verify(estados, times(2)).guardar(eq(pago), any(), eq(1));
    }

    @Test
    @DisplayName("el banco va antes que el ERP sin importar como vengan inyectados")
    void elOrdenEsFijo() {
        when(banco.enviar(pago)).thenReturn(Resultado.entregado(Destino.BANCO, 201, "BNK-1"));
        when(erp.enviar(pago)).thenReturn(Resultado.entregado(Destino.ERP, 201, "ERP-1"));

        // Se construyo con la lista al reves: List.of(erp, banco).
        Despacho despacho = despachador.despachar(pago, 1);

        assertThat(despacho.resultados()).extracting(Resultado::destino)
                .containsExactly(Destino.BANCO, Destino.ERP);
    }

    @Test
    @DisplayName("reintenta el fallo pasajero y lo logra en el segundo intento")
    void reintentaElFalloPasajero() {
        when(banco.enviar(pago))
                .thenReturn(Resultado.reintentable(Destino.BANCO, 503, "SERVICE_UNAVAILABLE",
                        "no disponible", Duration.ZERO))
                .thenReturn(Resultado.entregado(Destino.BANCO, 201, "BNK-1"));
        when(erp.enviar(pago)).thenReturn(Resultado.entregado(Destino.ERP, 201, "ERP-1"));

        Despacho despacho = despachador.despachar(pago, 1);

        assertThat(despacho.todoEntregado()).isTrue();
        verify(banco, times(2)).enviar(pago);
        // Los dos intentos quedan en la bitacora, no solo el que salio bien.
        verify(bitacora, times(3)).registrar(eq(pago), any(), anyInt(), eq(1));
    }

    @Test
    @DisplayName("no insiste con un rechazo definitivo")
    void noInsisteConUnRechazoDefinitivo() {
        when(banco.enviar(pago)).thenReturn(
                Resultado.definitivo(Destino.BANCO, 422, "UNSUPPORTED_CHANNEL", "canal no soportado"));
        when(erp.enviar(pago)).thenReturn(Resultado.entregado(Destino.ERP, 201, "ERP-1"));

        Despacho despacho = despachador.despachar(pago, 1);

        // Es el caso del cheque: el banco no lo recibe y reintentar da igual.
        verify(banco, times(1)).enviar(pago);
        assertThat(despacho.todoEntregado()).isFalse();
        assertThat(despacho.hayQueVolverAIntentar()).isFalse();
    }

    @Test
    @DisplayName("se rinde tras los tres intentos de esta vuelta")
    void seRindeTrasLosTresIntentos() {
        when(banco.enviar(pago)).thenReturn(Resultado.reintentable(
                Destino.BANCO, 500, "INTERNAL_ERROR", "error interno", Duration.ZERO));
        when(erp.enviar(pago)).thenReturn(Resultado.entregado(Destino.ERP, 201, "ERP-1"));

        Despacho despacho = despachador.despachar(pago, 1);

        verify(banco, times(3)).enviar(pago);
        // Sigue siendo reintentable: lo resuelve la cola, no esta vuelta.
        assertThat(despacho.hayQueVolverAIntentar()).isTrue();
    }

    @Test
    @DisplayName("no le vuelve a enviar al destino que ya lo tiene")
    void noLeVuelveAEnviarAlQueYaLoTiene() {
        when(estados.reconfirmar(pago.llave(), Destino.BANCO)).thenReturn(true);
        when(erp.enviar(pago)).thenReturn(Resultado.entregado(Destino.ERP, 201, "ERP-1"));

        Despacho despacho = despachador.despachar(pago, 2);

        verify(banco, never()).enviar(any());
        // Su fila no se guarda por el camino normal: la reescribe reconfirmar,
        // que conserva el recibo que trajo el envio original.
        verify(estados, times(1)).guardar(eq(pago), any(), anyInt());
        assertThat(despacho.todoEntregado()).isTrue();
    }

    @Test
    @DisplayName("al darlo por perdido, cierra en ERROR solo lo que no se entrego")
    void darPorPerdidoCierraSoloLoQueFalta() {
        Despacho despacho = new Despacho(List.of(
                Resultado.entregado(Destino.ERP, 201, "ERP-1"),
                Resultado.reintentable(Destino.BANCO, 503, "SERVICE_UNAVAILABLE", "no disponible",
                        Duration.ZERO)));

        despachador.darPorPerdido(pago, despacho, 5, "REINTENTOS_AGOTADOS");

        verify(estados, times(1)).guardar(eq(pago), guardado.capture(), eq(5));
        // Sin esto la fila del banco quedaria diciendo REINTENTANDO para
        // siempre, y nadie le avisaria al monolito.
        assertThat(guardado.getValue().destino()).isEqualTo(Destino.BANCO);
        assertThat(guardado.getValue().clasificacion()).isEqualTo(Clasificacion.DEFINITIVO);
        assertThat(guardado.getValue().codigo()).isEqualTo("REINTENTOS_AGOTADOS");
    }
}