package io.wyrmgate.iam.integration.event;

/** ADR-0013 classification of webhook HTTP response status. */
enum WebhookHttpOutcome {
    SUCCESS,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE;

    static WebhookHttpOutcome classify(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return SUCCESS;
        }
        if (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500) {
            return RETRYABLE_FAILURE;
        }
        if (statusCode >= 300 && statusCode < 500) {
            return TERMINAL_FAILURE;
        }
        return RETRYABLE_FAILURE;
    }
}
