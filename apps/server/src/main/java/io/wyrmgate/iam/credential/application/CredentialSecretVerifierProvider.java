package io.wyrmgate.iam.credential.application;

/**
 * Secret-provider adapter contract. The provider verifies a transient presented secret against private
 * material referenced by Credential without returning that private material to the IAM domain.
 */
public interface CredentialSecretVerifierProvider {

    String providerType();

    Verification verify(String referenceKey, char[] presentedSecret);

    enum Verification {
        MATCH,
        NO_MATCH,
        UNAVAILABLE
    }
}