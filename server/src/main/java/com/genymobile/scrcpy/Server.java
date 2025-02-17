package com.genymobile.scrcpy;

import com.genymobile.scrcpy.wrappers.ContentProvider;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.text.TextUtils;

import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class Server {

    public static final String SERVER_DIR = "/data/local/tmp";
    public static final String[] NATIVE_LIBRARIES = {
        "libjnidispatch.so",
    };

    private Server() {
        // not instantiable
    }

    private static void scrcpy(Options options) throws IOException {
        Ln.i("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        Ln.i("Supported ABIs: " + TextUtils.join(", ", Build.SUPPORTED_ABIS));
        final Device device = new Device(options);
        List<CodecOption> codecOptions = CodecOption.parse(options.getCodecOptions());

        for (String lib : NATIVE_LIBRARIES) {
            for (String abi : Build.SUPPORTED_ABIS) {
                try {
                    InputStream resStream = Server.class.getResourceAsStream("/lib/" + abi + "/" + lib);
                    FileOutputStream fileStream = new FileOutputStream(SERVER_DIR + "/" + lib);

                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = resStream.read(buffer)) > 0) {
                        fileStream.write(buffer, 0, length);
                    }

                    resStream.close();
                    fileStream.close();

                    break;
                } catch (Exception e) {
                    Ln.e("Could not extract native library for " + abi, e);
                }
            }
        }

        boolean mustDisableShowTouchesOnCleanUp = false;
        int restoreStayOn = -1;
        if (options.getShowTouches() || options.getStayAwake()) {
            try (ContentProvider settings = Device.createSettingsProvider()) {
                if (options.getShowTouches()) {
                    String oldValue = settings.getAndPutValue(ContentProvider.TABLE_SYSTEM, "show_touches", "1");
                    // If "show touches" was disabled, it must be disabled back on clean up
                    mustDisableShowTouchesOnCleanUp = !"1".equals(oldValue);
                }

                if (options.getStayAwake()) {
                    int stayOn = BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB | BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    String oldValue = settings.getAndPutValue(ContentProvider.TABLE_GLOBAL, "stay_on_while_plugged_in", String.valueOf(stayOn));
                    try {
                        restoreStayOn = Integer.parseInt(oldValue);
                        if (restoreStayOn == stayOn) {
                            // No need to restore
                            restoreStayOn = -1;
                        }
                    } catch (NumberFormatException e) {
                        restoreStayOn = 0;
                    }
                }
            }
        }

        CleanUp.configure(options.getDisplayId(), restoreStayOn, mustDisableShowTouchesOnCleanUp, true, options.getPowerOffScreenOnClose());

        boolean tunnelForward = options.isTunnelForward();

        try (DesktopConnection connection = DesktopConnection.open(device, tunnelForward)) {
            ScreenEncoder screenEncoder = new ScreenEncoder(options.getSendFrameMeta(), options.getBitRate(), options.getMaxFps(), codecOptions,
                    options.getEncoderName());

            Thread controllerThread = null;
            Thread deviceMessageSenderThread = null;
            if (options.getControl()) {
                final Controller controller = new Controller(device, connection);

                // asynchronous
                controllerThread = startController(controller);
                deviceMessageSenderThread = startDeviceMessageSender(controller.getSender());

                device.setClipboardListener(new Device.ClipboardListener() {
                    @Override
                    public void onClipboardTextChanged(String text) {
                        controller.getSender().pushClipboardText(text);
                    }
                });
            }

            try {
                // synchronous
                screenEncoder.streamScreen(device, connection.getVideoFd());
            } catch (IOException e) {
                // this is expected on close
                Ln.d("Screen streaming stopped");
            } finally {
                if (controllerThread != null) {
                    controllerThread.interrupt();
                }
                if (deviceMessageSenderThread != null) {
                    deviceMessageSenderThread.interrupt();
                }
            }
        }
    }

    private static Thread startController(final Controller controller) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    controller.control();
                } catch (IOException e) {
                    // this is expected on close
                    Ln.d("Controller stopped");
                }
            }
        });
        thread.start();
        return thread;
    }

    private static Thread startDeviceMessageSender(final DeviceMessageSender sender) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sender.loop();
                } catch (IOException | InterruptedException e) {
                    // this is expected on close
                    Ln.d("Device message sender stopped");
                }
            }
        });
        thread.start();
        return thread;
    }

    private static Options createOptions(String... args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Missing client version");
        }

        String clientVersion = args[0];
        if (!clientVersion.equals(BuildConfig.VERSION_NAME)) {
            throw new IllegalArgumentException(
                    "The server version (" + BuildConfig.VERSION_NAME + ") does not match the client " + "(" + clientVersion + ")");
        }

        final int expectedParameters = 16;
        if (args.length != expectedParameters) {
            throw new IllegalArgumentException("Expecting " + expectedParameters + " parameters");
        }

        Options options = new Options();

        Ln.Level level = Ln.Level.valueOf(args[1].toUpperCase(Locale.ENGLISH));
        options.setLogLevel(level);

        int maxSize = Integer.parseInt(args[2]) & ~7; // multiple of 8
        options.setMaxSize(maxSize);

        int bitRate = Integer.parseInt(args[3]);
        options.setBitRate(bitRate);

        int maxFps = Integer.parseInt(args[4]);
        options.setMaxFps(maxFps);

        int lockedVideoOrientation = Integer.parseInt(args[5]);
        options.setLockedVideoOrientation(lockedVideoOrientation);

        // use "adb forward" instead of "adb tunnel"? (so the server must listen)
        boolean tunnelForward = Boolean.parseBoolean(args[6]);
        options.setTunnelForward(tunnelForward);

        Rect crop = parseCrop(args[7]);
        options.setCrop(crop);

        boolean sendFrameMeta = Boolean.parseBoolean(args[8]);
        options.setSendFrameMeta(sendFrameMeta);

        boolean control = Boolean.parseBoolean(args[9]);
        options.setControl(control);

        int displayId = Integer.parseInt(args[10]);
        options.setDisplayId(displayId);

        boolean showTouches = Boolean.parseBoolean(args[11]);
        options.setShowTouches(showTouches);

        boolean stayAwake = Boolean.parseBoolean(args[12]);
        options.setStayAwake(stayAwake);

        String codecOptions = args[13];
        options.setCodecOptions(codecOptions);

        String encoderName = "-".equals(args[14]) ? null : args[14];
        options.setEncoderName(encoderName);

        boolean powerOffScreenOnClose = Boolean.parseBoolean(args[15]);
        options.setPowerOffScreenOnClose(powerOffScreenOnClose);

        return options;
    }

    private static Rect parseCrop(String crop) {
        if ("-".equals(crop)) {
            return null;
        }
        // input format: "width:height:x:y"
        String[] tokens = crop.split(":");
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Crop must contains 4 values separated by colons: \"" + crop + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        int x = Integer.parseInt(tokens[2]);
        int y = Integer.parseInt(tokens[3]);
        return new Rect(x, y, x + width, y + height);
    }

    private static void suggestFix(Throwable e) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (e instanceof MediaCodec.CodecException) {
                MediaCodec.CodecException mce = (MediaCodec.CodecException) e;
                if (mce.getErrorCode() == 0xfffffc0e) {
                    Ln.e("The hardware encoder is not able to encode at the given definition.");
                    Ln.e("Try with a lower definition:");
                    Ln.e("    scrcpy -m 1024");
                }
            }
        }
        if (e instanceof InvalidDisplayIdException) {
            InvalidDisplayIdException idie = (InvalidDisplayIdException) e;
            int[] displayIds = idie.getAvailableDisplayIds();
            if (displayIds != null && displayIds.length > 0) {
                Ln.e("Try to use one of the available display ids:");
                for (int id : displayIds) {
                    Ln.e("    scrcpy --display " + id);
                }
            }
        } else if (e instanceof InvalidEncoderException) {
            InvalidEncoderException iee = (InvalidEncoderException) e;
            MediaCodecInfo[] encoders = iee.getAvailableEncoders();
            if (encoders != null && encoders.length > 0) {
                Ln.e("Try to use one of the available encoders:");
                for (MediaCodecInfo encoder : encoders) {
                    Ln.e("    scrcpy --encoder '" + encoder.getName() + "'");
                }
            }
        }
    }

    public static void main(String... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Ln.e("Exception on thread " + t, e);
                suggestFix(e);
            }
        });

        Options options = createOptions(args);

        Ln.initLogLevel(options.getLogLevel());

        scrcpy(options);
    }
}
