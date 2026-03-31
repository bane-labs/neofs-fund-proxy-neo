package axlabs.com.neofsfundproxy;

import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoSendRawTransaction;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.transaction.Transaction;
import io.neow3j.transaction.TransactionBuilder;
import io.neow3j.types.Hash160;
import io.neow3j.types.Hash256;
import io.neow3j.wallet.Account;
import io.neow3j.wallet.Wallet;
import org.slf4j.Logger;

import java.io.File;

import static axlabs.com.neofsfundproxy.ScriptUtils.getConfig;
import static axlabs.com.neofsfundproxy.ScriptUtils.isDryRun;
import static axlabs.com.neofsfundproxy.ScriptUtils.loadDotenv;
import static axlabs.com.neofsfundproxy.ScriptUtils.parseHash160;
import static axlabs.com.neofsfundproxy.ScriptUtils.waitAndLogResult;
import static io.neow3j.transaction.AccountSigner.calledByEntry;
import static io.neow3j.types.ContractParameter.hash160;
import static org.slf4j.LoggerFactory.getLogger;

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

    private static final Logger logger = getLogger(SetExecutionManager.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";

    public static void main(String[] args) throws Throwable {
        loadDotenv(logger);

        String contractHashStr = getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String executionManagerStr = getConfig("executionManager", "N3_EXECUTION_MANAGER", true);
        String walletPath = getConfig("walletPath", "WALLET_FILEPATH_DEPLOYER", true);
        String walletPassword = getConfig("walletPassword", "WALLET_PASSWORD_DEPLOYER", false);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }
        boolean dryRun = isDryRun();
        if (isDryRun()) {
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
                .invokeFunction("setExecutionManager", hash160(executionManagerHash))
                .signers(calledByEntry(account));

        if (dryRun) {
            logger.info("");
            logger.info("=== DRY RUN COMPLETE ===");
            logger.info("Would call setExecutionManager({}) on contract {}", executionManagerStr, contractHashStr);
            return;
        }

        logger.info("Signing and sending transaction...");
        Transaction tx = builder.sign();
        NeoSendRawTransaction response = tx.send();
        if (response.hasError()) {
            throw new RuntimeException("Failed to send transaction: " + response.getError().getMessage());
        }

        Hash256 txHash = tx.getTxId();
        logger.info("Transaction sent: {}", txHash);
        waitAndLogResult(logger, neow3j, txHash, contractHash, "SET_EXECUTION_MANAGER");
    }
}
