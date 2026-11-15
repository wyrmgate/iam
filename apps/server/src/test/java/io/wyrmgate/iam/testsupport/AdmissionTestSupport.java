package io.wyrmgate.iam.testsupport;
import io.wyrmgate.iam.access.application.AccessAdmissionCoordinator;
import io.wyrmgate.iam.access.application.AccessPrivilegeIncreaseGuard;
import io.wyrmgate.iam.access.persistence.JdbcAccessAdmissionLockRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
public final class AdmissionTestSupport {
    private AdmissionTestSupport() {}
    private static final java.util.Map<TransactionExecutor,AccessAdmissionCoordinator> coordinators = new java.util.WeakHashMap<>();
    public static synchronized AccessAdmissionCoordinator coordinator(JdbcTemplate jdbc, TransactionExecutor transactions) {
        return coordinators.computeIfAbsent(transactions, tx -> new AccessAdmissionCoordinator(new JdbcAccessAdmissionLockRepository(jdbc),tx));
    }
    public static AccessPrivilegeIncreaseGuard allowForFixture() { return (t,i,k,id,f,from,until,at)->{}; }
}
