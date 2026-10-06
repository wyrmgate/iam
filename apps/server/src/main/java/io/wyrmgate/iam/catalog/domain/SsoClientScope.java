package io.wyrmgate.iam.catalog.domain;

/** Initial curated protocol scopes. These are not Wyrmgate Administration permissions. */
public enum SsoClientScope {
    OPENID("openid"),
    PROFILE("profile"),
    EMAIL("email");

    private final String protocolValue;

    SsoClientScope(String protocolValue) {
        this.protocolValue = protocolValue;
    }

    public String protocolValue() {
        return protocolValue;
    }

    public static SsoClientScope fromProtocolValue(String value) {
        if (value != null) {
            for (SsoClientScope scope : values()) {
                if (scope.protocolValue.equals(value)) return scope;
            }
        }
        throw new IllegalArgumentException("unsupported SSO client scope");
    }
}
