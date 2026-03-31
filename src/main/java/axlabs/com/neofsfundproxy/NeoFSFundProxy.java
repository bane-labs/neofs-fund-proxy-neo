package axlabs.com.neofsfundproxy;

import io.neow3j.devpack.ByteString;
import io.neow3j.devpack.Hash160;
import io.neow3j.devpack.StorageMap;
import io.neow3j.devpack.annotations.CallFlags;
import io.neow3j.devpack.annotations.DisplayName;
import io.neow3j.devpack.annotations.EventParameterNames;
import io.neow3j.devpack.annotations.ManifestExtra;
import io.neow3j.devpack.annotations.OnDeployment;
import io.neow3j.devpack.annotations.OnNEP17Payment;
import io.neow3j.devpack.annotations.Permission;
import io.neow3j.devpack.annotations.Safe;
import io.neow3j.devpack.annotations.Struct;
import io.neow3j.devpack.events.Event3Args;
import io.neow3j.devpack.contracts.ContractInterface;
import io.neow3j.devpack.contracts.GasToken;
import io.neow3j.devpack.contracts.ContractManagement;
import io.neow3j.devpack.contracts.StdLib;

import static io.neow3j.devpack.Helper.abort;
import static io.neow3j.devpack.Runtime.checkWitness;
import static io.neow3j.devpack.Runtime.getCallingScriptHash;
import static io.neow3j.devpack.Runtime.getExecutingScriptHash;

/**
 * NeoFS fund proxy contract for funding NeoFS via bridge.
 */
@DisplayName("NeoFSFundProxy")
@ManifestExtra(key = "author", value = "AxLabs")
@ManifestExtra(key = "description", value = "NeoFS fund proxy contract for funding NeoFS via bridge")
@Permission(contract = "*")
public class NeoFSFundProxy {

    private static final byte PREFIX_BASE = 0x0a;
    private static final int KEY_OWNER = 0x01;
    private static final int KEY_MESSAGE_BRIDGE = 0x02;
    private static final int KEY_TOKEN_BRIDGE = 0x03;
    private static final int KEY_EXECUTION_MANAGER = 0x04;
    private static final int KEY_NEOFS_FUND_PROXY_ON_EVM = 0x05;
    private static final int KEY_NEOFS_CONTRACT = 0x06;

    private static final StorageMap baseMap = new StorageMap(PREFIX_BASE);

    public static final GasToken gasToken = new GasToken();

    /**
     * Fired at the end of a successful {@link #fundNeoFS} call.
     *
     * @param requestId   The request ID returned by fundNeoFS
     * @param beneficiary The beneficiary address passed to fundNeoFS
     * @param amount      The amount of GAS (in fractions, 10^-8) that was transferred to NeoFS
     */
    @DisplayName("NeoFSFunded")
    @EventParameterNames({"requestId", "beneficiary", "amount"})
    private static Event3Args<Integer, Hash160, Integer> onNeoFSFunded;

    /**
     * Deployment data struct
     */
    @Struct
    public static class DeploymentData {
        public Hash160 owner;
        public Hash160 tokenBridge;
        public Hash160 neofsContract;
        public Hash160 messageBridge;
        public Hash160 executionManager;
        /**
         * EVM-side proxy contract address (20 bytes, same as Hash160). Required at deploy; owner may change it via
         * {@link #setNeoFSFundProxyOnEVM(Hash160)}.
         */
        public Hash160 neoFSFundProxyOnEVM;
    }

    /**
     * Message shape returned by the message bridge getMessage(nonce). Layout must match bridge's NeoMessage.
     */
    @Struct
    private static class MessageWithMetadata {
        public ByteString metadataBytes;
        public ByteString rawMessage;
    }

    /**
     * Metadata shape for executable messages. Used to deserialize message bridge metadata and read sender (EVM address).
     * Layout matches bridge's NeoMetadataExecutable: type, timestamp, sender, storeResult.
     */
    @Struct
    private static class MetadataWithSender {
        public int type;
        public int timestamp;
        public Hash160 sender;
        public boolean storeResult;
    }

    /**
     * Funds NeoFS by claiming tokens from bridge and transferring contract balance.
     * Only the message bridge can call this method.
     * Fires a {@code NeoFSFunded} event on completion containing the beneficiary,
     * the GAS amount transferred, and the request ID.
     *
     * @param requestId       The request ID returned at the end of the call
     * @param withdrawalNonce The nonce for claiming from the token bridge
     * @param beneficiary     The beneficiary address forwarded as transfer data to the NeoFS contract
     */
    public static void fundNeoFS(
            int requestId,
            int withdrawalNonce,
            Hash160 beneficiary
    ) {
        // Only execution manager can call this function
        onlyExecutionManager();

        validateHash(beneficiary, "Invalid beneficiary");

        // Verify that the message was sent from the allowed EVM proxy contract.
        // Use the executing nonce from the execution manager (message nonce), the arg nonce is only used with the token bridge
        Hash160 proxyOnEVM = getNeoFSFundProxyOnEVM();

        int executingNonce = new ExecutionManagerInterface(getExecutionManager()).getExecutingNonce();
        if (executingNonce == 0) {
            abort("No message is currently being executed");
        }

        MessageBridgeInterface messageBridge = new MessageBridgeInterface(getMessageBridge());
        MessageWithMetadata message = messageBridge.getMessage(executingNonce);
        if (message == null || message.metadataBytes == null) {
            abort("Message not found for nonce");
        }
        MetadataWithSender metadata = (MetadataWithSender) new StdLib().deserialize(message.metadataBytes);
        if (metadata == null || metadata.sender == null || !metadata.sender.equals(proxyOnEVM)) {
            abort("Message sender is not the allowed EVM proxy contract");
        }

        // Claim tokens from the token bridge using the provided nonce
        // TODO, check if claimable when possible
        TokenBridgeInterface tokenBridge = new TokenBridgeInterface(getTokenBridge());
        tokenBridge.claimNative(withdrawalNonce);

        // Transfer full contract balance to the NeoFS contract stored in contract storage
        Hash160 executingHash = getExecutingScriptHash();
        int fundAmount = gasToken.balanceOf(executingHash);

        if (!gasToken.transfer(executingHash, getNeoFSContract(), fundAmount, beneficiary)) {
           abort("Failed to transfer GAS to NeoFS contract");
        }

        onNeoFSFunded.fire(requestId, beneficiary, fundAmount);
    }

    /**
     * Upgrades the contract.
     * Only the owner can call this method.
     * 
     * @param nef The new NEF file
     * @param manifest The new manifest
     * @param data Optional data for the update
     */
    public static void upgrade(ByteString nef, String manifest, Object data) {
        onlyOwner();
        new ContractManagement().update(nef, manifest, data);
    }

    /**
     * Deploy function called automatically when contract is deployed.
     * Sets the owner, token bridge, and NeoFS contract during initial deployment.
     * 
     * @param data Deployment data. Must be a {@link DeploymentData} struct with owner, tokenBridge,
     *             neofsContract, and messageBridge fields (in that order).
     * @param isUpdate Whether this is an update (true) or initial deployment (false)
     */
    @OnDeployment
    public static void deploy(Object data, boolean isUpdate) {
        if (!isUpdate) {
            // On initial deployment, set owner, token bridge, and NeoFS contract from deployment data
            if (data == null) {
                abort("Invalid deployment data - DeploymentData struct required");
            }

            DeploymentData deployData = (DeploymentData) data;
            
            validateHash(deployData.owner, "Invalid owner");
            baseMap.put(KEY_OWNER, deployData.owner);
            
            validateHash(deployData.tokenBridge, "Invalid token bridge");
            baseMap.put(KEY_TOKEN_BRIDGE, deployData.tokenBridge);
            
            validateHash(deployData.neofsContract, "Invalid NeoFS contract");
            baseMap.put(KEY_NEOFS_CONTRACT, deployData.neofsContract);
            
            validateHash(deployData.messageBridge, "Invalid message bridge");
            baseMap.put(KEY_MESSAGE_BRIDGE, deployData.messageBridge);

            validateHash(deployData.executionManager, "Invalid execution manager");
            baseMap.put(KEY_EXECUTION_MANAGER, deployData.executionManager);

            validateHash(deployData.neoFSFundProxyOnEVM, "Invalid EVM proxy contract");
            baseMap.put(KEY_NEOFS_FUND_PROXY_ON_EVM, deployData.neoFSFundProxyOnEVM);
        }
    }

    /**
     * Sets the bridge contract address.
     * Only the owner can call this method.
     * 
     * @param bridgeHash The bridge contract hash
     */
    public static void setTokenBridge(Hash160 bridgeHash) {
        onlyOwner();
        validateHash(bridgeHash, "Invalid bridge hash");
        baseMap.put(KEY_TOKEN_BRIDGE, bridgeHash);
    }

    /**
     * Sets the execution manager contract address.
     * Only the owner can call this method.
     * 
     * @param executionManagerHash The execution manager contract hash
     */
    public static void setExecutionManager(Hash160 executionManagerHash) {
        onlyOwner();
        validateHash(executionManagerHash, "Invalid execution manager hash");
        baseMap.put(KEY_EXECUTION_MANAGER, executionManagerHash);
    }

    /**
     * Sets the NeoFS contract address.
     * Only the owner can call this method.
     * 
     * @param neoFSContractHash The NeoFS contract hash
     */
    public static void setNeoFSContract(Hash160 neoFSContractHash) {
        onlyOwner();
        validateHash(neoFSContractHash, "Invalid NeoFS contract hash");
        baseMap.put(KEY_NEOFS_CONTRACT, neoFSContractHash);
    }

    /**
     * Sets the EVM-side proxy contract address (20-byte address as Hash160).
     * Only the owner can call this method.
     * FundNeoFS will only accept messages whose metadata sender equals this address.
     *
     * @param evmProxyContractHash The EVM proxy contract address (Hash160, 20 bytes)
     */
    public static void setNeoFSFundProxyOnEVM(Hash160 evmProxyContractHash) {
        onlyOwner();
        validateHash(evmProxyContractHash, "Invalid EVM proxy contract hash");
        baseMap.put(KEY_NEOFS_FUND_PROXY_ON_EVM, evmProxyContractHash);
    }

    /**
     * Gets the NeoFS contract address.
     * 
     * @return The NeoFS contract hash
     */
    @Safe
    public static Hash160 getNeoFSContract() {
        return baseMap.getHash160(KEY_NEOFS_CONTRACT);
    }

    /**
     * Sets the message bridge contract address.
     * Only the owner can call this method.
     * 
     * @param messageBridgeHash The message bridge contract hash
     */
    public static void setMessageBridge(Hash160 messageBridgeHash) {
        onlyOwner();
        validateHash(messageBridgeHash, "Invalid message bridge hash");
        baseMap.put(KEY_MESSAGE_BRIDGE, messageBridgeHash);
    }

    /**
     * Gets the execution manager contract address.
     * 
     * @return The execution manager contract hash
     */
    @Safe
    public static Hash160 getExecutionManager() {
        return baseMap.getHash160(KEY_EXECUTION_MANAGER);
    }

    /**
     * Gets the message bridge contract address.
     * 
     * @return The message bridge contract hash
     */
    @Safe
    public static Hash160 getMessageBridge() {
        return baseMap.getHash160(KEY_MESSAGE_BRIDGE);
    }

    /**
     * @return The token bridge contract hash
     */
    @Safe
    public static Hash160 getTokenBridge() {
        return baseMap.getHash160(KEY_TOKEN_BRIDGE);
    }

    /**
     * @return The EVM proxy contract address (Hash160), or null if not set
     */
    @Safe
    public static Hash160 getNeoFSFundProxyOnEVM() {
        return baseMap.getHash160(KEY_NEOFS_FUND_PROXY_ON_EVM);
    }

    /**
     * Sets the owner address.
     * Only the current owner can call this method.
     * 
     * @param newOwner The new owner address
     */
    public static void setOwner(Hash160 newOwner) {
        onlyOwner();
        if (newOwner == null || !Hash160.isValid(newOwner)) {
            abort("Invalid new owner");
        }
        if (!checkWitness(newOwner)) {
            abort("New owner must witness owner change");
        }
        baseMap.put(KEY_OWNER, newOwner);
    }

    /**
     * @return The owner address
     */
    @Safe
    public static Hash160 owner() {
        return baseMap.getHash160(KEY_OWNER);
    }

    /**
     * Validates that a Hash160 is not null and not zero.
     * Aborts if validation fails.
     * 
     * @param hash The hash to validate
     * @param errorMessage The error message to display if validation fails
     */
    private static void validateHash(Hash160 hash, String errorMessage) {
        if (hash == null || !Hash160.isValid(hash) || hash.isZero()) {
            abort(errorMessage);
        }
    }

    /**
     * Checks that the caller is the owner.
     * Aborts if not authorized.
     */
    private static void onlyOwner() {
        if (!checkWitness(owner())) {
            abort("No authorization - only owner");
        }
    }

    /**
     * Checks that the caller is the execution manager.
     * Aborts if not authorized.
     */
    private static void onlyExecutionManager() {
        if (!getCallingScriptHash().equals(getExecutionManager())) {
            abort("No authorization - only execution manager");
        }
    }

    /**
     * NEP-17 payment callback.
     * This contract accepts only GAS token payments.
     * 
     * @param from The sender of the payment
     * @param amount The amount of tokens transferred
     * @param data Optional data parameter (not accepted)
     */
    @OnNEP17Payment
    public static void onNep17Payment(Hash160 from, int amount, Object data) {
        // Only accept GAS token payments
        if (!getCallingScriptHash().equals(gasToken.getHash())) {
            abort("Only GAS token payments are accepted");
        }
        
        // Reject payments with data
        if (data != null) {
            abort("No data accepted");
        }
        
        // Reject invalid amounts
        if (amount <= 0) {
            abort("Invalid amount");
        }
        
        // Payment accepted - GAS will be added to contract balance
    }

    /**
     * Interface for calling the execution manager (getExecutingNonce to get current message nonce).
     */
    private static class ExecutionManagerInterface extends ContractInterface {
        public ExecutionManagerInterface(Hash160 contractHash) {
            super(contractHash);
        }

        @CallFlags(io.neow3j.devpack.constants.CallFlags.ReadOnly)
        public native int getExecutingNonce();
    }

    /**
     * Interface for calling the message bridge contract (getMessage to read metadata).
     */
    private static class MessageBridgeInterface extends ContractInterface {
        public MessageBridgeInterface(Hash160 contractHash) {
            super(contractHash);
        }

        @CallFlags(io.neow3j.devpack.constants.CallFlags.ReadOnly)
        public native MessageWithMetadata getMessage(int nonce);
    }

    /**
     * Interface for calling token bridge contract methods
     */
    private static class TokenBridgeInterface extends ContractInterface {
        public TokenBridgeInterface(Hash160 contractHash) {
            super(contractHash);
        }

        @CallFlags(io.neow3j.devpack.constants.CallFlags.All)
        public native void claimNative(int nonce);
    }

}
