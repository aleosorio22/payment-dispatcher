package gt.umg.agua.dispatcher.facturador;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * El documento tal como lo espera Digifact: el "NUC" de Guatemala.
 *
 * <p>Los nombres de los campos no se discuten ni se corrigen, ni siquiera
 * {@code AdditionlInfo} y {@code AditionalData}, que vienen con el dedazo del
 * proveedor. Son su contrato; cambiarlos es dejar de hablar su idioma.
 *
 * <p>Todos los montos viajan como texto con seis decimales, que es lo que usa
 * el esquema. En ningun punto del camino un quetzal pasa por un {@code double}.
 */
public record Nuc(
        @JsonProperty("Version") String version,
        @JsonProperty("CountryCode") String countryCode,
        @JsonProperty("Header") Header header,
        @JsonProperty("Seller") Emisor seller,
        @JsonProperty("Buyer") Receptor buyer,
        @JsonProperty("ThirdParties") Object thirdParties,
        @JsonProperty("Items") List<Item> items,
        @JsonProperty("Totals") Totales totals,
        @JsonProperty("AdditionalDocumentInfo") InfoAdicional additionalDocumentInfo) {

    public record Header(
            @JsonProperty("DocType") String docType,
            @JsonProperty("IssuedDateTime") String issuedDateTime,
            @JsonProperty("Currency") String currency) {}

    /**
     * Un trio Nombre/Dato/Valor. Digifact lo usa para todo lo que no cabe en el
     * esquema fijo: la afiliacion del IVA, las frases fiscales, la adenda.
     *
     * <p>En el par de una frase, {@code data} es el numero de frase que agrupa
     * al tipo con su escenario. Si los dos no llevan el mismo, Digifact ve dos
     * frases a medias y rechaza el documento.
     */
    public record Atributo(
            @JsonProperty("Name") String name,
            @JsonProperty("Data") String data,
            @JsonProperty("Value") String value) {}

    public record Emisor(
            @JsonProperty("TaxID") String taxID,
            @JsonProperty("TaxIDAdditionalInfo") List<Atributo> taxIDAdditionalInfo,
            @JsonProperty("Name") String name,
            @JsonProperty("Contact") Contacto contact,
            @JsonProperty("AdditionlInfo") List<Atributo> additionlInfo,
            @JsonProperty("BranchInfo") Establecimiento branchInfo) {}

    public record Receptor(
            @JsonProperty("TaxID") String taxID,
            @JsonProperty("Name") String name,
            @JsonProperty("AddressInfo") Direccion addressInfo,
            @JsonProperty("Contact") Contacto contact) {}

    public record Contacto(@JsonProperty("EmailList") ListaCorreos emailList) {

        public static Contacto de(String correo) {
            return new Contacto(new ListaCorreos(List.of(correo)));
        }
    }

    public record ListaCorreos(@JsonProperty("Email") List<String> email) {}

    public record Establecimiento(
            @JsonProperty("Code") String code,
            @JsonProperty("Name") String name,
            @JsonProperty("AddressInfo") Direccion addressInfo) {}

    public record Direccion(
            @JsonProperty("Address") String address,
            @JsonProperty("City") String city,
            @JsonProperty("District") String district,
            @JsonProperty("State") String state,
            @JsonProperty("Country") String country) {}

    public record Item(
            @JsonProperty("Number") String number,
            @JsonProperty("Codes") Object codes,
            @JsonProperty("Type") String type,
            @JsonProperty("Description") String description,
            @JsonProperty("Qty") String qty,
            @JsonProperty("UnitOfMeasure") String unitOfMeasure,
            @JsonProperty("Price") String price,
            @JsonProperty("Discounts") Object discounts,
            @JsonProperty("Taxes") Impuestos taxes,
            @JsonProperty("Totals") TotalItem totals) {}

    public record Impuestos(@JsonProperty("Tax") List<Impuesto> tax) {}

    public record Impuesto(
            @JsonProperty("Code") String code,
            @JsonProperty("Description") String description,
            @JsonProperty("TaxableAmount") String taxableAmount,
            @JsonProperty("Amount") String amount) {}

    public record TotalItem(@JsonProperty("TotalItem") String totalItem) {}

    public record Totales(
            @JsonProperty("TotalTaxes") TotalImpuestos totalTaxes,
            @JsonProperty("GrandTotal") GranTotal grandTotal) {}

    public record TotalImpuestos(@JsonProperty("TotalTax") List<TotalImpuesto> totalTax) {}

    public record TotalImpuesto(
            @JsonProperty("Description") String description,
            @JsonProperty("Amount") String amount) {}

    public record GranTotal(@JsonProperty("InvoiceTotal") String invoiceTotal) {}

    /** La adenda. Digifact la exige aunque no se use: sin ella no transforma. */
    public record InfoAdicional(@JsonProperty("AdditionalInfo") List<Adenda> additionalInfo) {}

    public record Adenda(
            @JsonProperty("Code") String code,
            @JsonProperty("Type") String type,
            @JsonProperty("AditionalData") DatosAdenda aditionalData,
            @JsonProperty("AditionalInfo") List<Atributo> aditionalInfo) {}

    public record DatosAdenda(@JsonProperty("Data") List<GrupoAdenda> data) {}

    public record GrupoAdenda(
            @JsonProperty("Name") String name,
            @JsonProperty("Info") List<Atributo> info) {}
}
