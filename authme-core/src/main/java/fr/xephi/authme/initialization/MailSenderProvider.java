package fr.xephi.authme.initialization;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.mail.AgentMailSender;
import fr.xephi.authme.mail.MailSender;
import fr.xephi.authme.mail.SendMailSsl;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Provides the {@link MailSender} implementation selected by the
 * {@code Email.mailSender} config setting.
 *
 * <p>{@code smtp} (default) returns {@link SendMailSsl};
 * {@code agent_mail} returns {@link AgentMailSender}.</p>
 */
public class MailSenderProvider implements Provider<MailSender> {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(MailSenderProvider.class);

    @Inject
    private Settings settings;
    @Inject
    private SendMailSsl sendMailSsl;
    @Inject
    private AgentMailSender agentMailSender;

    @Override
    public MailSender get() {
        String sender = settings.getProperty(EmailSettings.MAIL_SENDER);
        if ("agent_mail".equalsIgnoreCase(sender)) {
            logger.info("Using Agent Mail CLI (agently-cli) as email sender");
            return agentMailSender;
        }
        return sendMailSsl;
    }
}
