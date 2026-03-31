package axlabs.com.neofsfundproxy;

import io.github.cdimascio.dotenv.Dotenv;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoApplicationLog;
import io.neow3j.types.Hash160;
import io.neow3j.types.Hash256;
import io.neow3j.types.NeoVMStateType;
import org.slf4j.Logger;

import java.io.File;

import static io.neow3j.utils.Await.waitUntilTransactionIsExecuted;

final class ScriptUtils {

    private static Dotenv dotenv = null;

    private ScriptUtils() {
    }

    static void loadDotenv(Logger logger) {
        try {
            File envFile = new File(".env");
            if (!envFile.isAbsolute()) {
                String cwd = System.getProperty("user.dir");
                envFile = new File(cwd, ".env");
            }
            if (envFile.exists()) {
                dotenv = Dotenv.configure()
                        .directory(envFile.getParentFile().getAbsolutePath())
                        .filename(".env")
                        .ignoreIfMissing()
                        .load();
                logger.info("Loaded configuration from .env file at {}", envFile.getAbsolutePath());
            } else {
                logger.debug(".env not found at {} (user.dir={})", envFile.getAbsolutePath(), System.getProperty("user.dir"));
            }
        } catch (Exception e) {
            logger.debug("Could not load .env file: {}", e.getMessage());
        }
    }

    static String getConfig(String propertyName, String envName, boolean required) {
        String value = System.getProperty(propertyName);

        if (value == null || value.isEmpty()) {
            value = System.getenv(envName);
        }

        if ((value == null || value.isEmpty()) && dotenv != null) {
            value = dotenv.get(envName);
        }

        if (required && (value == null || value.isEmpty())) {
            throw new IllegalArgumentException("Required parameter missing: " + propertyName +
                    " (property), " + envName + " (environment variable), or in .env file");
        }

        return value;
    }

    static boolean isDryRun() {
        String dryRunStr = getConfig("dryRun", "DRY_RUN", false);
        return dryRunStr != null && (dryRunStr.equalsIgnoreCase("true") || dryRunStr.equals("1"));
    }

    /**
     * Parse a Hash160 from either a Neo3 address (e.g. "NXzij...") or a raw script hash hex string.
     */
    static Hash160 parseHash160(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse empty hash/address");
        }
        if (value.startsWith("N") && value.length() == 34) {
            return Hash160.fromAddress(value);
        }
        return new Hash160(value);
    }

    static void waitAndLogResult(Logger logger, Neow3j neow3j, Hash256 txHash, Hash160 contractHash, String operation) throws Exception {
        logger.info("Waiting for transaction confirmation...");
        waitUntilTransactionIsExecuted(txHash, neow3j);

        NeoApplicationLog appLog = neow3j.getApplicationLog(txHash).send().getApplicationLog();
        if (appLog != null && !appLog.getExecutions().isEmpty()) {
            NeoApplicationLog.Execution execution = appLog.getFirstExecution();
            if (execution.getState() == NeoVMStateType.FAULT) {
                String error = execution.getException() != null ? execution.getException() : "Unknown error";
                logger.error("=== {} FAILED === TX: {} Exception: {}", operation, txHash, error);
                throw new RuntimeException(operation + " failed: " + error);
            }
            logger.info("=== {} SUCCESSFUL === TX: {} Contract: {}", operation, txHash, contractHash.toAddress());
        }
    }
}

