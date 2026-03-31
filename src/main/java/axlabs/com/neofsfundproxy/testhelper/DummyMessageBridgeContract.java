package axlabs.com.neofsfundproxy.testhelper;

import io.neow3j.devpack.annotations.DisplayName;

/**
 * Dummy message bridge contract for testing only.
 *
 * This is a placeholder used by the neow3j {@code ContractTest} harness. It is not intended
 * for production use and does not implement message verification, storage, or execution.
 */
@DisplayName("DummyMessageBridge")
public class DummyMessageBridgeContract {

    public static void sendMessage(int targetChainId, byte[] targetContract, byte[] payload) {
        // No-op for testing
    }
}
