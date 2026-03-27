package axlabs.com.neofsfundproxy;

import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoApplicationLog;
import io.neow3j.protocol.core.response.NeoSendRawTransaction;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.transaction.AccountSigner;
import io.neow3j.transaction.TransactionBuilder;
import io.neow3j.types.ContractParameter;
import io.neow3j.types.Hash160;
import io.neow3j.types.Hash256;
import io.neow3j.types.NeoVMStateType;
import io.neow3j.wallet.Account;
import io.neow3j.wallet.Wallet;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
// REVIEW

/**
 * Script to set the execution manager on the deployed NeoFSFundProxy contract.
 * Only the current owner can call setExecutionManager (enforced by the contract).
 * <p>
 * Usage:
 *   ./gradlew setExecutionManager -PcontractHash=<hash> -PexecutionManager=<hash> -PwalletPath=... [-PwalletPassword=...] [-PrpcUrl=...] [-PdryRun=true]
 * <p>
 * Or .env: N3_CONTRACT_HASH, N3_EXECUTION_MANAGER, WALLET_FILEPATH_DEPLOYER, etc.
 */
public class SetExecutionManager {

    private static final Logger logger = LoggerFactory.getLogger(SetExecutionManager.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";
    private static Dotenv dotenv = null;

    public static void main(String[] args) throws Throwable {
        loadDotenv();

        String contractHashStr = getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String executionManagerStr = getConfig("executionManager", "N3_EXECUTION_MANAGER", true);
        String walletPath = getConfig("walletPath", "WALLET_FILEPATH_DEPLOYER", true);
        String walletPassword = getConfig("walletPassword", "WALLET_PASSWORD_DEPLOYER", false);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }
        boolean dryRun = isDryRun();

        if (dryRun) {
            logger.info("=== DRY RUN MODE - Transaction will NOT be submitted ===");
        }
        logger.info("Setting execution manager on NeoFSFundProxy...");
        logger.info("RPC URL:            {}", rpcUrl);
        logger.info("Contract Hash:      {}", contractHashStr);
        logger.info("Execution Manager:  {}", executionManagerStr);

        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));
        Wallet wallet = Wallet.fromNEP6Wallet(new File(walletPath));
        String password = walletPassword != null ? walletPassword : "";
        wallet.decryptAllAccounts(password);
        Account account = wallet.getDefaultAccount();
        logger.info("Owner account:      {}", account.getAddress());

        Hash160 contractHash = parseHash160(contractHashStr);
        Hash160 executionManagerHash = parseHash160(executionManagerStr);

        TransactionBuilder builder = new SmartContract(contractHash, neow3j)
                .invokeFunction("setExecutionManager", ContractParameter.hash160(executionManagerHash))
                .signers(AccountSigner.calledByEntry(account));

        if (dryRun) {
            logger.info("");
            logger.info("=== DRY RUN COMPLETE ===");
            logger.info("Would call setExecutionManager({}) on contract {}", executionManagerStr, contractHashStr);
            return;
        }

        logger.info("Signing and sending transaction...");
        io.neow3j.transaction.Transaction tx = builder.sign();
        NeoSendRawTransaction response = tx.send();
        if (response.hasError()) {
            throw new RuntimeException("Failed to send transaction: " + response.getError().getMessage());
        }

        Hash256 txHash = tx.getTxId();
        logger.info("Transaction sent: {}", txHash);
        waitAndLogResult(neow3j, txHash, contractHash, "SET_EXECUTION_MANAGER");
    }

    static void loadDotenv() {
        try {
            File envFile = new File(".env");
            if (envFile.exists()) {
                dotenv = Dotenv.configure().directory(".").filename(".env").ignoreIfMissing().load();
                logger.info("Loaded configuration from .env file");
            }
        } catch (Exception e) {
            logger.debug("Could not load .env file: {}", e.getMessage());
        }
    }

    static boolean isDryRun() {
        String dryRunStr = getConfig("dryRun", "DRY_RUN", false);
        return dryRunStr != null && (dryRunStr.equalsIgnoreCase("true") || dryRunStr.equals("1"));
    }

    static void waitAndLogResult(Neow3j neow3j, Hash256 txHash, Hash160 contractHash, String operation) throws Exception {
        logger.info("Waiting for transaction confirmation...");
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(1000);
            io.neow3j.protocol.core.response.NeoGetTransaction txResponse = neow3j.getTransaction(txHash).send();
            if (!txResponse.hasError() && txResponse.getTransaction() != null
                    && txResponse.getTransaction().getBlockHash() != null) {
                logger.info("Transaction confirmed in block ({})", txResponse.getTransaction().getBlockHash());
                break;
            }
        }

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

    static Hash160 parseHash160(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse empty hash/address");
        }
        if (value.startsWith("N") && value.length() == 34) {
            return Hash160.fromAddress(value);
        }
        String hex = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        return new Hash160(hex);
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
            throw new IllegalArgumentException("Required parameter missing: " + propertyName + " (property) or " + envName + " (env)");
        }
        return value;
    }
}
