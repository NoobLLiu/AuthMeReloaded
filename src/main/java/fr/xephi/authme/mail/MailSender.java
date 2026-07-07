package fr.xephi.authme.mail;

import java.io.File;

/**
 * Abstraction over the underlying email delivery channel.
 *
 * <p>Implementations may use SMTP (e.g. {@link SendMailSsl}) or an external CLI
 * tool (e.g. {@link AgentMailSender} backed by {@code agently-cli}). The
 * {@code imageFile} argument of {@link #sendMail} is optional: when non-null,
 * SMTP senders may embed it inline (replacing any {@code <image />} placeholder
 * present in {@code htmlContent}); other senders may attach it or ignore it.</p>
 */
public interface MailSender {

    /**
     * Returns whether all settings required by this sender are present and
     * non-empty. Used as the gate for all email-dependent features.
     *
     * @return true if the sender is fully configured, false otherwise
     */
    boolean hasAllInformation();

    /**
     * Sends an HTML email to the given recipient.
     *
     * @param recipient the recipient email address
     * @param subject the email subject
     * @param htmlContent the HTML body; may contain an {@code <image />} placeholder
     *                    when {@code imageFile} is provided
     * @param imageFile optional image file to embed/attach (may be {@code null})
     * @return true if the email was sent successfully, false otherwise
     */
    boolean sendMail(String recipient, String subject, String htmlContent, File imageFile);

}
