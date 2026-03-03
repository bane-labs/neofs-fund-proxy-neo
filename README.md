# NeoFS Fund Proxy - Neo3 Contracts

NeoFS fund proxy contract for Neo3 that facilitates funding NeoFS via bridge. Built with Gradle and neow3j.

## Project Structure

```
neofs-fund-proxy-neo/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── axlabs/
│   │           └── com/
│   │               ├── neofsfundproxy/
│   │               │   └── NeoFSFundProxy.java
│   │               └── lib/
│   │                   └── EvmSerializerLib.java
│   └── deploy/
│       └── java/
│           └── axlabs/
│               └── com/
│                   └── neofsfundproxy/
│                       └── DeployNeoFSFundProxy.java
├── wallets/                    # Wallet files (gitignored)
├── build.gradle                # Gradle build configuration
├── settings.gradle             # Gradle settings
├── gradle.properties           # Gradle properties
└── LICENSE                     # Apache 2.0 License
```

## Prerequisites

- Java 8 or higher
- Gradle (or use the included Gradle wrapper)

## Setup

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd neofs-fund-proxy-neo
   ```

2. Build the project:
   ```bash
   ./gradlew build
   ```

   Or on Windows:
   ```bash
   gradlew.bat build
   ```

## Compilation

Compile the contract to NEF format using the neow3j Gradle plugin:

```bash
./gradlew neow3jCompile
```

Or specify the class name explicitly:
```bash
./gradlew neow3jCompile -PclassName=axlabs.com.neofsfundproxy.NeoFSFundProxy
```

Compiled artifacts will be generated in `build/neow3j/`:
- `NeoFSFundProxy.nef` - The compiled contract bytecode
- `NeoFSFundProxy.manifest.json` - The contract manifest
- `NeoFSFundProxy.nefdbgnfo` - Debug information

## Contract Overview

### NeoFSFundProxy.java

The NeoFS fund proxy contract provides the following functionality:

- **Claims native tokens** from the bridge using a nonce
- **Transfers contract balance** to the NeoFS contract
- **Accepts GAS token payments** via NEP-17 payment callback (payments are stored in contract balance for later transfer to NeoFS)

#### Key Functions

- `fundNeoFS(beneficiary, nonce, requestId)` - Claims native tokens from bridge using the provided nonce and transfers contract balance to NeoFS contract.
- `setNativeBridge()` - Sets the native bridge contract address (owner only)
- `setNeoFSContract()` - Sets the NeoFS contract address (owner only)
- `getNeoFSContract()` - Gets the NeoFS contract address
- `setOwner()` - Sets the contract owner (owner only)
- `owner()` - Gets the contract owner address
- `upgrade()` - Upgrades the contract (owner only)

### EvmSerializerLib.java

Library for encoding data in EVM ABI format. Provides functions to serialize data for EVM contracts, including:
- Function selector encoding
- Parameter encoding (uint256, address, bytes, arrays)
- Full function call encoding

## Configuration

The `build.gradle` file configures:
- neow3j compiler plugin (version 3.24.0)
- Main contract class: `axlabs.com.neofsfundproxy.NeoFSFundProxy`
- Java source compatibility: 1.8
- Group: `axlabs.com.neofsfundproxy`

To compile a different contract, set the `className` property:
```bash
./gradlew neow3jCompile -PclassName=axlabs.com.neofsfundproxy.YourContract
```

## Deployment

### Prerequisites

1. **Neo3 Network Access** - Access to a Neo3 network (mainnet, testnet, or local)
2. **Wallet** - A Neo3 wallet with GAS for deployment
3. **RPC Endpoint** - Neo3 JSON-RPC endpoint URL

### Deployment using Gradle Task

The project includes a `deploy` Gradle task that compiles and deploys the contract with all required deployment data.

Configuration can be provided in three ways (in order of precedence):
1. **System properties** (`-Pkey=value`) - highest priority
2. **Environment variables** - medium priority
3. **`.env` file** - lowest priority (fallback)

#### Option 1: Using .env File (Recommended)

Create a `.env` file in the project root with your configuration. You can copy `.env.example` as a template:

```bash
cp .env.example .env
# Then edit .env with your values
```

```bash
# Required: Owner address for the contract (Neo3 address format)
N3_OWNER_ADDRESS=NRozNKnv4aSMEUL3KyD4UyeHoiPdLpi4y6

# Required: NeoFS contract address
N3_NEOFS_CONTRACT=NZNos2WqTbu5oCgyfss9kUJgBXJqhuYAaj

# Required: Path to deployer wallet file
WALLET_FILEPATH_DEPLOYER=wallets/deployer.json

# Optional: Native bridge contract address
N3_NATIVE_BRIDGE=NVTiAjNgagDkTr5oTzLfUz4Utf4XySHmPx

# Optional: Password for deployer wallet (leave empty if no password)
WALLET_PASSWORD_DEPLOYER=

# Optional: Neo3 JSON-RPC endpoint URL (defaults to http://localhost:40332)
N3_JSON_RPC=http://localhost:40332

# Optional: Path to save contract hash after deployment
N3_HASH_FILE=contract.hash
```

Then simply run:
```bash
./gradlew deploy
```

**Note:** Make sure to add `.env` to your `.gitignore` file to avoid committing sensitive information.

#### Option 2: Using System Properties

```bash
./gradlew deploy \
  -Powner=<owner_address> \
  -PneofsContract=<neofs_contract_address> \
  -PnativeBridge=<native_bridge_address> \
  -PwalletPath=<wallet_path> \
  -PwalletPassword=<wallet_password> \
  -PrpcUrl=<rpc_url> \
  -PhashFile=<hash_file_path>
```

Example:
```bash
./gradlew deploy \
  -Powner=NRozNKnv4aSMEUL3KyD4UyeHoiPdLpi4y6 \
  -PneofsContract=NZNos2WqTbu5oCgyfss9kUJgBXJqhuYAaj \
  -PnativeBridge=NVTiAjNgagDkTr5oTzLfUz4Utf4XySHmPx \
  -PwalletPath=wallets/deployer.json \
  -PwalletPassword="" \
  -PrpcUrl=http://localhost:40332
```

#### Option 3: Using Environment Variables

Alternatively, you can use environment variables:

```bash
export N3_OWNER_ADDRESS="NRozNKnv4aSMEUL3KyD4UyeHoiPdLpi4y6"
export N3_NEOFS_CONTRACT="NZNos2WqTbu5oCgyfss9kUJgBXJqhuYAaj"
export N3_NATIVE_BRIDGE="NVTiAjNgagDkTr5oTzLfUz4Utf4XySHmPx"  # Optional
export WALLET_FILEPATH_DEPLOYER="wallets/deployer.json"
export WALLET_PASSWORD_DEPLOYER=""  # Optional, empty string if no password
export N3_JSON_RPC="http://localhost:40332"  # Optional, defaults to http://localhost:40332
export N3_HASH_FILE="contract.hash"  # Optional, saves contract hash to file

./gradlew deploy
```

#### Required Parameters

- `owner` / `N3_OWNER_ADDRESS` - Owner address for the contract (Neo3 address format, required)
- `neofsContract` / `N3_NEOFS_CONTRACT` - NeoFS contract address (required)
- `walletPath` / `WALLET_FILEPATH_DEPLOYER` - Path to deployer wallet file (required)

#### Optional Parameters

- `nativeBridge` / `N3_NATIVE_BRIDGE` - Native bridge contract address (optional)
- `walletPassword` / `WALLET_PASSWORD_DEPLOYER` - Password for deployer wallet (optional, empty string if no password)
- `rpcUrl` / `N3_JSON_RPC` - Neo3 JSON-RPC endpoint URL (optional, defaults to http://localhost:40332)
- `hashFile` / `N3_HASH_FILE` - Optional path to save contract hash

The deployment task will:
1. Automatically compile the contract (runs `neow3jCompile` first)
2. Load the compiled NEF and manifest files
3. Create deployment data with owner, native bridge, and NeoFS contract
4. Deploy the contract to the Neo3 network
5. Wait for transaction confirmation
6. Display the contract hash and address
7. Optionally save the contract hash to a file

### Post-Deployment Configuration

After deployment, configure the contract if needed:

1. **Set Native Bridge Address (if not set during deployment):**
   ```bash
   # Call setNativeBridge() method with the native bridge contract hash
   ```

2. **Set NeoFS Contract Address (if not set during deployment):**
   ```bash
   # Call setNeoFSContract() method with the NeoFS contract hash
   ```

3. **Verify Configuration:**
   ```bash
   # Call getNeoFSContract() to verify NeoFS contract is set
   # Call owner() to verify owner is set
   ```

## Dependencies

- `io.neow3j:devpack:3.24.0` - Neo3 smart contract development pack
- `io.neow3j:compiler:3.24.0` - Neo3 compiler (for deployment tools)
- `io.neow3j:contract:3.24.0` - Neo3 contract utilities (for deployment tools)

## Testing

Run tests (if test files exist):
```bash
./gradlew test
```

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please ensure all code follows the existing style and includes appropriate tests.
