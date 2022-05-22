package com.simplemobiletools.gallery.pro.dialogs

import android.app.Activity
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.value
import com.simplemobiletools.gallery.pro.R
import kotlinx.android.synthetic.main.dialog_login_input.view.*

class CloudLoginDialog(activity: Activity, val callback: (username: String, password: String) -> Unit) {
    var dialog: AlertDialog
    var view: View = activity.layoutInflater.inflate(R.layout.dialog_login_input, null)

    init {

        val builder = AlertDialog.Builder(activity)
            .setPositiveButton(R.string.login) { dialog, which -> dialogConfirmed() }
            .setNegativeButton(R.string.cancel, null)

        dialog = builder.create().apply {
            activity.setupDialogStuff(view, this, R.string.login_title)
        }
    }

    private fun dialogConfirmed() {
        dialog.dismiss()
        callback(view.username_input.value, view.password_input.value)
    }
}

