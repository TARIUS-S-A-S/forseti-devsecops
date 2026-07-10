package ec.tarius.forseti.empresa.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "empresa")
public class Empresa {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "ruc", nullable = false, unique = true, length = 13)
    private String ruc;

    @Column(name = "razon_social", nullable = false, length = 300)
    private String razonSocial;

    @Column(name = "nombre_comercial", length = 300)
    private String nombreComercial;

    @Column(name = "tipo_contribuyente", nullable = false, length = 20)
    private String tipoContribuyente = "OTRO";

    @Column(name = "regimen_tributario", nullable = false, length = 30)
    private String regimenTributario = "RIMPE_EMPRENDEDOR";

    @Column(name = "periodicidad_iva", nullable = false, length = 20)
    private String periodicidadIva = "SEMESTRAL";

    @Column(name = "direccion", columnDefinition = "text")
    private String direccion;

    @Column(name = "ciudad", length = 100)
    private String ciudad;

    @Column(name = "provincia", length = 100)
    private String provincia;

    @Column(name = "telefono", length = 30)
    private String telefono;

    @Column(name = "email", columnDefinition = "citext")
    private String email;

    @Column(name = "obligado_contabilidad", nullable = false)
    private boolean obligadoContabilidad = false;

    @Column(name = "agente_retencion", nullable = false)
    private boolean agenteRetencion = false;

    /**
     * Ambiente SRI por defecto al emitir comprobantes (PRUEBAS o PRODUCCION). Toda empresa
     * nueva arranca en PRUEBAS para evitar emitir facturas reales por accidente. Cambiar a
     * PRODUCCION requiere validaciones del EmpresaService.
     */
    @Column(name = "ambiente_default", nullable = false, length = 20)
    private String ambienteDefault = "PRUEBAS";

    /**
     * Código numérico (resolución SRI) si la empresa fue designada CONTRIBUYENTE ESPECIAL
     * por el SRI. NULL para >99% de empresas. Si está seteado, va al XML SRI como elemento
     * {@code <contribuyenteEspecial>}; si es NULL/blank, el elemento se OMITE (es opcional).
     */
    @Column(name = "codigo_contribuyente_especial", length = 13)
    private String codigoContribuyenteEspecial;

    @Column(name = "logo_url", columnDefinition = "text")
    private String logoUrl;

    @Column(name = "activa", nullable = false)
    private boolean activa = true;

    @Column(name = "creada_at", nullable = false, updatable = false)
    private Instant creadaAt = Instant.now();

    @Column(name = "actualizada_at", nullable = false)
    private Instant actualizadaAt = Instant.now();

    protected Empresa() {}

    public static Empresa nueva(String ruc, String razonSocial) {
        Empresa e = new Empresa();
        e.ruc = ruc;
        e.razonSocial = razonSocial;
        return e;
    }

    // Getters
    public UUID getId() { return id; }
    public String getRuc() { return ruc; }
    public String getRazonSocial() { return razonSocial; }
    public String getNombreComercial() { return nombreComercial; }
    public String getTipoContribuyente() { return tipoContribuyente; }
    public String getRegimenTributario() { return regimenTributario; }
    public String getPeriodicidadIva() { return periodicidadIva; }
    public String getDireccion() { return direccion; }
    public String getCiudad() { return ciudad; }
    public String getProvincia() { return provincia; }
    public String getTelefono() { return telefono; }
    public String getEmail() { return email; }
    public boolean isObligadoContabilidad() { return obligadoContabilidad; }
    public boolean isAgenteRetencion() { return agenteRetencion; }
    public String getAmbienteDefault() { return ambienteDefault; }
    public void setAmbienteDefault(String ambienteDefault) { this.ambienteDefault = ambienteDefault; }
    public String getCodigoContribuyenteEspecial() { return codigoContribuyenteEspecial; }
    public void setCodigoContribuyenteEspecial(String codigoContribuyenteEspecial) {
        this.codigoContribuyenteEspecial = (codigoContribuyenteEspecial != null && codigoContribuyenteEspecial.isBlank())
            ? null : codigoContribuyenteEspecial;
    }
    public String getLogoUrl() { return logoUrl; }
    public boolean isActiva() { return activa; }
    public Instant getCreadaAt() { return creadaAt; }

    // Setters principales
    public void setRazonSocial(String razonSocial) { this.razonSocial = razonSocial; }
    public void setNombreComercial(String nombreComercial) { this.nombreComercial = nombreComercial; }
    public void setTipoContribuyente(String tipoContribuyente) { this.tipoContribuyente = tipoContribuyente; }
    public void setRegimenTributario(String regimenTributario) { this.regimenTributario = regimenTributario; }
    public void setPeriodicidadIva(String periodicidadIva) { this.periodicidadIva = periodicidadIva; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
    public void setCiudad(String ciudad) { this.ciudad = ciudad; }
    public void setProvincia(String provincia) { this.provincia = provincia; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
    public void setEmail(String email) { this.email = email; }
    public void setObligadoContabilidad(boolean obligadoContabilidad) { this.obligadoContabilidad = obligadoContabilidad; }
    public void setAgenteRetencion(boolean agenteRetencion) { this.agenteRetencion = agenteRetencion; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public void setActiva(boolean activa) { this.activa = activa; }
}
