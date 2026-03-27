package axlabs.com.neofsfundproxy;

import io.neow3j.contract.ContractManagement;
import io.neow3j.contract.NefFile;
import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoApplicationLog;
import io.neow3j.protocol.core.response.NeoSendRawTransaction;
import io.neow3j.protocol.core.response.ContractManifest;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.transaction.AccountSigner;
import io.neow3j.transaction.TransactionBuilder;
import io.neow3j.types.Hash160;
import io.neow3j.types.Hash256;
import io.neow3j.types.NeoVMStateType;
import io.neow3j.wallet.Account;
import io.neow3j.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.cdimascio.dotenv.Dotenv;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
// REVIEW

/**
 * Deployment script for NeoFSFundProxy contract.
 *
 * Configuration can be provided in three ways (in order of precedence):
 * 1. System properties (-Pkey=value)
 * 2. Environment variables
 * 3. .env file in the project root
 *
 * Usage:
 *   ./gradlew deploy -Powner=<owner> -PneofsContract=<hash> -PmessageBridge=<hash> -PexecutionManager=<hash> [-PnativeBridge=<hash>] [-PevmProxyContract=<evm_20_byte_address>] [-PwalletPath=...] [-PwalletPassword=...] [-PrpcUrl=...] [-PdryRun=true]
 *
 * Or create a .env file with:
 *   N3_OWNER_ADDRESS=<owner_address>
 *   N3_NEOFS_CONTRACT=<neofs_contract_address>
 *   N3_MESSAGE_BRIDGE=<message_bridge_address>
 *   N3_EXECUTION_MANAGER=<execution_manager_contract_address>  # Required
 *   N3_NATIVE_BRIDGE=<native_bridge_address>  # Optional
 *   N3_EVM_PROXY_CONTRACT=<evm_proxy_20_byte_hex>  # Optional; can be set later via setEvmProxyContract
 *   WALLET_FILEPATH_DEPLOYER=<wallet_path>
 *   WALLET_PASSWORD_DEPLOYER=<wallet_password>  # Optional
 *   N3_JSON_RPC=<rpc_url>  # Optional, defaults to http://localhost:40332
 *   N3_HASH_FILE=<hash_file_path>  # Optional
 *   DRY_RUN=true  # Optional, if set to true, transaction will not be submitted
 *
 * Then run: ./gradlew deploy
 */
public class DeployNeoFSFundProxy {

    private static final Logger logger = LoggerFactory.getLogger(DeployNeoFSFundProxy.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";
    private static Dotenv dotenv = null;

    public static void main(String[] args) throws Throwable {
        // Load .env file from current working directory (project root when run via Gradle with workingDir = projectDir)
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
            logger.warn("Could not load .env file: {}", e.getMessage());
        }

        // Get configuration from system properties, environment variables, or .env file
        String ownerAddress = getConfig("owner", "N3_OWNER_ADDRESS", true);
        String neofsContract = getConfig("neofsContract", "N3_NEOFS_CONTRACT", true);
        String messageBridge = getConfig("messageBridge", "N3_MESSAGE_BRIDGE", true);
        String executionManager = getConfig("executionManager", "N3_EXECUTION_MANAGER", true);
        String nativeBridge = getConfig("nativeBridge", "N3_NATIVE_BRIDGE", false);
        String evmProxyContract = getConfig("evmProxyContract", "N3_EVM_PROXY_CONTRACT", false);
        String walletPath = getConfig("walletPath", "WALLET_FILEPATH_DEPLOYER", true);
        String walletPassword = getConfig("walletPassword", "WALLET_PASSWORD_DEPLOYER", false);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }
        String hashFile = getConfig("hashFile", "N3_HASH_FILE", false);
        String dryRunStr = getConfig("dryRun", "DRY_RUN", false);
        boolean dryRun = dryRunStr != null && (dryRunStr.equalsIgnoreCase("true") || dryRunStr.equals("1"));

        if (dryRun) {
            logger.info("=== DRY RUN MODE - Transaction will NOT be submitted ===");
        }
        logger.info("Deploying NeoFSFundProxy contract...");
        logger.info("RPC URL: {}", rpcUrl);
        logger.info("Owner: {}", ownerAddress);
        logger.info("NeoFS Contract: {}", neofsContract);
        logger.info("Message Bridge: {}", messageBridge);
        logger.info("Execution Manager: {}", executionManager);
        if (nativeBridge != null && !nativeBridge.isEmpty()) {
            logger.info("Native Bridge: {}", nativeBridge);
        }
        if (evmProxyContract != null && !evmProxyContract.isEmpty()) {
            logger.info("EVM Proxy Contract: {}", evmProxyContract);
        }

        // Connect to Neo3 network
        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));

        // Load NEP-6 wallet and decrypt accounts
        Wallet wallet = Wallet.fromNEP6Wallet(new File(walletPath));
        String password = walletPassword != null ? walletPassword : "";
        wallet.decryptAllAccounts(password);
        Account account = wallet.getDefaultAccount();
        logger.info("Deployer account: {}", account.getAddress());

        // Load compiled contract files
        String buildDir = "build/neow3j";
        File nefFile = new File(buildDir, "NeoFSFundProxy.nef");
        File manifestFile = new File(buildDir, "NeoFSFundProxy.manifest.json");

        if (!nefFile.exists() || !manifestFile.exists()) {
            throw new IOException("Contract files not found. Please run './gradlew neow3jCompile' first.");
        }

        NefFile nef = NefFile.readFromFile(nefFile);
        // Parse manifest using Jackson ObjectMapper from Wallet
        String manifestJson = new String(Files.readAllBytes(manifestFile.toPath()));
        ContractManifest manifest = Wallet.OBJECT_MAPPER.readValue(manifestJson, ContractManifest.class);

        // Parse addresses/hashes (both Neo3 address format and raw script hash are accepted)
        Hash160 owner = parseHash160(ownerAddress);
        Hash160 neofsContractHash = parseHash160(neofsContract);
        Hash160 messageBridgeHash = parseHash160(messageBridge);
        Hash160 executionManagerHash = parseHash160(executionManager);
        Hash160 nativeBridgeHash = null;
        if (nativeBridge != null && !nativeBridge.isEmpty()) {
            nativeBridgeHash = parseHash160(nativeBridge);
        }
        Hash160 evmProxyContractHash = null;
        if (evmProxyContract != null && !evmProxyContract.isEmpty()) {
            evmProxyContractHash = parseHash160(evmProxyContract);
        }

        // Create deployment data struct: owner, nativeBridge, neofsContract, messageBridge, executionManager, evmProxyContract
        io.neow3j.types.ContractParameter deploymentData = io.neow3j.types.ContractParameter.array(
                io.neow3j.types.ContractParameter.hash160(owner),
                io.neow3j.types.ContractParameter.hash160(nativeBridgeHash != null ? nativeBridgeHash : Hash160.ZERO),
                io.neow3j.types.ContractParameter.hash160(neofsContractHash),
                io.neow3j.types.ContractParameter.hash160(messageBridgeHash),
                io.neow3j.types.ContractParameter.hash160(executionManagerHash),
                io.neow3j.types.ContractParameter.hash160(evmProxyContractHash != null ? evmProxyContractHash : Hash160.ZERO)
        );

        // Build deployment transaction using ContractManagement.
        // AccountSigner.global is required because checkWitness() is called inside the
        // @OnDeployment callback, which is invoked by ContractManagement (two call levels
        // deep from the entry script). calledByEntry would not cover that depth.
        TransactionBuilder builder = new ContractManagement(neow3j)
                .deploy(nef, manifest, deploymentData)
                .signers(AccountSigner.global(account));

        // Calculate contract hash from sender, nef checksum, and manifest name
        Hash160 contractHash = SmartContract.calcContractHash(
                account.getScriptHash(),
                nef.getCheckSumAsInteger(),
                manifest.getName()
        );

        logger.info("Contract Hash (calculated): {}", contractHash);
        logger.info("Contract Address (calculated): {}", contractHash.toAddress());

        if (dryRun) {
            logger.info("");
            logger.info("=== DRY RUN COMPLETE ===");
            logger.info("Transaction was prepared but NOT submitted to the network.");
            logger.info("Contract Hash: {}", contractHash);
            logger.info("Contract Address: {}", contractHash.toAddress());
            logger.info("");
            logger.info("To actually deploy, run without -PdryRun=true or set DRY_RUN=false");

            // Save contract hash to file if specified (even in dry run)
            if (hashFile != null && !hashFile.isEmpty()) {
                Files.write(Paths.get(hashFile), contractHash.toString().getBytes());
                logger.info("Contract hash saved to: {}", hashFile);
            }
            return;
        }

        // Sign and send transaction
        logger.info("Signing and sending deployment transaction...");
        io.neow3j.transaction.Transaction tx = builder.sign();
        NeoSendRawTransaction response = tx.send();
        if (response.hasError()) {
            throw new RuntimeException("Failed to send deployment transaction: " + response.getError().getMessage());
        }

        Hash256 txHash = tx.getTxId();
        logger.info("Deployment transaction sent: {}", txHash);

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
                logger.info("=== DEPLOYMENT FAILED ===");
                logger.info("TX Hash:    {}", txHash);
                logger.info("VM State:   {}", execution.getState());
                logger.info("Exception:  {}", error);
                throw new RuntimeException("Deployment failed: " + error);
            }
            logger.info("");
            logger.info("=== DEPLOYMENT SUCCESSFUL ===");
            logger.info("TX Hash:         {}", txHash);
            logger.info("VM State:        {}", execution.getState());
            long gasConsumedRaw = Long.parseLong(execution.getGasConsumed());
            logger.info("GAS Consumed:    {} GAS", String.format("%.8f", gasConsumedRaw / 1e8));
            logger.info("Contract Hash:   {}", contractHash);
            logger.info("Contract Address:{}", contractHash.toAddress());
        } else {
            logger.info("");
            logger.info("=== DEPLOYMENT SUCCESSFUL ===");
            logger.info("TX Hash:         {}", txHash);
            logger.info("Contract Hash:   {}", contractHash);
            logger.info("Contract Address:{}", contractHash.toAddress());
        }

        // Save contract hash to file if specified
        if (hashFile != null && !hashFile.isEmpty()) {
            Files.write(Paths.get(hashFile), contractHash.toString().getBytes());
            logger.info("Contract hash saved to: {}", hashFile);
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
