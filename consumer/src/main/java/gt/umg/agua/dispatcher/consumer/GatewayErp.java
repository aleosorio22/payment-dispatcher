package gt.umg.agua.dispatcher.consumer;

import feign.FeignException;
import feign.RetryableException;
import gt.umg.agua.dispatcher.common.Destino;
import gt.umg.agua.dispatcher.common.Dinero;
import gt.umg.agua.dispatcher.common.FormaPago;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import gt.umg.agua.dispatcher.erp.api.ErpApiClient;
import gt.umg.agua.dispatcher.erp.model.ErpComprobante;
import gt.umg.agua.dispatcher.erp.model.ErpContribuyente;
import gt.umg.agua.dispatcher.erp.model.ErpDocumentoOrigen;
import gt.umg.agua.dispatcher.erp.model.ErpEquipo;
import gt.umg.agua.dispatcher.erp.model.ErpLineaDetalle;
import gt.umg.agua.dispatcher.erp.model.ErpPeriodo;
import gt.umg.agua.dispatcher.erp.model.ErpRegistroPago;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Traduce el pago canonico al contrato del ERP municipal y lo entrega.
 *
 * <p>El ERP no tiene cabecera de idempotencia: su llave de unicidad es el
 * {@code documento_origen.numero}, o sea el folio del recibo. Por eso el folio
 * tiene que ser estable entre reintentos, igual que la llave del banco.
 *
 * <p>Los montos van como texto con dos decimales porque el ERP los valida
 * contra una expresion regular. Un numero JSON no pasaria.
 */
@Component
public class GatewayErp implements Gateway {

    private final ErpApiClient erp;
    private final Clasificador clasificador;
    private final ConsumerProps props;

    public GatewayErp(ErpApiClient erp, Clasificador clasificador, ConsumerProps props) {
        this.erp = erp;
        this.clasificador = clasificador;
        this.props = props;
    }

    @Override
    public Destino destino() {
        return Destino.ERP;
    }

    @Override
    public Resultado enviar(PagoMensaje pago) {
        try {
            ResponseEntity<ErpComprobante> respuesta = erp.registrarPagoErp(
                    cuerpo(pago),
                    pago.correlationId(),
                    props.simulacion().escenarioErp());

            ErpComprobante comprobante = respuesta.getBody();
            if (comprobante == null) {
                return Resultado.entregado(Destino.ERP, respuesta.getStatusCode().value(), "");
            }
            // La partida es el asiento contable donde quedo el pago. El
            // monolito la guarda aparte del comprobante porque es lo que la
            // municipalidad usa para cuadrar sus libros.
            return Resultado
                    .entregado(Destino.ERP, respuesta.getStatusCode().value(),
                            comprobante.getNumeroComprobante())
                    .conPartida(comprobante.getNumeroPartida());

        } catch (RetryableException sinRespuesta) {
            return Resultado.sinRespuesta(Destino.ERP, sinRespuesta.getMessage());

        } catch (FeignException e) {
            return interpretar(e);
        }
    }

    private Resultado interpretar(FeignException e) {
        int status = e.status();
        // El ERP nombra sus campos en espanol: el mismo contrato, otras llaves.
        String codigo = clasificador.campoDeTexto(e, "codigo_error");
        String delContrato = clasificador.campoDeTexto(e, "mensaje");
        String mensaje = delContrato.isEmpty() ? "HTTP " + status : delContrato;

        return switch (clasificador.clasificar(status, codigo)) {
            case ENTREGADO -> Resultado.duplicado(Destino.ERP, codigo, mensaje);
            case REINTENTABLE -> Resultado.reintentable(Destino.ERP, status, codigo, mensaje,
                    clasificador.esperaPedida(e, Duration.ofMillis(props.reintentos().retryAfterMaxMs())));
            case DEFINITIVO -> Resultado.definitivo(Destino.ERP, status, codigo, mensaje);
        };
    }

    private ErpRegistroPago cuerpo(PagoMensaje pago) {
        ErpDocumentoOrigen documento = new ErpDocumentoOrigen(
                tipoDocumento(pago.formaPago()), pago.folio(), pago.fechaEmision());

        ErpContribuyente contribuyente = new ErpContribuyente(pago.cliente().codigo())
                .codigoContador(pago.contador())
                .nit(Textos.recortar(pago.cliente().nit(), 20));

        // Los rubros del canonico se llaman igual que los del ERP a proposito,
        // asi que name() basta y no hace falta un switch que haya que recordar
        // actualizar.
        List<ErpLineaDetalle> detalle = pago.detalle().stream()
                .map(linea -> new ErpLineaDetalle(
                        ErpLineaDetalle.RubroEnum.fromValue(linea.rubro().name()),
                        Dinero.aTexto(linea.monto()))
                        .descripcion(Textos.recortar(linea.descripcion(), 120)))
                .toList();

        return new ErpRegistroPago(
                ErpEquipo.fromValue(props.equipo()),
                documento,
                contribuyente,
                new ErpPeriodo(pago.periodo().anio(), pago.periodo().mes()),
                ErpRegistroPago.FormaPagoEnum.fromValue(pago.formaPago().name()),
                pago.fechaPago(),
                Dinero.aTexto(pago.total()),
                detalle)
                .observaciones(Textos.recortar(pago.descripcion(), 250));
    }

    private ErpDocumentoOrigen.TipoEnum tipoDocumento(FormaPago formaPago) {
        return switch (formaPago) {
            case EFECTIVO -> ErpDocumentoOrigen.TipoEnum.RECIBO_CAJA;
            case TARJETA -> ErpDocumentoOrigen.TipoEnum.VOUCHER_TARJETA;
            // Un cheque se deposita, y lo que queda de respaldo es la boleta.
            case TRANSFERENCIA, CHEQUE -> ErpDocumentoOrigen.TipoEnum.BOLETA_DEPOSITO;
        };
    }
}