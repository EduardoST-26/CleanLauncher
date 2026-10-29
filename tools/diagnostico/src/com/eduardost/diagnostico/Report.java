package com.eduardost.diagnostico;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the diagnostics report. Every section only reads; a section that fails writes its error
 * and the next one still runs. {@link #summary()} is the short version shown on screen (one photo),
 * the full text goes to the file.
 */
final class Report {
    interface Progress { void step(String message); }

    private interface Part { void run() throws Exception; }

    /** Packages CleanLauncher talks to, plus the factory ones worth knowing about. */
    private static final String[] NWD_PACKAGES = {
            "com.nwd.radio.service", "com.nwd.setting.service", "com.bt.bc03", "com.nwd.kernel",
            "com.nwd.radio", "com.nwd.android.phone", "com.nwd.android.music.ui", "com.nwd.bt.music",
            "com.nwd.audioset", "com.android.launcher", "com.nwd.carad.service", "com.nwd.factory.setting",
            "com.nwd.whole.machine", "com.didi365.miudrive.navi", "com.zjinnova.zlink",
    };

    /** Folders where factory configs or the "reinstall after battery loss" copies usually live. */
    private static final String[] FACTORY_DIRS = {
            "/nwdappconfig", "/nwdappconfig/app", "/system/preinstall", "/system/vendor/preinstall",
            "/vendor/preinstall", "/system/etc/preinstall", "/system/preinstall_apps", "/data/preinstall",
            "/system/usr/preinstall", "/oem", "/system/app", "/system/priv-app", "/vendor/app",
    };

    private final Context c;
    private final StringBuilder out = new StringBuilder(256 * 1024);
    private final StringBuilder sum = new StringBuilder();
    private final Map<String, String> props = new HashMap<>();

    Report(Context c) {
        this.c = c;
    }

    String collect(Progress p) {
        out.append("Diagnóstico Radio 1.0 — ").append(new Date()).append('\n');
        section(p, "Sistema", this::system);
        section(p, "Propiedades (getprop)", this::props);
        section(p, "Memoria", this::memory);
        section(p, "Procesos y servicios", this::processes);
        section(p, "CPU y temperatura", this::cpu);
        section(p, "Almacenamiento", this::storage);
        section(p, "Apps instaladas", this::apps);
        section(p, "Piezas NWD y carpetas de fábrica", this::nwd);
        section(p, "Ajustes del sistema", this::settings);
        section(p, "Red", this::network);
        section(p, "Audio y Bluetooth", this::audio);
        section(p, "Entradas, USB y sensores", this::devices);
        return "==================== RESUMEN ====================\n" + sum + out;
    }

    String summary() {
        return sum.toString();
    }

    private void section(Progress p, String title, Part part) {
        p.step("Revisando: " + title + "…");
        out.append("\n==================== ").append(title.toUpperCase(Locale.ROOT))
                .append(" ====================\n");
        long start = SystemClock.uptimeMillis();
        try {
            part.run();
        } catch (Throwable e) {
            out.append("!! error: ").append(e).append('\n');
        }
        out.append("(").append(SystemClock.uptimeMillis() - start).append(" ms)\n");
    }

    // ---------- Sections ----------

    private void system() {
        kv("Modelo", Build.MODEL);
        kv("Fabricante / marca", Build.MANUFACTURER + " / " + Build.BRAND);
        kv("Dispositivo / producto", Build.DEVICE + " / " + Build.PRODUCT);
        kv("Placa / hardware", Build.BOARD + " / " + Build.HARDWARE);
        kv("Android (dice)", Build.VERSION.RELEASE);
        kv("SDK real", Build.VERSION.SDK_INT + " = Android " + sdkName(Build.VERSION.SDK_INT));
        kv("Parche de seguridad", Build.VERSION.SECURITY_PATCH);
        kv("Compilación", Build.DISPLAY);
        kv("Fingerprint", Build.FINGERPRINT);
        kv("Fecha de compilación", new Date(Build.TIME).toString());
        kv("ABIs", Arrays.toString(Build.SUPPORTED_ABIS));
        long up = SystemClock.elapsedRealtime();
        kv("Encendido desde hace", duration(up));

        DisplayMetrics real = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(real);
        int sw = Math.round(Math.min(real.widthPixels, real.heightPixels) / real.density);
        String screen = real.widthPixels + "x" + real.heightPixels + " px, " + real.densityDpi
                + " dpi, ancho mínimo " + sw + " dp";
        kv("Pantalla", screen);

        line("Radio: " + Build.MODEL + " (" + Build.DEVICE + "), " + Build.HARDWARE);
        line("Android: dice " + Build.VERSION.RELEASE + ", real " + sdkName(Build.VERSION.SDK_INT)
                + " (SDK " + Build.VERSION.SDK_INT + ")");
        line("Encendido desde hace: " + duration(up) + (up > 3 * 86_400_000L ? "  <- no se reinicia" : ""));
        line("Pantalla: " + screen);
    }

    private void props() throws Exception {
        String all = exec("getprop");
        out.append(all);
        for (String l : all.split("\n")) {
            int mid = l.indexOf("]: [");
            if (l.startsWith("[") && mid > 0 && l.endsWith("]")) {
                props.put(l.substring(1, mid), l.substring(mid + 4, l.length() - 1));
            }
        }
        List<String> mcu = new ArrayList<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if ((k.contains("mcu") || k.contains("nwd")) && !e.getValue().isEmpty()) {
                mcu.add(e.getKey() + "=" + e.getValue());
            }
        }
        Collections.sort(mcu);
        for (int i = 0; i < Math.min(mcu.size(), 6); i++) line("Prop: " + mcu.get(i));
        line("adb por red: " + firstNonEmpty(props.get("service.adb.tcp.port"),
                props.get("persist.adb.tcp.port"), "apagado") + " | USB: " + props.get("sys.usb.config"));
    }

    private void memory() {
        out.append(read("/proc/meminfo", 8192));
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        kv("Total", mb(mi.totalMem));
        kv("Disponible", mb(mi.availMem));
        kv("Umbral de memoria baja", mb(mi.threshold));
        kv("Memoria baja ahora", String.valueOf(mi.lowMemory));
        kv("Límite por app", am.getMemoryClass() + " MB (grande " + am.getLargeMemoryClass() + " MB)");
        kv("Equipo de poca RAM", String.valueOf(am.isLowRamDevice()));
        line("RAM: " + mb(mi.availMem) + " libres de " + mb(mi.totalMem) + " ("
                + (100 * mi.availMem / Math.max(1, mi.totalMem)) + "%)" + (mi.lowMemory ? "  <- MEMORIA BAJA" : ""));
    }

    private static final class Proc {
        final int pid;
        final String name;
        final int uid;
        final List<String> services = new ArrayList<>();
        int pssKb;

        Proc(int pid, String name, int uid) {
            this.pid = pid;
            this.name = name;
            this.uid = uid;
        }
    }

    private void processes() {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        // Before Android 8 this lists the services of every app, which is how we see other processes.
        @SuppressWarnings("deprecation")
        List<ActivityManager.RunningServiceInfo> svcs = am.getRunningServices(1000);
        Map<Integer, Proc> byPid = new HashMap<>();
        List<String> idle = new ArrayList<>();
        for (ActivityManager.RunningServiceInfo s : svcs) {
            String svc = s.service.flattenToShortString() + (s.foreground ? " [primer plano]" : "");
            if (s.pid == 0) {
                idle.add(svc);
                continue;
            }
            Proc p = byPid.get(s.pid);
            if (p == null) byPid.put(s.pid, p = new Proc(s.pid, s.process, s.uid));
            p.services.add(svc);
        }
        // Processes the system lets us see under /proc (native daemons included, if not hidden).
        Map<Integer, String> procNames = new HashMap<>();
        String[] dirs = new File("/proc").list();
        if (dirs != null) {
            for (String d : dirs) {
                if (!d.matches("\\d+")) continue;
                String cmd = read("/proc/" + d + "/cmdline", 256).replace('\0', ' ').trim();
                if (!cmd.isEmpty()) procNames.put(Integer.parseInt(d), cmd);
            }
        }
        for (Map.Entry<Integer, String> e : procNames.entrySet()) {
            if (!byPid.containsKey(e.getKey())) byPid.put(e.getKey(), new Proc(e.getKey(), e.getValue(), -1));
        }

        List<Proc> list = new ArrayList<>(byPid.values());
        int[] pids = new int[list.size()];
        for (int i = 0; i < pids.length; i++) pids[i] = list.get(i).pid;
        Debug.MemoryInfo[] mem = am.getProcessMemoryInfo(pids);
        long totalPss = 0;
        for (int i = 0; i < pids.length && i < mem.length; i++) {
            list.get(i).pssKb = mem[i].getTotalPss();
            totalPss += list.get(i).pssKb;
        }
        Collections.sort(list, (a, b) -> b.pssKb - a.pssKb);

        kv("Procesos vistos", list.size() + " (" + procNames.size() + " visibles en /proc)");
        kv("RAM sumada (PSS)", (totalPss / 1024) + " MB");
        for (Proc p : list) {
            out.append(String.format(Locale.US, "%6d KB  pid %-6d uid %-6d %s%n", p.pssKb, p.pid, p.uid, p.name));
            for (String s : p.services) out.append("            - ").append(s).append('\n');
        }
        out.append("\nServicios registrados sin proceso (").append(idle.size()).append("):\n");
        for (String s : idle) out.append("   ").append(s).append('\n');

        line("Procesos: " + list.size() + ", usan " + (totalPss / 1024) + " MB. Los que más pesan:");
        for (int i = 0; i < Math.min(12, list.size()); i++) {
            Proc p = list.get(i);
            line(String.format(Locale.US, "  %4d MB  %s", p.pssKb / 1024, p.name));
        }
    }

    private void cpu() throws Exception {
        out.append(read("/proc/cpuinfo", 16384));
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            kv("cpu" + i, "actual " + read(base + "scaling_cur_freq", 64).trim() + " kHz, máx "
                    + read(base + "cpuinfo_max_freq", 64).trim() + " kHz, " + read(base + "scaling_governor", 64).trim());
        }
        String load = read("/proc/loadavg", 128).trim();
        kv("Carga (loadavg)", load);

        long[] a = cpuTimes();
        Thread.sleep(1000);
        long[] b = cpuTimes();
        int busy = a == null || b == null ? -1
                : (int) (100 - 100 * (b[1] - a[1]) / Math.max(1, b[0] - a[0]));
        kv("CPU en uso (1 s)", busy < 0 ? "no disponible" : busy + "%");

        double hottest = Double.NaN;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                String raw = read(z.getPath() + "/temp", 32).trim();
                String type = read(z.getPath() + "/type", 64).trim();
                try {
                    double t = Double.parseDouble(raw);
                    if (t > 1000) t /= 1000;
                    kv(z.getName(), type + " " + t + " °C");
                    if (Double.isNaN(hottest) || t > hottest) hottest = t;
                } catch (NumberFormatException e) {
                    kv(z.getName(), type + " (sin lectura)");
                }
            }
        }
        line("CPU: " + cores + " núcleos, en uso " + (busy < 0 ? "?" : busy + "%") + ", carga " + load
                + ", temp. máx " + (Double.isNaN(hottest) ? "?" : hottest + " °C"));
    }

    private void storage() {
        Set<String> paths = new LinkedHashSet<>(Arrays.asList("/data", "/system", "/cache",
                Environment.getExternalStorageDirectory().getPath()));
        for (File d : usbRoots()) paths.add(d.getPath());
        for (File d : c.getExternalFilesDirs(null)) if (d != null) paths.add(d.getPath());
        for (String p : paths) {
            try {
                StatFs fs = new StatFs(p);
                String v = mb(fs.getAvailableBytes()) + " libres de " + mb(fs.getTotalBytes());
                kv(p, v);
                if (p.equals("/data")) line("Almacenamiento (/data): " + v);
            } catch (Exception e) {
                kv(p, "no se puede leer");
            }
        }
        List<File> usb = usbRoots();
        line("Pendrives vistos: " + (usb.isEmpty() ? "ninguno" : usb.toString()));
        out.append("\n/proc/mounts:\n").append(read("/proc/mounts", 32768));
    }

    private void apps() {
        PackageManager pm = c.getPackageManager();
        List<PackageInfo> list = pm.getInstalledPackages(
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        List<String> user = new ArrayList<>(), system = new ArrayList<>();
        int disabled = 0, userInSystem = 0;
        for (PackageInfo pi : list) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            String state;
            switch (pm.getApplicationEnabledSetting(pi.packageName)) {
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED: state = "DESACTIVADA"; break;
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER: state = "DESACTIVADA(usuario)"; break;
                case PackageManager.COMPONENT_ENABLED_STATE_ENABLED: state = "activada"; break;
                default: state = ai.enabled ? "normal" : "DESACTIVADA";
            }
            if (state.startsWith("DESACTIVADA")) disabled++;
            if (!sys && ai.sourceDir != null && ai.sourceDir.startsWith("/system")) userInSystem++;
            long size = ai.sourceDir == null ? 0 : new File(ai.sourceDir).length();
            String row = String.format(Locale.US, "%-45s %-28s v%-12s %-20s %6.1f MB  %s  instalada %tF  de %s",
                    pi.packageName, label(pm, ai), pi.versionName, state + (updated ? ",actualizada" : ""),
                    size / 1048576.0, ai.sourceDir, new Date(pi.firstInstallTime), pm.getInstallerPackageName(pi.packageName));
            (sys ? system : user).add(row);
        }
        Collections.sort(user);
        Collections.sort(system);
        out.append("--- Apps del usuario (").append(user.size()).append(") ---\n");
        for (String r : user) out.append(r).append('\n');
        out.append("--- Apps del sistema (").append(system.size()).append(") ---\n");
        for (String r : system) out.append(r).append('\n');

        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        out.append("\nLaunchers instalados:\n");
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            out.append("   ").append(ri.activityInfo.packageName).append('/').append(ri.activityInfo.name).append('\n');
        }
        String current = def == null || def.activityInfo == null ? "?" : def.activityInfo.packageName;
        kv("Launcher predeterminado", current);
        line("Apps: " + user.size() + " del usuario, " + system.size() + " del sistema, " + disabled
                + " desactivadas. Launcher: " + current);
        if (userInSystem > 0) line("Apps del usuario guardadas dentro de /system: " + userInSystem);
    }

    private void nwd() {
        PackageManager pm = c.getPackageManager();
        StringBuilder brief = new StringBuilder();
        for (String pkg : NWD_PACKAGES) {
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES
                        | PackageManager.GET_RECEIVERS | PackageManager.MATCH_DISABLED_COMPONENTS);
                boolean on = pi.applicationInfo.enabled;
                out.append(pkg).append("  v").append(pi.versionName).append(on ? "  activada" : "  DESACTIVADA")
                        .append("  ").append(pi.applicationInfo.sourceDir).append('\n');
                if (pi.services != null) {
                    for (ServiceInfo s : pi.services) {
                        out.append("     servicio ").append(s.name).append(s.exported ? " (exportado)" : "").append('\n');
                    }
                }
                out.append("     receptores: ").append(pi.receivers == null ? 0 : pi.receivers.length).append('\n');
                brief.append(short_(pkg)).append(on ? " ✓  " : " (off)  ");
            } catch (PackageManager.NameNotFoundException e) {
                out.append(pkg).append("  NO INSTALADA\n");
                brief.append(short_(pkg)).append(" ✗  ");
            }
        }
        line("NWD: " + brief.toString().trim());

        for (String dir : FACTORY_DIRS) listDir(new File(dir));
        out.append("\nRaíz del sistema: ").append(Arrays.toString(new File("/").list())).append('\n');
        out.append("/system: ").append(Arrays.toString(new File("/system").list())).append('\n');
    }

    private void settings() {
        for (String table : new String[]{"system", "global", "secure"}) {
            out.append("--- ").append(table).append(" ---\n");
            try (Cursor cur = c.getContentResolver().query(Uri.parse("content://settings/" + table),
                    new String[]{"name", "value"}, null, null, null)) {
                if (cur == null) {
                    out.append("(sin acceso)\n");
                    continue;
                }
                List<String> rows = new ArrayList<>();
                while (cur.moveToNext()) rows.add(cur.getString(0) + "=" + cur.getString(1));
                Collections.sort(rows);
                for (String r : rows) out.append(r).append('\n');
                for (String r : rows) {
                    if (r.startsWith("mcu_current_source=") || r.startsWith("adb_enabled=")) line("Ajuste: " + r);
                }
            } catch (Exception e) {
                out.append("(error: ").append(e).append(")\n");
            }
        }
    }

    private void network() throws Exception {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        kv("Red activa", ni == null ? "ninguna" : ni.getTypeName() + " " + ni.getState());
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wi = wm.getConnectionInfo();
        String ip = "?";
        if (wi != null) {
            int a = wi.getIpAddress();
            ip = (a & 0xFF) + "." + (a >> 8 & 0xFF) + "." + (a >> 16 & 0xFF) + "." + (a >> 24 & 0xFF);
            kv("WiFi", "señal " + wi.getRssi() + " dBm, " + wi.getLinkSpeed() + " Mbps, IP " + ip);
        }
        for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e != null && e.hasMoreElements(); ) {
            NetworkInterface nif = e.nextElement();
            StringBuilder addrs = new StringBuilder();
            for (Enumeration<InetAddress> a = nif.getInetAddresses(); a.hasMoreElements(); ) {
                addrs.append(a.nextElement().getHostAddress()).append(' ');
            }
            kv(nif.getName(), (nif.isUp() ? "activa " : "apagada ") + addrs);
        }
        line("Red: " + (ni == null ? "sin conexión" : ni.getTypeName()) + ", IP " + ip);
    }

    private void audio() {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        kv("Modo", String.valueOf(am.getMode()));
        kv("Sonando música", String.valueOf(am.isMusicActive()));
        String[] names = {"llamada", "sistema", "timbre", "música", "alarma", "notificación", "bt_sco",
                "sistema_forzado", "dtmf", "tts", "accesibilidad"};
        for (int s = 0; s < names.length; s++) {
            try {
                kv("Volumen " + names[s], am.getStreamVolume(s) + "/" + am.getStreamMaxVolume(s));
            } catch (Exception ignored) {
                // Stream not present on this build.
            }
        }
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        String btState = bt == null ? "no hay Bluetooth de Android (el BT lo maneja el módulo NWD)"
                : "Bluetooth de Android " + (bt.isEnabled() ? "encendido" : "apagado") + ", nombre " + bt.getName();
        kv("Bluetooth", btState);
        line("BT: " + btState);
    }

    private void devices() {
        InputManager im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        out.append("Dispositivos de entrada (pantalla táctil, botones, volante):\n");
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d == null) continue;
            out.append(String.format(Locale.US, "   %-40s fuentes 0x%08x  vid %04x pid %04x%n",
                    d.getName(), d.getSources(), d.getVendorId(), d.getProductId()));
        }
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> usb = um == null ? new HashMap<String, UsbDevice>() : um.getDeviceList();
        out.append("Dispositivos USB conectados (").append(usb.size()).append("):\n");
        for (UsbDevice d : usb.values()) {
            out.append(String.format(Locale.US, "   %s  vid %04x pid %04x clase %d  %s%n", d.getDeviceName(),
                    d.getVendorId(), d.getProductId(), d.getDeviceClass(), d.getProductName()));
        }
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        out.append("Sensores:\n");
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            out.append("   ").append(s.getName()).append(" (").append(s.getVendor()).append(")\n");
        }
    }

    // ---------- Helpers ----------

    /** Folders where a pendrive may be mounted on this kind of head unit. */
    static List<File> usbRoots() {
        Set<String> seen = new LinkedHashSet<>();
        for (String parent : new String[]{"/storage", "/mnt/media_rw", "/mnt"}) {
            File[] kids = new File(parent).listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName().toLowerCase(Locale.ROOT);
                boolean candidate = parent.equals("/mnt")
                        ? n.startsWith("usb") || n.startsWith("udisk") || n.startsWith("extsd")
                        : !n.equals("emulated") && !n.equals("self") && !n.equals("sdcard0");
                if (candidate && k.isDirectory()) seen.add(k.getPath());
            }
        }
        List<File> out = new ArrayList<>();
        for (String s : seen) out.add(new File(s));
        return out;
    }

    private void listDir(File dir) {
        String[] names = dir.list();
        if (names == null) {
            if (dir.exists()) out.append('\n').append(dir).append(": existe pero no se puede leer\n");
            return;
        }
        Arrays.sort(names);
        out.append('\n').append(dir).append(" (").append(names.length).append("):\n");
        for (String n : names) {
            File f = new File(dir, n);
            out.append("   ").append(n).append(f.isDirectory() ? "/" : "  " + f.length() + " B").append('\n');
            String lower = n.toLowerCase(Locale.ROOT);
            boolean text = lower.endsWith(".ini") || lower.endsWith(".xml") || lower.endsWith(".txt")
                    || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".prop");
            if (text && f.isFile() && f.length() <= 32 * 1024) {
                out.append("   ----- ").append(n).append(" -----\n").append(read(f.getPath(), 32 * 1024))
                        .append("\n   ----- fin -----\n");
            }
        }
    }

    /** Total and idle jiffies from the first line of /proc/stat. */
    private static long[] cpuTimes() {
        String[] f = read("/proc/stat", 512).split("\n")[0].trim().split("\\s+");
        if (f.length < 5 || !f[0].equals("cpu")) return null;
        long total = 0;
        for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
        long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
        return new long[]{total, idle};
    }

    private static String read(String path, int max) {
        try (InputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[max];
            int n = 0, r;
            while (n < max && (r = in.read(buf, n, max - n)) > 0) n += r;
            return new String(buf, 0, n, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String exec(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(cmd);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            for (String l; (l = r.readLine()) != null; ) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    private static String label(PackageManager pm, ApplicationInfo ai) {
        try {
            return String.valueOf(ai.loadLabel(pm));
        } catch (Exception e) {
            return "?";
        }
    }

    private static String short_(String pkg) {
        switch (pkg) {
            case "com.nwd.radio.service": return "radio";
            case "com.nwd.setting.service": return "ajustes";
            case "com.bt.bc03": return "BT";
            case "com.nwd.kernel": return "kernel";
            default: return pkg.substring(pkg.lastIndexOf('.') + 1);
        }
    }

    private static String sdkName(int sdk) {
        switch (sdk) {
            case 21: return "5.0";
            case 22: return "5.1";
            case 23: return "6.0";
            case 24: return "7.0";
            case 25: return "7.1";
            case 26: return "8.0";
            case 27: return "8.1";
            case 28: return "9";
            case 29: return "10";
            case 30: return "11";
            case 31: return "12";
            case 32: return "12L";
            case 33: return "13";
            default: return "?";
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String mb(long bytes) {
        return bytes >= 1L << 30
                ? String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30))
                : (bytes >> 20) + " MB";
    }

    private static String duration(long ms) {
        long m = ms / 60_000, h = m / 60, d = h / 24;
        return d > 0 ? d + " d " + h % 24 + " h" : h + " h " + m % 60 + " min";
    }

    private void kv(String k, String v) {
        out.append(String.format(Locale.US, "%-26s %s%n", k, v));
    }

    private void line(String s) {
        sum.append(s).append('\n');
    }
}
