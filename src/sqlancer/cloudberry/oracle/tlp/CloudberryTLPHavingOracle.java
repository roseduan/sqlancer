package sqlancer.cloudberry.oracle.tlp;

import java.sql.SQLException;
import java.util.Arrays;

import sqlancer.cloudberry.gen.CloudberryCommon;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.oracle.tlp.PostgresTLPHavingOracle;

/**
 * Reuses the PostgreSQL TLP HAVING oracle. Cloudberry's planner transparently inserts Motion nodes for joins across any
 * distribution, so no colocation-aware query generation is required; only the expected-error set is augmented with
 * Cloudberry-specific errors.
 *
 * <p>
 * Generated queries are restricted to IMMUTABLE functions. TLP compares two query executions and requires them to be
 * deterministic; STABLE/VOLATILE functions such as {@code pg_current_logfile()} or {@code now()} can legitimately
 * return different values between executions and would otherwise cause false-positive result-set mismatches.
 */
public class CloudberryTLPHavingOracle extends PostgresTLPHavingOracle {

    public CloudberryTLPHavingOracle(PostgresGlobalState state) {
        super(state);
        CloudberryCommon.addCloudberryErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        state.setAllowedFunctionTypes(Arrays.asList(PostgresGlobalState.IMMUTABLE));
        try {
            super.check();
        } finally {
            state.setDefaultAllowedFunctionTypes();
        }
    }

}
