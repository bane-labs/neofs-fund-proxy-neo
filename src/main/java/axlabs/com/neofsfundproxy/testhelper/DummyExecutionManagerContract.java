package axlabs.com.neofsfundproxy.testhelper;

import io.neow3j.devpack.annotations.DisplayName;
// REVIEW

/**
 * Dummy execution manager contract for testing. Used as a placeholder for the execution manager
 * hash in NeoFSFundProxy deployment.
 */
@DisplayName("DummyExecutionManager")
public class DummyExecutionManagerContract {

    public static int getExecutingNonce() {
        return 0;
    }
}
