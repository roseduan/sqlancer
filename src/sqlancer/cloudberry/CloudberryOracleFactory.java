package sqlancer.cloudberry;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sqlancer.OracleFactory;
import sqlancer.cloudberry.gen.CloudberryCommon;
import sqlancer.cloudberry.oracle.CloudberryOptimizerOracle;
import sqlancer.cloudberry.oracle.tlp.CloudberryTLPAggregateOracle;
import sqlancer.cloudberry.oracle.tlp.CloudberryTLPHavingOracle;
import sqlancer.common.oracle.CERTOracle;
import sqlancer.common.oracle.CompositeTestOracle;
import sqlancer.common.oracle.NoRECOracle;
import sqlancer.common.oracle.TLPWhereOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.gen.PostgresCommon;
import sqlancer.postgres.gen.PostgresExpressionGenerator;

public enum CloudberryOracleFactory implements OracleFactory<PostgresGlobalState> {
    NOREC {
        @Override
        public TestOracle<PostgresGlobalState> create(PostgresGlobalState globalState) throws SQLException {
            PostgresExpressionGenerator gen = new PostgresExpressionGenerator(globalState);
            ExpectedErrors errors = ExpectedErrors.newErrors().with(PostgresCommon.getCommonExpressionErrors())
                    .with(PostgresCommon.getCommonFetchErrors())
                    .withRegex(PostgresCommon.getCommonExpressionRegexErrors())
                    .with(CloudberryCommon.getCloudberryErrors()).build();
            return new NoRECOracle<>(globalState, gen, errors);
        }
    },
    WHERE {
        @Override
        public TestOracle<PostgresGlobalState> create(PostgresGlobalState globalState) throws SQLException {
            PostgresExpressionGenerator gen = new PostgresExpressionGenerator(globalState);
            ExpectedErrors errors = ExpectedErrors.newErrors().with(PostgresCommon.getCommonExpressionErrors())
                    .with(PostgresCommon.getCommonFetchErrors())
                    .withRegex(PostgresCommon.getCommonExpressionRegexErrors())
                    .with(CloudberryCommon.getCloudberryErrors()).build();
            return new TLPWhereOracle<>(globalState, gen, errors);
        }
    },
    HAVING {
        @Override
        public TestOracle<PostgresGlobalState> create(PostgresGlobalState globalState) throws SQLException {
            return new CloudberryTLPHavingOracle(globalState);
        }
    },
    QUERY_PARTITIONING {
        @Override
        public TestOracle<PostgresGlobalState> create(PostgresGlobalState globalState) throws Exception {
            List<TestOracle<PostgresGlobalState>> oracles = new ArrayList<>();
            oracles.add(WHERE.create(globalState));
            oracles.add(HAVING.create(globalState));
            oracles.add(new CloudberryTLPAggregateOracle(globalState));
            return new CompositeTestOracle<PostgresGlobalState>(oracles, globalState);
        }
    },
    OPTIMIZER {
        @Override
        public TestOracle<PostgresGlobalState> create(PostgresGlobalState globalState) throws SQLException {
            return new CloudberryOptimizerOracle(globalState);
        }
    },
    // EXPERIMENTAL. CERT reports estimated-cardinality non-monotonicities, which are potential performance issues
    // rather than clear correctness bugs (as upstream documents). Under GPORCA (the default optimizer) it is noisy:
    // GROUP BY / DISTINCT mutators change cardinality semantics and ORCA's estimates are non-monotonic near zero, so
    // most findings are benign estimator artifacts. Treat its output as an estimator-quality signal, not as bugs.
    CERT {
        @Override
        public TestOracle<PostgresGlobalState> create(PostgresGlobalState globalState) throws SQLException {
            PostgresExpressionGenerator gen = new PostgresExpressionGenerator(globalState);
            ExpectedErrors errors = ExpectedErrors.newErrors().with(PostgresCommon.getCommonExpressionErrors())
                    .withRegex(PostgresCommon.getCommonExpressionRegexErrors())
                    .with(PostgresCommon.getCommonFetchErrors()).with(PostgresCommon.getCommonInsertUpdateErrors())
                    .with(PostgresCommon.getGroupingErrors()).with(PostgresCommon.getCommonRangeExpressionErrors())
                    .with(CloudberryCommon.getCloudberryErrors()).build();
            // Cloudberry EXPLAIN puts the top plan node (a "Gather Motion") first, with the total estimated row count
            // in its "(cost=... rows=N ...)" annotation. getRow() takes the first parsed row count, i.e. this top-node
            // estimate. (The PostgreSQL parser looks for a "Result" node, which Cloudberry plans do not have.)
            CERTOracle.CheckedFunction<SQLancerResultSet, Optional<Long>> rowCountParser = (rs) -> {
                String content = rs.getString(1).trim();
                int ind = content.indexOf("rows=");
                if (ind >= 0) {
                    try {
                        return Optional.of(Long.parseLong(content.substring(ind + 5).split(" ")[0]));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                }
                return Optional.empty();
            };
            CERTOracle.CheckedFunction<SQLancerResultSet, Optional<String>> queryPlanParser = (rs) -> {
                String content = rs.getString(1).trim();
                // Only real plan nodes carry a cost estimate; skip trailing lines such as "Optimizer:" and "Settings:".
                if (!content.contains("(cost=")) {
                    return Optional.empty();
                }
                String[] planPart = content.split("-> ");
                String plan = planPart[planPart.length - 1];
                return Optional.of(plan.split("  ")[0].trim());
            };
            return new CERTOracle<>(globalState, gen, errors, rowCountParser, queryPlanParser);
        }

        @Override
        public boolean requiresAllTablesToContainRows() {
            return true;
        }
    };

}
