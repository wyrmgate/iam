package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.ControlPlaneActorBindingRepository;
import io.wyrmgate.iam.administration.domain.ControlPlaneActorBinding;
import io.wyrmgate.iam.administration.domain.ControlPlaneActorBindingState;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Administration-owned external-subject actor bindings. */
public final class JdbcControlPlaneActorBindingRepository implements ControlPlaneActorBindingRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcControlPlaneActorBindingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<ControlPlaneActorBinding> findActiveByExternalSubject(
            ExternalAuthenticationSubject externalSubject) {
        Objects.requireNonNull(externalSubject, "externalSubject");
        List<ControlPlaneActorBinding> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, issuer, subject, actor_identity_id,
                       state, revision, created_at, updated_at
                FROM administration.control_plane_actor_binding
                WHERE external_subject_key = ?
                  AND issuer = ?
                  AND subject = ?
                  AND state = 'ACTIVE'
                """,
                (rs, rowNum) -> new ControlPlaneActorBinding(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getObject("tenant_id", java.util.UUID.class),
                        new ExternalAuthenticationSubject(rs.getString("issuer"), rs.getString("subject")),
                        rs.getObject("actor_identity_id", java.util.UUID.class),
                        ControlPlaneActorBindingState.valueOf(rs.getString("state")),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                externalSubjectKey(externalSubject),
                externalSubject.issuer(),
                externalSubject.subject());
        return rows.stream().findFirst();
    }

    @Override
    public boolean insertIfExternalSubjectUnbound(TenantContext tenant, ControlPlaneActorBinding binding) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(binding, "binding");
        if (!tenant.tenantId().equals(binding.tenantId())) {
            throw new IllegalArgumentException("binding tenant must match TenantContext");
        }
        int affected = jdbcTemplate.update(
                """
                INSERT INTO administration.control_plane_actor_binding (
                    id, tenant_id, external_subject_key, issuer, subject, actor_identity_id,
                    state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (external_subject_key) DO NOTHING
                """,
                binding.id(),
                tenant.tenantId(),
                externalSubjectKey(binding.externalSubject()),
                binding.externalSubject().issuer(),
                binding.externalSubject().subject(),
                binding.actorIdentityId(),
                binding.state().name(),
                binding.revision(),
                Timestamp.from(binding.createdAt()),
                Timestamp.from(binding.updatedAt()));
        return affected == 1;
    }

    static String externalSubjectKey(ExternalAuthenticationSubject subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] issuer = subject.issuer().getBytes(StandardCharsets.UTF_8);
            byte[] externalSubject = subject.subject().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(issuer.length).array());
            digest.update(issuer);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(externalSubject.length).array());
            digest.update(externalSubject);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
