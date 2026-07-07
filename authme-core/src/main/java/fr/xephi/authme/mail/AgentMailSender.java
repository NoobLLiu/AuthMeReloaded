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
 * <p>The CLI uses a two-phase confirmation flow: the first call returns a
 * {@code confirmation_token} in its JSON output; a second call with
 * {@code --confirmation-token <token>} completes the send. This class
 * automates both phases in a single {@link #sendMail} invocation.</p>
 */
public class AgentMailSender implements MailSender {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AgentMailSender.class);

    /** Pattern to extract confirmation_token from JSON output. */
    private static final Pattern TOKEN_PATTERN =
        Pattern.compile("\"confirmation_token\"\\s*:\\s*\"([^\"]+)\"");
    /** Pattern to detect success in the second-phase output. */
    private static final Pattern QUEUED_PATTERN =
        Pattern.compile("\"queued\"\\s*:\\s*true");
    /** Pattern to detect "ok": true in JSON output. */
    private static final Pattern OK_PATTERN =
        Pattern.compile("\"ok\"\\s*:\\s*true");
    /** Pattern to detect error messages in JSON output. */
    private static final Pattern ERROR_MSG_PATTERN =
        Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");

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

        // On Windows, Java's ProcessBuilder does not resolve .cmd/.bat extensions
        // automatically. If the cliPath is a bare name (no path separator, no extension),
        // try appending ".cmd" to locate the npm-generated wrapper.
        cliPath = resolveWindowsCommand(cliPath);

        // Build the base command (without confirmation token)
        List<String> baseCommand = buildSendCommand(cliPath, recipient, subject, htmlContent, imageFile);

        // Phase 1: send request to get confirmation token
        String phase1Output = runCliCommand(baseCommand, timeoutSeconds);
        if (phase1Output == null) {
            return false;
        }

        String token = extractToken(phase1Output);
        if (token == null) {
            // No token returned — check if it's already sent or an error
            if (OK_PATTERN.matcher(phase1Output).find()) {
                logger.info("agently-cli sent mail to " + recipient + " without confirmation");
                return true;
            }
            logger.warning("agently-cli did not return a confirmation token. Output: " + phase1Output);
            if (looksLikeAuthError(phase1Output)) {
                logger.warning("Agent Mail CLI may not be authorized. Run 'agently-cli auth login' "
                    + "and complete the WeChat OAuth flow on the server host.");
            }
            return false;
        }

        // Phase 2: confirm and send with the token
        List<String> confirmCommand = new ArrayList<>(baseCommand);
        confirmCommand.add("--confirmation-token");
        confirmCommand.add(token);

        String phase2Output = runCliCommand(confirmCommand, timeoutSeconds);
        if (phase2Output == null) {
            return false;
        }

        if (QUEUED_PATTERN.matcher(phase2Output).find() || OK_PATTERN.matcher(phase2Output).find()) {
            return true;
        }

        logger.warning("agently-cli confirmation phase failed. Output: " + phase2Output);
        return false;
    }

    private List<String> buildSendCommand(String cliPath, String recipient, String subject,
                                          String htmlContent, File imageFile) {
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
            // The CLI cannot embed inline images; fall back to a note.
            body = body.replace("<image />",
                "[Password image is attached to this email.]");
        }
        command.add(body == null ? "" : body);
        if (imageFile != null) {
            command.add("--attachment");
            command.add(imageFile.getName());
        }
        return command;
    }

    /**
     * Runs the CLI command and returns its stdout output, or null on failure.
     */
    private String runCliCommand(List<String> command, int timeoutSeconds) {
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
                logger.warning("agently-cli timed out after " + timeoutSeconds + "s");
                return null;
            }
            if (process.exitValue() != 0) {
                logger.warning("agently-cli exited with code " + process.exitValue()
                    + ". Output: " + output);
                return null;
            }
            return output.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            logger.logException("Interrupted while waiting for agently-cli:", e);
            return null;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            logger.logException("Failed to invoke agently-cli:", e);
            logger.warning("Ensure agently-cli is installed (npm install -g @tencent-qqmail/agently-cli) "
                + "and present in the server process PATH, or set Email.agentMailCliPath to an absolute path.");
            return null;
        }
    }

    private static String extractToken(String output) {
        Matcher m = TOKEN_PATTERN.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    /**
     * On Windows, Java's ProcessBuilder does not automatically resolve .cmd/.bat
     * extensions for bare command names (unlike cmd.exe or PowerShell). npm global
     * installs create {@code agently-cli.cmd} wrappers, so if the configured path
     * is a bare name on Windows, try appending {@code .cmd}.
     *
     * @param cliPath the configured CLI path
     * @return resolved path usable by ProcessBuilder
     */
    private static String resolveWindowsCommand(String cliPath) {
        if (cliPath == null || cliPath.isEmpty()) {
            return cliPath;
        }
        // Already has an extension or is an absolute/relative path — leave as-is.
        if (cliPath.contains(".") || cliPath.contains("\\") || cliPath.contains("/")) {
            return cliPath;
        }
        // Try .cmd extension (npm global install creates .cmd wrappers on Windows).
        String cmdPath = cliPath + ".cmd";
        if (new File(cmdPath).exists()) {
            return cmdPath;
        }
        // Fall back to checking common npm global paths.
        String npmGlobal = System.getenv("APPDATA");
        if (npmGlobal != null && !npmGlobal.isEmpty()) {
            String npmCmd = npmGlobal + "\\npm\\" + cliPath + ".cmd";
            if (new File(npmCmd).exists()) {
                return npmCmd;
            }
        }
        // Last resort: return with .cmd appended and let ProcessBuilder try.
        return cmdPath;
    }

    private static boolean looksLikeAuthError(String output) {
        if (output == null) {
            return false;
        }
        Pattern p = Pattern.compile("auth|unauthorized|not logged in|login|invalid_grant", Pattern.CASE_INSENSITIVE);
        return p.matcher(output).find();
    }
}
