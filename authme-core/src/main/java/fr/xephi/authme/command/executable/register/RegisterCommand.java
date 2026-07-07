package fr.xephi.authme.command.executable.register;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.captcha.RegistrationCaptchaManager;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.mail.EmailService;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.process.register.RegisterSecondaryArgument;
import fr.xephi.authme.process.register.RegistrationType;
import fr.xephi.authme.process.register.executors.EmailRegisterParams;
import fr.xephi.authme.process.register.executors.EmailVerifiedRegisterParams;
import fr.xephi.authme.process.register.executors.PasswordRegisterParams;
import fr.xephi.authme.process.register.executors.RegistrationMethod;
import fr.xephi.authme.process.register.executors.TwoFactorRegisterParams;
import fr.xephi.authme.security.HashAlgorithm;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.PendingRegistrationCache;
import fr.xephi.authme.service.ValidationService;
import fr.xephi.authme.settings.properties.EmailSettings;
import fr.xephi.authme.settings.properties.RegistrationSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import fr.xephi.authme.util.RandomStringUtils;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;

import static fr.xephi.authme.process.register.RegisterSecondaryArgument.CONFIRMATION;
import static fr.xephi.authme.process.register.RegisterSecondaryArgument.EMAIL_MANDATORY;
import static fr.xephi.authme.process.register.RegisterSecondaryArgument.EMAIL_OPTIONAL;
import static fr.xephi.authme.process.register.RegisterSecondaryArgument.NONE;
import static fr.xephi.authme.settings.properties.RegistrationSettings.REGISTER_SECOND_ARGUMENT;

/**
 * Command for /register.
 */
public class RegisterCommand extends PlayerCommand {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(RegisterCommand.class);

    @Inject
    private Management management;

    @Inject
    private CommonService commonService;

    @Inject
    private EmailService emailService;

    @Inject
    private ValidationService validationService;

    @Inject
    private RegistrationCaptchaManager registrationCaptchaManager;

    @Inject
    private PendingRegistrationCache pendingRegistrationCache;

    @Inject
    private DataSource dataSource;

    @Inject
    private BukkitService bukkitService;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        if (!isCaptchaFulfilled(player)) {
            return; // isCaptchaFulfilled handles informing the player on failure
        }

        if (commonService.getProperty(SecuritySettings.PASSWORD_HASH) == HashAlgorithm.TWO_FACTOR) {
            //for two factor auth we don't need to check the usage
            management.performRegister(RegistrationMethod.TWO_FACTOR_REGISTRATION,
                TwoFactorRegisterParams.of(player));
            return;
        } else if (arguments.size() < 1) {
            RegistrationType registrationType = commonService.getProperty(RegistrationSettings.REGISTRATION_TYPE);
            if (registrationType == RegistrationType.EMAIL_VERIFIED_PASSWORD) {
                commonService.send(player, MessageKey.USAGE_REGISTER_VERIFY);
            } else {
                commonService.send(player, MessageKey.USAGE_REGISTER);
            }
            return;
        }

        RegistrationType registrationType = commonService.getProperty(RegistrationSettings.REGISTRATION_TYPE);
        if (registrationType == RegistrationType.PASSWORD) {
            handlePasswordRegistration(player, arguments);
        } else if (registrationType == RegistrationType.EMAIL) {
            handleEmailRegistration(player, arguments);
        } else if (registrationType == RegistrationType.EMAIL_VERIFIED_PASSWORD) {
            handleEmailVerifiedRegistration(player, arguments);
        } else {
            throw new IllegalStateException("Unknown registration type '" + registrationType + "'");
        }
    }

    @Override
    protected String getAlternativeCommand() {
        return "/authme register <playername> <password>";
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_REGISTER;
    }

    private boolean isCaptchaFulfilled(Player player) {
        if (registrationCaptchaManager.isCaptchaRequired(player.getName())) {
            String code = registrationCaptchaManager.getCaptchaCodeOrGenerateNew(player.getName());
            commonService.send(player, MessageKey.CAPTCHA_FOR_REGISTRATION_REQUIRED, code);
            return false;
        }
        return true;
    }

    private void handlePasswordRegistration(Player player, List<String> arguments) {
        if (isSecondArgValidForPasswordRegistration(player, arguments)) {
            final String password = arguments.get(0);
            final String email = getEmailIfAvailable(arguments);

            management.performRegister(RegistrationMethod.PASSWORD_REGISTRATION,
                PasswordRegisterParams.of(player, password, email));
        }
    }

    private String getEmailIfAvailable(List<String> arguments) {
        if (arguments.size() >= 2) {
            RegisterSecondaryArgument secondArgType = commonService.getProperty(REGISTER_SECOND_ARGUMENT);
            if (secondArgType == EMAIL_MANDATORY || secondArgType == EMAIL_OPTIONAL) {
                return arguments.get(1);
            }
        }
        return null;
    }

    /**
     * Verifies that the second argument is valid (based on the configuration)
     * to perform a password registration. The player is informed if the check
     * is unsuccessful.
     *
     * @param player the player to register
     * @param arguments the provided arguments
     * @return true if valid, false otherwise
     */
    private boolean isSecondArgValidForPasswordRegistration(Player player, List<String> arguments) {
        RegisterSecondaryArgument secondArgType = commonService.getProperty(REGISTER_SECOND_ARGUMENT);
        // cases where args.size < 2
        if (secondArgType == NONE || secondArgType == EMAIL_OPTIONAL && arguments.size() < 2) {
            return true;
        } else if (arguments.size() < 2) {
            commonService.send(player, MessageKey.USAGE_REGISTER);
            return false;
        }

        if (secondArgType == CONFIRMATION) {
            if (arguments.get(0).equals(arguments.get(1))) {
                return true;
            } else {
                commonService.send(player, MessageKey.PASSWORD_MATCH_ERROR);
                return false;
            }
        } else if (secondArgType == EMAIL_MANDATORY || secondArgType == EMAIL_OPTIONAL) {
            if (validationService.validateEmail(arguments.get(1))) {
                return true;
            } else {
                commonService.send(player, MessageKey.INVALID_EMAIL);
                return false;
            }
        } else {
            throw new IllegalStateException("Unknown secondary argument type '" + secondArgType + "'");
        }
    }

    private void handleEmailRegistration(Player player, List<String> arguments) {
        if (!emailService.hasAllInformation()) {
            commonService.send(player, MessageKey.INCOMPLETE_EMAIL_SETTINGS);
            logger.warning("Cannot register player '" + player.getName() + "': no email or password is set "
                + "to send emails from. Please adjust your config at " + EmailSettings.MAIL_ACCOUNT.getPath());
            return;
        }

        final String email = arguments.get(0);
        if (!validationService.validateEmail(email)) {
            commonService.send(player, MessageKey.INVALID_EMAIL);
        } else if (isSecondArgValidForEmailRegistration(player, arguments)) {
            management.performRegister(RegistrationMethod.EMAIL_REGISTRATION,
                EmailRegisterParams.of(player, email));
        }
    }

    /**
     * Verifies that the second argument is valid (based on the configuration)
     * to perform an email registration. The player is informed if the check
     * is unsuccessful.
     *
     * @param player the player to register
     * @param arguments the provided arguments
     * @return true if valid, false otherwise
     */
    private boolean isSecondArgValidForEmailRegistration(Player player, List<String> arguments) {
        RegisterSecondaryArgument secondArgType = commonService.getProperty(REGISTER_SECOND_ARGUMENT);
        // cases where args.size < 2
        if (secondArgType == NONE || secondArgType == EMAIL_OPTIONAL && arguments.size() < 2) {
            return true;
        } else if (arguments.size() < 2) {
            commonService.send(player, MessageKey.USAGE_REGISTER);
            return false;
        }

        if (secondArgType == EMAIL_OPTIONAL || secondArgType == EMAIL_MANDATORY || secondArgType == CONFIRMATION) {
            if (arguments.get(0).equals(arguments.get(1))) {
                return true;
            } else {
                commonService.send(player, MessageKey.USAGE_REGISTER);
                return false;
            }
        } else {
            throw new IllegalStateException("Unknown secondary argument type '" + secondArgType + "'");
        }
    }

    // -------------------------------------------------------------------------
    // Email-verified password registration (two-stage)
    // -------------------------------------------------------------------------

    /**
     * Handles the two-stage email-verified password registration.
     *
     * <p>Stage 1: {@code /register <email>} — sends a verification code to the email.
     * Stage 2: {@code /register verify <code> <password>} — verifies the code and creates the account.</p>
     *
     * @param player the player to register
     * @param arguments the provided arguments
     */
    private void handleEmailVerifiedRegistration(Player player, List<String> arguments) {
        if (!emailService.hasAllInformation()) {
            commonService.send(player, MessageKey.INCOMPLETE_EMAIL_SETTINGS);
            logger.warning("Cannot register player '" + player.getName() + "': no email sender is configured "
                + "to send verification emails. Please adjust your config at " + EmailSettings.MAIL_SENDER.getPath());
            return;
        }

        // Stage 2: /register verify <code> <password>
        if (arguments.size() >= 1 && "verify".equalsIgnoreCase(arguments.get(0))) {
            handleEmailVerifiedStage2(player, arguments);
            return;
        }

        // Stage 1: /register <email>
        handleEmailVerifiedStage1(player, arguments);
    }

    /**
     * Stage 1: the player submits an email address. A verification code is generated
     * and sent to that address; the pending entry is stored in
     * {@link PendingRegistrationCache}. No account is created yet.
     *
     * @param player the player to register
     * @param arguments the provided arguments (expected: [email])
     */
    private void handleEmailVerifiedStage1(Player player, List<String> arguments) {
        final String name = player.getName().toLowerCase(Locale.ROOT);

        // Reject if account already exists
        if (dataSource.isAuthAvailable(name)) {
            commonService.send(player, MessageKey.NAME_ALREADY_REGISTERED);
            return;
        }

        // Reject if already logged in
        if (arguments.isEmpty()) {
            commonService.send(player, MessageKey.USAGE_REGISTER);
            return;
        }

        final String email = arguments.get(0);
        if (!validationService.validateEmail(email)) {
            commonService.send(player, MessageKey.INVALID_EMAIL);
            return;
        }

        // Check email not already used by another account
        if (!validationService.isEmailFreeForRegistration(email, player)) {
            commonService.send(player, MessageKey.EMAIL_ALREADY_USED_ERROR);
            return;
        }

        // Generate a 6-digit code and send it asynchronously
        final String code = RandomStringUtils.generateNum(6);
        bukkitService.runTaskAsynchronously(() -> {
            if (emailService.sendEmailConfirmationMail(player.getName(), email, code)) {
                pendingRegistrationCache.addPending(name, email, code);
                commonService.send(player, MessageKey.REGISTRATION_VERIFY_EMAIL_SENT, email);
            } else {
                commonService.send(player, MessageKey.EMAIL_SEND_FAILURE);
            }
        });
    }

    /**
     * Stage 2: the player submits the verification code and a chosen password.
     * If the code matches the pending entry, the account is created via
     * {@link Management#performRegister} with the verified email.
     *
     * @param player the player to register
     * @param arguments the provided arguments (expected: [verify, code, password])
     */
    private void handleEmailVerifiedStage2(Player player, List<String> arguments) {
        // Expected: /register verify <code> <password>
        if (arguments.size() < 3) {
            commonService.send(player, MessageKey.USAGE_REGISTER_VERIFY);
            return;
        }

        final String name = player.getName().toLowerCase(Locale.ROOT);
        final String code = arguments.get(1);
        final String password = arguments.get(2);

        PendingRegistrationCache.PendingRegistration entry = pendingRegistrationCache.getEntry(name);
        if (entry == null) {
            commonService.send(player, MessageKey.REGISTRATION_VERIFY_EXPIRED);
            return;
        }

        if (!entry.code().equals(code)) {
            commonService.send(player, MessageKey.REGISTRATION_VERIFY_WRONG_CODE);
            return;
        }

        // Code is correct — consume the pending entry and proceed to account creation
        pendingRegistrationCache.removePending(name);
        management.performRegister(RegistrationMethod.EMAIL_VERIFIED_PASSWORD_REGISTRATION,
            EmailVerifiedRegisterParams.of(player, password, entry.email()));
    }
}
