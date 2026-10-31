package gt.umg.agua.dispatcher.facturador;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Convierte un pago cobrado en el documento que Digifact espera.
 *
 * <p>Es el equivalente de los gateways del consumer: el pago canonico no se
 * parece al NUC y nunca deberia parecerse. Toda la traduccion vive aqui.
 */
@Component
public class ConstructorNuc {

    /** Factura. Es el unico tipo que emite la oficina de agua. */
    private static final String TIPO_DOCUMENTO = "FACT";

    private static final String MONEDA = "GTQ";

    /** El que dice "sin IVA" tiene codigo 2; el agua lleva IVA general. */
    private static final String CODIGO_IVA = "1";

    /** Una unidad de servicio por linea: la boleta ya trae el monto del mes. */
    private static final String CANTIDAD = "1.000000";

    private static final DateTimeFormatter EMISION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final FacturadorProps props;

    public ConstructorNuc(FacturadorProps props) {
        this.props = props;
    }

    public Nuc construir(PagoMensaje pago) {
        return new Nuc(
                "1.00",
                "GT",
                new Nuc.Header(TIPO_DOCUMENTO, pago.fechaPago().format(EMISION), MONEDA),
                emisor(),
                receptor(pago),
                null,
                items(pago),
                totales(pago),
                adenda(pago));
    }

    private Nuc.Emisor emisor() {
        FacturadorProps.Emisor e = props.emisor();
        return new Nuc.Emisor(
                e.nit(),
                List.of(new Nuc.Atributo("AfiliacionIVA", null, e.afiliacionIva())),
                e.nombre(),
                Nuc.Contacto.de(e.correo()),
                // Las dos mitades de la frase llevan el mismo "data": es el
                // numero de frase que las une. Separadas, Digifact ve dos
                // frases incompletas y rechaza el documento.
                List.of(
                        new Nuc.Atributo("TipoFrase", "1", e.tipoFrase()),
                        new Nuc.Atributo("Escenario", "1", e.escenario())),
                new Nuc.Establecimiento(e.establecimiento(), e.nombreEstablecimiento(), direccion()));
    }

    private Nuc.Direccion direccion() {
        FacturadorProps.Direccion d = props.emisor().direccion();
        return new Nuc.Direccion(d.calle(), d.codigoPostal(), d.municipio(), d.departamento(), d.pais());
    }

    private Nuc.Receptor receptor(PagoMensaje pago) {
        String nit = pago.cliente().nit() == null || pago.cliente().nit().isBlank()
                ? "CF"
                : pago.cliente().nit().trim().toUpperCase();

        // Con NIT propio va el nombre del vecino; sin el, la SAT espera la
        // leyenda de consumidor final y no un nombre cualquiera.
        String nombre = "CF".equals(nit) ? "CONSUMIDOR FINAL" : pago.cliente().nombre();

        return new Nuc.Receptor(nit, nombre, direccion(), Nuc.Contacto.de(correoDe(pago)));
    }

    /**
     * A donde va la factura. En pruebas, a un buzon unico; en produccion, al
     * vecino, para no llenarle el correo a nadie mientras se ensaya.
     */
    private String correoDe(PagoMensaje pago) {
        String fijo = props.correoReceptor();
        if (fijo != null && !fijo.isBlank()) {
            return fijo;
        }
        return pago.cliente().email();
    }

    private List<Nuc.Item> items(PagoMensaje pago) {
        List<Nuc.Item> items = new ArrayList<>();
        int numero = 1;

        for (PagoMensaje.LineaDetalle linea : pago.detalle()) {
            BigDecimal monto = linea.monto();
            items.add(new Nuc.Item(
                    String.valueOf(numero++),
                    null,
                    "Servicio",
                    linea.descripcion(),
                    CANTIDAD,
                    "SER",
                    Iva.texto(monto),
                    null,
                    new Nuc.Impuestos(List.of(new Nuc.Impuesto(
                            CODIGO_IVA, "IVA", Iva.texto(Iva.base(monto)), Iva.texto(Iva.impuesto(monto))))),
                    new Nuc.TotalItem(Iva.texto(monto))));
        }

        return items;
    }

    private Nuc.Totales totales(PagoMensaje pago) {
        // El impuesto total se suma linea por linea en vez de calcularse sobre
        // el gran total: asi coincide con la suma de los items, que es lo que
        // la SAT compara.
        BigDecimal impuesto = pago.detalle().stream()
                .map(linea -> Iva.impuesto(linea.monto()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Nuc.Totales(
                new Nuc.TotalImpuestos(List.of(new Nuc.TotalImpuesto("IVA", Iva.texto(impuesto)))),
                new Nuc.GranTotal(Iva.texto(pago.total())));
    }

    /**
     * Digifact exige el bloque de adenda aunque no se use. Se aprovecha para
     * dejar escrito el folio del recibo que el vecino tiene en la mano, que es
     * lo que va a traer cuando venga a reclamar.
     */
    private Nuc.InfoAdicional adenda(PagoMensaje pago) {
        List<Nuc.GrupoAdenda> grupos = new ArrayList<>();
        grupos.add(new Nuc.GrupoAdenda("INFORMACION_ADICIONAL", List.of(
                new Nuc.Atributo("OBSERVACIONES", null,
                        "Recibo %s periodo %04d-%02d".formatted(
                                pago.folio(), pago.periodo().anio(), pago.periodo().mes())),
                new Nuc.Atributo("CANTIDAD_LETRAS", null, "Q" + pago.total().toPlainString()))));

        int numero = 1;
        for (PagoMensaje.LineaDetalle linea : pago.detalle()) {
            grupos.add(new Nuc.GrupoAdenda("DetallesAux_Detalle", List.of(
                    new Nuc.Atributo("NumeroLinea", null, String.valueOf(numero++)),
                    new Nuc.Atributo("Descripcion_Adicional", null, linea.rubro().name()),
                    new Nuc.Atributo("CodigoEAN", null, "-"),
                    new Nuc.Atributo("CategoriaAdicional", null, "-"))));
        }

        return new Nuc.InfoAdicional(List.of(new Nuc.Adenda(
                "FRONT-263C-444B-89BA-6F87EC1330C0",
                "ADENDA",
                new Nuc.DatosAdenda(grupos),
                List.of(new Nuc.Atributo("VALIDAR_REFERENCIA_INTERNA", null, "NO_VALIDAR")))));
    }
}
