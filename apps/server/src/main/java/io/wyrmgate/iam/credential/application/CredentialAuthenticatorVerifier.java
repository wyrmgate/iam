package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Credential-owned verification contract consumed by Authentication without exposing private material. */
public interface CredentialAuthenticatorVerifier {

    Result verifyPassword(
            TenantContext tenant,
            UUID principalId,
            char[] presentedSecret,
            Instant now);

    enum Status {
        VERIFIED,
        INVALID,
        UNAVAILABLE
    }

    enum Strength {
        BASELINE,
        STRONG
    }

    record Result(Status status, Strength strength) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (status == Status.VERIFIED) {
                Objects.requireNonNull(strength, "strength");
            } else if (strength != null) {
                throw new IllegalArgumentException("non-verified result must not carry strength");
            }
        }

        public static Result verified(Strength strength) {
            return new Result(Status.VERIFIED, strength);
        }

        public static Result invalid() {
            return new Result(Status.INVALID, null);
        }

        public static Result unavailable() {
            return new Result(Status.UNAVAILABLE, null);
        }
    }
}