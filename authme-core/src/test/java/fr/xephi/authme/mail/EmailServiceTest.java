package fr.xephi.authme.mail;

import fr.xephi.authme.TestHelper;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;
import fr.xephi.authme.settings.properties.PluginSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Test for {@link EmailService}.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    private EmailService emailService;

    @Mock
    private Settings settings;
    @Mock
    private MailSender mailSender;
    @TempDir
    File dataFolder;

    @BeforeAll
    static void initLogger() {
        TestHelper.setupLogger();
    }

    @BeforeEach
    void initFieldsAndService() {
        emailService = new EmailService(dataFolder, settings, mailSender);
    }

    @Test
    void shouldHaveAllInformation() {
        // given
        given(mailSender.hasAllInformation()).willReturn(true);

        // when / then
        assertThat(emailService.hasAllInformation(), equalTo(true));
    }

    @Test
    void shouldSendPasswordMail() {
        // given
        given(settings.getPasswordEmailMessage())
            .willReturn("Hi <playername />, your new password for <servername /> is <generatedpass />");
        given(settings.getProperty(EmailSettings.PASSWORD_AS_IMAGE)).willReturn(false);
        given(settings.getProperty(PluginSettings.SERVER_NAME)).willReturn("serverName");
        given(settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT)).willReturn("Your new AuthMe password");
        given(mailSender.hasAllInformation()).willReturn(true);
        given(mailSender.sendMail(anyString(), anyString(), anyString(), any())).willReturn(true);

        // when
        boolean result = emailService.sendPasswordMail("Player", "user@example.com", "new_password");

        // then
        assertThat(result, equalTo(true));
        ArgumentCaptor<String> recipientCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendMail(recipientCaptor.capture(), anyString(), messageCaptor.capture(), any());
        assertThat(recipientCaptor.getValue(), equalTo("user@example.com"));
        assertThat(messageCaptor.getValue(),
            equalTo("Hi Player, your new password for serverName is new_password"));
    }

    @Test
    void shouldHandleMailSendingFailure() {
        // given
        given(settings.getPasswordEmailMessage()).willReturn("Hi <playername />, your new pass is <generatedpass />");
        given(settings.getProperty(EmailSettings.PASSWORD_AS_IMAGE)).willReturn(false);
        given(settings.getProperty(PluginSettings.SERVER_NAME)).willReturn("serverName");
        given(settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT)).willReturn("Subject");
        given(mailSender.hasAllInformation()).willReturn(true);
        given(mailSender.sendMail(anyString(), anyString(), anyString(), any())).willReturn(false);

        // when
        boolean result = emailService.sendPasswordMail("bobby", "user@example.com", "myPassw0rd");

        // then
        assertThat(result, equalTo(false));
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendMail(anyString(), anyString(), messageCaptor.capture(), any());
        assertThat(messageCaptor.getValue(), equalTo("Hi bobby, your new pass is myPassw0rd"));
    }

    @Test
    void shouldNotSendMailIfSettingsIncomplete() {
        // given
        given(mailSender.hasAllInformation()).willReturn(false);

        // when
        boolean result = emailService.sendPasswordMail("Player", "user@example.com", "new_password");

        // then
        assertThat(result, equalTo(false));
        verify(mailSender, never()).sendMail(anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldSendRecoveryCode() {
        // given
        given(settings.getProperty(SecuritySettings.RECOVERY_CODE_HOURS_VALID)).willReturn(7);
        given(settings.getProperty(PluginSettings.SERVER_NAME)).willReturn("serverName");
        given(settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT)).willReturn("Subject");
        given(settings.getRecoveryCodeEmailMessage())
            .willReturn("Hi <playername />, your code on <servername /> is <recoverycode /> (valid <hoursvalid /> hours)");
        given(mailSender.sendMail(anyString(), anyString(), anyString(), any())).willReturn(true);

        // when
        boolean result = emailService.sendRecoveryCode("Timmy", "tim@example.com", "12C56A");

        // then
        assertThat(result, equalTo(true));
        ArgumentCaptor<String> recipientCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendMail(recipientCaptor.capture(), anyString(), messageCaptor.capture(), isNull());
        assertThat(recipientCaptor.getValue(), equalTo("tim@example.com"));
        assertThat(messageCaptor.getValue(), equalTo("Hi Timmy, your code on serverName is 12C56A (valid 7 hours)"));
    }

    @Test
    void shouldHandleFailureToSendRecoveryCode() {
        // given
        given(settings.getProperty(SecuritySettings.RECOVERY_CODE_HOURS_VALID)).willReturn(7);
        given(settings.getProperty(PluginSettings.SERVER_NAME)).willReturn("Server? I barely know her!");
        given(settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT)).willReturn("Subject");
        given(settings.getRecoveryCodeEmailMessage()).willReturn("Hi <playername />, your code is <recoverycode /> for <servername />");
        given(mailSender.sendMail(anyString(), anyString(), anyString(), any())).willReturn(false);

        // when
        boolean result = emailService.sendRecoveryCode("John", "user@example.com", "1DEF77");

        // then
        assertThat(result, equalTo(false));
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendMail(anyString(), anyString(), messageCaptor.capture(), isNull());
        assertThat(messageCaptor.getValue(), equalTo("Hi John, your code is 1DEF77 for Server? I barely know her!"));
    }
}
