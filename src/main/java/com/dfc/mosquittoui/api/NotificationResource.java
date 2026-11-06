package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.audit.NotAudited;
import com.dfc.mosquittoui.backup.BackupException;
import com.dfc.mosquittoui.notify.MailService;
import com.dfc.mosquittoui.notify.NotificationSettingsService;
import com.dfc.mosquittoui.notify.NotificationSettingsService.NotificationInput;
import com.dfc.mosquittoui.notify.NotificationSettingsService.NotificationSettings;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.io.IOException;

/** The mail server and who gets told what. */
@Audited
@Path("/api/notifications")
public class NotificationResource {

    @Inject
    NotificationSettingsService settings;

    @Inject
    MailService mail;

    @GET
    public NotificationSettings getNotifications() {
        return settings.get();
    }

    @PUT
    public NotificationSettings updateNotifications(NotificationInput input) {
        return settings.update(input);
    }

    @NotAudited
    @POST
    @Path("/test-mail")
    public void sendTestMail() {
        try {
            mail.sendTest();
        } catch (IOException e) {
            throw BackupException.validation("mail", e.getMessage());
        }
    }
}
