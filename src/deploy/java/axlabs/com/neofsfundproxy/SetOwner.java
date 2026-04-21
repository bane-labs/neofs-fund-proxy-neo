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
 * Script to transfer NeoFSFundProxy ownership to another account.
 * The contract requires the current owner to call {@code setOwner} and the new owner to witness the transaction.
 * <p>
 * <strong>Wallet mode:</strong> {@code WALLET_FILEPATH_DEPLOYER} (or {@code -PwalletPath}). The default account must
 * be the current on-chain owner; the new owner's key must be another account in the same NEP-6 file.
 * {@code N3_NEW_OWNER_ADDRESS} / {@code -PnewOwner} is required (Neo3 address or script hash hex).
 * <p>
 * <strong>WIF mode:</strong> {@code N3_OWNER_WIF} and {@code N3_NEW_OWNER_WIF} (or {@code -PownerWif} /
 * {@code -PnewOwnerWif}). No wallet file. The new owner's script hash is taken from the new-owner WIF; if
 * {@code N3_NEW_OWNER_ADDRESS} is also set, it must match that WIF.
 * <p>
 * Usage (wallet):
 *   ./gradlew setOwner -PcontractHash=&lt;hash&gt; -PnewOwner=&lt;Neo3_address&gt; -PwalletPath=... [-PwalletPassword=...] [-PrpcUrl=...] [-PdryRun=true]
 * <p>
 * Usage (WIF):
 *   ./gradlew setOwner -PcontractHash=&lt;hash&gt; -PownerWif=&lt;wif&gt; -PnewOwnerWif=&lt;wif&gt; [-PrpcUrl=...] [-PdryRun=true]
 */
public class SetOwner {

    private static final Logger logger = getLogger(SetOwner.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";

    public static void main(String[] args) throws Throwable {
        loadDotenv(logger);

        String contractHashStr = getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String newOwnerStr = trimToNull(getConfig("newOwner", "N3_NEW_OWNER_ADDRESS", false));
        String ownerWif = trimToNull(getConfig("ownerWif", "N3_OWNER_WIF", false));
        String newOwnerWif = trimToNull(getConfig("newOwnerWif", "N3_NEW_OWNER_WIF", false));
        String walletPath = trimToNull(getConfig("walletPath", "WALLET_FILEPATH_DEPLOYER", false));
        String walletPassword = getConfig("walletPassword", "WALLET_PASSWORD_DEPLOYER", false);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }

        boolean wifMode = ownerWif != null && newOwnerWif != null;
        boolean walletMode = walletPath != null;

        if (wifMode && walletPath != null) {
            logger.warn("WIF mode is active (N3_OWNER_WIF and N3_NEW_OWNER_WIF set); wallet path is ignored.");
        }

        if (!wifMode && !walletMode) {
            throw new IllegalArgumentException(
                    "Provide either (N3_OWNER_WIF and N3_NEW_OWNER_WIF) for WIF mode, "
                            + "or WALLET_FILEPATH_DEPLOYER (-PwalletPath) for wallet mode.");
        }

        if (!wifMode && walletMode && newOwnerStr == null) {
            throw new IllegalArgumentException(
                    "Wallet mode requires new owner address: N3_NEW_OWNER_ADDRESS or -PnewOwner=<Neo3_address_or_hex>.");
        }

        boolean dryRun = isDryRun();
        if (dryRun) {
            logger.info("=== DRY RUN MODE - Transaction will NOT be submitted ===");
        }
        logger.info("Setting owner on NeoFSFundProxy...");
        logger.info("RPC URL:            {}", rpcUrl);
        logger.info("Contract Hash:      {}", contractHashStr);
        logger.info("Auth:               {}", wifMode ? "WIF" : "NEP-6 wallet");

        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));
        Hash160 contractHash = parseHash160(contractHashStr);

        Account currentOwnerAccount;
        Account newOwnerAccount;
        Hash160 newOwnerHash;

        if (wifMode) {
            currentOwnerAccount = Account.fromWIF(ownerWif);
            newOwnerAccount = Account.fromWIF(newOwnerWif);
            newOwnerHash = newOwnerAccount.getScriptHash();
            if (newOwnerStr != null) {
                Hash160 stated = parseHash160(newOwnerStr);
                if (!stated.equals(newOwnerHash)) {
                    throw new IllegalArgumentException(
                            "N3_NEW_OWNER_ADDRESS / -PnewOwner does not match the address derived from N3_NEW_OWNER_WIF.");
                }
            }
            logger.info("Current owner (from WIF):       {}", currentOwnerAccount.getAddress());
            logger.info("New owner (from WIF):           {}", newOwnerAccount.getAddress());
        } else {
            Wallet wallet = Wallet.fromNEP6Wallet(new File(walletPath));
            String password = walletPassword != null ? walletPassword : "";
            wallet.decryptAllAccounts(password);

            currentOwnerAccount = wallet.getDefaultAccount();
            logger.info("Current owner (wallet default): {}", currentOwnerAccount.getAddress());

            newOwnerHash = parseHash160(newOwnerStr);
            newOwnerAccount = wallet.getAccount(newOwnerHash);
            if (newOwnerAccount == null) {
                throw new IllegalArgumentException(
                        "The new owner's account must be in the same NEP-6 wallet file so it can sign the transaction. "
                                + "Expected an entry for " + newOwnerHash.toAddress() + " in " + walletPath);
            }
            logger.info("New owner (resolved):             {}", newOwnerAccount.getAddress());
        }

        TransactionBuilder builder = new SmartContract(contractHash, neow3j)
                .invokeFunction("setOwner", hash160(newOwnerHash))
                .signers(
                        calledByEntry(currentOwnerAccount),
                        calledByEntry(newOwnerAccount));

        if (dryRun) {
            logger.info("");
            logger.info("=== DRY RUN COMPLETE ===");
            logger.info("Would call setOwner({}) on contract {}", newOwnerAccount.getAddress(), contractHashStr);
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
        waitAndLogResult(logger, neow3j, txHash, contractHash, "SET_OWNER");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
