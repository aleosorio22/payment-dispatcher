package gt.umg.agua.dispatcher.facturador;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que el facturador lee del application.yml.
 *
 * <p>Los datos del emisor no son adorno: el establecimiento y la frase fiscal
 * tienen que coincidir con lo que la SAT tiene en el Registro Tributario del
 * contribuyente, o el documento se rechaza antes de llegar a certificarse.
 *
 * @param correoReceptor correo al que Digifact envia la factura. Vacio
 *                       significa usar el del vecino que trae el pago
 */
@ConfigurationProperties("app")
public record FacturadorProps(
        String colaFacturas,
        String tablaFacturas,
        String correoReceptor,
        Digifact digifact,
        Emisor emisor) {

    /**
     * @param usuario usuario completo, con la forma GT.&lt;nit con ceros&gt;.&lt;nombre&gt;
     */
    public record Digifact(String baseUrl, String usuario, String password) {

        /**
         * El NIT completado con ceros que piden los parametros de la URL.
         *
         * <p>Se deriva del usuario en vez de configurarse aparte: son el mismo
         * dato, y tenerlo dos veces solo abre la puerta a que se desincronicen.
         */
        public String taxId() {
            return partes()[1];
        }

        /** El nombre de usuario suelto, sin el pais ni el NIT. */
        public String usuarioCorto() {
            return partes()[2];
        }

        private String[] partes() {
            String[] partes = usuario.split("\\.");
            if (partes.length != 3) {
                throw new IllegalStateException(
                        "El usuario de Digifact debe tener la forma GT.<nit>.<usuario>, y es: " + usuario);
            }
            return partes;
        }
    }

    /**
     * @param afiliacionIva  como figura en el RTU; GEN es el regimen general
     * @param tipoFrase      1 es la frase de retencion del ISR
     * @param escenario      2 es el regimen opcional simplificado sobre ingresos
     * @param establecimiento codigo del establecimiento en el RTU, rara vez es 1
     */
    public record Emisor(
            String nit,
            String nombre,
            String correo,
            String afiliacionIva,
            String tipoFrase,
            String escenario,
            String establecimiento,
            String nombreEstablecimiento,
            Direccion direccion) {}

    public record Direccion(
            String calle,
            String codigoPostal,
            String municipio,
            String departamento,
            String pais) {}
}
