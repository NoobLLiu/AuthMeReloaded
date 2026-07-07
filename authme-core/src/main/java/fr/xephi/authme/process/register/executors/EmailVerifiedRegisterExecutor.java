package fr.xephi.authme.process.register.executors;

import fr.xephi.authme.data.auth.PlayerAuth;

import static fr.xephi.authme.process.register.executors.PlayerAuthBuilderHelper.createPlayerAuth;

/**
 * Registration executor for the second stage of email-verified password
 * registration. The player's email has already been verified in the first
 * stage (via {@code /register <email>}); this executor handles
 * {@code /register verify <code> <password>} and creates the account with
 * the verified email and the player-chosen password.
 *
 * <p>Extends {@link AbstractPasswordRegisterExecutor} to inherit password
 * validation and post-persist behavior (auto-login, sync processing).</p>
 */
class EmailVerifiedRegisterExecutor extends AbstractPasswordRegisterExecutor<EmailVerifiedRegisterParams> {

    @Override
    public PlayerAuth createPlayerAuthObject(EmailVerifiedRegisterParams params) {
        return createPlayerAuth(params.getPlayer(), params.getHashedPassword(), params.getEmail());
    }
}
