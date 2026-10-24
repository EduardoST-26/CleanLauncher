package com.simple.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Minimal client for the factory Bluetooth module service (com.bt.bc03).
 * Raw Binder calls using the transaction codes of its com.bt.BTFeature AIDL interface.
 */
public class BtClient implements ServiceConnection {

    private static final String DESCRIPTOR = "com.bt.BTFeature";
    private static final int TX_IS_CONNECT_DEVICE = 6;
    private static final int TX_DIAL = 13;
    private static final int TX_PLAY_CONTROL = 23;
    private static final int TX_IS_MUSIC_PLAYING = 24;
    private static final int TX_GET_CONNECT_DEVICE = 38;
    private static final int TX_QUERY_ID3 = 60;

    // BTConstant.MusicCommand
    public static final int PLAY = 0, PAUSE = 1, NEXT = 2, PREVIOUS = 3;

    private IBinder binder;
    private Runnable onConnected;

    public void bind(Context c, Runnable onConnected) {
        this.onConnected = onConnected;
        Intent i = new Intent("com.bt.ACTION_START_SERVICE");
        i.setPackage("com.bt.bc03");
        try {
            c.bindService(i, this, Context.BIND_AUTO_CREATE);
        } catch (SecurityException ignored) { }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        binder = service;
        if (onConnected != null) onConnected.run();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        binder = null;
    }

    public boolean isBound() {
        return binder != null && binder.isBinderAlive();
    }

    public boolean isPhoneConnected() {
        return callBool(TX_IS_CONNECT_DEVICE);
    }

    public boolean isMusicPlaying() {
        return callBool(TX_IS_MUSIC_PLAYING);
    }

    public void playControl(int command) {
        if (!isBound()) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(command);
            binder.transact(TX_PLAY_CONTROL, data, reply, 0);
            reply.readException();
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** The service answers with a com.bt.ACTION_AVRCP_MUSIC_ID3 broadcast. */
    public void queryId3() {
        if (!isBound()) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TX_QUERY_ID3, data, reply, 0);
            reply.readException();
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Name of the connected phone, or null. */
    public String deviceName() {
        if (!isBound()) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TX_GET_CONNECT_DEVICE, data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) return null;
            reply.readString(); // address
            return reply.readString();
        } catch (Exception e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public boolean dial(String number) {
        if (!isBound()) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(number);
            binder.transact(TX_DIAL, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callBool(int code) {
        if (!isBound()) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
