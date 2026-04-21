package axlabs.com.neofsfundproxy;

import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.InvocationResult;
import io.neow3j.protocol.core.stackitem.StackItem;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.types.Hash160;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static axlabs.com.neofsfundproxy.ScriptUtils.getConfig;
import static axlabs.com.neofsfundproxy.ScriptUtils.loadDotenv;
import static axlabs.com.neofsfundproxy.ScriptUtils.parseHash160;

/**
 * Read-only script that queries all configurable values from the deployed NeoFSFundProxy contract
 * and prints them.
 * <p>
 * Usage:
 *   ./gradlew getConfig -PcontractHash=&lt;deployed_contract_hash&gt; [-PrpcUrl=&lt;rpc_url&gt;]
 * <p>
 * Or set in .env:
 *   N3_CONTRACT_HASH=&lt;deployed_contract_hash&gt;
 *   N3_JSON_RPC=&lt;rpc_url&gt;
 */
public class GetConfig {

    private static final Logger logger = LoggerFactory.getLogger(GetConfig.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";

    /** Safe getters on {@link NeoFSFundProxy} (order matches typical config review). */
    private static final String[] GETTER_FUNCTIONS = {
            "owner",
            "getTokenBridge",
            "getNeoFSContract",
            "getMessageBridge",
            "getExecutionManager",
            "getEvmProxy",
    };

    public static void main(String[] args) throws Throwable {
        loadDotenv(logger);

        String contractHashStr = getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }

        Hash160 contractHash = parseHash160(contractHashStr);
        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));
        SmartContract contract = new SmartContract(contractHash, neow3j);

        logger.info("");
        logger.info("=== NeoFSFundProxy configuration ===");
        logger.info("RPC URL:        {}", rpcUrl);
        logger.info("Contract Hash:  {}", contractHash);
        logger.info("Contract Addr:  {}", contractHash.toAddress());
        logger.info("");

        for (String fn : GETTER_FUNCTIONS) {
            String value = invokeRead(contract, fn);
            logger.info("{}: {}", padRight(fn, 24), value);
        }

        logger.info("");
    }

    private static String invokeRead(SmartContract contract, String function) {
        try {
            InvocationResult result = contract.callInvokeFunction(function)
                    .getInvocationResult();

            if (result.hasStateFault()) {
                return "FAULT - " + (result.getException() != null ? result.getException() : "unknown error");
            }

            List<StackItem> stack = result.getStack();
            if (stack.isEmpty()) {
                return "(empty)";
            }

            StackItem item = stack.get(0);
            return formatStackItem(item);
        } catch (Exception e) {
            return "ERROR - " + e.getMessage();
        }
    }

    private static String formatStackItem(StackItem item) {
        switch (item.getType()) {
            case BYTE_STRING:
            case BUFFER:
                try {
                    byte[] bytes = item.getByteArray();
                    if (bytes.length == 20) {
                        Hash160 hash = new Hash160(bytes);
                        return hash + " (" + hash.toAddress() + ")";
                    }
                    String hex = bytesToHex(bytes);
                    String ascii = tryAscii(bytes);
                    return ascii != null ? ascii + " (0x" + hex + ")" : "0x" + hex;
                } catch (Exception e) {
                    return item.getString();
                }
            case INTEGER:
                return item.getInteger().toString();
            case BOOLEAN:
                return String.valueOf(item.getBoolean());
            case ARRAY:
                return "(array with " + item.getList().size() + " elements)";
            default:
                return item.toString();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String tryAscii(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0x20 || b > 0x7e) {
                return null;
            }
        }
        return new String(bytes);
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
