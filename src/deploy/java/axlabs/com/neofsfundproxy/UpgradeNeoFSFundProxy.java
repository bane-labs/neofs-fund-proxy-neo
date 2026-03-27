package axlabs.com.neofsfundproxy;

import io.neow3j.contract.NefFile;
import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.ContractManifest;
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
import java.io.IOException;
import java.nio.file.Files;
// REVIEW

/**
 * Upgrade script for NeoFSFundProxy contract.
 * <p>
 * Calls the contract's {@code upgrade(nef, manifest, data)} method on the already-deployed
 * contract. The caller must be the current contract owner (enforced by {@code onlyOwner()}).
 * <p>
 * Configuration can be provided in three ways (in order of precedence):
 * 1. System properties (-Pkey=value passed to Gradle)
 * 2. Environment variables
 * 3. .env file in the project root
 * <p>
 * Usage:
 *   ./gradlew upgrade -PcontractHash=<deployed_contract_hash> [-PwalletPath=<wallet_path>] [-PwalletPassword=<wallet_password>] [-PrpcUrl=<rpc_url>] [-PdryRun=true]
 * <p>
 * Or create a .env file with:
 *   N3_CONTRACT_HASH=<deployed_contract_hash>   # Required
 *   WALLET_FILEPATH_DEPLOYER=<wallet_path>       # Required
 *   WALLET_PASSWORD_DEPLOYER=<wallet_password>   # Optional
 *   N3_JSON_RPC=<rpc_url>                        # Optional, defaults to http://localhost:40332
 *   DRY_RUN=true                                 # Optional
 * <p>
 * Then run: ./gradlew upgrade
 */
public class UpgradeNeoFSFundProxy {

    private static final Logger logger = LoggerFactory.getLogger(UpgradeNeoFSFundProxy.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";
    private static Dotenv dotenv = null;

    public static void main(String[] args) throws Throwable {
        // Load .env file if it exists (silently ignore if it doesn't)
        try {
            File envFile = new File(".env");
            if (envFile.exists()) {
                dotenv = Dotenv.configure()
                        .directory(".")
                        .filename(".env")
                        .ignoreIfMissing()
                        .load();
                logger.info("Loaded configuration from .env file");
            }
        } catch (Exception e) {
            logger.debug("Could not load .env file: {}", e.getMessage());
        }

        // Get configuration from system properties, environment variables, or .env file
        String contractHashStr = getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String walletPath = getConfig("walletPath", "WALLET_FILEPATH_DEPLOYER", true);
        String walletPassword = getConfig("walletPassword", "WALLET_PASSWORD_DEPLOYER", false);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }
        String dryRunStr = getConfig("dryRun", "DRY_RUN", false);
        boolean dryRun = dryRunStr != null && (dryRunStr.equalsIgnoreCase("true") || dryRunStr.equals("1"));

        if (dryRun) {
            logger.info("=== DRY RUN MODE - Transaction will NOT be submitted ===");
        }
        logger.info("Upgrading NeoFSFundProxy contract...");
        logger.info("RPC URL:         {}", rpcUrl);
        logger.info("Contract Hash:   {}", contractHashStr);

        // Connect to Neo3 network
        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));

        // Load NEP-6 wallet and decrypt accounts
        Wallet wallet = Wallet.fromNEP6Wallet(new File(walletPath));
        String password = walletPassword != null ? walletPassword : "";
        wallet.decryptAllAccounts(password);
        Account account = wallet.getDefaultAccount();
        logger.info("Owner account:   {}", account.getAddress());

        // Load compiled contract files
        String buildDir = "build/neow3j";
        File nefFile = new File(buildDir, "NeoFSFundProxy.nef");
        File manifestFile = new File(buildDir, "NeoFSFundProxy.manifest.json");

        if (!nefFile.exists() || !manifestFile.exists()) {
            throw new IOException("Contract files not found. Please run './gradlew neow3jCompile' first.");
        }

        NefFile nef = NefFile.readFromFile(nefFile);
        String manifestJson = new String(Files.readAllBytes(manifestFile.toPath()));
        // Validate the manifest can be parsed
        ContractManifest manifest = Wallet.OBJECT_MAPPER.readValue(manifestJson, ContractManifest.class);

        logger.info("NEF loaded:      {} bytes", nef.toArray().length);
        logger.info("Manifest name:   {}", manifest.getName());

        // Parse deployed contract hash (accepts Neo3 address or raw hex script hash)
        Hash160 contractHash = parseHash160(contractHashStr);

        // Build the upgrade invocation:
        // upgrade(ByteString nef, String manifest, Object data)
        TransactionBuilder builder = new SmartContract(contractHash, neow3j)
                .invokeFunction("upgrade",
                        ContractParameter.byteArray(nef.toArray()),
                        ContractParameter.string(manifestJson),
                        ContractParameter.any(null))
                .signers(AccountSigner.calledByEntry(account));

        if (dryRun) {
            logger.info("");
            logger.info("=== DRY RUN COMPLETE ===");
            logger.info("Transaction was prepared but NOT submitted to the network.");
            logger.info("Contract Hash:   {}", contractHash);
            logger.info("Contract Address:{}", contractHash.toAddress());
            logger.info("");
            logger.info("To actually upgrade, run without -PdryRun=true or set DRY_RUN=false");
            return;
        }

        // Sign and send transaction
        logger.info("Signing and sending upgrade transaction...");
        io.neow3j.transaction.Transaction tx = builder.sign();
        NeoSendRawTransaction response = tx.send();
        if (response.hasError()) {
            throw new RuntimeException("Failed to send upgrade transaction: " + response.getError().getMessage());
        }

        Hash256 txHash = tx.getTxId();
        logger.info("Upgrade transaction sent: {}", txHash);

        // Wait for transaction to be included in a block.
        // getTransaction returns the TX from the mempool before it is mined, but
        // getApplicationLog only works once the TX is in a confirmed block.
        // We check getBlockHash() != null to ensure it has been mined.
        logger.info("Waiting for transaction confirmation...");
        io.neow3j.protocol.core.response.Transaction confirmedTx = null;
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(1000);
            io.neow3j.protocol.core.response.NeoGetTransaction txResponse =
                    neow3j.getTransaction(txHash).send();
            if (!txResponse.hasError() && txResponse.getTransaction() != null
                    && txResponse.getTransaction().getBlockHash() != null) {
                confirmedTx = txResponse.getTransaction();
                logger.info("Transaction confirmed in block ({})", confirmedTx.getBlockHash());
                break;
            }
        }

        if (confirmedTx == null) {
            throw new RuntimeException("Transaction not confirmed after waiting");
        }

        // Get application log to check result and log details
        NeoApplicationLog appLog = neow3j.getApplicationLog(txHash).send().getApplicationLog();
        if (appLog != null && !appLog.getExecutions().isEmpty()) {
            NeoApplicationLog.Execution execution = appLog.getFirstExecution();
            if (execution.getState() == NeoVMStateType.FAULT) {
                String error = execution.getException() != null ? execution.getException() : "Unknown error";
                logger.info("");
                logger.info("=== UPGRADE FAILED ===");
                logger.info("TX Hash:    {}", txHash);
                logger.info("VM State:   {}", execution.getState());
                logger.info("Exception:  {}", error);
                throw new RuntimeException("Upgrade failed: " + error);
            }
            logger.info("");
            logger.info("=== UPGRADE SUCCESSFUL ===");
            logger.info("TX Hash:         {}", txHash);
            logger.info("VM State:        {}", execution.getState());
            long gasConsumedRaw = Long.parseLong(execution.getGasConsumed());
            logger.info("GAS Consumed:    {} GAS", String.format("%.8f", gasConsumedRaw / 1e8));
            logger.info("Contract Hash:   {}", contractHash);
            logger.info("Contract Address:{}", contractHash.toAddress());
        } else {
            logger.info("");
            logger.info("=== UPGRADE SUCCESSFUL ===");
            logger.info("TX Hash:         {}", txHash);
            logger.info("Contract Hash:   {}", contractHash);
            logger.info("Contract Address:{}", contractHash.toAddress());
        }
    }

    /**
     * Parse a Hash160 from either a Neo3 address (e.g. "NXzij...") or a raw script hash
     * hex string (e.g. "bd98300a..." or "0xbd98300a...").
     */
    private static Hash160 parseHash160(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse empty hash/address");
        }
        // Neo3 addresses start with 'N' and are 34 characters long
        if (value.startsWith("N") && value.length() == 34) {
            return Hash160.fromAddress(value);
        }
        // Otherwise treat as a hex script hash (strip optional 0x prefix)
        String hex = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        return new Hash160(hex);
    }

    /**
     * Get configuration value from system properties, environment variables, or .env file.
     * Priority: System property > Environment variable > .env file
     */
    private static String getConfig(String propertyName, String envName, boolean required) {
        // First try system property
        String value = System.getProperty(propertyName);

        // Then try environment variable
        if (value == null || value.isEmpty()) {
            value = System.getenv(envName);
        }

        // Finally try .env file
        if ((value == null || value.isEmpty()) && dotenv != null) {
            value = dotenv.get(envName);
        }

        if (required && (value == null || value.isEmpty())) {
            throw new IllegalArgumentException("Required parameter missing: " + propertyName +
                    " (property), " + envName + " (environment variable), or in .env file");
        }

        return value;
    }
}
