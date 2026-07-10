package ec.tarius.forseti.shared.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;

/**
 * Servicio de envío de correos via Brevo SMTP (Spring Mail).
 * Async para no bloquear request del usuario.
 * Sprint 2: migrar a templates Thymeleaf con HTML rico + branding Forseti.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;
    private final String baseUrl;

    public EmailService(JavaMailSender mailSender,
                        @Value("${forseti.brevo.sender.email:hernan.jurado@tarius.ec}") String fromAddress,
                        @Value("${forseti.brevo.sender.name:Forseti}") String fromName,
                        @Value("${forseti.app.base-url:https://forseti.tarius.ec}") String baseUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.baseUrl = baseUrl;
    }

    @Async
    public void enviarVerificacion(String to, String nombre, String token) {
        String link = baseUrl + "/verificar-email?token=" + token;
        String html = """
            <html><body style="font-family:system-ui,sans-serif;background:#F8FAFC;padding:40px;color:#0F172A;">
              <div style="max-width:480px;margin:0 auto;background:white;border-radius:12px;padding:32px;box-shadow:0 4px 12px rgba(0,0,0,0.06);">
                <h1 style="font-family:Georgia,serif;color:#0F172A;margin:0 0 24px;">Forseti<span style="color:#FB923C;">.</span></h1>
                <h2 style="font-size:20px;margin:0 0 16px;">Hola %s,</h2>
                <p style="margin:0 0 16px;color:#64748B;line-height:1.5;">Bienvenido a Forseti. Para activar tu cuenta confirmá tu dirección de correo:</p>
                <p style="text-align:center;margin:32px 0;">
                  <a href="%s" style="background:#1E3A8A;color:white;padding:14px 32px;text-decoration:none;border-radius:8px;font-weight:500;display:inline-block;">Confirmar email</a>
                </p>
                <p style="font-size:12px;color:#64748B;margin:24px 0 0;">O copiá este link: %s</p>
                <p style="font-size:12px;color:#64748B;margin:8px 0 0;">El link expira en 24 horas.</p>
                <hr style="border:none;border-top:1px solid #E2E8F0;margin:32px 0;">
                <p style="font-size:11px;color:#94A3B8;text-align:center;margin:0;">
                  <em>un producto de</em> <strong style="color:#0F2A43;">TARIUS<span style="color:#D97706;">.</span></strong>
                </p>
              </div>
            </body></html>
            """.formatted(escapeHtml(nombre), link, link);
        enviar(to, "Verificá tu email — Forseti", html);
    }

    @Async
    public void enviarRecovery(String to, String token) {
        String link = baseUrl + "/reset-password?token=" + token;
        String html = """
            <html><body style="font-family:system-ui,sans-serif;background:#F8FAFC;padding:40px;color:#0F172A;">
              <div style="max-width:480px;margin:0 auto;background:white;border-radius:12px;padding:32px;box-shadow:0 4px 12px rgba(0,0,0,0.06);">
                <h1 style="font-family:Georgia,serif;color:#0F172A;margin:0 0 24px;">Forseti<span style="color:#FB923C;">.</span></h1>
                <h2 style="font-size:20px;margin:0 0 16px;">Reseteo de contraseña</h2>
                <p style="margin:0 0 16px;color:#64748B;line-height:1.5;">Recibimos un pedido para cambiar tu contraseña. Si fuiste vos, hacé click acá:</p>
                <p style="text-align:center;margin:32px 0;">
                  <a href="%s" style="background:#FB923C;color:white;padding:14px 32px;text-decoration:none;border-radius:8px;font-weight:500;display:inline-block;">Cambiar contraseña</a>
                </p>
                <p style="font-size:12px;color:#64748B;margin:24px 0 0;">El link expira en 30 minutos.</p>
                <p style="font-size:12px;color:#DC2626;margin:8px 0 0;">Si NO pediste este cambio, ignorá este correo. Tu cuenta está segura.</p>
                <hr style="border:none;border-top:1px solid #E2E8F0;margin:32px 0;">
                <p style="font-size:11px;color:#94A3B8;text-align:center;margin:0;">
                  <em>un producto de</em> <strong style="color:#0F2A43;">TARIUS<span style="color:#D97706;">.</span></strong>
                </p>
              </div>
            </body></html>
            """.formatted(link);
        enviar(to, "Reseteo de contraseña — Forseti", html);
    }

    @Async
    public void enviarInvitacion(String to, String nombreInvitado, String nombreEmpresa, String token) {
        String link = baseUrl + "/invitacion/" + token;
        String saludo = (nombreInvitado != null && !nombreInvitado.isBlank()) ? "Hola " + nombreInvitado : "Hola";
        String html = """
            <html><body style="font-family:system-ui,sans-serif;background:#F8FAFC;padding:40px;color:#0F172A;">
              <div style="max-width:480px;margin:0 auto;background:white;border-radius:12px;padding:32px;box-shadow:0 4px 12px rgba(0,0,0,0.06);">
                <h1 style="font-family:Georgia,serif;color:#0F172A;margin:0 0 24px;">Forseti<span style="color:#FB923C;">.</span></h1>
                <h2 style="font-size:20px;margin:0 0 16px;">%s,</h2>
                <p style="margin:0 0 16px;color:#64748B;line-height:1.5;">Te invitaron a unirte a <strong style="color:#0F172A;">%s</strong> en Forseti — la app de facturación electrónica y cumplimiento tributario.</p>
                <p style="text-align:center;margin:32px 0;">
                  <a href="%s" style="background:#1E3A8A;color:white;padding:14px 32px;text-decoration:none;border-radius:8px;font-weight:500;display:inline-block;">Aceptar invitación</a>
                </p>
                <p style="font-size:12px;color:#64748B;margin:24px 0 0;">O copiá este link: %s</p>
                <p style="font-size:12px;color:#64748B;margin:8px 0 0;">El link expira en 7 días.</p>
                <hr style="border:none;border-top:1px solid #E2E8F0;margin:32px 0;">
                <p style="font-size:11px;color:#94A3B8;text-align:center;margin:0;">
                  <em>un producto de</em> <strong style="color:#0F2A43;">TARIUS<span style="color:#D97706;">.</span></strong>
                </p>
              </div>
            </body></html>
            """.formatted(escapeHtml(saludo), escapeHtml(nombreEmpresa), link, link);
        enviar(to, "Invitación a " + nombreEmpresa + " — Forseti", html);
    }

    /**
     * Envía al cliente final un comprobante AUTORIZADO. Es el correo "te enviamos tu factura"
     * que el cliente recibe luego de que el SRI autoriza. Adjunta:
     *   - el XML autorizado (con firma SRI),
     *   - el RIDE en PDF.
     *
     * Async — si falla por red/SMTP, no rompe el flow de emisión. El callsite (job de
     * autorización) puede usar el flag `correo_enviado_at` en el Comprobante para
     * idempotencia.
     */
    /**
     * Envía el correo del comprobante autorizado. @Async desacopla del thread del job;
     * @Retryable reintenta hasta 3 veces con backoff exponencial (5s, 10s, 20s) si Brevo
     * tira un MailException (red, rate limit, downtime SMTP). Si agota los intentos,
     * @Recover loggea ERROR pero NO propaga: el comprobante ya está AUTORIZADA y no debe
     * rollback por un fallo de correo. El receptor siempre puede pedir reenvío desde la UI.
     */
    @Async
    @Retryable(
        retryFor = { MailException.class, MessagingException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 5_000L, multiplier = 2.0)
    )
    public void enviarComprobanteAutorizado(String toEmail,
                                              String razonSocialEmisor,
                                              String numeroComprobante,
                                              String claveAcceso,
                                              String numeroAutorizacion,
                                              byte[] xmlAutorizado,
                                              byte[] ridePdf) throws MessagingException {
        String subject = "Factura " + numeroComprobante + " — " + razonSocialEmisor;
        String html = """
            <html><body style="font-family:system-ui,sans-serif;background:#F8FAFC;padding:40px;color:#0F172A;">
              <div style="max-width:520px;margin:0 auto;background:white;border-radius:12px;padding:32px;box-shadow:0 4px 12px rgba(0,0,0,0.06);">
                <h1 style="font-family:Georgia,serif;color:#0F172A;margin:0 0 24px;">Forseti<span style="color:#FB923C;">.</span></h1>
                <h2 style="font-size:20px;margin:0 0 16px;">Tu factura</h2>
                <p style="margin:0 0 8px;color:#475569;line-height:1.5;">Recibiste una factura electrónica de <strong style="color:#0F172A;">%s</strong>.</p>
                <table style="width:100%%;margin:24px 0;border-collapse:collapse;">
                  <tr><td style="padding:6px 0;color:#64748B;font-size:13px;">Factura N°</td>
                      <td style="padding:6px 0;font-family:monospace;font-weight:600;">%s</td></tr>
                  <tr><td style="padding:6px 0;color:#64748B;font-size:13px;">Clave de acceso</td>
                      <td style="padding:6px 0;font-family:monospace;font-size:11px;word-break:break-all;">%s</td></tr>
                  <tr><td style="padding:6px 0;color:#64748B;font-size:13px;">N° autorización</td>
                      <td style="padding:6px 0;font-family:monospace;font-size:11px;word-break:break-all;color:#15803D;font-weight:600;">%s</td></tr>
                </table>
                <p style="margin:16px 0;color:#475569;line-height:1.5;font-size:14px;">Adjuntamos el documento electrónico autorizado por el SRI (XML) y su representación impresa (RIDE PDF). Guardalos por 7 años — son la única evidencia fiscal válida.</p>
                <p style="margin:16px 0;color:#64748B;line-height:1.5;font-size:13px;">También podés validar la autenticidad de esta factura en el portal SRI con la clave de acceso de arriba.</p>
                <hr style="border:none;border-top:1px solid #E2E8F0;margin:32px 0;">
                <p style="font-size:11px;color:#94A3B8;text-align:center;margin:0;">
                  Facturación electrónica via <strong style="color:#FB923C;">Forseti</strong> — <em>un producto de</em> <strong style="color:#0F2A43;">TARIUS<span style="color:#D97706;">.</span></strong>
                </p>
              </div>
            </body></html>
            """.formatted(escapeHtml(razonSocialEmisor), escapeHtml(numeroComprobante),
                          escapeHtml(claveAcceso), escapeHtml(numeroAutorizacion));

        String xmlName  = "factura-" + claveAcceso + ".xml";
        String pdfName  = "RIDE-" + numeroComprobante.replace("-", "_") + ".pdf";

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, fromName);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            if (xmlAutorizado != null && xmlAutorizado.length > 0) {
                helper.addAttachment(xmlName, new ByteArrayResource(xmlAutorizado), "application/xml");
            }
            if (ridePdf != null && ridePdf.length > 0) {
                helper.addAttachment(pdfName, new ByteArrayResource(ridePdf), "application/pdf");
            }
            mailSender.send(msg);
            log.info("Correo de comprobante AUTORIZADO enviado: to={} factura={} clave={}",
                toEmail, numeroComprobante, claveAcceso);
        } catch (java.io.UnsupportedEncodingException e) {
            // Error de programación, no de red — NO retry.
            log.error("Error de encoding al enviar correo a {}: {}", toEmail, e.getMessage(), e);
        }
        // MailException + MessagingException se propagan → @Retryable las captura para retry.
    }

    /**
     * Llamado por spring-retry cuando se agotan los 3 intentos. NO rollback del comprobante:
     * ya está AUTORIZADA frente al SRI; la falla de Brevo es un problema operativo aparte
     * que se puede resolver con un re-envío manual desde la UI (futuro Sprint).
     */
    @Recover
    public void recoverComprobanteAutorizado(Exception e,
                                              String toEmail,
                                              String razonSocialEmisor,
                                              String numeroComprobante,
                                              String claveAcceso,
                                              String numeroAutorizacion,
                                              byte[] xmlAutorizado,
                                              byte[] ridePdf) {
        log.error("Brevo agotó 3 intentos para comprobante {} (clave {}). Receptor {} NO recibió el correo. "
                + "El comprobante sigue AUTORIZADA; usar UI para reenviar manualmente.",
            numeroComprobante, claveAcceso, toEmail, e);
    }

    private void enviar(String to, String subject, String html) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("Email enviado: to={} subject={}", to, subject);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Error enviando email a {}: {}", to, e.getMessage(), e);
        } catch (Exception e) {
            // Si falla Brevo (red, auth, rate limit), NO romper el flujo principal
            log.error("Error inesperado enviando email a {}: {}", to, e.getMessage(), e);
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
