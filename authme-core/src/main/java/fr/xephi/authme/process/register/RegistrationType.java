package fr.xephi.authme.process.register;

/**
 * Registration type.
 */
public enum RegistrationType {

    /**
     * Password registration: account is registered with a password supplied by the player.
     */
    PASSWORD,

    /**
     * Email registration: account is registered with an email supplied by the player. A password
     * is generated and sent to the email address.
     */
    EMAIL,

    /**
     * Email-verified password registration: the player first submits an email address, receives a
     * verification code, and only after verifying the code can they set their password. The account
     * is not created until the email is verified.
     */
    EMAIL_VERIFIED_PASSWORD

}