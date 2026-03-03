package axlabs.com.neofsfundproxy;

import io.neow3j.contract.Contract;
import io.neow3j.contract.NefFile;
import io.neow3j.contract.SmartContract;
import io.neow3j.crypto.WIF;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoApplicationLog;
import io.neow3j.protocol.core.response.NeoSendRawTransaction;
import io.neow3j.protocol.core.response.Transaction;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.transaction.AccountSigner;
import io.neow3j.transaction.TransactionBuilder;
import io.neow3j.types.Hash160;
import io.neow3j.types.Hash256;
import io.neow3j.wallet.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.cdimascio.dotenv.Dotenv;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Deployment script for NeoFSFundProxy contract.
 * 
 * Configuration can be provided in three ways (in order of precedence):
 * 1. System properties (-Pkey=value)
 * 2. Environment variables
 * 3. .env file in the project root
 * 
 * Usage:
 *   ./gradlew deploy -Powner=<owner_address> -PneofsContract=<neofs_contract_address> -PmessageBridge=<message_bridge_address> [-PnativeBridge=<native_bridge_address>] [-PwalletPath=<wallet_path>] [-PwalletPassword=<wallet_password>] [-PrpcUrl=<rpc_url>]
 * 
 * Or create a .env file with:
 *   N3_OWNER_ADDRESS=<owner_address>
 *   N3_NEOFS_CONTRACT=<neofs_contract_address>
 *   N3_MESSAGE_BRIDGE=<message_bridge_address>
 *   N3_NATIVE_BRIDGE=<native_bridge_address>  # Optional
 *   WALLET_FILEPATH_DEPLOYER=<wallet_path>
 *   WALLET_PASSWORD_DEPLOYER=<wallet_password>  # Optional
 *   N3_JSON_RPC=<rpc_url>  # Optional, defaults to http://localhost:40332
 *   N3_HASH_FILE=<hash_file_path>  # Optional
 * 
 * Then run: ./gradlew deploy
 */
public class DeployNeoFSFundProxy {

    private static final Logger logger = LoggerFactory.getLogger(DeployNeoFSFundProxy.class);
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
            // Ignore errors loading .env file
            logger.debug("Could not load .env file: {}", e.getMessage());
        }

        // Get configuration from system properties, environment variables, or .env file
        String ownerAddress = getConfig("owner", "N3_OWNER_ADDRESS", true);
        String neofsContract = getConfig("neofsContract", "N3_NEOFS_CONTRACT", true);
        String messageBridge = getConfig("messageBridge", "N3_MESSAGE_BRIDGE", true);
        String nativeBridge = getConfig("nativeBridge", "N3_NATIVE_BRIDGE", false);
        String walletPath = getConfig("walletPath", "WALLET_FILEPATH_DEPLOYER", true);
        String walletPassword = getConfig("walletPassword", "WALLET_PASSWORD_DEPLOYER", false);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }
        String hashFile = getConfig("hashFile", "N3_HASH_FILE", false);

        logger.info("Deploying NeoFSFundProxy contract...");
        logger.info("RPC URL: {}", rpcUrl);
        logger.info("Owner: {}", ownerAddress);
        logger.info("NeoFS Contract: {}", neofsContract);
        logger.info("Message Bridge: {}", messageBridge);
        if (nativeBridge != null && !nativeBridge.isEmpty()) {
            logger.info("Native Bridge: {}", nativeBridge);
        }

        // Connect to Neo3 network
        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));

        // Load wallet
        Account account = Account.fromNEP2Wallet(new File(walletPath), walletPassword != null ? walletPassword : "");
        logger.info("Deployer account: {}", account.getAddress());

        // Load compiled contract files
        String buildDir = "build/neow3j";
        File nefFile = new File(buildDir, "NeoFSFundProxy.nef");
        File manifestFile = new File(buildDir, "NeoFSFundProxy.manifest.json");

        if (!nefFile.exists() || !manifestFile.exists()) {
            throw new IOException("Contract files not found. Please run './gradlew neow3jCompile' first.");
        }

        NefFile nef = NefFile.readFromFile(nefFile);
        SmartContract.Manifest manifest = SmartContract.Manifest.fromJson(manifestFile);

        // Parse addresses
        Hash160 owner = Hash160.fromAddress(ownerAddress);
        Hash160 neofsContractHash = Hash160.fromAddress(neofsContract);
        Hash160 messageBridgeHash = Hash160.fromAddress(messageBridge);
        Hash160 nativeBridgeHash = null;
        if (nativeBridge != null && !nativeBridge.isEmpty()) {
            nativeBridgeHash = Hash160.fromAddress(nativeBridge);
        }

        // Create deployment data struct
        // The DeploymentData struct has 4 Hash160 fields: owner, nativeBridge, neofsContract, messageBridge
        io.neow3j.types.ContractParameter deploymentData = io.neow3j.types.ContractParameter.array(
                io.neow3j.types.ContractParameter.hash160(owner),
                io.neow3j.types.ContractParameter.hash160(nativeBridgeHash != null ? nativeBridgeHash : Hash160.ZERO),
                io.neow3j.types.ContractParameter.hash160(neofsContractHash),
                io.neow3j.types.ContractParameter.hash160(messageBridgeHash)
        );

        // Build deployment transaction
        Contract contract = new Contract(neow3j);
        TransactionBuilder builder = contract.deploy(nef, manifest, deploymentData)
                .signers(AccountSigner.calledByEntry(account));

        // Calculate network fee and set it
        builder = builder.calculateNetworkFee();

        // Sign and send transaction
        NeoSendRawTransaction response = builder.sign().send();
        if (response.hasError()) {
            throw new RuntimeException("Failed to send deployment transaction: " + response.getError().getMessage());
        }
        
        Hash256 txHash = response.getSendRawTransaction().getHash();
        logger.info("Deployment transaction sent: {}", txHash);

        // Wait for transaction to be confirmed
        logger.info("Waiting for transaction confirmation...");
        Transaction confirmedTx = neow3j.waitForTransaction(txHash).send().getTransaction().orElse(null);
        
        if (confirmedTx == null) {
            throw new RuntimeException("Transaction not found after waiting");
        }

        // Get application log to check for errors
        NeoApplicationLog appLog = neow3j.getApplicationLog(txHash).send().getApplicationLog().orElse(null);
        if (appLog != null && appLog.getExecutions().size() > 0) {
            NeoApplicationLog.Execution execution = appLog.getExecutions().get(0);
            if (execution.getState().equals("FAULT")) {
                String error = execution.getException() != null ? execution.getException() : "Unknown error";
                throw new RuntimeException("Deployment failed: " + error);
            }
        }

        // Calculate contract hash from sender, nef checksum, and manifest name
        Hash160 contractHash = SmartContract.calcContractHash(
                account.getScriptHash(), 
                nef.getCheckSumAsInteger(), 
                manifest.getName()
        );

        logger.info("Contract deployed successfully!");
        logger.info("Contract Hash: {}", contractHash);
        logger.info("Contract Address: {}", contractHash.toAddress());

        // Save contract hash to file if specified
        if (hashFile != null && !hashFile.isEmpty()) {
            Files.write(Paths.get(hashFile), contractHash.toString().getBytes());
            logger.info("Contract hash saved to: {}", hashFile);
        }
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
            throw new IllegalArgumentException("Required parameter missing: " + propertyName + " (property), " + envName + " (environment variable), or in .env file");
        }
        
        return value;
    }
}
