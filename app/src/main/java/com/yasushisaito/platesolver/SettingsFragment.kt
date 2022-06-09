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
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.File

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

// Fragment for showing a solution.
class SettingsFragment : Fragment() {
    companion object {
        const val TAG = "SettingsFragment"
        const val EVENT_SET_STATE = 1
        const val EVENT_MESSAGE = 2

        const val INVALID_DOWNLOAD_ID: Long = -1

        // The only star database name in use now.
        const val STARDB_NAME = STARDB_DEFAULT
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

    private var downloadState = DownloadState(DownloadStatus.IDLE, "")

    // The location of h17.zip, etc downloaded from the web.
    private lateinit var starDbZipPath: File
    private lateinit var downloadStarDbButton: Button

    // Circling progress widget shown while download is in progress
    private lateinit var downloadProgressBar: ProgressBar

    // Shows the last download status message.
    private lateinit var downloadMessageTextView: TextView

    private var downloadStarDbRequestId: Long = INVALID_DOWNLOAD_ID
    private lateinit var downloadManager: DownloadManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        starDbZipPath = File(getStarDbDir(requireContext(), STARDB_NAME), "h17.zip")
        downloadStarDbButton = view.findViewById(R.id.button_settings_download_stardb)
        val starDbInstalled =  isStarDbInstalled(requireContext(), STARDB_NAME)

        downloadStarDbButton.setOnClickListener {
            when (downloadState.status) {
                DownloadStatus.IDLE, DownloadStatus.DONE, DownloadStatus.ERROR -> {
                    if (starDbInstalled) {
                        val dialog = AlertDialog.Builder(requireContext())
                            .setMessage("Do you really want to delete the local copy of the database and reinstall?")
                            .setTitle("Reinstall star DB")
                            .setPositiveButton(R.string.reinstall, { dialog, which ->
                                Log.d(TAG, "reinstall start")
                                startDownloadStarDb()
                            })
                            .setNegativeButton(R.string.cancel, { dialog, which ->
                                Log.d(TAG, "reinstall canceled")
                            }).create()
                        dialog.show()
                    } else {
                        startDownloadStarDb()
                    }
                }
                DownloadStatus.DOWNLOADING -> cancelDownloadStarDb()
                else -> {
                    Log.d(TAG, "Ignore download button event with state $downloadState")
                }
            }
        }
        downloadProgressBar = view.findViewById(R.id.progress_settings_download)
        downloadMessageTextView = view.findViewById(R.id.text_settings_download_message)

        if (isStarDbInstalled(requireContext(), STARDB_NAME)) {
            downloadState = DownloadState(DownloadStatus.DONE, "")
        } else if (isStardbZipDownloaded()) {
            downloadState = DownloadState(DownloadStatus.DOWNLOADED, "")
        }
        downloadManager =
            (requireActivity().getSystemService(DOWNLOAD_SERVICE) as? DownloadManager)!!
        updateView()
    }

    private val eventHandler = Handler(Looper.getMainLooper()) { msg: Message ->
        Log.d(TAG, "HANDLER $msg")
        when (msg.what) {
            EVENT_SET_STATE -> {
                val newState = msg.obj as DownloadState
                if (newState.status <= downloadState.status) {
                    // This happens when the user presses "Cancel", and
                    // the download manager sends a message through the intent mechanism async.
                    Log.d(TAG, "Ignoring status message $newState")
                } else {
                    downloadState = newState
                    updateView()
                }
            }
            EVENT_MESSAGE -> {
                Toast.makeText(requireContext(), msg.obj as? String, Toast.LENGTH_LONG).show()
            }
        }
        return@Handler true
    }

    private val probeDownloadStatusRunner = object : Runnable {
        override fun run() {
            if (downloadState.status != DownloadStatus.DOWNLOADING) return
            assert(downloadStarDbRequestId != INVALID_DOWNLOAD_ID)
            val cursor = downloadManager.query(
                DownloadManager.Query().setFilterById(downloadStarDbRequestId)
            )
            eventHandler.postDelayed(this, 2000)
            if (!cursor.moveToFirst()) return
            @SuppressLint("Range")
            val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    eventHandler.removeCallbacks(this)
                    Log.d(TAG, "done downloading $starDbZipPath")
                    assert(starDbZipPath.exists()) {
                        Log.e(TAG, "Download succeeded, but file $starDbZipPath was not created")
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
                    downloadState = DownloadState(DownloadStatus.DOWNLOADING, message)
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

    private fun isStardbZipDownloaded(): Boolean {
        try {
            return starDbZipPath.exists()
        } catch (ex: Exception) {
            Log.d(TAG, "isStardbZipDownloaded: exception $ex")
        }
        return false
    }

    // Start downloading H17.
    private fun startDownloadStarDb() {
        // https://medium.com/@aungkyawmyint_26195/downloading-file-properly-in-android-d8cc28d25aca
        val context = requireContext()
        val srcUrl =
            "https://github.com/yasushi-saito/platesolver-assets/raw/main/${STARDB_NAME}.zip"

        assert(
            downloadStarDbRequestId == INVALID_DOWNLOAD_ID &&
                    downloadState.status == DownloadStatus.IDLE ||
                    downloadState.status == DownloadStatus.DONE ||
                    downloadState.status == DownloadStatus.ERROR
        ) {
            Log.e(TAG, "download id=$downloadStarDbRequestId state=$downloadState")
        }

        getStarDbDir(context, STARDB_NAME).mkdirs()

        val readyPath = getStarDbReadyPath(context, STARDB_NAME)
        if (readyPath.exists()) {
            Log.d(TAG, "deleting $readyPath")
            readyPath.delete()
        }
        if (starDbZipPath.exists()) {
            Log.d(TAG, "deleting $starDbZipPath")
            starDbZipPath.delete()
        }

        val request = DownloadManager.Request(Uri.parse(srcUrl))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)// Visibility of the download Notification
            .setDestinationUri(Uri.fromFile(starDbZipPath))
            .setTitle("h17.zip")
            .setDescription("Downloading h17.zip")
            .setAllowedOverRoaming(false)
        val downloadManager =
            requireActivity().getSystemService(DOWNLOAD_SERVICE) as? DownloadManager
        downloadState = DownloadState(DownloadStatus.DOWNLOADING, "Downloading...")
        downloadStarDbRequestId = downloadManager!!.enqueue(request)
        eventHandler.postDelayed(probeDownloadStatusRunner, 2000)


        Log.d(TAG, "start downloading $srcUrl to $starDbZipPath, id=$downloadStarDbRequestId")
        updateView()
    }

    // Cancel downloading H17.
    //
    // REQUIRES: download is currently running.
    private fun cancelDownloadStarDb() {
        assert(downloadStarDbRequestId != INVALID_DOWNLOAD_ID)
        downloadManager.remove(downloadStarDbRequestId)
        eventHandler.removeCallbacks(probeDownloadStatusRunner)
        Log.d(TAG, "Canceled download")
        downloadState = DownloadState(DownloadStatus.ERROR, "Canceled")
        updateView()
    }

    // Update the state of the button and texts to match downloadState.
    private fun updateView() {
        val setButton = fun(text_id: Int, enabled: Boolean, progress: Int) {
            downloadStarDbButton.setText(text_id)
            downloadStarDbButton.isEnabled = enabled
            downloadProgressBar.visibility = progress
        }
        Log.d(TAG, "updateView downloadState=$downloadState")
        downloadMessageTextView.text = downloadState.message
        when (downloadState.status) {
            DownloadStatus.IDLE -> {
                setButton(R.string.settings_install_stardb, true, View.INVISIBLE)
            }
            DownloadStatus.DOWNLOADING -> {
                setButton(R.string.cancel_download, true, View.VISIBLE)
            }
            DownloadStatus.DOWNLOADED -> {
                setButton(R.string.cancel_download, false, View.VISIBLE)

                downloadState = DownloadState(DownloadStatus.EXPANDING, "Expanding zip file...")
                updateView()
                Thread {
                    try {
                        val cmdline = arrayOf("unzip", "-o", starDbZipPath.path)
                        Log.d(TAG, "Running ${cmdline.joinToString()}")
                        val proc = Runtime.getRuntime().exec(
                            cmdline,
                            null,
                            starDbZipPath.parentFile
                        )
                        val exitCode = proc.waitFor()
                        if (exitCode != 0) {
                            throw Exception("unzip failed with exit code $exitCode")
                        }
                        Log.d(TAG, "unzip finished successfully")
                        val readyPath = File(starDbZipPath.parentFile, "ready.txt")
                        readyPath.writeBytes("ready".toByteArray())
                        sendMessage(DownloadStatus.DONE, "Stardb installed successfully")
                    } catch (ex: Exception) {
                        Log.e(TAG, "expand: exception $ex")
                        sendMessage(DownloadStatus.ERROR, "exception: $ex")
                    }
                }.start()
            }
            DownloadStatus.EXPANDING -> {
                setButton(R.string.cancel_download, false, View.VISIBLE)
            }
            DownloadStatus.DONE -> {
                setButton(R.string.settings_reinstall_stardb, true, View.INVISIBLE)
            }
            DownloadStatus.ERROR -> {
                setButton(R.string.settings_retry_install_stardb, true, View.INVISIBLE)
            }
        }
    }
}
