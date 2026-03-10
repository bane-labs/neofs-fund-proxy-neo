package axlabs.com.neofsfundproxy;

import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.types.Hash160;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Script to invoke all getters on the deployed NeoFSFundProxy contract and print the results.
 * No wallet required - read-only invocations.
 * <p>
 * Usage:
 *   ./gradlew getNeoFSFundProxyStatus -PcontractHash=<hash> [-PrpcUrl=...]
 * <p>
 * Or .env: N3_CONTRACT_HASH, N3_JSON_RPC
 */
public class GetNeoFSFundProxyStatus {

    private static final Logger logger = LoggerFactory.getLogger(GetNeoFSFundProxyStatus.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";

    public static void main(String[] args) throws Throwable {
        SetExecutionManager.loadDotenv();

        String contractHashStr = SetExecutionManager.getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String rpcUrl = SetExecutionManager.getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }

        logger.info("=== NeoFSFundProxy Status ===");
        logger.info("RPC URL:       {}", rpcUrl);
        logger.info("Contract Hash: {}", contractHashStr);
        logger.info("");

        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));
        Hash160 contractHash = SetExecutionManager.parseHash160(contractHashStr);
        SmartContract contract = new SmartContract(contractHash, neow3j);

        // owner()
        printGetter("owner", () -> formatHash160(contract.callFunctionReturningScriptHash("owner")));

        // getNeoFSContract()
        printGetter("getNeoFSContract", () -> formatHash160(contract.callFunctionReturningScriptHash("getNeoFSContract")));

        // getMessageBridge()
        printGetter("getMessageBridge", () -> formatHash160(contract.callFunctionReturningScriptHash("getMessageBridge")));

        // getEvmProxyContract()
        printGetter("getEvmProxyContract", () -> formatHash160(contract.callFunctionReturningScriptHash("getEvmProxyContract")));

        logger.info("=== End ===");
    }

    private static void printGetter(String name, ValueSupplier supplier) {
        try {
            String value = supplier.get();
            logger.info("  {}: {}", name, value);
        } catch (Exception e) {
            logger.info("  {}: ERROR - {}", name, e.getMessage());
        }
    }

    private static String formatHash160(Hash160 hash) {
        if (hash == null) {
            return "(not set)";
        }
        String hex = hash.toString();
        if (hex == null || hex.isEmpty() || "0000000000000000000000000000000000000000".equalsIgnoreCase(hex.replace("0x", ""))) {
            return "(not set)";
        }
        try {
            return hash.toAddress() + " (0x" + hex + ")";
        } catch (Exception e) {
            return "0x" + hex;
        }
    }

    @FunctionalInterface
    private interface ValueSupplier {
        String get() throws Exception;
    }
}
