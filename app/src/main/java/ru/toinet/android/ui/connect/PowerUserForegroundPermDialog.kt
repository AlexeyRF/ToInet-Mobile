package ru.toinet.android.ui.connect

import ru.toinet.android.R
import ru.toinet.android.ui.core.RequestScheduleExactAlarmDialogFragment

class PowerUserForegroundPermDialog : RequestScheduleExactAlarmDialogFragment() {
    override fun getTitleId(): Int = R.string.power_user_mode_permission
    override fun getMessageId(): Int = R.string.power_user_mode_permission_msg
}