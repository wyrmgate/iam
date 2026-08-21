package io.wyrmgate.iam.platform.id;

import java.util.UUID;

/** Generates opaque stable identifiers before persistence. */
public interface IdGenerator {

    UUID nextId();
}
