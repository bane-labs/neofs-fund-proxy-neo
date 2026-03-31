package axlabs.com.neofsfundproxy.testhelper;

import io.neow3j.devpack.annotations.DisplayName;

/**
 * Dummy execution manager contract for testing only.
 *
 * This is a placeholder used by the neow3j {@code ContractTest} harness. It is not intended
 * for production use and does not track a real "executing nonce".
 */
@DisplayName("DummyExecutionManager")
public class DummyExecutionManagerContract {

    public static int getExecutingNonce() {
        return 0;
    }
}
