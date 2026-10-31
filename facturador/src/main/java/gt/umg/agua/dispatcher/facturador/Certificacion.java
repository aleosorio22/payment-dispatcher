package gt.umg.agua.dispatcher.facturador;

/**
 * Lo que respondio Digifact, ya interpretado.
 *
 * @param numeroAutorizacion el identificador que da la SAT; vacio si no certifico
 * @param serie              serie del documento autorizado
 * @param correlativo        numero dentro de la serie
 * @param certificadaEn      momento en que la SAT la dio por buena
 * @param codigo             codigo de respuesta de Digifact
 * @param mensaje            lo que dijo, para dejarlo escrito
 */
public record Certificacion(
        boolean certificada,
        String numeroAutorizacion,
        String serie,
        String correlativo,
        String certificadaEn,
        int codigo,
        String mensaje) {

    public static Certificacion lograda(
            String numeroAutorizacion, String serie, String correlativo, String certificadaEn) {
        return new Certificacion(
                true, numeroAutorizacion, serie, correlativo, certificadaEn, 1, "Certificada");
    }

    /**
     * Digifact contesto, pero rechazo el documento. No se reintenta: un
     * rechazo fiscal no mejora porque se insista, y lo unico que se lograria
     * es gastar cuota y ensuciar el log.
     */
    public static Certificacion rechazada(int codigo, String mensaje) {
        return new Certificacion(false, "", "", "", "", codigo, mensaje);
    }
}
