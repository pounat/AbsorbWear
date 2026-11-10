package com.barnabas.absorb.wear.sleep

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.UserActivityInfo

/** Health Services wakes this with each change of the wearer's activity state. */
class SleepListenerService : PassiveListenerService() {

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        SleepWatch.onActivity(info.userActivityState, info.stateChangeTime.toEpochMilli())
    }

    override fun onPermissionLost() {
        Log.w("AbsorbWearSleep", "activity permission lost, pause when asleep is off")
        SleepWatch.setEnabled(false)
    }
}
