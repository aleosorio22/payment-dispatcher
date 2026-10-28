package gt.umg.agua.dispatcher.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversiones de dinero entre las unidades que pide cada destino.
 *
 * <p>El banco cobra en centavos enteros y el ERP en texto con dos decimales.
 * Las dos conversiones viven aqui para que ningun gateway improvise la suya.
 *
 * <p>Todas usan {@link RoundingMode#UNNECESSARY}: si llega un monto con mas de
 * dos decimales, el metodo falla en vez de redondear. Un error ruidoso es
 * mejor que un quetzal que desaparece sin que nadie se entere.
 */
public final class Dinero {

    private Dinero() {
    }

    /**
     * Q150.75 se convierte en 15075.
     *
     * @throws ArithmeticException si el monto trae mas de dos decimales
     */
    public static long aCentavos(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2)
                .longValueExact();
    }

    /**
     * Q150.7 se convierte en "150.70".
     *
     * @throws ArithmeticException si el monto trae mas de dos decimales
     */
    public static String aTexto(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
