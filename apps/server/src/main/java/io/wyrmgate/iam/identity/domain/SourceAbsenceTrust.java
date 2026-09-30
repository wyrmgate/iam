package io.wyrmgate.iam.identity.domain;

/** Run-scoped evidence that a completed source import is safe for destructive absence inference. */
public enum SourceAbsenceTrust {
    UNTRUSTED,
    TRUSTED
}
