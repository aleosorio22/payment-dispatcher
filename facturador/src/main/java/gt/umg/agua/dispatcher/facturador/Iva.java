package gt.umg.agua.dispatcher.facturador;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * El desglose del IVA guatemalteco.
 *
 * <p>Las boletas de agua se cobran con el impuesto ya adentro: el vecino paga
 * Q50.40 y esos Q50.40 son el total de la factura. La SAT, en cambio, quiere
 * ver por separado cuanto es servicio y cuanto es impuesto.
 *
 * <p>El impuesto se calcula por resta y no multiplicando la base por la tasa.
 * Es la unica forma de que base + impuesto de exactamente el total cobrado: si
 * se calcularan los dos por separado, el redondeo los separaria un centavo y
 * la SAT rechazaria el documento.
 */
final class Iva {

    /** Doce por ciento, el IVA general. */
    private static final BigDecimal UNO_MAS_TASA = new BigDecimal("1.12");

    /** Los seis decimales que pide el esquema del NUC. */
    private static final int DECIMALES = 6;

    private Iva() {
    }

    /** De Q50.40 cobrados, Q45.00 son el servicio. */
    static BigDecimal base(BigDecimal conIva) {
        return conIva.divide(UNO_MAS_TASA, DECIMALES, RoundingMode.HALF_UP);
    }

    /** Y Q5.40 son impuesto. */
    static BigDecimal impuesto(BigDecimal conIva) {
        return conIva.setScale(DECIMALES, RoundingMode.HALF_UP).subtract(base(conIva));
    }

    /** Un monto como lo escribe el NUC: texto plano con seis decimales. */
    static String texto(BigDecimal monto) {
        return monto.setScale(DECIMALES, RoundingMode.HALF_UP).toPlainString();
    }
}
