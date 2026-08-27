package sqlancer.cloudberry.oracle.tlp;

import java.sql.SQLException;
import java.util.Arrays;

import sqlancer.cloudberry.gen.CloudberryCommon;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.oracle.tlp.PostgresTLPAggregateOracle;

/**
 * Reuses the PostgreSQL TLP aggregate oracle, augmenting only the expected-error set with Cloudberry-specific errors.
 * Generated queries are restricted to IMMUTABLE functions (see {@link CloudberryTLPHavingOracle}) so that
 * non-deterministic functions do not cause false-positive result-set mismatches.
 */
public class CloudberryTLPAggregateOracle extends PostgresTLPAggregateOracle {

    public CloudberryTLPAggregateOracle(PostgresGlobalState state) {
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
