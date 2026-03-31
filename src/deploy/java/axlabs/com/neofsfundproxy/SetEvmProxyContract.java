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
 * Script to set the EVM proxy contract address on the deployed NeoFSFundProxy contract.
 * Only the current owner can call setEvmProxyContract (enforced by the contract).
 * The value is the EVM-side proxy contract address as 20-byte hex (same as Hash160).
 * <p>
 * Usage:
 *   ./gradlew setEvmProxyContract -PcontractHash=<hash> -PevmProxyContract=<20_byte_hex> -PwalletPath=... [-PwalletPassword=...] [-PrpcUrl=...] [-PdryRun=true]
 * <p>
 * Or .env: N3_CONTRACT_HASH, N3_EVM_PROXY_CONTRACT, WALLET_FILEPATH_DEPLOYER, etc.
 */
public class SetEvmProxyContract {

    private static final Logger logger = getLogger(SetEvmProxyContract.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";

    public static void main(String[] args) throws Throwable {
        loadDotenv(logger);

        String contractHashStr = getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String evmProxyStr = getConfig("evmProxyContract", "N3_EVM_PROXY_CONTRACT", true);
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
        logger.info("Setting EVM proxy contract on NeoFSFundProxy...");
        logger.info("RPC URL:            {}", rpcUrl);
        logger.info("Contract Hash:      {}", contractHashStr);
        logger.info("EVM Proxy Contract: {}", evmProxyStr);

        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));
        Wallet wallet = Wallet.fromNEP6Wallet(new File(walletPath));
        String password = walletPassword != null ? walletPassword : "";
        wallet.decryptAllAccounts(password);
        Account account = wallet.getDefaultAccount();
        logger.info("Owner account:      {}", account.getAddress());

        Hash160 contractHash = parseHash160(contractHashStr);
        Hash160 evmProxyHash = parseHash160(evmProxyStr);

        TransactionBuilder builder = new SmartContract(contractHash, neow3j)
                .invokeFunction("setEvmProxyContract", hash160(evmProxyHash))
                .signers(calledByEntry(account));

        if (dryRun) {
            logger.info("");
            logger.info("=== DRY RUN COMPLETE ===");
            logger.info("Would call setEvmProxyContract({}) on contract {}", evmProxyStr, contractHashStr);
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
        waitAndLogResult(logger, neow3j, txHash, contractHash, "SET_EVM_PROXY_CONTRACT");
    }
}
