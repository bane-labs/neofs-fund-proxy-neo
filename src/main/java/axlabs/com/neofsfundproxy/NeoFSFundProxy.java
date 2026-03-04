package axlabs.com.neofsfundproxy;

import io.neow3j.devpack.ByteString;
import io.neow3j.devpack.Hash160;
import io.neow3j.devpack.Storage;
import io.neow3j.devpack.StorageContext;
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

    private static final StorageContext ctx = Storage.getStorageContext();
    private static final StorageMap baseMap = new StorageMap(ctx, PREFIX_BASE);

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
    public static int fundNeoFS(
            Hash160 beneficiary,
            int nonce,
            int requestId
    ) {
        // Only message bridge can call this function
        OnlyMsgBridge();

        // Claim native tokens from the bridge using the provided nonce
        Hash160 bridgeHash = baseMap.getHash160(KEY_NATIVE_BRIDGE);
        if (bridgeHash != null && !bridgeHash.isZero()) {
            BridgeInterface bridge = new BridgeInterface(bridgeHash);
            bridge.claimNative(nonce);
        }

        // Transfer full contract balance to the NeoFS contract stored in contract storage
        Hash160 neofsContract = baseMap.getHash160(KEY_NEOFS_CONTRACT);
        validateHash(neofsContract, "NeoFS contract address not set");
        
        Hash160 contractHash = getExecutingScriptHash();
        int balance = gasToken.balanceOf(contractHash);
        
        if (balance > 0) {
            boolean transferred = gasToken.transfer(contractHash, neofsContract, balance, beneficiary);
            if (!transferred) {
                abort("Failed to transfer GAS to NeoFS contract");
            }
        }

        onNeoFSFunded.fire(beneficiary, balance, requestId);

        return requestId;
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
            
            // Validate and set owner
            if (!Hash160.isValid(deployData.owner)) {
                abort("Invalid owner");
            }
            validateHash(deployData.owner, "Invalid owner");
            baseMap.put(KEY_OWNER, deployData.owner);
            
            // Owner must witness the deployment
            if (!checkWitness(owner())) {
                abort("Owner must witness the deployment");
            }
            
            // Set native bridge if provided
            if (deployData.nativeBridge != null && Hash160.isValid(deployData.nativeBridge) && !deployData.nativeBridge.isZero()) {
                baseMap.put(KEY_NATIVE_BRIDGE, deployData.nativeBridge);
            }
            
            // Set NeoFS contract - required
            if (!Hash160.isValid(deployData.neofsContract)) {
                abort("Invalid NeoFS contract");
            }
            validateHash(deployData.neofsContract, "Invalid NeoFS contract");
            baseMap.put(KEY_NEOFS_CONTRACT, deployData.neofsContract);
            
            // Set message bridge - required
            if (!Hash160.isValid(deployData.messageBridge)) {
                abort("Invalid message bridge");
            }
            validateHash(deployData.messageBridge, "Invalid message bridge");
            baseMap.put(KEY_MESSAGE_BRIDGE, deployData.messageBridge);
        }
    }

    /**
     * Sets the bridge contract address.
     * Only the owner can call this method.
     * 
     * @param bridgeHash The bridge contract hash
     */
    public static void setNativeBridge(Hash160 bridgeHash) {
        onlyOwner();
        if (!Hash160.isValid(bridgeHash)) {
            abort("Invalid bridge hash");
        }
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
        if (!Hash160.isValid(neofsContractHash)) {
            abort("Invalid NeoFS contract hash");
        }
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
        if (!Hash160.isValid(messageBridgeHash)) {
            abort("Invalid message bridge hash");
        }
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
        if (hash == null || hash.isZero()) {
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
     * Checks that the caller is the message bridge.
     * Aborts if not authorized.
     */
    private static void OnlyMsgBridge() {
        Hash160 messageBridge = baseMap.getHash160(KEY_MESSAGE_BRIDGE);
        validateHash(messageBridge, "Message bridge address not set");
        Hash160 callingScriptHash = getCallingScriptHash();
        if (!callingScriptHash.equals(messageBridge)) {
            abort("No authorization - only message bridge");
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
