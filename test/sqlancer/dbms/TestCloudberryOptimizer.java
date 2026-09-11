package sqlancer.dbms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import sqlancer.Main;

/**
 * Cloudberry ships two query optimizers (GPORCA and the PostgreSQL-derived planner), toggled via the {@code
 * optimizer} GUC. The OPTIMIZER oracle runs the same query under both and requires identical results; a mismatch
 * reveals a logic bug in one of the two. This oracle has no equivalent in any other provider, since no other DBMS in
 * this project ships two independent optimizers for the same engine.
 */
public class TestCloudberryOptimizer {

    @Test
    public void testCloudberryOptimizer() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLOUDBERRY_ENV));
        assertEquals(0,
                Main.executeMain(new String[] { "--random-seed", "0", "--timeout-seconds", TestConfig.SECONDS,
                        "--num-threads", "4", "--num-queries", TestConfig.NUM_QUERIES, "--username", "gpadmin",
                        "cloudberry", "--connection-url", "postgresql://localhost:7000/test", "--test-collations",
                        "false", "--cloudberry-oracle", "OPTIMIZER" }));
    }

}
