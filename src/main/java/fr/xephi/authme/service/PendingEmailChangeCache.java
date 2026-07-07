package fr.xephi.authme.service;

import fr.xephi.authme.initialization.HasCleanup;
import fr.xephi.authme.util.expiring.ExpiringMap;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

/**
 * In-memory cache for pending email changes awaiting confirmation.
 *
 * <p>When a player issues {@code /email add} or {@code /email change}, the new
 * email address and a verification code are stored here instead of being
 * persisted immediately. The player must confirm with {@code /email confirm
 * <code>} within the TTL for the change to take effect.</p>
 */
public class PendingEmailChangeCache implements HasCleanup {

    /** Default TTL: 10 minutes. */
    private static final long DEFAULT_TTL_MINUTES = 10;

    private final ExpiringMap<String, PendingEmailChange> pendingChanges;

    @Inject
    PendingEmailChangeCache() {
        pendingChanges = new ExpiringMap<>(DEFAULT_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Stores a pending email change for the given player.
     *
     * @param playerName the player's name (case-insensitive, stored lowercased)
     * @param newEmail the new email address to confirm
     * @param code the verification code sent to the new email
     */
    public void put(String playerName, String newEmail, String code) {
        pendingChanges.put(playerName.toLowerCase(java.util.Locale.ROOT),
            new PendingEmailChange(newEmail, code));
    }

    /**
     * Returns the pending email change for the given player, or {@code null}
     * if no pending change exists or it has expired.
     *
     * @param playerName the player's name
     * @return the pending change, or {@code null}
     */
    public PendingEmailChange get(String playerName) {
        return pendingChanges.get(playerName.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Removes the pending email change for the given player, if any.
     *
     * @param playerName the player's name
     */
    public void remove(String playerName) {
        pendingChanges.remove(playerName.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Returns whether a pending email change exists for the given player.
     *
     * @param playerName the player's name
     * @return true if a non-expired pending change exists
     */
    public boolean has(String playerName) {
        return get(playerName) != null;
    }

    @Override
    public void performCleanup() {
        pendingChanges.removeExpiredEntries();
    }

    /**
     * Holds the pending email address and its verification code.
     */
    public static final class PendingEmailChange {
        private final String newEmail;
        private final String code;

        PendingEmailChange(String newEmail, String code) {
            this.newEmail = newEmail;
            this.code = code;
        }

        public String getNewEmail() {
            return newEmail;
        }

        public String getCode() {
            return code;
        }
    }
}
