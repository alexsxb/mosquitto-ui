package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.CertificateSetForm;
import com.dfc.mosquittoui.api.Dtos.CertificateSetView;
import com.dfc.mosquittoui.api.Dtos.CertificateView;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.certificate.CertificateService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Audited
@Path("/api/certificate")
public class CertificateResource {

    @Inject
    CertificateService certificates;

    @GET
    public CertificateView getCertificate() {
        return certificates.view();
    }

    @POST
    @Path("/sets")
    public CertificateSetView createCertificateSet(@Valid CertificateSetForm form) {
        return certificates.createSet(form);
    }

    @PUT
    @Path("/sets/{id}")
    public void updateCertificateSet(@PathParam("id") String id, @Valid CertificateSetForm form) {
        certificates.updateSet(id, form);
    }

    @DELETE
    @Path("/sets/{id}")
    public void deleteCertificateSet(@PathParam("id") String id) {
        certificates.deleteSet(id);
    }

    /** Starts an order and returns at once, the view shows its progress. */
    @POST
    @Path("/sets/{id}/issue")
    public void issueCertificate(@PathParam("id") String id) {
        certificates.issue(id);
    }

    /** Switches the broker to the certificate of this set. */
    @POST
    @Path("/sets/{id}/activate")
    public void activateCertificateSet(@PathParam("id") String id) {
        certificates.activate(id);
    }
}
