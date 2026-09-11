package sqlancer.cloudberry.oracle;

import java.sql.SQLException;

import sqlancer.IgnoreMeException;
import sqlancer.cloudberry.gen.CloudberryCommon;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.oracle.PostgresPivotedQuerySynthesisOracle;

/**
 * Reuses the PostgreSQL Pivoted Query Synthesis oracle unchanged; only the expected-error set is augmented with
 * Cloudberry-specific errors (distribution, AO/PAX storage restrictions), mirroring how the TLP oracles and
 * {@link CloudberryOptimizerOracle} are wired up.
 */
public class CloudberryPivotedQuerySynthesisOracle extends PostgresPivotedQuerySynthesisOracle {

    public CloudberryPivotedQuerySynthesisOracle(PostgresGlobalState globalState) throws SQLException {
        super(globalState);
        CloudberryCommon.addCloudberryErrors(errors);
    }

    @Override
    public SQLQueryAdapter getRectifiedQuery() throws SQLException {
        try {
            return super.getRectifiedQuery();
        } catch (AssertionError e) {
            // PostgresSchema.PostgresTables.getRandomRowValue() picks a pivot row across a fresh comma-join of every
            // table returned by getRandomTableNonEmptyTables(), which only reflects a one-time non-emptiness check
            // done long before this call (before the check loop starts). A table can legitimately become empty in
            // between without any DBMS bug being involved -- e.g. a TEMP table declared ON COMMIT DELETE ROWS/DROP
            // loses its rows (or itself) at the next COMMIT, which the Action-driven statement mix issues routinely.
            // Treat that specific, known-benign race as a generation dead end rather than a reported bug.
            if (e.getMessage() != null && e.getMessage().startsWith("could not find random row!")) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

}
