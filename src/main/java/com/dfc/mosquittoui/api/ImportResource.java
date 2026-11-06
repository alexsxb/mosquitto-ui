package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.ImportAnalysis;
import com.dfc.mosquittoui.api.Dtos.ImportFiles;
import com.dfc.mosquittoui.api.Dtos.ImportOutcome;
import com.dfc.mosquittoui.api.Dtos.ImportRun;
import com.dfc.mosquittoui.audit.Audited;
import com.dfc.mosquittoui.audit.NotAudited;
import com.dfc.mosquittoui.importer.ImportService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/** Migration of an existing broker. The files are not kept, each step gets them again. */
@Audited
@Path("/api/import")
public class ImportResource {

    @Inject
    ImportService imports;

    @NotAudited
    @POST
    @Path("/analyze")
    public ImportAnalysis analyzeImport(@Valid ImportFiles files) {
        return imports.analyze(files.files());
    }

    @POST
    public ImportOutcome runImport(@Valid ImportRun run) {
        return imports.run(run);
    }
}
