package fr.xephi.authme.mail;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends emails by invoking the Tencent Agent Mail CLI ({@code agently-cli}).
 *
 * <p>This sender does not use SMTP; instead it shells out to the
 * {@code agently-cli} command-line tool (installed via
 * {@code npm install -g @tencent-qqmail/agently-cli} and authorized with
 * {@code agently-cli auth login}). The sender address is the
 * {@code @agent.qq.com} mailbox bound to the authorized account.</p>
 *
 * <p>Inline image embedding (the {@code <image />} placeholder used by
 * {@link SendMailSsl}) is not supported by the CLI send path; when an image
 * file is supplied it is passed as an attachment via {@code --attachment} and
 * the placeholder is replaced with a fallback note.</p>
 */
public class AgentMailSender implements MailSender {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AgentMailSender.class);

    @Inject
    private Settings settings;

    @Override
    public boolean hasAllInformation() {
        // Agent Mail relies on an externally authorized CLI; there is no in-config
        // account or password to validate. We assume the admin has installed and
        // authorized agently-cli when selecting this sender.
        return true;
    }

    @Override
    public boolean sendMail(String recipient, String subject, String htmlContent, File imageFile) {
        String cliPath = settings.getProperty(EmailSettings.AGENT_MAIL_CLI_PATH);
        int timeoutSeconds = settings.getProperty(EmailSettings.AGENT_MAIL_TIMEOUT_SECONDS);

        List<String> command = new ArrayList<>();
        command.add(cliPath);
        command.add("message");
        command.add("+send");
        command.add("--to");
        command.add(recipient);
        command.add("--subject");
        command.add(subject == null ? "" : subject);
        command.add("--body");
        String body = htmlContent;
        if (imageFile != null && body != null) {
            // The CLI cannot embed inline images; fall back to an attachment + note.
            body = body.replace("<image />",
                "[Password image is attached to this email.]");
        }
        command.add(body == null ? "" : body);
        command.add("--body-format");
        command.add("html");
        command.add("--auto-confirm");
        if (imageFile != null) {
            command.add("--attachment");
            command.add(imageFile.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = null;
        try {
            process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warning("agently-cli timed out after " + timeoutSeconds + "s sending mail to " + recipient);
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warning("agently-cli exited with code " + exitCode + " while sending mail to "
                    + recipient + ". Output: " + output);
                if (looksLikeAuthError(output.toString())) {
                    logger.warning("Agent Mail CLI may not be authorized. Run 'agently-cli auth login' "
                        + "and complete the WeChat OAuth flow on the server host.");
                }
                return false;
            }
            // On success the CLI prints a JSON object; treat any 0-exit as success.
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            logger.logException("Interrupted while waiting for agently-cli:", e);
            return false;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            logger.logException("Failed to invoke agently-cli to send mail to " + recipient + ":", e);
            logger.warning("Ensure agently-cli is installed (npm install -g @tencent-qqmail/agently-cli) "
                + "and present in the server process PATH, or set Email.agentMailCliPath to an absolute path.");
            return false;
        }
    }

    private static boolean looksLikeAuthError(String output) {
        if (output == null) {
            return false;
        }
        Pattern p = Pattern.compile("auth|unauthorized|not logged in|login", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(output);
        return m.find();
    }
}
