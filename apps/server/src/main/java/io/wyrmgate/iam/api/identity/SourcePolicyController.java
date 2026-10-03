package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.identity.SourcePolicyApiModels.AbsencePolicyResource;
import io.wyrmgate.iam.api.identity.SourcePolicyApiModels.CorrelationPolicyResource;
import io.wyrmgate.iam.api.identity.SourcePolicyApiModels.LifecyclePolicyResource;
import io.wyrmgate.iam.api.identity.SourcePolicyApiModels.LifecycleRuleResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.identity.application.SourceCorrelationRepository;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.SourceAbsencePolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceCorrelationPolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceLifecyclePolicyVersion;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/source-systems/{sourceSystemId}")
public final class SourcePolicyController {

    private final SourceCorrelationRepository repository;
    private final SourcePolicyApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    public SourcePolicyController(
            SourceCorrelationRepository repository,
            SourcePolicyApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids) {
        this.repository=repository;
        this.mutations=mutations;
        this.authorization=authorization;
        this.ids=ids;
    }

    @GetMapping("/correlation-policy")
    public ResponseEntity<CorrelationPolicyResource> correlation(
            @PathVariable UUID sourceSystemId,
            HttpServletRequest request) {
        UUID correlationId=IdentityApiRequestContext.resolveCorrelationId(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        require(actor, sourceSystemId, AdministrativePermissions.SOURCE_CORRELATION_POLICY_READ,
                "source-correlation-policy", correlationId);
        var policy=repository.findActiveCorrelationPolicy(actor.tenant(),sourceSystemId)
                .orElseThrow(() -> IdentityApiException.notFound(correlationId));
        return response(resource(policy),correlationId);
    }

    @PostMapping("/correlation-policy:activate")
    public ResponseEntity<CorrelationPolicyResource> activateCorrelation(
            @PathVariable UUID sourceSystemId,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId=IdentityApiRequestContext.resolveCorrelationId(request,ids);
        String key=idempotencyKey(idempotencyKey,correlationId);
        exact(body,Set.of(
                "canonicalKey","createIdentityOnNoMatch","createdIdentityType","displayNameSourcePath"),
                correlationId);
        String canonicalKey=text(body.get("canonicalKey"),"canonicalKey",correlationId);
        boolean create=bool(body.get("createIdentityOnNoMatch"),"createIdentityOnNoMatch",correlationId);
        IdentityType createdType=optionalEnum(
                body.get("createdIdentityType"),IdentityType.class,"createdIdentityType",correlationId);
        String displayNamePath=optionalText(
                body.get("displayNameSourcePath"),"displayNameSourcePath",correlationId);
        if (create && (createdType==null || displayNamePath==null)) {
            throw IdentityApiException.validation(
                    correlationId,"createdIdentityType","required",
                    "creation-enabled correlation policy requires createdIdentityType and displayNameSourcePath.");
        }
        if (!create && (createdType!=null || displayNamePath!=null)) {
            throw IdentityApiException.validation(
                    correlationId,"createdIdentityType","not_allowed",
                    "creation metadata must be absent when createIdentityOnNoMatch is false.");
        }
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        var result=mutations.activateCorrelation(
                actor,sourceSystemId,canonicalKey,create,createdType,displayNamePath,
                key,fingerprint(
                        "correlation",sourceSystemId,canonicalKey,create,createdType,displayNamePath),
                Instant.now(),correlationId);
        return response(resource(result),correlationId);
    }

    @GetMapping("/lifecycle-policy")
    public ResponseEntity<LifecyclePolicyResource> lifecycle(
            @PathVariable UUID sourceSystemId,
            HttpServletRequest request) {
        UUID correlationId=IdentityApiRequestContext.resolveCorrelationId(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        require(actor, sourceSystemId, AdministrativePermissions.SOURCE_LIFECYCLE_POLICY_READ,
                "source-lifecycle-policy", correlationId);
        var policy=repository.findActiveLifecyclePolicy(actor.tenant(),sourceSystemId)
                .orElseThrow(() -> IdentityApiException.notFound(correlationId));
        return response(resource(policy),correlationId);
    }

    @PostMapping("/lifecycle-policy:activate")
    public ResponseEntity<LifecyclePolicyResource> activateLifecycle(
            @PathVariable UUID sourceSystemId,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId=IdentityApiRequestContext.resolveCorrelationId(request,ids);
        String key=idempotencyKey(idempotencyKey,correlationId);
        exact(body,Set.of("sourcePath","rules"),correlationId);
        String sourcePath=text(body.get("sourcePath"),"sourcePath",correlationId);
        Map<String,IdentityLifecycleState> mappings=parseLifecycleRules(body.get("rules"),correlationId);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        var result=mutations.activateLifecycle(
                actor,sourceSystemId,sourcePath,mappings,key,
                lifecycleFingerprint(sourceSystemId,sourcePath,mappings),
                Instant.now(),correlationId);
        return response(resource(result),correlationId);
    }

    @GetMapping("/absence-policy")
    public ResponseEntity<AbsencePolicyResource> absence(
            @PathVariable UUID sourceSystemId,
            HttpServletRequest request) {
        UUID correlationId=IdentityApiRequestContext.resolveCorrelationId(request,ids);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        require(actor, sourceSystemId, AdministrativePermissions.SOURCE_ABSENCE_POLICY_READ,
                "source-absence-policy", correlationId);
        var policy=repository.findActiveAbsencePolicy(actor.tenant(),sourceSystemId)
                .orElseThrow(() -> IdentityApiException.notFound(correlationId));
        return response(resource(policy),correlationId);
    }

    @PostMapping("/absence-policy:activate")
    public ResponseEntity<AbsencePolicyResource> activateAbsence(
            @PathVariable UUID sourceSystemId,
            @RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId=IdentityApiRequestContext.resolveCorrelationId(request,ids);
        String key=idempotencyKey(idempotencyKey,correlationId);
        exact(body,Set.of("maxInferredTransitions"),correlationId);
        int maxTransitions=positiveInt(
                body.get("maxInferredTransitions"),"maxInferredTransitions",correlationId);
        AuthenticatedAdministrativeActor actor=ControlPlaneActorRequestContext.require(request);
        var result=mutations.activateAbsence(
                actor,sourceSystemId,maxTransitions,key,
                fingerprint("absence",sourceSystemId,maxTransitions),
                Instant.now(),correlationId);
        return response(resource(result),correlationId);
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            UUID sourceSystemId,
            AdministrativePermission permission,
            String resourceType,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,permission,new AdministrativeResource(resourceType,sourceSystemId),Instant.now()).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }
    }

    private static CorrelationPolicyResource resource(SourceCorrelationPolicyVersion p) {
        return new CorrelationPolicyResource(
                p.id(),p.sourceSystemId(),p.matchAttributeDefinitionVersionId(),p.matchMappingVersionId(),
                p.versionNumber(),p.createIdentityOnNoMatch(),
                p.createdIdentityType()==null?null:p.createdIdentityType().name(),
                p.displayNameSourcePath(),p.state().name(),p.createdAt(),p.activatedAt(),p.supersededAt());
    }

    private static LifecyclePolicyResource resource(SourceLifecyclePolicyVersion p) {
        return new LifecyclePolicyResource(
                p.id(),p.sourceSystemId(),p.sourcePath(),p.versionNumber(),
                p.rules().stream()
                        .map(r -> new LifecycleRuleResource(r.sourceValue(),r.targetState().name()))
                        .toList(),
                p.state().name(),p.createdAt(),p.activatedAt(),p.supersededAt());
    }

    private static AbsencePolicyResource resource(SourceAbsencePolicyVersion p) {
        return new AbsencePolicyResource(
                p.id(),p.sourceSystemId(),p.versionNumber(),p.maxInferredTransitions(),
                p.state().name(),p.createdAt(),p.activatedAt(),p.supersededAt());
    }

    private static <T> ResponseEntity<T> response(T body, UUID correlationId) {
        return ResponseEntity.ok()
                .header("X-Correlation-Id",correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(body);
    }

    private static Map<String,IdentityLifecycleState> parseLifecycleRules(
            Object raw,
            UUID correlationId) {
        if (!(raw instanceof List<?> items) || items.isEmpty() || items.size()>50) {
            throw IdentityApiException.validation(
                    correlationId,"rules","invalid_size","rules must contain 1 to 50 entries.");
        }
        List<Map.Entry<String,IdentityLifecycleState>> entries=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for (Object item:items) {
            if (!(item instanceof Map<?,?> rawMap)) {
                throw IdentityApiException.validation(
                        correlationId,"rules","invalid_shape","each rule must be an object.");
            }
            Map<String,Object> map=stringMap(rawMap,correlationId);
            exact(map,Set.of("sourceValue","targetState"),correlationId);
            String sourceValue=text(map.get("sourceValue"),"sourceValue",correlationId);
            if (sourceValue.isBlank() || !seen.add(sourceValue)) {
                throw IdentityApiException.validation(
                        correlationId,"sourceValue","duplicate_or_blank","sourceValue must be non-blank and distinct.");
            }
            IdentityLifecycleState state=enumValue(
                    map.get("targetState"),IdentityLifecycleState.class,"targetState",correlationId);
            if (state==IdentityLifecycleState.PENDING) {
                throw IdentityApiException.validation(
                        correlationId,"targetState","invalid_enum","source lifecycle policy cannot target PENDING.");
            }
            entries.add(Map.entry(sourceValue,state));
        }
        entries.sort(Map.Entry.comparingByKey());
        Map<String,IdentityLifecycleState> result=new LinkedHashMap<>();
        entries.forEach(e -> result.put(e.getKey(),e.getValue()));
        return result;
    }

    private static RequestFingerprint lifecycleFingerprint(
            UUID sourceSystemId,
            String sourcePath,
            Map<String,IdentityLifecycleState> mappings) {
        StringBuilder b=new StringBuilder("v1|")
                .append(part("lifecycle")).append(part(sourceSystemId)).append(part(sourcePath));
        mappings.forEach((k,v) -> b.append(part(k)).append(part(v.name())));
        return RequestFingerprint.sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static RequestFingerprint fingerprint(Object... values) {
        StringBuilder b=new StringBuilder("v1|");
        for (Object value:values) b.append(part(value));
        return RequestFingerprint.sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String part(Object value) {
        String text=value==null?"<null>":value.toString();
        return text.length()+":"+text+"|";
    }

    private static String idempotencyKey(String value, UUID correlationId) {
        if (value==null || value.isBlank() || value.length()>200) {
            throw IdentityApiException.validation(
                    correlationId,"Idempotency-Key","invalid_header",
                    "Idempotency-Key is required and must be at most 200 characters.");
        }
        return value;
    }

    private static void exact(Map<String,Object> body, Set<String> allowed, UUID correlationId) {
        if (body==null) {
            throw IdentityApiException.validation(
                    correlationId,"request","required_object","Request body must be an object.");
        }
        for (String field:allowed) {
            if (!body.containsKey(field)) {
                throw IdentityApiException.validation(
                        correlationId,field,"required",field+" is required.");
            }
        }
        Set<String> unknown=new HashSet<>(body.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            String field=unknown.stream().sorted().findFirst().orElseThrow();
            throw IdentityApiException.validation(
                    correlationId,field,"unknown_field","Unknown field is not permitted.");
        }
    }

    private static Map<String,Object> stringMap(Map<?,?> raw, UUID correlationId) {
        Map<String,Object> result=new LinkedHashMap<>();
        for (var e:raw.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw IdentityApiException.validation(
                        correlationId,"request","invalid_object","Object keys must be strings.");
            }
            result.put(key,e.getValue());
        }
        return result;
    }

    private static String text(Object raw,String field,UUID correlationId) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw IdentityApiException.validation(
                    correlationId,field,"required_string",field+" must be a non-blank string.");
        }
        return value;
    }

    private static String optionalText(Object raw,String field,UUID correlationId) {
        if (raw==null) return null;
        if (!(raw instanceof String value) || value.isBlank()) {
            throw IdentityApiException.validation(
                    correlationId,field,"invalid_string",field+" must be a non-blank string.");
        }
        return value;
    }

    private static boolean bool(Object raw,String field,UUID correlationId) {
        if (!(raw instanceof Boolean value)) {
            throw IdentityApiException.validation(
                    correlationId,field,"required_boolean",field+" must be boolean.");
        }
        return value;
    }

    private static int positiveInt(Object raw,String field,UUID correlationId) {
        if (!(raw instanceof Number n)) {
            throw IdentityApiException.validation(
                    correlationId,field,"required_integer",field+" must be an integer.");
        }
        long value=n.longValue();
        if (value<1 || value>Integer.MAX_VALUE
                || (n instanceof Double d && d.doubleValue()!=value)
                || (n instanceof Float f && f.floatValue()!=value)) {
            throw IdentityApiException.validation(
                    correlationId,field,"invalid_integer",field+" must be a positive integer.");
        }
        return (int)value;
    }

    private static <E extends Enum<E>> E enumValue(
            Object raw,Class<E> type,String field,UUID correlationId) {
        if (!(raw instanceof String value)) {
            throw IdentityApiException.validation(
                    correlationId,field,"required_enum",field+" must be a string enum.");
        }
        try {
            return Enum.valueOf(type,value);
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId,field,"invalid_enum",field+" contains an unsupported value.");
        }
    }

    private static <E extends Enum<E>> E optionalEnum(
            Object raw,Class<E> type,String field,UUID correlationId) {
        if (raw==null) return null;
        return enumValue(raw,type,field,correlationId);
    }
}
