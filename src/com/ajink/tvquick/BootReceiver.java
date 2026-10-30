package com.ajink.tvquick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts the gear bridge after a reboot.
 *
 * <p>The bridge is the only way the gear button can reach the panel, so it has
 * to come back without the user opening anything first.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Log.i(GearBridgeService.TAG, "boot completed, starting the gear bridge");
        GearBridgeService.start(context);
    }
}
