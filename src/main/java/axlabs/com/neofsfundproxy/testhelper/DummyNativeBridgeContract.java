package axlabs.com.neofsfundproxy.testhelper;

import io.neow3j.devpack.annotations.DisplayName;

/**
 * Dummy native bridge contract for testing only.
 *
 * This contract is not a full bridge implementation. It exists solely to support local unit tests
 * of {@code NeoFSFundProxy} by emulating the effect of {@code claimNative(nonce)}: transferring GAS
 * to the calling contract.
 */
@DisplayName("DummyNativeBridge")
public class DummyNativeBridgeContract {

    public static void claimNative(int nonce) {
        // Intentionally minimal: in tests we fund this dummy with GAS, then claimNative() transfers
        // whatever balance it holds to the calling contract to simulate "bridged GAS being claimed".
        //
        // The nonce is ignored because we don't model bridge state in unit tests.
        io.neow3j.devpack.Hash160 calling = io.neow3j.devpack.Runtime.getCallingScriptHash();
        io.neow3j.devpack.Hash160 self = io.neow3j.devpack.Runtime.getExecutingScriptHash();
        io.neow3j.devpack.contracts.GasToken gas = new io.neow3j.devpack.contracts.GasToken();
        int balance = gas.balanceOf(self);
        if (balance > 0) {
            gas.transfer(self, calling, balance, null);
        }
    }
}
