package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.idp.runtime.IdpAttemptLimiter;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpInteractiveLoginService;
import io.wyrmgate.iam.idp.session.IdpSessionTokenCodec;
import java.time.Duration;
import java.time.Instant;

final class IdpLoginInteractionService {
    static final String COOKIE = "__Host-wyrmgate_interaction";
    private final IdpProtocolService protocol;
    private final SsoClientProtocolQuery clients;
    private final IdpInteractiveLoginService login;
    private final IdpBrowserSessionService sessions;
    private final IdpSessionTokenCodec tokens;
    private final IdpLoginInteractionRepository interactions;
    private final IdpAttemptLimiter limiter;

    IdpLoginInteractionService(IdpProtocolService protocol, SsoClientProtocolQuery clients,
            IdpInteractiveLoginService login, IdpBrowserSessionService sessions, IdpSessionTokenCodec tokens,
            IdpLoginInteractionRepository interactions, IdpAttemptLimiter limiter) {
        this.protocol = protocol; this.clients = clients; this.login = login;
        this.sessions = sessions; this.tokens = tokens; this.interactions = interactions; this.limiter = limiter;
    }

    Started begin(IdpProtocolService.ValidatedAuthorization validated, Instant now) {
        var r = validated.request();
        var client = validated.resolved();
        if (!limiter.acquire(client.tenant(), IdpAttemptLimiter.Kind.INTERACTION, client.tenant().tenantId())) {
            throw IdpProtocolService.ProtocolException.redirect("temporarily_unavailable", r.redirectUri(), r.state());
        }
        var target = clients.resolveAuthenticationTarget(client).orElseThrow(() ->
                IdpProtocolService.ProtocolException.redirect("login_required", r.redirectUri(), r.state()));
        var form = tokens.issue();
        var browser = tokens.issue();
        interactions.insert(new IdpLoginInteractionRepository.Interaction(form.hash(), browser.hash(),
                client.tenant(), client.registration().id(), client.registration().revision(), target,
                r, now.plus(Duration.ofMinutes(5))));
        return new Started(form.value(), browser.value());
    }

    Completed complete(String form, String browser, String key, char[] password, Instant now) {
        var i = consume(form,browser,now);
        var validated = current(i);
        var established = login.authenticate(i.request().clientId(), i.targetId(), key, password, now)
                .orElseThrow(() -> IdpProtocolService.ProtocolException.redirect("access_denied", validated.request().redirectUri(), validated.request().state()));
        try { return new Completed(protocol.authorizeValidated(current(i), established.token(), now), established); }
        catch (RuntimeException failure) { sessions.logout(established.token(), now); throw failure; }
    }

    IdpLoginInteractionRepository.Interaction consume(String form,String browser,Instant now){
        if (form == null || browser == null || !form.matches("[A-Za-z0-9_-]{43}") || !browser.matches("[A-Za-z0-9_-]{43}")) {
            throw IdpProtocolService.ProtocolException.direct("invalid_request");
        }
        return interactions.consume(tokens.hash(form), tokens.hash(browser), now)
                .orElseThrow(() -> IdpProtocolService.ProtocolException.direct("invalid_request"));
    }

    IdpProtocolService.ValidatedAuthorization current(IdpLoginInteractionRepository.Interaction i) {
        // Reject retired, changed, or cross-tenant registration before any redirect.
        var client = clients.resolveActive(i.request().clientId()).orElseThrow(() ->
                IdpProtocolService.ProtocolException.direct("invalid_request"));
        if (!client.tenant().equals(i.tenant()) || !client.registration().id().equals(i.registrationId())
                || client.registration().revision() != i.clientRevision()
                || clients.resolveAuthenticationTarget(client).filter(i.targetId()::equals).isEmpty()) {
            throw IdpProtocolService.ProtocolException.direct("invalid_request");
        }
        var validated = protocol.validate(i.request());
        if (!validated.resolved().equals(client)) throw IdpProtocolService.ProtocolException.direct("invalid_request");
        return validated;
    }

    record Started(String formProof, String browserProof) {
        @Override public String toString() { return "Started[REDACTED]"; }
    }
    record Completed(IdpProtocolService.AuthorizationResult result, IdpBrowserSessionService.EstablishedSession session) {
        @Override public String toString() { return "Completed[REDACTED]"; }
    }
}
