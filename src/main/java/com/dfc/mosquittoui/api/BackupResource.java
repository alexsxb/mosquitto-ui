package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.audit.NotAudited;
import com.dfc.mosquittoui.backup.ArchiveService;
import com.dfc.mosquittoui.backup.ArchiveService.ArchiveInfo;
import com.dfc.mosquittoui.backup.ArchiveService.ArchiveRestoreReport;
import com.dfc.mosquittoui.backup.BackupException;
import com.dfc.mosquittoui.backup.BackupFiles;
import com.dfc.mosquittoui.backup.BackupJobService;
import com.dfc.mosquittoui.backup.BackupRunService;
import com.dfc.mosquittoui.backup.BackupSection;
import com.dfc.mosquittoui.backup.BackupSettingsService;
import com.dfc.mosquittoui.backup.BackupTargetService;
import com.dfc.mosquittoui.backup.crypto.Gpg;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/** Targets, jobs, runs, settings and restore of backups. */
@Audited
@Path("/api/backup")
public class BackupResource {

    private static final Logger LOG = Logger.getLogger(BackupResource.class);

    public record SectionOption(BackupSection section, boolean encryptedOnly) {
    }

    /** serverTime: now in the time zone of the backups. */
    public record BackupOverview(List<BackupTargetService.TargetView> targets, List<BackupJobService.JobView> jobs, BackupSettingsService.BackupSettings settings,
            String serverTime, List<SectionOption> sections, List<BackupRunService.JobHealth> health) {
    }

    public record BackupSettingsResult(BackupSettingsService.BackupSettings settings, int pruned) {
    }

    public record BackupStartResult(long runId) {
    }

    public record GpgKeyView(String fingerprint, List<String> names, Instant createdAt, @Schema(nullable = true) Instant validUntil, String algorithm, String armored,
            @Schema(nullable = true) String problem) {
    }

    /** password: encrypts the archive; parts with secrets need it. */
    public record ArchiveRequest(List<BackupSection> sections, @Schema(nullable = true) String password) {
    }

    /** own: a backup of this application, the others cannot be restored. size and modified: null if the target does not tell. */
    public record TargetFile(String name, @Schema(nullable = true) Long size, @Schema(nullable = true) Instant modified, boolean own) {
    }

    /** password: of an encrypted backup. trustUnsigned: restore although the backup is not signed by this installation. */
    public record RestoreRequest(@Schema(nullable = true) String password, @Schema(nullable = true) Set<BackupSection> sections, boolean trustUnsigned) {
    }

    @Inject
    BackupTargetService targets;

    @Inject
    BackupJobService jobs;

    @Inject
    BackupRunService runs;

    @Inject
    BackupSettingsService settings;

    @Inject
    BackupFiles files;

    @Inject
    ArchiveService archives;

    @Inject
    SecurityIdentity identity;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.backup.keyserver")
    String keyserver;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private String user() {
        return identity.getPrincipal().getName();
    }

    @GET
    public BackupOverview getBackupOverview() {
        return new BackupOverview(targets.list(), jobs.list(), settings.get(), files.serverTime(),
                Arrays.stream(BackupSection.values()).map(section -> new SectionOption(section, section.encryptedOnly())).toList(), runs.health());
    }

    @POST
    @Path("/targets")
    public BackupTargetService.TargetView createBackupTarget(BackupTargetService.TargetInput input) {
        return targets.create(input);
    }

    @PUT
    @Path("/targets/{id}")
    public BackupTargetService.TargetView updateBackupTarget(@PathParam("id") long id, BackupTargetService.TargetInput input) {
        return targets.update(id, input);
    }

    @DELETE
    @Path("/targets/{id}")
    public void deleteBackupTarget(@PathParam("id") long id) {
        targets.delete(id);
    }

    @NotAudited
    @POST
    @Path("/targets/{id}/test")
    public BackupTargetService.TargetTestResult testBackupTarget(@PathParam("id") long id) {
        return targets.test(id);
    }

    @DELETE
    @Path("/targets/{id}/host-key")
    public void forgetBackupTargetHostKey(@PathParam("id") long id) {
        targets.forgetHostKey(id);
    }

    /** All files on the target, newest backups first. */
    @GET
    @Path("/targets/{id}/files")
    public List<TargetFile> listBackupTargetFiles(@PathParam("id") long id) throws Exception {
        return files.list(id).stream().map(file -> new TargetFile(file.name(), file.size(), file.modified(), BackupFiles.own(file.name()))).toList();
    }

    /** Names on a target are not ours to trust, so this one is encoded and cannot break out of the header. */
    @GET
    @Path("/targets/{id}/files/{name}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadBackupTargetFile(@PathParam("id") long id, @PathParam("name") String name) throws Exception {
        java.nio.file.Path file = files.fetch(id, name);
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return Response.ok((StreamingOutput) out -> {
            try {
                Files.copy(file, out);
            } finally {
                Files.deleteIfExists(file);
            }
        }).header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded).build();
    }

    @DELETE
    @Path("/targets/{id}/files/{name}")
    public void deleteBackupTargetFile(@PathParam("id") long id, @PathParam("name") String name) throws Exception {
        files.delete(id, name);
        LOG.infof("%s deleted %s from backup target %d", user(), name, id);
    }

    @NotAudited
    @POST
    @Path("/targets/{id}/files/{name}/inspect")
    public ArchiveInfo inspectBackupTargetFile(@PathParam("id") long id, @PathParam("name") String name, RestoreRequest request) throws Exception {
        return archives.info(archives.read(files.download(id, name), request == null ? null : request.password()));
    }

    @POST
    @Path("/targets/{id}/files/{name}/restore")
    public ArchiveRestoreReport restoreBackupTargetFile(@PathParam("id") long id, @PathParam("name") String name, RestoreRequest request) throws Exception {
        if (request == null) {
            throw BackupException.validation("sections", "Select at least one part of the backup");
        }
        return archives.restore(archives.read(files.download(id, name), request.password()), request.sections(), request.trustUnsigned());
    }

    @POST
    @Path("/jobs")
    public BackupJobService.JobView createBackupJob(BackupJobService.JobInput input) {
        return jobs.create(input);
    }

    @PUT
    @Path("/jobs/{id}")
    public BackupJobService.JobView updateBackupJob(@PathParam("id") long id, BackupJobService.JobInput input) {
        return jobs.update(id, input);
    }

    @DELETE
    @Path("/jobs/{id}")
    public void deleteBackupJob(@PathParam("id") long id) {
        jobs.delete(id);
    }

    @POST
    @Path("/jobs/{id}/copy")
    public BackupJobService.JobView copyBackupJob(@PathParam("id") long id) {
        return jobs.copy(id);
    }

    @POST
    @Path("/jobs/{id}/start")
    public BackupStartResult startBackupJob(@PathParam("id") long id) {
        return new BackupStartResult(runs.start(id, user()));
    }

    @POST
    @Path("/jobs/{id}/cancel")
    public void cancelBackupJob(@PathParam("id") long id) {
        runs.cancel(id, user());
    }

    @GET
    @Path("/runs")
    public BackupRunService.RunPage listBackupRuns(@QueryParam("limit") @DefaultValue("25") int limit, @QueryParam("offset") @DefaultValue("0") int offset) {
        return runs.list(limit, offset);
    }

    @GET
    @Path("/runs/{id}")
    public BackupRunService.RunView getBackupRun(@PathParam("id") long id) {
        return runs.get(id);
    }

    @PUT
    @Path("/settings")
    public BackupSettingsResult updateBackupSettings(BackupSettingsService.BackupSettings input) {
        BackupSettingsService.BackupSettings saved = settings.update(input);
        return new BackupSettingsResult(saved, runs.pruneRuns());
    }

    /** Looks a key up on the keyserver: e-mail address, fingerprint or key id. */
    @GET
    @Path("/gpg/search")
    public GpgKeyView searchGpgKey(@QueryParam("q") String query) {
        try {
            Gpg.Key key = Gpg.search(query == null ? "" : query.strip(), keyserver, http);
            if (key == null) {
                throw BackupException.notFound("GPG key");
            }
            return view(key);
        } catch (IllegalArgumentException e) {
            throw BackupException.validation("q", e.getMessage());
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw BackupException.validation("q", "Keyserver: " + e.getMessage());
        }
    }

    /** Checks a pasted armored public key. */
    @NotAudited
    @POST
    @Path("/gpg/key")
    @Consumes(MediaType.TEXT_PLAIN)
    public GpgKeyView parseGpgKey(String armored) {
        try {
            return view(Gpg.parse(armored));
        } catch (IllegalArgumentException e) {
            throw BackupException.validation("key", e.getMessage());
        }
    }

    private static GpgKeyView view(Gpg.Key key) {
        return new GpgKeyView(key.fingerprint(), key.names(), key.createdAt(), key.validUntil(), key.algorithm(), key.armored(), key.problem());
    }

    /** With a password encrypted; parts with secrets are refused without one. */
    @POST
    @Path("/archive")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadBackupArchive(ArchiveRequest request) throws Exception {
        BackupFiles.Download download = files.archive(request == null ? null : request.sections(), request == null ? null : request.password(), user());
        return Response.ok((StreamingOutput) out -> out.write(download.content()))
                .header("Content-Disposition", "attachment; filename=\"" + download.name() + "\"").build();
    }

    @NotAudited
    @POST
    @Path("/restore/inspect")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public ArchiveInfo inspectBackupUpload(@RestForm("file") FileUpload file, @RestForm("password") String password) throws IOException {
        return archives.info(archives.read(read(file), password));
    }

    /** request: JSON of RestoreRequest; the password comes as its own part. */
    @POST
    @Path("/restore")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public ArchiveRestoreReport restoreBackupUpload(@RestForm("file") FileUpload file, @RestForm("password") String password, @RestForm("request") String request)
            throws IOException {
        if (request == null) {
            throw BackupException.validation("sections", "Select at least one part of the backup");
        }
        RestoreRequest parsed = mapper.readValue(request, RestoreRequest.class);
        return archives.restore(archives.read(read(file), password), parsed.sections(), parsed.trustUnsigned());
    }

    private static byte[] read(FileUpload file) throws IOException {
        if (file == null) {
            throw BackupException.validation("file", "No file uploaded");
        }
        return Files.readAllBytes(file.uploadedFile());
    }
}
