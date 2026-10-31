package gt.umg.agua.dispatcher.facturador;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IvaTest {

    @Test
    @DisplayName("de Q50.40 cobrados, Q45.00 son servicio y Q5.40 impuesto")
    void desglosaUnMontoRedondo() {
        BigDecimal cobrado = new BigDecimal("50.40");

        assertThat(Iva.base(cobrado)).isEqualByComparingTo("45.00");
        assertThat(Iva.impuesto(cobrado)).isEqualByComparingTo("5.40");
    }

    @ParameterizedTest
    @ValueSource(strings = {"50.40", "37.65", "0.01", "1.00", "102.37", "1234.56", "9999.99"})
    @DisplayName("base mas impuesto siempre da exactamente lo cobrado")
    void elDesgloseSiempreCuadra(String monto) {
        BigDecimal cobrado = new BigDecimal(monto);

        BigDecimal reconstruido = Iva.base(cobrado).add(Iva.impuesto(cobrado));

        // Es la razon de calcular el impuesto por resta. Si se calculara
        // multiplicando la base por 0.12, el redondeo separaria los dos
        // numeros un centavo y la SAT rechazaria el documento.
        assertThat(reconstruido).isEqualByComparingTo(cobrado);
    }

    @Test
    @DisplayName("escribe los montos con los seis decimales que pide el esquema")
    void escribeConSeisDecimales() {
        assertThat(Iva.texto(new BigDecimal("50.40"))).isEqualTo("50.400000");
        assertThat(Iva.texto(new BigDecimal("45"))).isEqualTo("45.000000");
        // Sin notacion cientifica, que es lo que haria toString con montos chicos.
        assertThat(Iva.texto(new BigDecimal("0.01"))).isEqualTo("0.010000");
    }

    @Test
    @DisplayName("un monto con decimales feos reparte el redondeo en el impuesto")
    void repartElRedondeoEnElImpuesto() {
        BigDecimal cobrado = new BigDecimal("37.65");

        assertThat(Iva.base(cobrado)).isEqualByComparingTo("33.616071");
        assertThat(Iva.impuesto(cobrado)).isEqualByComparingTo("4.033929");
    }
}
