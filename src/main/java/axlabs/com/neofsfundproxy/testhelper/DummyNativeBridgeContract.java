package axlabs.com.neofsfundproxy.testhelper;

import io.neow3j.devpack.annotations.DisplayName;
// REVIEW

/**
 * Dummy native bridge contract for testing. Used as a placeholder for the native bridge
 * hash in NeoFSFundProxy deployment.
 */
@DisplayName("DummyNativeBridge")
public class DummyNativeBridgeContract {

    public static void claimNative(int nonce) {
        // No-op for testing
    }
}
