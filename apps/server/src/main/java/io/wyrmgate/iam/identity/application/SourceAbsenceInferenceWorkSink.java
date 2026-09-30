package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.SourceAbsenceInference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Durable Identity-owned continuation work for source absence inference. */
public interface SourceAbsenceInferenceWorkSink {

    String INFERENCE_REQUESTED = "identity.source-absence-inference-requested";

    void inferenceRequested(
            TenantContext tenant,
            SourceAbsenceInference inference,
            UUID correlationId,
            UUID causationId);
}
