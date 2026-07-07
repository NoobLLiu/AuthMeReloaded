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
import fr.xephi.authme.util.Utils;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Async task to add an email to an account.
 *
 * <p>Two-phase flow: this method validates the email and sends a verification
 * code to the new address. The email is only persisted after the player
 * confirms with {@code /email confirm <code>} (handled by
 * {@link fr.xephi.authme.command.executable.email.EmailConfirmCommand}).</p>
 */
public class AsyncAddEmail implements AsynchronousProcess {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AsyncAddEmail.class);

    @Inject
    private CommonService service;

    @Inject
    private DataSource dataSource;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private ValidationService validationService;

    @Inject
    private BukkitService bukkitService;

    @Inject
    private EmailService emailService;

    @Inject
    private PendingEmailChangeCache pendingEmailChangeCache;

    AsyncAddEmail() {
    }

    /**
     * Handles the request to add the given email to the player's account.
     *
     * @param player the player to add the email to
     * @param email the email to add
     */
    public void addEmail(Player player, String email) {
        String playerName = player.getName().toLowerCase(Locale.ROOT);

        if (playerCache.isAuthenticated(playerName)) {
            PlayerAuth auth = playerCache.getAuth(playerName);
            String currentEmail = auth.getEmail();

            if (!Utils.isEmailEmpty(currentEmail)) {
                service.send(player, MessageKey.USAGE_CHANGE_EMAIL);
            } else if (!validationService.validateEmail(email)) {
                service.send(player, MessageKey.INVALID_EMAIL);
            } else if (!validationService.isEmailFreeForRegistration(email, player)) {
                service.send(player, MessageKey.EMAIL_ALREADY_USED_ERROR);
            } else if (!emailService.hasAllInformation()) {
                service.send(player, MessageKey.INCOMPLETE_EMAIL_SETTINGS);
            } else {
                EmailChangedEvent event = bukkitService.createAndCallEvent(isAsync
                    -> new EmailChangedEvent(player, null, email, isAsync));
                if (event.isCancelled()) {
                    logger.info("Could not add email to player '" + player + "' – event was cancelled");
                    service.send(player, MessageKey.EMAIL_ADD_NOT_ALLOWED);
                    return;
                }
                // Phase 1: generate code, send verification email, cache pending change
                String code = RandomStringUtils.generateNum(6);
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy'-'MM'-'dd'-' HH:mm:ss");
                String time = dateFormat.format(new Date(System.currentTimeMillis()));
                pendingEmailChangeCache.put(playerName, email, code);
                emailService.sendVerificationMail(player.getName(), email, code, time);
                service.send(player, MessageKey.EMAIL_VERIFICATION_SENT);
            }
        } else {
            sendUnloggedMessage(player);
        }
    }

    private void sendUnloggedMessage(Player player) {
        if (dataSource.isAuthAvailable(player.getName())) {
            service.send(player, MessageKey.LOGIN_MESSAGE);
        } else {
            service.send(player, MessageKey.REGISTER_MESSAGE);
        }
    }

}
