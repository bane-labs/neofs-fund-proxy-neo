package axlabs.com.neofsfundproxy.testhelper;

import io.neow3j.devpack.annotations.DisplayName;

/**
 * Dummy message bridge contract for testing. Used as a placeholder for the message bridge
 * hash in NeoFSFundProxy deployment.
 */
@DisplayName("DummyMessageBridge")
public class DummyMessageBridgeContract {

    public static void sendMessage(int targetChainId, byte[] targetContract, byte[] payload) {
        // No-op for testing
    }
}
