package com.siddheshfulawade.payflow.recon.app;

import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import java.time.LocalDate;
import java.util.List;

/** recon_result, unique on (business date, payment id): "insert ... on conflict do nothing". */
public interface ReportStore {

    /** Returns how many rows were new. */
    int insertIgnoringDuplicates(LocalDate businessDate, List<ReconResult> chunk);
}
