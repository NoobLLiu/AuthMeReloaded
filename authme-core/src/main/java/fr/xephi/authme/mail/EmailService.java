package fr.xephi.authme.mail;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.initialization.DataFolder;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;
import fr.xephi.authme.settings.properties.PluginSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import fr.xephi.authme.util.FileUtils;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;

/**
 * Creates emails and sends them.
 */
public class EmailService {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(EmailService.class);

    private final File dataFolder;
    private final Settings settings;
    private final MailSender mailSender;

    @Inject
    EmailService(@DataFolder File dataFolder, Settings settings, MailSender mailSender) {
        this.dataFolder = dataFolder;
        this.settings = settings;
        this.mailSender = mailSender;
    }

    public boolean hasAllInformation() {
        return mailSender.hasAllInformation();
    }


    /**
     * Sends an email to the user with his new password.
     *
     * @param name the name of the player
     * @param mailAddress the player's email
     * @param newPass the new password
     * @return true if email could be sent, false otherwise
     */
    public boolean sendPasswordMail(String name, String mailAddress, String newPass) {
        if (!hasAllInformation()) {
            logger.warning("Cannot perform email registration: not all email settings are complete");
            return false;
        }

        String mailText = replaceTagsForPasswordMail(settings.getPasswordEmailMessage(), name, newPass);
        // Generate an image?
        File file = null;
        if (settings.getProperty(EmailSettings.PASSWORD_AS_IMAGE)) {
            try {
                file = generatePasswordImage(name, newPass);
            } catch (IOException e) {
                logger.logException(
                    "Unable to send new password as image for email " + mailAddress + ":", e);
            }
        }

        boolean couldSendEmail = mailSender.sendMail(mailAddress,
            settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT), mailText, file);
        FileUtils.delete(file);
        return couldSendEmail;
    }

    /**
     * Sends an email to the user with the temporary verification code.
     *
     * @param name the name of the player
     * @param mailAddress the player's email
     * @param code the verification code
     * @return true if email could be sent, false otherwise
     */
    public boolean sendVerificationMail(String name, String mailAddress, String code) {
        if (!hasAllInformation()) {
            logger.warning("Cannot send verification email: not all email settings are complete");
            return false;
        }

        String mailText = replaceTagsForVerificationEmail(settings.getVerificationEmailMessage(), name, code,
            settings.getProperty(SecuritySettings.VERIFICATION_CODE_EXPIRATION_MINUTES));
        return mailSender.sendMail(mailAddress, settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT),
            mailText, null);
    }

    /**
     * Sends an email to the new address with a code to confirm the address change.
     *
     * @param name  the name of the player
     * @param email the new email address to confirm
     * @param code  the confirmation code
     * @return true if email could be sent, false otherwise
     */
    public boolean sendEmailConfirmationMail(String name, String email, String code) {
        if (!hasAllInformation()) {
            logger.warning("Cannot send email confirmation: not all email settings are complete");
            return false;
        }

        // TTL matches PendingEmailVerificationCache.TTL_MS (10 minutes)
        String mailText = replaceTagsForVerificationEmail(settings.getEmailConfirmationMessage(), name, code, 10);
        return mailSender.sendMail(email, settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT),
            mailText, null);
    }

    /**
     * Sends an email to the user with a recovery code for the password recovery process.
     *
     * @param name the name of the player
     * @param email the player's email address
     * @param code the recovery code
     * @return true if email could be sent, false otherwise
     */
    public boolean sendRecoveryCode(String name, String email, String code) {
        String message = replaceTagsForRecoveryCodeMail(settings.getRecoveryCodeEmailMessage(),
            name, code, settings.getProperty(SecuritySettings.RECOVERY_CODE_HOURS_VALID));
        return mailSender.sendMail(email, settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT),
            message, null);
    }

    private File generatePasswordImage(String name, String newPass) throws IOException {
        ImageGenerator gen = new ImageGenerator(newPass);
        File file = new File(dataFolder, name + "_new_pass.jpg");
        ImageIO.write(gen.generateImage(), "jpg", file);
        return file;
    }

    private String replaceTagsForPasswordMail(String mailText, String name, String newPass) {
        return mailText
            .replace("<playername />", name)
            .replace("<servername />", settings.getProperty(PluginSettings.SERVER_NAME))
            .replace("<generatedpass />", newPass);
    }

    private String replaceTagsForVerificationEmail(String mailText, String name, String code, int minutesValid) {
        return mailText
            .replace("<playername />", name)
            .replace("<servername />", settings.getProperty(PluginSettings.SERVER_NAME))
            .replace("<generatedcode />", code)
            .replace("<minutesvalid />", String.valueOf(minutesValid));
    }

    private String replaceTagsForRecoveryCodeMail(String mailText, String name, String code, int hoursValid) {
        return mailText
            .replace("<playername />", name)
            .replace("<servername />", settings.getProperty(PluginSettings.SERVER_NAME))
            .replace("<recoverycode />", code)
            .replace("<hoursvalid />", String.valueOf(hoursValid));
    }
}