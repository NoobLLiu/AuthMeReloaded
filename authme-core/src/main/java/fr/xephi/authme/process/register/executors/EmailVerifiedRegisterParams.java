package fr.xephi.authme.process.register.executors;

import org.bukkit.entity.Player;

/**
 * Parameters for the second stage of email-verified password registration:
 * the player has submitted the correct verification code and a chosen password.
 * The email address comes from the {@link fr.xephi.authme.service.PendingRegistrationCache}
 * entry that was created in the first stage.
 */
public class EmailVerifiedRegisterParams extends AbstractPasswordRegisterParams {

    private final String email;

    protected EmailVerifiedRegisterParams(Player player, String password, String email) {
        super(player, password);
        this.email = email;
    }

    /**
     * Creates a params object.
     *
     * @param player the player to register
     * @param password the password chosen by the player
     * @param email the verified email address (from the pending cache)
     * @return params object with the given data
     */
    public static EmailVerifiedRegisterParams of(Player player, String password, String email) {
        return new EmailVerifiedRegisterParams(player, password, email);
    }

    public String getEmail() {
        return email;
    }
}
