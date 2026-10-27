package gt.umg.agua.dispatcher.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DineroTest {

    @Test
    @DisplayName("convierte quetzales a centavos enteros, que es lo que pide el banco")
    void convierteACentavos() {
        assertThat(Dinero.aCentavos(new BigDecimal("150.75"))).isEqualTo(15075);
        assertThat(Dinero.aCentavos(new BigDecimal("0.10"))).isEqualTo(10);
        assertThat(Dinero.aCentavos(new BigDecimal("89"))).isEqualTo(8900);
    }

    @Test
    @DisplayName("completa los dos decimales que exige el ERP")
    void formateaConDosDecimales() {
        assertThat(Dinero.aTexto(new BigDecimal("89.5"))).isEqualTo("89.50");
        assertThat(Dinero.aTexto(new BigDecimal("102.37"))).isEqualTo("102.37");
        assertThat(Dinero.aTexto(new BigDecimal("7"))).isEqualTo("7.00");
    }

    @Test
    @DisplayName("falla en vez de redondear un monto con mas de dos decimales")
    void rechazaMasDeDosDecimales() {
        BigDecimal conTresDecimales = new BigDecimal("150.755");

        assertThatThrownBy(() -> Dinero.aCentavos(conTresDecimales))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Dinero.aTexto(conTresDecimales))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("no pierde precision en montos grandes")
    void conservaLaPrecision() {
        assertThat(Dinero.aCentavos(new BigDecimal("99999999.99"))).isEqualTo(9999999999L);
    }
}
