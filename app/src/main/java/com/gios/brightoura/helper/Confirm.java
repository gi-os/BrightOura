package com.gios.brightoura.helper;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Looper;

/**
 * Pair the ring from the shell, because nothing on this phone can pair it from an app.
 *
 * <h2>Why this class exists at all</h2>
 *
 * Every route the platform offers ends at the same broken screen. A bond raises a
 * {@code PAIRING_VARIANT_CONSENT} request; the request becomes
 * {@code com.android.settings/.bluetooth.BluetoothPairingDialog}; and on LightOS that fragment
 * builds a <b>null</b> dialog and dies with a {@code NullPointerException} in
 * {@code DialogFragment.prepareDialog}. Asleep, the phone posts a notification instead — but its
 * "Pair &amp; connect" action fires {@code ACTION_PAIRING_DIALOG}, whose only job is to start that
 * same activity, and it takes the pairing service down with it. There is no fourth branch, and no
 * amount of doing the app side correctly reaches one.
 *
 * <p>{@link BluetoothDevice#setPairingConfirmation} answers the request with no UI whatsoever. It
 * needs {@code BLUETOOTH_PRIVILEGED}, which is {@code signature|privileged} and therefore
 * ungrantable to a sideloaded app — but <b>{@code com.android.shell} already holds it</b>, granted
 * and privileged, along with {@code BLUETOOTH_STACK}. So the one process on this phone that can
 * accept a pairing request is the adb shell, and BrightControl holds an adb shell.
 *
 * <h2>How it is run</h2>
 *
 * Not as an APK entry point — as a main class inside this app's own APK, which is world-readable:
 *
 * <pre>
 * CLASSPATH=/data/app/…/base.apk app_process / com.gios.brightoura.helper.Confirm &lt;MAC&gt;
 * </pre>
 *
 * BrightControl rebuilds that line itself from the requesting package (see its
 * {@code adb/GrantRequest.kt}); nothing but a MAC address crosses over, and the user approves the
 * result before it runs. There is no dex to ship, no file to drop in shared storage, and nothing
 * left behind on the phone afterwards.
 *
 * <h2>Why it does the bonding too</h2>
 *
 * Confirming from here while the app bonds from there is a race across two processes and a consent
 * screen. Doing both in one place removes it: this asks for the bond, then answers the request it
 * raised, in the same loop, as the same uid. A bond is phone-wide, so it does not matter which
 * process made it — the ring ends up paired to the phone either way.
 *
 * <p>Written in Java rather than Kotlin because {@code app_process} wants a plain
 * {@code public static void main}, and because this must run with no framework of ours around it.
 */
public final class Confirm {

    /** How long to keep answering. The request stands for about thirty seconds; this covers it. */
    private static final long DEFAULT_BUDGET_MS = 24_000L;

    /** Between attempts. The request can arrive several seconds after the bond is asked for. */
    private static final long POLL_MS = 500L;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("usage: Confirm <MAC> [budgetMs]");
            return;
        }
        final String mac = args[0].toUpperCase();
        long budget = DEFAULT_BUDGET_MS;
        if (args.length > 1) {
            try {
                budget = Long.parseLong(args[1]);
            } catch (NumberFormatException ignored) {
                // Keep the default. A bad number is not worth failing a pairing over.
            }
        }
        try {
            run(mac, budget);
        } catch (Throwable t) {
            // Printed rather than thrown: the adb shell service carries no exit status, so a stack
            // trace on stdout is the only way this reports anything at all.
            System.out.println("FAILED " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void run(String mac, long budget) throws Exception {
        Looper.prepareMainLooper();

        // `app_process` starts a bare VM: there is no Application, no ActivityThread, and so no
        // Context to ask for a system service. `systemMain()` builds the one the system server's
        // own tools use, and everything below hangs off it.
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);

        // The permission check on the far side validates the *attribution source* — the package
        // name travelling with the call has to belong to the calling uid. The system context says
        // "android", and this runs as uid 2000, which is a mismatch the Bluetooth service is
        // entitled to refuse. A package context for the shell says the true thing about both.
        Context context = system;
        try {
            context = system.createPackageContext("com.android.shell", 0);
        } catch (Throwable t) {
            System.out.println("note: no shell package context (" + t.getClass().getSimpleName()
                    + "), using the system one");
        }

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) {
            System.out.println("FAILED no bluetooth service");
            return;
        }
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            System.out.println("FAILED bluetooth is off");
            return;
        }
        BluetoothDevice device = adapter.getRemoteDevice(mac);
        System.out.println("device " + mac + " state " + name(device.getBondState()));

        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            System.out.println("RESULT already bonded");
            return;
        }

        // Clear a half-made bond first. One of these poisons every attempt after it: the phone
        // believes it holds a key the ring does not, and each new pairing fails as "incorrect PIN"
        // until the stale one is gone.
        if (device.getBondState() == BluetoothDevice.BOND_BONDING) {
            invokeBoolean(device, "cancelBondProcess");
            sleep(600);
        }
        invokeBoolean(device, "removeBond");
        sleep(400);

        boolean asked = device.createBond();
        System.out.println("createBond " + asked);

        long deadline = System.currentTimeMillis() + budget;
        boolean confirmed = false;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            int state = device.getBondState();
            if (state != last) {
                System.out.println("state " + name(state));
                last = state;
            }
            if (state == BluetoothDevice.BOND_BONDED) {
                System.out.println("RESULT bonded");
                return;
            }
            if (state == BluetoothDevice.BOND_BONDING && !confirmed) {
                // Returns false until the request actually exists, so this is asked repeatedly
                // rather than once: the request arrives seconds after the bond is started, and a
                // single early attempt is the difference between a paired ring and a timeout.
                boolean ok = device.setPairingConfirmation(true);
                if (ok) {
                    confirmed = true;
                    System.out.println("setPairingConfirmation true");
                }
            }
            if (state == BluetoothDevice.BOND_NONE && confirmed) {
                System.out.println("RESULT refused after confirming");
                return;
            }
            sleep(POLL_MS);
        }
        System.out.println("RESULT gave up in state " + name(device.getBondState())
                + (confirmed ? " (request was answered)" : " (no request ever arrived)"));
    }

    /**
     * `cancelBondProcess` and `removeBond` are hidden. Reflected rather than skipped because the
     * state they clear is exactly the state that makes every later attempt fail, and a phone that
     * cannot be un-stuck without a factory reset is not a phone anybody wants to be handed.
     */
    private static void invokeBoolean(BluetoothDevice device, String method) {
        try {
            Object result = BluetoothDevice.class.getMethod(method).invoke(device);
            System.out.println(method + " " + result);
        } catch (Throwable t) {
            System.out.println(method + " unavailable (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String name(int bondState) {
        switch (bondState) {
            case BluetoothDevice.BOND_NONE:
                return "NONE";
            case BluetoothDevice.BOND_BONDING:
                return "BONDING";
            case BluetoothDevice.BOND_BONDED:
                return "BONDED";
            default:
                return "unknown(" + bondState + ")";
        }
    }

    private Confirm() {
    }
}
