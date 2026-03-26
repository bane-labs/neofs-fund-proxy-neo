package axlabs.com.neofsfundproxy;

import io.neow3j.devpack.ByteString;
import io.neow3j.devpack.Hash160;
import io.neow3j.devpack.Storage;
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
    private static final int KEY_NATIVE_BRIDGE = 0x02;
    private static final int KEY_OWNER = 0x03;
    private static final int KEY_NEOFS_CONTRACT = 0x04;
    private static final int KEY_MESSAGE_BRIDGE = 0x05;
    private static final int KEY_EXECUTION_MANAGER = 0x06;
    private static final int KEY_EVM_PROXY_CONTRACT = 0x07;

    private static final StorageMap baseMap = new StorageMap(PREFIX_BASE);

    public static final GasToken gasToken = new GasToken();

    /**
     * Fired at the end of a successful {@link #fundNeoFS} call.
     *
     * @param beneficiary The beneficiary address passed to fundNeoFS
     * @param amount      The amount of GAS (in fractions, 10^-8) that was transferred to NeoFS
     * @param requestId   The request ID returned by fundNeoFS
     */
    @DisplayName("NeoFSFunded")
    @EventParameterNames({"beneficiary", "amount", "requestId"})
    private static Event3Args<Hash160, Integer, Integer> onNeoFSFunded;

    /**
     * Deployment data struct
     */
    @Struct
    public static class DeploymentData {
        public Hash160 owner;
        public Hash160 nativeBridge;
        public Hash160 neofsContract;
        public Hash160 messageBridge;
        public Hash160 executionManager;
        /** EVM-side proxy contract address (20 bytes, same as Hash160). Optional; can be set later via setEvmProxyContract. */
        public Hash160 evmProxyContract;
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
     * @param beneficiary The beneficiary address forwarded as transfer data to the NeoFS contract
     * @param nonce       The nonce for claiming from the native bridge
     * @param requestId   The request ID returned at the end of the call
     * @return The request ID
     */
    public static void fundNeoFS(
            Hash160 beneficiary,
            int nonce,
            int requestId
    ) {
        // Only execution manager can call this function
        onlyExecutionManager();

        validateHash(beneficiary, "Invalid beneficiary");

        // Verify that the message was sent from the allowed EVM proxy contract.
        // Use the executing nonce from the execution manager (message nonce), the arg nonce is only used with the native bridge
        Hash160 evmProxy = baseMap.getHash160(KEY_EVM_PROXY_CONTRACT);
        //validateHash(evmProxy, "EVM proxy bridge not set");

        Hash160 executionManagerHash = baseMap.getHash160(KEY_EXECUTION_MANAGER);
        //validateHash(executionManagerHash, "Execution manager not set");
        int executingNonce = new ExecutionManagerInterface(executionManagerHash).getExecutingNonce();
        if (executingNonce == 0) {
            abort("No message is currently being executed");
        }

        Hash160 messageBridgeHash = baseMap.getHash160(KEY_MESSAGE_BRIDGE);
        //validateHash(messageBridgeHash, "Message bridge not set");

        MessageBridgeInterface messageBridge = new MessageBridgeInterface(messageBridgeHash);
        MessageWithMetadata message = messageBridge.getMessage(executingNonce);
        if (message == null || message.metadataBytes == null) {
            abort("Message not found for nonce");
        }
        MetadataWithSender metadata = (MetadataWithSender) new StdLib().deserialize(message.metadataBytes);
        if (metadata == null || metadata.sender == null || !metadata.sender.equals(evmProxy)) {
            abort("Message sender is not the allowed EVM proxy contract");
        }

        // Claim native tokens from the bridge using the provided nonce
        Hash160 bridgeHash = baseMap.getHash160(KEY_NATIVE_BRIDGE);
        //validateHash(bridgeHash, "NeoFS contract address not set");

        BridgeInterface bridge = new BridgeInterface(bridgeHash);
        bridge.claimNative(nonce);

        // Transfer full contract balance to the NeoFS contract stored in contract storage
        Hash160 neofsContract = baseMap.getHash160(KEY_NEOFS_CONTRACT);
        //validateHash(neofsContract, "NeoFS contract address not set");
        
        Hash160 executingHash = getExecutingScriptHash();
        int balance = gasToken.balanceOf(executingHash);
        
        if (gasToken.transfer(executingHash, neofsContract, balance, beneficiary)) {
           abort("Failed to transfer GAS to NeoFS contract");
        }

        onNeoFSFunded.fire(beneficiary, balance, requestId);
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
     * Sets the owner, native bridge, and NeoFS contract during initial deployment.
     * 
     * @param data Deployment data. Must be a {@link DeploymentData} struct with owner, nativeBridge,
     *             neofsContract, and messageBridge fields (in that order).
     * @param isUpdate Whether this is an update (true) or initial deployment (false)
     */
    @OnDeployment
    public static void deploy(Object data, boolean isUpdate) {
        if (!isUpdate) {
            // On initial deployment, set owner, native bridge, and NeoFS contract from deployment data
            if (data == null) {
                abort("Invalid deployment data - DeploymentData struct required");
            }

            DeploymentData deployData = (DeploymentData) data;
            
            validateHash(deployData.owner, "Invalid owner");
            baseMap.put(KEY_OWNER, deployData.owner);
            
            // Set native bridge if provided
            validateHash(deployData.nativeBridge, "Invalid native bridge");
            baseMap.put(KEY_NATIVE_BRIDGE, deployData.nativeBridge);
            
            validateHash(deployData.neofsContract, "Invalid NeoFS contract");
            baseMap.put(KEY_NEOFS_CONTRACT, deployData.neofsContract);
            
            validateHash(deployData.messageBridge, "Invalid message bridge");
            baseMap.put(KEY_MESSAGE_BRIDGE, deployData.messageBridge);

            validateHash(deployData.executionManager, "Invalid execution manager");
            baseMap.put(KEY_EXECUTION_MANAGER, deployData.executionManager);

            // Set EVM proxy contract address if provided (optional at deploy; can be set later via setEvmProxyContract)
            validateHash(deployData.evmProxyContract, "Invalid proxy contract");
            baseMap.put(KEY_EVM_PROXY_CONTRACT, deployData.evmProxyContract);
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
        baseMap.put(KEY_NATIVE_BRIDGE, bridgeHash);
    }

    /**
     * Sets the NeoFS contract address.
     * Only the owner can call this method.
     * 
     * @param neofsContractHash The NeoFS contract hash
     */
    public static void setNeoFSContract(Hash160 neofsContractHash) {
        onlyOwner();
        validateHash(neofsContractHash, "Invalid NeoFS contract hash");
        baseMap.put(KEY_NEOFS_CONTRACT, neofsContractHash);
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
     * Gets the message bridge contract address.
     * 
     * @return The message bridge contract hash
     */
    @Safe
    public static Hash160 getMessageBridge() {
        return baseMap.getHash160(KEY_MESSAGE_BRIDGE);
    }

    /**
     * Sets the EVM-side proxy contract address (20-byte address as Hash160).
     * Only the owner can call this method.
     * When set, fundNeoFS will only accept messages whose metadata sender equals this address.
     *
     * @param evmProxyContractHash The EVM proxy contract address (Hash160, 20 bytes)
     */
    public static void setEvmProxyContract(Hash160 evmProxyContractHash) {
        onlyOwner();
	validateHash(evmProxyContractHash, "Invalid proxy contract hash")
        baseMap.put(KEY_EVM_PROXY_CONTRACT, evmProxyContractHash);
    }

    /**
     * Gets the EVM-side proxy contract address.
     *
     * @return The EVM proxy contract address (Hash160), or null if not set
     */
    @Safe
    public static Hash160 getEvmProxyContract() {
        return baseMap.getHash160(KEY_EVM_PROXY_CONTRACT);
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
     * Gets the owner address.
     * 
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
        Hash160 executionManager = baseMap.getHash160(KEY_EXECUTION_MANAGER);
        if (executionManager == null || executionManager.isZero()) {
            abort("Execution manager not set");
        }
        Hash160 callingScriptHash = getCallingScriptHash();
        if (!callingScriptHash.equals(executionManager)) {
            abort("No authorization - only execution manager");
        }
    }

    /**
     * Sets the execution manager contract address.
     * Only the owner can call this method.
     * 
     * @param executionManagerHash The execution manager contract hash
     */
    public static void setExecutionManager(Hash160 executionManagerHash) {
        onlyOwner();
        if (executionManagerHash == null || !Hash160.isValid(executionManagerHash) || executionManagerHash.isZero()) {
            abort("Invalid execution manager hash");
        }
        baseMap.put(KEY_EXECUTION_MANAGER, executionManagerHash);
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
        Hash160 callingScriptHash = getCallingScriptHash();
        if (!callingScriptHash.equals(new GasToken().getHash())) {
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

        @CallFlags(io.neow3j.devpack.constants.CallFlags.ReadStates)
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
     * Interface for calling Bridge contract methods
     */
    private static class BridgeInterface extends ContractInterface {
        public BridgeInterface(Hash160 contractHash) {
            super(contractHash);
        }

        @CallFlags(io.neow3j.devpack.constants.CallFlags.All)
        public native void claimNative(int nonce);
    }

}
