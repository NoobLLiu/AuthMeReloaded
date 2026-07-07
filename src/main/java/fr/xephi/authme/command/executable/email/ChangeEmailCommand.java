package fr.xephi.authme.command.executable.email;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Change email command.
 *
 * <p>Two-phase flow: issues {@code /email change <oldEmail> <newEmail>}; a
 * verification code is sent to the new address. The player must then run
 * {@code /email confirm <code>} to complete the change.</p>
 */
public class ChangeEmailCommand extends PlayerCommand {

    @Inject
    private Management management;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        String playerMailOld = arguments.get(0);
        String playerMailNew = arguments.get(1);
        management.performChangeEmail(player, playerMailOld, playerMailNew);
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_CHANGE_EMAIL;
    }
}
