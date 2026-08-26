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

    /** How often to say something when nothing has changed, so a live transcript keeps moving. */
    private static final long HEARTBEAT_MS = 3_000L;

    /**
     * How long to wait for the Bluetooth service to be handed over.
     *
     * It arrives on a callback, so this is a wait rather than a call. Three seconds is far longer
     * than it takes when the looper is running, and short enough to fail usefully when it is not.
     */
    private static final long SERVICE_WAIT_MS = 3_000L;

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
        // **The looper has to actually run.**
        //
        // `createBond` came back false with an adapter that reported itself enabled, which is the
        // shape of a `BluetoothAdapter` whose internal binder is still null. That binder does not
        // arrive with the adapter: `IBluetoothManager` hands it over **asynchronously**, through a
        // callback posted to this process's main looper. The looper was prepared and never run, so
        // the callback sat in a queue nobody was reading, `mService` stayed null, and every
        // operation that needs it — `removeBond`, `createBond` — politely returned false.
        //
        // So the work moves to a worker thread and the main thread does its job: loop, dispatch the
        // callbacks, and quit when the worker is done.
        // `systemMain()` prepares the main looper itself, but that is an implementation detail of
        // somebody else's class and `Looper.loop()` below is not survivable without one. Asked for
        // explicitly, and an "already prepared" complaint is the answer we wanted.
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // Already prepared.
        }

        // Framework setup stays on **this** thread, because `ActivityThread.systemMain()` prepares
        // the main looper on whichever thread calls it — doing it on the worker would leave the
        // callbacks queued against a looper the worker is not running either.
        final Context context;
        try {
            context = systemContext();
        } catch (Throwable t) {
            System.out.println("FAILED could not build a context: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }

        final String macFinal = mac;
        final long budgetFinal = budget;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Confirm.run(context, macFinal, budgetFinal);
                } catch (Throwable t) {
                    // Printed rather than thrown: the adb shell service carries no exit status, so
                    // stdout is the only way this reports anything at all.
                    System.out.println("FAILED " + t.getClass().getName() + ": " + t.getMessage());
                } finally {
                    Looper.getMainLooper().quit();
                }
            }
        });
        worker.setName("confirm");
        worker.start();
        // Dispatches the callback that carries the Bluetooth service. Returns when the worker quits.
        Looper.loop();
    }

    /**
     * A context in a process that has none, with its main looper prepared and ready to be run.
     *
     * `app_process` starts a bare VM: no Application, no ActivityThread, and so nothing to ask for a
     * system service. `systemMain()` builds the one the system server's own tools use — and prepares
     * the main looper as it goes, which is why this must happen on the thread that will loop.
     */
    private static Context systemContext() throws Exception {
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
        return context;
    }

    static void run(Context context, String mac, long budget) throws Exception {

        BluetoothAdapter adapter = adapter(context);
        if (adapter == null) {
            System.out.println("FAILED no bluetooth adapter in this process");
            return;
        }

        // **What this does not do is refuse to continue.**
        //
        // This used to stop here on `!isEnabled()`, and that guard was the only thing standing
        // between a phone with Bluetooth plainly on and a bond: in a process with no ActivityThread
        // the adapter cannot always reach the Bluetooth service, and when it cannot, `isEnabled()`
        // answers **false** rather than throwing. A defensive check that cannot tell the difference
        // between "off" and "cannot see" is worse than no check — the operations below fail with
        // their own reasons if Bluetooth really is off, and those reasons are true.
        //
        // The state is printed instead, next to a reading nobody can get wrong: the global setting,
        // which says what the *phone* thinks and is readable by anyone.
        System.out.println("adapter state " + adapter.getState()
                + " enabled=" + adapter.isEnabled()
                + " setting bluetooth_on=" + globalInt(context, "bluetooth_on"));
        // Wait for the service the callback delivers, and prove it with a call that needs it.
        // `getBondedDevices` returns null — not an empty set — while the binder is missing, which
        // makes it the cheapest honest test there is.
        long until = System.currentTimeMillis() + SERVICE_WAIT_MS;
        boolean ready = false;
        while (System.currentTimeMillis() < until) {
            try {
                if (adapter.getBondedDevices() != null) {
                    ready = true;
                    break;
                }
            } catch (Throwable ignored) {
                // Not up yet.
            }
            sleep(200);
        }
        System.out.println("bluetooth service " + (ready ? "reachable" : "NOT reachable"));
        if (!ready) {
            System.out.println("FAILED the adapter exists but its service never arrived — nothing "
                    + "that changes a bond can work from here");
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
        long spoke = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            int state = device.getBondState();
            if (state != last) {
                System.out.println("state " + name(state));
                last = state;
                spoke = System.currentTimeMillis();
            }
            // **Say something every few seconds even when nothing has happened.**
            //
            // The wait here is the point: the platform raises its pairing request several seconds
            // after the bond starts, and this has to be sitting here when it does. But the output
            // is read live by whoever ran it, and twenty seconds of silence in a transcript reads
            // as a hung command — which is what the last one was reported as. A countdown says the
            // difference between waiting and stuck.
            long now = System.currentTimeMillis();
            if (now - spoke >= HEARTBEAT_MS) {
                System.out.println("waiting… " + ((deadline - now) / 1000) + "s left, state "
                        + name(state));
                spoke = now;
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

    /**
     * Reach the Bluetooth adapter from a process the framework never finished setting up.
     *
     * ### Why this needs three attempts
     *
     * Since Android 13 the Bluetooth stack lives in its own mainline module, and the wrapper that
     * answers `getSystemService(BLUETOOTH_SERVICE)` is registered by module initialisation that
     * runs when an *application* starts. `app_process` starting a plain main class is not an
     * application, so that registration may never have happened — and the symptom is not an
     * exception, it is `null`, twice, from both of the routes anybody would try first.
     *
     * The third route is the one {@code BluetoothManager} uses on the inside:
     * {@code BluetoothAdapter.createAdapter(AttributionSource)}, which goes straight to the
     * {@code bluetooth_manager} binder without needing any of the module plumbing above it. It is
     * hidden, so it is reflected; the attribution source is built for this process's real uid and
     * for {@code com.android.shell}, which is the package whose privileges the far side will check.
     *
     * Every step says whether it worked, because "no adapter" on its own does not say which of
     * three quite different things went wrong.
     */
    private static BluetoothAdapter adapter(Context context) {
        System.out.println("bluetooth_manager binder: " + hasService("bluetooth_manager"));
        System.out.println("bluetooth binder: " + hasService("bluetooth"));
        // Must come first: without it the three routes below all return null, politely.
        installServiceManager();

        BluetoothManager manager = null;
        try {
            manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        } catch (Throwable t) {
            System.out.println("getSystemService threw " + t.getClass().getSimpleName());
        }
        System.out.println("manager: " + (manager == null ? "null" : "ok"));
        if (manager != null) {
            BluetoothAdapter fromManager = manager.getAdapter();
            System.out.println("manager.getAdapter(): " + (fromManager == null ? "null" : "ok"));
            if (fromManager != null) return fromManager;
        }

        BluetoothAdapter fromStatic = null;
        try {
            fromStatic = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            System.out.println("getDefaultAdapter threw " + t.getClass().getSimpleName());
        }
        System.out.println("getDefaultAdapter(): " + (fromStatic == null ? "null" : "ok"));
        if (fromStatic != null) return fromStatic;

        // The route with no module plumbing in front of it.
        try {
            Object source = new android.content.AttributionSource.Builder(
                    android.os.Process.myUid())
                    .setPackageName("com.android.shell")
                    .build();
            java.lang.reflect.Method create = BluetoothAdapter.class.getMethod(
                    "createAdapter", android.content.AttributionSource.class);
            BluetoothAdapter built = (BluetoothAdapter) create.invoke(null, source);
            System.out.println("createAdapter(): " + (built == null ? "null" : "ok"));
            return built;
        } catch (Throwable t) {
            System.out.println("createAdapter threw " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
        return null;
    }

    /**
     * Wire this process up to the Bluetooth mainline module, which nobody has done for it.
     *
     * ### Why all three routes returned null
     *
     * They returned null *without throwing*, on a phone with the `bluetooth_manager` binder present
     * and a `BluetoothManager` that constructed fine. That combination is the tell. Since Android 13
     * the Bluetooth stack is a mainline module, and `BluetoothAdapter.createAdapter()` does not look
     * the binder up itself — it asks
     * {@code BluetoothFrameworkInitializer.getBluetoothServiceManager()} for it. That object is
     * installed by {@code ActivityThread} while an **application** starts.
     *
     * `app_process` running a plain main class is not an application, so the setter is never called,
     * the manager is null, and every route politely reports nothing. The binder was reachable the
     * whole time; there was simply nothing pointing at it.
     *
     * So this constructs the service manager and installs it, exactly as application startup would.
     * Both plausible package names are tried, the constructor is taken as declared because it is not
     * public API, and being installed twice is a success rather than a failure — the framework
     * throws {@link IllegalStateException} for the second call, which means somebody got there
     * first, which is the state we wanted.
     */
    private static void installServiceManager() {
        Class<?> initializer = null;
        for (String name : new String[] {
                "android.bluetooth.BluetoothFrameworkInitializer",
        }) {
            try {
                initializer = Class.forName(name);
            } catch (Throwable ignored) {
                // Tried the next one.
            }
        }
        if (initializer == null) {
            System.out.println("no BluetoothFrameworkInitializer on this build");
            return;
        }
        Class<?> managerClass = null;
        for (String name : new String[] {
                "android.os.BluetoothServiceManager",
                "android.bluetooth.BluetoothServiceManager",
        }) {
            try {
                managerClass = Class.forName(name);
                break;
            } catch (Throwable ignored) {
                // Tried the next one.
            }
        }
        if (managerClass == null) {
            System.out.println("no BluetoothServiceManager class on this build");
            return;
        }
        try {
            java.lang.reflect.Constructor<?> ctor = managerClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object manager = ctor.newInstance();
            java.lang.reflect.Method setter =
                    initializer.getMethod("setBluetoothServiceManager", managerClass);
            setter.setAccessible(true);
            setter.invoke(null, manager);
            System.out.println("service manager installed");
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            if (cause instanceof IllegalStateException) {
                // Already installed — which is the outcome this was for.
                System.out.println("service manager was already installed");
            } else {
                System.out.println("could not install the service manager: "
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
    }

    /** Whether a system service of that name is registered at all. */
    private static String hasService(String name) {
        try {
            java.lang.reflect.Method get = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class);
            return get.invoke(null, name) == null ? "missing" : "present";
        } catch (Throwable t) {
            return "unaskable (" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * A global setting, read straight out of the provider.
     *
     * A second opinion on whether Bluetooth is on, from a source that does not depend on this
     * process having a working adapter. `1` with an adapter reporting disabled is the exact shape of
     * the bug this replaced.
     */
    private static String globalInt(Context context, String key) {
        try {
            return String.valueOf(
                    android.provider.Settings.Global.getInt(context.getContentResolver(), key, -1));
        } catch (Throwable t) {
            return "unreadable";
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
