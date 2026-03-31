package axlabs.com.neofsfundproxy.testhelper;

import io.neow3j.devpack.Hash160;
import io.neow3j.devpack.annotations.DisplayName;
import io.neow3j.devpack.annotations.OnNEP17Payment;

/**
 * Dummy NeoFS contract for testing only.
 *
 * This contract exists to receive GAS transfers from {@code NeoFSFundProxy} in unit tests.
 */
@DisplayName("DummyNeoFS")
public class DummyNeoFSContract {

    @OnNEP17Payment
    public static void onNep17Payment(Hash160 from, int amount, Object data) {
        // Accept GAS - no-op
    }
}
