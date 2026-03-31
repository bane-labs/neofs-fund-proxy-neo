package axlabs.com.neofsfundproxy;

import io.neow3j.contract.ContractManagement;
import io.neow3j.contract.GasToken;
import io.neow3j.contract.NefFile;
import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.ContractManifest;
import io.neow3j.test.ContractTest;
import io.neow3j.test.ContractTestExtension;
import io.neow3j.test.DeployConfig;
import io.neow3j.test.DeployConfiguration;
import io.neow3j.transaction.AccountSigner;
import io.neow3j.transaction.exceptions.TransactionConfigurationException;
import io.neow3j.types.Hash160;
import io.neow3j.utils.Await;
import io.neow3j.wallet.Account;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;

import static io.neow3j.types.ContractParameter.hash160;
import static io.neow3j.types.ContractParameter.integer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Basic unit tests for NeoFSFundProxy contract.
 * Uses neow3j 3.24.2-SNAPSHOT and ContractTest framework.
 */
@ContractTest(
        blockTime = 1,
        contracts = {
                axlabs.com.neofsfundproxy.testhelper.DummyExecutionManagerContract.class,
                axlabs.com.neofsfundproxy.testhelper.DummyMessageBridgeContract.class,
                axlabs.com.neofsfundproxy.testhelper.DummyNativeBridgeContract.class,
                axlabs.com.neofsfundproxy.testhelper.DummyNeoFSContract.class
        },
        batchFile = "setup.batch"
)
public class NeoFSFundProxyTest {

    private static final String OWNER_ADDRESS = "NVcvzyBDBQeRH6PQgbfvwwRtsgPkERY28R";
    private static final String ALICE_ADDRESS = "NM7Aky765FG8NhhwtxjXRx7jEL1cnw7PBP";

    @RegisterExtension
    public static final ContractTestExtension ext = new ContractTestExtension();

    private static Neow3j neow3j;
    private static Account owner;
    private static Account alice;
    private static Hash160 proxyHash;
    private static GasToken gasToken;

    @BeforeAll
    public static void setUp() throws Throwable {
        neow3j = ext.getNeow3j();
        owner = ext.getAccount(OWNER_ADDRESS);
        alice = ext.getAccount(ALICE_ADDRESS);
        gasToken = new GasToken(neow3j);

        // Deploy NeoFSFundProxy manually with references to the dummy contracts
        Hash160 execManagerHash = ext.getDeployedContract(
                axlabs.com.neofsfundproxy.testhelper.DummyExecutionManagerContract.class).getScriptHash();
        Hash160 messageBridgeHash = ext.getDeployedContract(
                axlabs.com.neofsfundproxy.testhelper.DummyMessageBridgeContract.class).getScriptHash();
        Hash160 nativeBridgeHash = ext.getDeployedContract(
                axlabs.com.neofsfundproxy.testhelper.DummyNativeBridgeContract.class).getScriptHash();
        Hash160 neofsContractHash = ext.getDeployedContract(
                axlabs.com.neofsfundproxy.testhelper.DummyNeoFSContract.class).getScriptHash();

        // Deployment data: owner, nativeBridge, neofsContract, messageBridge, executionManager, evmProxyContract
        Hash160 testEvmProxy = new Hash160("0000000000000000000000000000000000000001");
        io.neow3j.types.ContractParameter deployData = io.neow3j.types.ContractParameter.array(
                hash160(owner.getScriptHash()),
                hash160(nativeBridgeHash),
                hash160(neofsContractHash),
                hash160(messageBridgeHash),
                hash160(execManagerHash),
                hash160(testEvmProxy)
        );

        File nefFile = new File("build/neow3j/NeoFSFundProxy.nef");
        File manifestFile = new File("build/neow3j/NeoFSFundProxy.manifest.json");
        if (!nefFile.exists() || !manifestFile.exists()) {
            throw new IllegalStateException("NeoFSFundProxy not compiled. Run ./gradlew neow3jCompile first.");
        }

        NefFile nef = NefFile.readFromFile(nefFile);
        String manifestJson = new String(Files.readAllBytes(manifestFile.toPath()));
        ContractManifest manifest = io.neow3j.wallet.Wallet.OBJECT_MAPPER.readValue(manifestJson, ContractManifest.class);

        proxyHash = SmartContract.calcContractHash(owner.getScriptHash(), nef.getCheckSumAsInteger(), manifest.getName());

        io.neow3j.transaction.Transaction tx = new ContractManagement(neow3j)
                .deploy(nef, manifest, deployData)
                .signers(AccountSigner.global(owner))
                .sign();
        tx.send();
        Await.waitUntilTransactionIsExecuted(tx.getTxId(), neow3j);
    }

    @DeployConfig(axlabs.com.neofsfundproxy.testhelper.DummyExecutionManagerContract.class)
    public static DeployConfiguration deployConfigDummyExecutionManager() throws Exception {
        return new DeployConfiguration();
    }

    @DeployConfig(axlabs.com.neofsfundproxy.testhelper.DummyMessageBridgeContract.class)
    public static DeployConfiguration deployConfigDummyMessageBridge() throws Exception {
        return new DeployConfiguration();
    }

    @DeployConfig(axlabs.com.neofsfundproxy.testhelper.DummyNativeBridgeContract.class)
    public static DeployConfiguration deployConfigDummyNativeBridge() throws Exception {
        return new DeployConfiguration();
    }

    @DeployConfig(axlabs.com.neofsfundproxy.testhelper.DummyNeoFSContract.class)
    public static DeployConfiguration deployConfigDummyNeoFS() throws Exception {
        return new DeployConfiguration();
    }

    // --- Tests ---

    @Test
    public void testDeployment_ownerIsSet() throws Exception {
        SmartContract proxy = new SmartContract(proxyHash, neow3j);
        Hash160 result = proxy.callFunctionReturningScriptHash("owner");
        assertThat(result.toAddress(), is(OWNER_ADDRESS));
    }

    @Test
    public void testDeployment_getNeoFSContractReturnsDeployedHash() throws Exception {
        Hash160 neofsHash = ext.getDeployedContract(
                axlabs.com.neofsfundproxy.testhelper.DummyNeoFSContract.class).getScriptHash();
        SmartContract proxy = new SmartContract(proxyHash, neow3j);
        Hash160 result = proxy.callFunctionReturningScriptHash("getNeoFSContract");
        assertThat(result.toAddress(), is(neofsHash.toAddress()));
    }

    @Test
    public void testDeployment_getMessageBridgeReturnsDeployedHash() throws Exception {
        Hash160 msgBridgeHash = ext.getDeployedContract(
                axlabs.com.neofsfundproxy.testhelper.DummyMessageBridgeContract.class).getScriptHash();
        SmartContract proxy = new SmartContract(proxyHash, neow3j);
        Hash160 result = proxy.callFunctionReturningScriptHash("getMessageBridge");
        assertThat(result.toAddress(), is(msgBridgeHash.toAddress()));
    }

    @Test
    public void testSetOwner_onlyOwnerCanCall() {
        TransactionConfigurationException thrown = assertThrows(TransactionConfigurationException.class, () ->
                new SmartContract(proxyHash, neow3j)
                        .invokeFunction("setOwner", hash160(alice.getScriptHash()))
                        .signers(AccountSigner.calledByEntry(alice))
                        .sign()
        );
        assertThat(thrown.getMessage(), containsString("No authorization - only owner"));
    }

    @Test
    public void testAcceptGASPayment() throws Throwable {
        BigInteger amount = gasToken.toFractions(BigDecimal.ONE);
        io.neow3j.types.Hash256 txHash = gasToken.transfer(alice, proxyHash, amount).sign().send()
                .getSendRawTransaction().getHash();
        Await.waitUntilTransactionIsExecuted(txHash, neow3j);
        assertThat(gasToken.getBalanceOf(proxyHash), is(amount));
    }

    @Test
    public void testRejectGASPaymentWithData() throws Exception {
        BigInteger amount = gasToken.toFractions(BigDecimal.ONE);
        TransactionConfigurationException thrown = assertThrows(TransactionConfigurationException.class, () ->
                gasToken.transfer(alice, proxyHash, amount, integer(42)).sign().send()
        );
        assertThat(thrown.getMessage(), containsString("No data accepted"));
    }
}
