package fr.xephi.authme.service;

import javax.inject.Inject;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds in-memory email-verified registration requests that are waiting for
 * the player to confirm the email address before the account is created.
 * Entries expire after {@link #TTL_MS} (10 minutes).
 *
 * <p>A player is placed here during the first stage of
 * {@code EMAIL_VERIFIED_PASSWORD} registration when they run
 * {@code /register <email>}. A 6-digit verification code is emailed to the
 * given address. The entry is consumed (and the account created) when the
 * player submits the correct code via
 * {@code /register verify <code> <password>}. Expired entries are silently
 * discarded.</p>
 */
public class PendingRegistrationCache {

    static final long TTL_MS = 10 * 60 * 1000L;

    private final ConcurrentHashMap<String, PendingRegistration> pending = new ConcurrentHashMap<>();

    @Inject
    PendingRegistrationCache() {
    }

    /**
     * Registers a pending email-verified registration for the given player.
     * Any previous entry is replaced. Also evicts expired entries.
     *
     * @param name  the player name (case-insensitive)
     * @param email the email address awaiting verification
     * @param code  the verification code sent to that address
     */
    public void addPending(String name, String email, String code) {
        evictExpired();
        pending.put(name.toLowerCase(Locale.ROOT),
            new PendingRegistration(email, code, System.currentTimeMillis() + TTL_MS));
    }

    /**
     * Returns whether a non-expired pending registration exists for the given player name.
     *
     * @param name the player name (case-insensitive)
     * @return true if a pending entry exists and has not yet expired
     */
    public boolean isPending(String name) {
        return getEntry(name) != null;
    }

    /**
     * Returns the pending registration for the given player name without removing it,
     * or {@code null} if no entry exists or it has expired.
     *
     * @param name the player name (case-insensitive)
     * @return the pending entry, or null
     */
    public PendingRegistration getEntry(String name) {
        PendingRegistration entry = pending.get(name.toLowerCase(Locale.ROOT));
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            pending.remove(name.toLowerCase(Locale.ROOT), entry);
            return null;
        }
        return entry;
    }

    /**
     * Removes and returns the pending registration for the given player name,
     * or {@code null} if no entry exists or it has expired.
     *
     * @param name the player name (case-insensitive)
     * @return the pending entry if valid, or null
     */
    public PendingRegistration removePending(String name) {
        PendingRegistration entry = pending.remove(name.toLowerCase(Locale.ROOT));
        if (entry == null || System.currentTimeMillis() > entry.expiresAt()) {
            return null;
        }
        return entry;
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }

    public record PendingRegistration(String email, String code, long expiresAt) {}
}
