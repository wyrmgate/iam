package io.wyrmgate.iam.identity.domain;

/**
 * Typed profile boundary for an Identity.
 *
 * <p>The controlled v0.2 specifications require exactly one profile compatible with the
 * Identity type but do not yet define profile-specific business fields. The concrete profile
 * records therefore intentionally carry no invented attributes.</p>
 */
public sealed interface IdentityProfile
        permits IdentityProfile.PersonProfile,
                IdentityProfile.ServiceProfile,
                IdentityProfile.WorkloadProfile {

    IdentityType identityType();

    record PersonProfile() implements IdentityProfile {
        @Override
        public IdentityType identityType() {
            return IdentityType.PERSON;
        }
    }

    record ServiceProfile() implements IdentityProfile {
        @Override
        public IdentityType identityType() {
            return IdentityType.SERVICE;
        }
    }

    record WorkloadProfile() implements IdentityProfile {
        @Override
        public IdentityType identityType() {
            return IdentityType.WORKLOAD;
        }
    }
}
