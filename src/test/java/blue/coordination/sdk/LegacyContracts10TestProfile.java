package blue.coordination.sdk;

/** Historical broad-closure/numbered-epoch profile from accepted base 44aa25b4. */
final class LegacyContracts10TestProfile {
    private LegacyContracts10TestProfile() { }

    static BlueCoordination.Builder builder() {
        return BlueCoordination.builder().release(
                "sha256:77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144",
                "sha256:0d7496790fb87d4589628c81fa8ca5e72b7d955458e20bc393f7837115ecb3b7");
    }
}
