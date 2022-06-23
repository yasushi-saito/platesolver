package com.yasushisaito.platesolver

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context.DOWNLOAD_SERVICE
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit

// Given a DownloadManager error code from COLUMN_REASON, report its
// human readable description.
private fun getErrorReasonString(code: Int): String {
    return when (code) {
        DownloadManager.ERROR_FILE_ERROR -> "Storage problem"
        DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download"
        DownloadManager.ERROR_UNKNOWN -> "Unknown error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient space"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage device not found"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP error code"
        else -> "Error code %d".format(code)
    }
}

private class Downloader(
    private val activity: FragmentActivity,
    private val dbName: String,
    // Button for starting a download.
    private val button: Button,
    // Circling progress widget shown while download is in progress
    private val progressBar: ProgressBar,
    // The location of h17.zip, etc downloaded from the web.
    private val downloadMessageTextView: TextView,
) {
    companion object {
        const val TAG = "Downloader"
        const val INVALID_DOWNLOAD_ID: Long = -1

        // List of events sent from a DB installation thread to the UI thread.
        //
        // Change the downloadState. Arg is DownloadState.
        const val EVENT_SET_STATE = 1
        // Set the download status message. Arg is string.
        const val EVENT_MESSAGE = 2
        // Switch to RunAstapFragment. Arg is unused.
        const val EVENT_SWITCH_TO_RUN_ASTAP_FRAGMENT = 3
    }
    private enum class DownloadStatus {
        IDLE,
        DOWNLOADING,
        DOWNLOADED,
        EXPANDING,
        DONE,
        ERROR
    }

    private data class DownloadState(val status: DownloadStatus, val message: String)
    private val context = activity
    private val downloadManager: DownloadManager
    private val zipPath = getStarDbZipPath(context, dbName)
    private var state = DownloadState(DownloadStatus.IDLE, "")
    private var requestId: Long = INVALID_DOWNLOAD_ID

    private val eventHandler = Handler(Looper.getMainLooper()) { msg: Message ->
        Log.d(TAG, "HANDLER $msg")
        when (msg.what) {
            EVENT_SET_STATE -> {
                val newState = msg.obj as DownloadState
                if (newState.status <= state.status) {
                    // This happens when the user presses "Cancel", and
                    // the download manager sends a message through the intent mechanism async.
                    Log.d(TAG, "Ignoring status message $newState")
                } else {
                    state = newState
                    updateView()
                }
            }
            EVENT_MESSAGE -> {
                Toast.makeText(context, msg.obj as? String, Toast.LENGTH_LONG).show()
            }
            EVENT_SWITCH_TO_RUN_ASTAP_FRAGMENT -> {
                val fm = activity.supportFragmentManager
                val frag = findOrCreateFragment(fm, FragmentType.RunAstap, null)
                val fragName = getFragmentType(frag).name
                fm.commit {
                    replace(R.id.content_frame, frag, fragName)
                    setReorderingAllowed(true)
                    addToBackStack(fragName)
                }
            }
        }
        return@Handler true
    }

    init {
        button.setOnClickListener {
            when (state.status) {
                DownloadStatus.IDLE, DownloadStatus.DONE, DownloadStatus.ERROR -> {
                    if (isStarDbInstalled(context, dbName)) {
                        val dialog = AlertDialog.Builder(context)
                            .setMessage("Do you really want to delete the local copy of the database and reinstall?")
                            .setTitle("Reinstall star DB")
                            .setPositiveButton(R.string.reinstall) { _, _ -> startDownloadStarDb() }
                            .setNegativeButton(R.string.cancel) { _, _ -> }
                            .create()
                        dialog.show()
                    } else {
                        startDownloadStarDb()
                    }
                }
                DownloadStatus.DOWNLOADING -> cancelDownloadStarDb()
                else -> {
                    Log.d(TAG, "Ignore download button event with state $state")
                }
            }
        }

        if (isStarDbInstalled(context, dbName)) {
            state = DownloadState(DownloadStatus.DONE, "")
        } else if (isStardbZipDownloaded()) {
            state = DownloadState(DownloadStatus.DOWNLOADED, "")
        }
        downloadManager = (activity.getSystemService(DOWNLOAD_SERVICE) as? DownloadManager)!!
        updateView()
    }

    private fun isStardbZipDownloaded(): Boolean {
        try {
            return zipPath.exists()
        } catch (ex: Exception) {
            Log.d(TAG, "isStardbZipDownloaded: exception $ex")
        }
        return false
    }

    // Start downloading a database dbName.
    private fun startDownloadStarDb() {
        // https://medium.com/@aungkyawmyint_26195/downloading-file-properly-in-android-d8cc28d25aca
        val srcUrl =
            "https://github.com/yasushi-saito/platesolver-assets/raw/main/${dbName}.zip"

        assert(
            requestId == INVALID_DOWNLOAD_ID &&
                    state.status == DownloadStatus.IDLE ||
                    state.status == DownloadStatus.DONE ||
                    state.status == DownloadStatus.ERROR
        ) {
            Log.e(TAG, "download id=$requestId state=$state")
        }

        getStarDbDir(context, dbName).mkdirs()

        val readyPath = getStarDbReadyPath(context, dbName)
        if (readyPath.exists()) {
            Log.d(TAG, "deleting $readyPath")
            readyPath.delete()
        }
        val dbZipPath =getStarDbZipPath(context, dbName)
        if (dbZipPath.exists()) {
            Log.d(TAG, "deleting $dbZipPath")
            dbZipPath.delete()
        }

        val request = DownloadManager.Request(Uri.parse(srcUrl))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)// Visibility of the download Notification
            .setDestinationUri(Uri.fromFile(dbZipPath))
            .setTitle("h17.zip")
            .setDescription("Downloading ${dbName}.zip")
            .setAllowedOverRoaming(false)
        val downloadManager = context.getSystemService(DOWNLOAD_SERVICE) as? DownloadManager
        state = DownloadState(DownloadStatus.DOWNLOADING, "Downloading...")
        requestId = downloadManager!!.enqueue(request)
        eventHandler.postDelayed(probeDownloadStatusRunner, 2000)


        Log.d(TAG, "start downloading $srcUrl")
        updateView()
    }
    // Cancel downloading H17.
    //
    // REQUIRES: download is currently running.
    private fun cancelDownloadStarDb() {
        assert(requestId != INVALID_DOWNLOAD_ID)
        downloadManager.remove(requestId)
        eventHandler.removeCallbacks(probeDownloadStatusRunner)
        Log.d(TAG, "Canceled download")
        state = DownloadState(DownloadStatus.ERROR, "Canceled")
        updateView()
    }

    // Update the state of the button and texts to match downloadState.
    private fun updateView() {
        val setEditable = fun(text_id: Int, enabled: Boolean, progress: Int) {
            button.setText(text_id)
            button.isEnabled = enabled
            progressBar.visibility = progress
        }
        Log.d(TAG, "updateView downloadState=$state")
        downloadMessageTextView.text = state.message
        when (state.status) {
            DownloadStatus.IDLE -> {
                setEditable(R.string.settings_install_stardb, true, View.INVISIBLE)
            }
            DownloadStatus.DOWNLOADING -> {
                setEditable(R.string.cancel_download, true, View.VISIBLE)
            }
            DownloadStatus.DOWNLOADED -> {
                setEditable(R.string.cancel_download, false, View.VISIBLE)

                state = DownloadState(DownloadStatus.EXPANDING, "Expanding zip file...")
                updateView()
                Thread {
                    try {
                        val cmdline = arrayOf("unzip", "-o", zipPath.path)
                        Log.d(TAG, "Running ${cmdline.joinToString()}")
                        val proc = Runtime.getRuntime().exec(
                            cmdline,
                            null,
                            zipPath.parentFile
                        )
                        val exitCode = proc.waitFor()
                        if (exitCode != 0) {
                            throw Exception("unzip failed with exit code $exitCode")
                        }
                        Log.d(TAG, "unzip finished successfully")
                        val readyPath = getStarDbReadyPath(context, dbName)
                        readyPath.writeBytes("ready".toByteArray())
                        sendMessage(DownloadStatus.DONE, "Stardb installed successfully")
                        //eventHandler.sendMessage(
                        //Message.obtain(eventHandler, EVENT_SWITCH_TO_RUN_ASTAP_FRAGMENT, null)
                        //)
                    } catch (ex: Exception) {
                        Log.e(TAG, "expand: exception $ex")
                        sendMessage(DownloadStatus.ERROR, "exception: $ex")
                    }
                }.start()
            }
            DownloadStatus.EXPANDING -> {
                setEditable(R.string.cancel_download, false, View.VISIBLE)
            }
            DownloadStatus.DONE -> {
                setEditable(R.string.settings_reinstall_stardb, true, View.INVISIBLE)
            }
            DownloadStatus.ERROR -> {
                setEditable(R.string.settings_retry_install_stardb, true, View.INVISIBLE)
            }
        }
    }
    private val probeDownloadStatusRunner = object : Runnable {
        override fun run() {
            if (state.status != DownloadStatus.DOWNLOADING) return
            assert(requestId != INVALID_DOWNLOAD_ID)
            val cursor = downloadManager.query(
                DownloadManager.Query().setFilterById(requestId)
            )
            eventHandler.postDelayed(this, 2000)
            if (!cursor.moveToFirst()) return
            when (cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    eventHandler.removeCallbacks(this)
                    Log.d(TAG, "done downloading $zipPath")
                    assert(zipPath.exists()) {
                        Log.e(TAG, "Download succeeded, but file $zipPath was not created")
                    }
                    sendMessage(DownloadStatus.DOWNLOADED, "Download finished")
                }
                DownloadManager.STATUS_FAILED -> {
                    @SuppressLint("Range")
                    val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                    Log.d(TAG, "DownloadManager error=$reason")
                    sendMessage(DownloadStatus.ERROR, "Error: " + getErrorReasonString(reason))
                }
                DownloadManager.STATUS_RUNNING -> {
                    @SuppressLint("Range")
                    val totalBytes =
                        cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    @SuppressLint("Range")
                    val downloadedBytes =
                        cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))

                    val message = if (totalBytes > 0) {
                        "Downloaded %.2fMiB of %.2fMiB".format(
                            downloadedBytes.toDouble() / (1 shl 20),
                            totalBytes.toDouble() / (1 shl 20)
                        )
                    } else if (downloadedBytes > 0){
                        "Downloaded %2fMiB".format(
                            downloadedBytes.toDouble() / (1 shl 20),
                        )
                    } else {
                        "Downloading..."
                    }
                    Log.d(TAG, "downloaded $downloadedBytes / $totalBytes")
                    state = DownloadState(DownloadStatus.DOWNLOADING, message)
                    updateView()
                }
            }
        }
    }
    private fun sendMessage(newStatus: DownloadStatus, message: String) {
        eventHandler.sendMessage(
            Message.obtain(eventHandler, EVENT_SET_STATE, DownloadState(newStatus, message))
        )
    }

}

// Fragment for showing a solution.
class SettingsFragment : Fragment() {
    companion object {
        const val TAG = "SettingsFragment"

        // List of events sent from a DB installation thread to the UI thread.
        //
        // Change the downloadState. Arg is DownloadState.
        const val EVENT_SET_STATE = 1
        // Set the download status message. Arg is string.
        const val EVENT_MESSAGE = 2
        // Switch to RunAstapFragment. Arg is unused.
        const val EVENT_SWITCH_TO_RUN_ASTAP_FRAGMENT = 3

        const val INVALID_DOWNLOAD_ID: Long = -1

        // The only star database name in use now.
        // const val STARDB_NAME = STARDB_H17
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    private enum class DownloadStatus {
        IDLE,
        DOWNLOADING,
        DOWNLOADED,
        EXPANDING,
        DONE,
        ERROR
    }

    private data class DownloadState(val status: DownloadStatus, val message: String)

    private lateinit var h18Downloader: Downloader
    private lateinit var v17Downloader: Downloader

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // starDbZipPath = getStarDbZipPath(requireContext(), STARDB_NAME)

        val versionText = view.findViewById<TextView>(R.id.text_settings_version)
        versionText.setText("Platesolver version ${BuildConfig.VERSION_NAME}")

        h18Downloader = Downloader(
            requireActivity(),
            STARDB_H18,
            view.findViewById<Button>(R.id.button_settings_download_h18),
            view.findViewById<ProgressBar>(R.id.progress_settings_download_h18),
            view.findViewById<TextView>(R.id.text_settings_download_message_h18)
        )

        v17Downloader = Downloader(
            requireActivity(),
            STARDB_V17,
            view.findViewById<Button>(R.id.button_settings_download_v17),
            view.findViewById<ProgressBar>(R.id.progress_settings_download_v17),
            view.findViewById<TextView>(R.id.text_settings_download_message_v17)
        )
    }
}
