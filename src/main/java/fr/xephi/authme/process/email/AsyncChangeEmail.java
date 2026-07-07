package fr.xephi.authme.process.email;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.events.EmailChangedEvent;
import fr.xephi.authme.mail.EmailService;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.process.AsynchronousProcess;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.PendingEmailChangeCache;
import fr.xephi.authme.service.ValidationService;
import fr.xephi.authme.util.RandomStringUtils;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Async task for changing the email.
 *
 * <p>Two-phase flow: this method validates the old/new email and sends a
 * verification code to the new address. The email is only persisted after
 * the player confirms with {@code /email confirm <code>} (handled by
 * {@link fr.xephi.authme.command.executable.email.EmailConfirmCommand}).</p>
 */
public class AsyncChangeEmail implements AsynchronousProcess {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AsyncChangeEmail.class);

    @Inject
    private CommonService service;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private DataSource dataSource;

    @Inject
    private ValidationService validationService;

    @Inject
    private BukkitService bukkitService;

    @Inject
    private EmailService emailService;

    @Inject
    private PendingEmailChangeCache pendingEmailChangeCache;

    AsyncChangeEmail() {
    }

    /**
     * Handles the request to change the player's email address.
     *
     * @param player   the player to change the email for
     * @param oldEmail provided old email
     * @param newEmail provided new email
     */
    public void changeEmail(Player player, String oldEmail, String newEmail) {
        String playerName = player.getName().toLowerCase(Locale.ROOT);
        if (playerCache.isAuthenticated(playerName)) {
            PlayerAuth auth = playerCache.getAuth(playerName);
            String currentEmail = auth.getEmail();

            if (currentEmail == null) {
                service.send(player, MessageKey.USAGE_ADD_EMAIL);
            } else if (newEmail == null || !validationService.validateEmail(newEmail)) {
                service.send(player, MessageKey.INVALID_NEW_EMAIL);
            } else if (!oldEmail.equalsIgnoreCase(currentEmail)) {
                service.send(player, MessageKey.INVALID_OLD_EMAIL);
            } else if (!validationService.isEmailFreeForRegistration(newEmail, player)) {
                service.send(player, MessageKey.EMAIL_ALREADY_USED_ERROR);
            } else if (!emailService.hasAllInformation()) {
                service.send(player, MessageKey.INCOMPLETE_EMAIL_SETTINGS);
            } else {
                // Phase 1: generate code, send verification email, cache pending change
                EmailChangedEvent event = bukkitService.createAndCallEvent(isAsync
                    -> new EmailChangedEvent(player, oldEmail, newEmail, isAsync));
                if (event.isCancelled()) {
                    logger.info("Could not change email for player '" + player + "' – event was cancelled");
                    service.send(player, MessageKey.EMAIL_CHANGE_NOT_ALLOWED);
                    return;
                }
                String code = RandomStringUtils.generateNum(6);
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy'-'MM'-'dd'-' HH:mm:ss");
                String time = dateFormat.format(new Date(System.currentTimeMillis()));
                pendingEmailChangeCache.put(playerName, newEmail, code);
                emailService.sendVerificationMail(player.getName(), newEmail, code, time);
                service.send(player, MessageKey.EMAIL_VERIFICATION_SENT);
            }
        } else {
            outputUnloggedMessage(player);
        }
    }

    private void outputUnloggedMessage(Player player) {
        if (dataSource.isAuthAvailable(player.getName())) {
            service.send(player, MessageKey.LOGIN_MESSAGE);
        } else {
            service.send(player, MessageKey.REGISTER_MESSAGE);
        }
    }
}
