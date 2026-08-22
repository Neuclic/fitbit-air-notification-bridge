package com.fitbit;

/**
 * Research-only wrapper around Google Health 5.05's original Application.
 *
 * The wrapper is loaded under the original package name after the APK is
 * re-signed. It loads Frida Gadget only in the main app process so the
 * :primes_lifeboat process does not contend for the same localhost port.
 */
public final class FitbitHook_Application extends FitbitRoot_Application {
    private static final String MAIN_PROCESS = "com.fitbit.FitbitMobile";
    private static final String GADGET_LIBRARY = "fba_bridge";

    public FitbitHook_Application() {
        super();
        try {
            String processName = android.app.Application.getProcessName();
            if (MAIN_PROCESS.equals(processName)) {
                System.loadLibrary(GADGET_LIBRARY);
            }
        } catch (Throwable ignored) {
            // Compatibility-gate build: never prevent Google Health from starting.
        }
    }
}
