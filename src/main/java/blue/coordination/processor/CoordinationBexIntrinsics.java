package blue.coordination.processor;

import blue.bex.api.BexIntrinsicProcessor;
import blue.bex.api.BexIntrinsicRegistry;
import blue.coordination.processor.support.CoordinationBexIntrinsicsSupport;

/** Stable public facade for Coordination's hosted-BEX intrinsic catalog. */
public final class CoordinationBexIntrinsics {
    public static final String COMMON_CRYPTO_REGISTRY_IDENTITY =
            CoordinationBexIntrinsicsSupport
                    .COMMON_CRYPTO_REGISTRY_IDENTITY;
    public static final String COMMON_CRYPTO_ED25519_VERIFY_COUNTER =
            CoordinationBexIntrinsicsSupport
                    .COMMON_CRYPTO_ED25519_VERIFY_COUNTER;
    public static final long COMMON_CRYPTO_ED25519_VERIFY_GAS =
            CoordinationBexIntrinsicsSupport
                    .COMMON_CRYPTO_ED25519_VERIFY_GAS;

    private CoordinationBexIntrinsics() {
    }

    public static BexIntrinsicRegistry common() {
        return CoordinationBexIntrinsicsSupport.common();
    }

    public static BexIntrinsicRegistry registerCommon(
            BexIntrinsicRegistry registry) {
        return CoordinationBexIntrinsicsSupport.registerCommon(registry);
    }

    public static BexIntrinsicProcessor commonCryptoEd25519Verify() {
        return CoordinationBexIntrinsicsSupport
                .commonCryptoEd25519Verify();
    }
}
