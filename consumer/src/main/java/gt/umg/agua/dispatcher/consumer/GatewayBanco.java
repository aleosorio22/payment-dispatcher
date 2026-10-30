package gt.umg.agua.dispatcher.consumer;

import feign.FeignException;
import feign.RetryableException;
import gt.umg.agua.dispatcher.banco.api.BankApiClient;
import gt.umg.agua.dispatcher.banco.model.BankPaymentReceipt;
import gt.umg.agua.dispatcher.banco.model.BankPaymentRequest;
import gt.umg.agua.dispatcher.banco.model.Money;
import gt.umg.agua.dispatcher.banco.model.Payer;
import gt.umg.agua.dispatcher.banco.model.TeamCode;
import gt.umg.agua.dispatcher.common.Destino;
import gt.umg.agua.dispatcher.common.Dinero;
import gt.umg.agua.dispatcher.common.FormaPago;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Traduce el pago canonico al contrato del banco y lo entrega.
 *
 * <p>El banco cobra en centavos enteros, y este es el unico lugar de todo el
 * sistema donde un quetzal se convierte a entero.
 *
 * <p>La llave del mensaje viaja como {@code Idempotency-Key}. Es lo que hace
 * que reintentar sea seguro: el segundo envio del mismo pago devuelve 409
 * {@code DUPLICATE_PAYMENT} en vez de cobrarle dos veces al vecino.
 */
@Component
public class GatewayBanco implements Gateway {

    private final BankApiClient banco;
    private final Clasificador clasificador;
    private final ConsumerProps props;

    public GatewayBanco(BankApiClient banco, Clasificador clasificador, ConsumerProps props) {
        this.banco = banco;
        this.clasificador = clasificador;
        this.props = props;
    }

    @Override
    public Destino destino() {
        return Destino.BANCO;
    }

    @Override
    public Resultado enviar(PagoMensaje pago) {
        try {
            ResponseEntity<BankPaymentReceipt> respuesta = banco.sendBankPayment(
                    pago.llave(),
                    cuerpo(pago),
                    pago.correlationId(),
                    props.simulacion().escenarioBanco());

            BankPaymentReceipt recibo = respuesta.getBody();
            return Resultado.entregado(
                    Destino.BANCO,
                    respuesta.getStatusCode().value(),
                    recibo == null ? "" : recibo.getReceiptId());

        } catch (RetryableException sinRespuesta) {
            // Feign la lanza cuando no llego nada que leer. Hay que atraparla
            // antes que FeignException, porque es una subclase y su status es
            // negativo.
            return Resultado.sinRespuesta(Destino.BANCO, sinRespuesta.getMessage());

        } catch (FeignException e) {
            return interpretar(e);
        }
    }

    private Resultado interpretar(FeignException e) {
        int status = e.status();
        String codigo = clasificador.campoDeTexto(e, "code");
        String mensaje = mensajeDe(e, status);

        return switch (clasificador.clasificar(status, codigo)) {
            case ENTREGADO -> Resultado.duplicado(Destino.BANCO, codigo, mensaje);
            case REINTENTABLE -> Resultado.reintentable(Destino.BANCO, status, codigo, mensaje,
                    clasificador.esperaPedida(e, Duration.ofMillis(props.reintentos().retryAfterMaxMs())));
            case DEFINITIVO -> Resultado.definitivo(Destino.BANCO, status, codigo, mensaje);
        };
    }

    /** El del contrato si vino; nunca el de FeignException, que trae el cuerpo entero. */
    private String mensajeDe(FeignException e, int status) {
        String delContrato = clasificador.campoDeTexto(e, "message");
        return delContrato.isEmpty() ? "HTTP " + status : delContrato;
    }

    private BankPaymentRequest cuerpo(PagoMensaje pago) {
        Payer pagador = new Payer(pago.cliente().codigo())
                .fullName(Textos.recortar(pago.cliente().nombre(), 150))
                .taxId(Textos.recortar(pago.cliente().nit(), 20));

        Money monto = new Money(Dinero.aCentavos(pago.total()), Money.CurrencyEnum.GTQ);

        return new BankPaymentRequest(
                TeamCode.fromValue(props.equipo()),
                pagador,
                monto,
                pago.fechaPago(),
                canal(pago.formaPago()))
                .description(Textos.recortar(pago.descripcion(), 140))
                // El banco no modela el recibo ni el periodo, pero sin ellos
                // nadie puede amarrar un movimiento del estado de cuenta con
                // el papel que el vecino tiene en la mano.
                .metadata(Map.of(
                        "folio", pago.folio(),
                        "periodo", "%04d-%02d".formatted(pago.periodo().anio(), pago.periodo().mes()),
                        "contador", pago.contador()));
    }

    private BankPaymentRequest.ChannelEnum canal(FormaPago formaPago) {
        return switch (formaPago) {
            case EFECTIVO -> BankPaymentRequest.ChannelEnum.CASH;
            case TARJETA -> BankPaymentRequest.ChannelEnum.CARD;
            case TRANSFERENCIA -> BankPaymentRequest.ChannelEnum.BANK_TRANSFER;
            case CHEQUE -> BankPaymentRequest.ChannelEnum.CHECK;
        };
    }
}