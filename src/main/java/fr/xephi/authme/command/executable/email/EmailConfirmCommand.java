package fr.xephi.authme.command.executable.email;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.PendingEmailChangeCache;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;

/**
 * Command for confirming a pending email change with a verification code.
 *
 * <p>Completes the two-phase email binding flow started by
 * {@code /email add} or {@code /email change}. The player supplies the code
 * received by email; if it matches the cached pending change, the new email
 * is persisted to the database.</p>
 */
public class EmailConfirmCommand extends PlayerCommand {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(EmailConfirmCommand.class);

    @Inject
    private CommonService commonService;

    @Inject
    private PendingEmailChangeCache pendingEmailChangeCache;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private DataSource dataSource;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        String playerName = player.getName().toLowerCase(Locale.ROOT);

        if (!playerCache.isAuthenticated(playerName)) {
            commonService.send(player, MessageKey.LOGIN_MESSAGE);
            return;
        }

        PendingEmailChangeCache.PendingEmailChange pending = pendingEmailChangeCache.get(playerName);
        if (pending == null) {
            commonService.send(player, MessageKey.EMAIL_NO_PENDING_CHANGE);
            return;
        }

        String code = arguments.get(0);
        if (!pending.getCode().equals(code)) {
            commonService.send(player, MessageKey.EMAIL_CONFIRM_WRONG_CODE);
            return;
        }

        // Phase 2: code matches — persist the new email
        PlayerAuth auth = playerCache.getAuth(playerName);
        auth.setEmail(pending.getNewEmail());
        if (dataSource.updateEmail(auth)) {
            playerCache.updatePlayer(auth);
            pendingEmailChangeCache.remove(playerName);
            commonService.send(player, MessageKey.EMAIL_CONFIRM_SUCCESS);
        } else {
            logger.warning("Could not save email for player '" + player + "'");
            commonService.send(player, MessageKey.ERROR);
        }
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_EMAIL_CONFIRM;
    }
}
