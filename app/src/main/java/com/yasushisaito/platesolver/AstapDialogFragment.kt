package com.yasushisaito.platesolver

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment

// A dialog shown while astap is running.
// Can be accessed only by the UI thread.
class AstapDialogFragment(
    val imageName: String,
    val fovDeg: Double,
    val searchOrigin: CelestialCoordinate?,
    val onAbort: () -> Unit
) : DialogFragment() {
    // The TextView that shows status messages.
    private var messageWidget: TextView? = null
    private var lastMessage: String? = null
    private var lastError: String? = null

    private lateinit var abortButton: Button
    private lateinit var closeButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogView =
            requireActivity().layoutInflater.inflate(R.layout.fragment_astap_dialog, null)
        progressBar = dialogView.findViewById(R.id.progress_astap_dialog)

        abortButton = dialogView.findViewById(R.id.button_astap_abort)
        abortButton.setOnClickListener { onAbort() }
        abortButton.isEnabled = true

        closeButton = dialogView.findViewById(R.id.button_astap_close)
        closeButton.setOnClickListener { dismiss() }
        closeButton.isEnabled = false

        dialogView.findViewById<TextView>(R.id.text_astap_dialog_image_name).text = imageName
        dialogView.findViewById<TextView>(R.id.text_astap_dialog_fov_deg).text =
            "%.2f".format(fovDeg)
        dialogView.findViewById<TextView>(R.id.text_astap_dialog_search_origin).text = run {
            searchOrigin?.toDisplayString() ?: "Auto"
        }
        messageWidget = dialogView.findViewById(R.id.text_astap_message)
        // Backfill the message in case set{Error,Message} was called before the view was created.
        lastMessage?.let { setMessage(it) }
        lastError?.let { setError(it) }
        return AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
    }

    // Set the status message line.
    fun setMessage(message: String) {
        messageWidget?.let {
            it.text = message
            it.setTextColor(Color.BLACK)
        }
        lastMessage = message
    }

    // Set the status message line with an error color.
    fun setError(message: String) {
        messageWidget?.let {
            it.text = message
            it.setTextColor(Color.RED)
        }
        lastError = message
    }

    fun suspend() {
        abortButton.isEnabled = false
        closeButton.isEnabled = true
        progressBar.visibility = View.GONE
    }
}