package com.yasushisaito.platesolver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.DOWNLOAD_SERVICE
import android.content.Intent
import android.content.IntentFilter
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

// Fragment for showing a solution.
class SettingsFragment : Fragment() {
    companion object {
        const val TAG = "SettingsFragment"
        const val EVENT_SET_STATE = 1
        const val EVENT_MESSAGE = 2

        const val INVALID_DOWNLOAD_ID: Long = -1
        const val INVALID_DOWNLOAD_ID_1: Long = -2

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        starDbZipPath = File(getStarDbDir(requireContext(), STARDB_NAME), "h17.zip")
        downloadStarDbButton = view.findViewById(R.id.button_settings_download_stardb)
        downloadStarDbButton.setOnClickListener {
            when (downloadState.status) {
                DownloadStatus.IDLE, DownloadStatus.DONE, DownloadStatus.ERROR -> startDownloadStarDb()
                DownloadStatus.DOWNLOADING -> cancelDownloadStarDb()
                else -> {
                    Log.d(TAG, "Ignore download button event with state $downloadState")
                }
            }
        }
        downloadProgressBar = view.findViewById(R.id.progress_settings_download)
        downloadMessageTextView = view.findViewById(R.id.text_settings_download_message)
        requireActivity().registerReceiver(
            onDownloadComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )

        if (isStarDbInstalled(requireContext(), STARDB_NAME)) {
            downloadState = DownloadState(DownloadStatus.DONE, "")
        } else if (isStardbZipDownloaded()) {
            downloadState = DownloadState(DownloadStatus.DOWNLOADED, "")
        }
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
            Log.d(TAG, "Prober running")
            if (downloadStarDbRequestId != INVALID_DOWNLOAD_ID) {
                eventHandler.postDelayed(this, 2000)
            } else {
                Log.d(TAG, "Prober stopped")
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

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, INVALID_DOWNLOAD_ID_1)
            Log.d(TAG, "onReceive intent $id")
            if (downloadStarDbRequestId == id) {
                downloadStarDbRequestId = INVALID_DOWNLOAD_ID
                eventHandler.removeCallbacks(probeDownloadStatusRunner)
                Log.d(TAG, "done downloading $starDbZipPath")
                if (!starDbZipPath.exists()) {
                    sendMessage(DownloadStatus.ERROR, "Download failed")
                    return
                }
                sendMessage(DownloadStatus.DOWNLOADED, "Download finished")
            }
        }
    }

    private fun cancelDownloadStarDb() {
        assert(downloadStarDbRequestId != INVALID_DOWNLOAD_ID)
        val downloadManager =
            requireActivity().getSystemService(DOWNLOAD_SERVICE) as? DownloadManager
        downloadManager!!.remove(downloadStarDbRequestId)
        eventHandler.removeCallbacks(probeDownloadStatusRunner)
        Log.d(TAG, "Canceled download")
        downloadState = DownloadState(DownloadStatus.ERROR, "Canceled")
        updateView()
    }

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
        if (false) {
            val readyPath = getStarDbReadyPath(context, STARDB_NAME)
            if (readyPath.exists()) {
                Log.d(TAG, "deleting $readyPath")
                readyPath.delete()
            }
            if (starDbZipPath.exists()) {
                Log.d(TAG, "deleting $starDbZipPath")
                starDbZipPath.delete()
            }
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
                setButton(R.string.settings_install_stardb, true, View.GONE)
            }
            DownloadStatus.DOWNLOADING -> {
                setButton(R.string.cancel, true, View.VISIBLE)
            }
            DownloadStatus.DOWNLOADED -> {
                setButton(R.string.cancel, false, View.VISIBLE)

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
                setButton(R.string.cancel, false, View.VISIBLE)
            }
            DownloadStatus.DONE -> {
                setButton(R.string.settings_reinstall_stardb, true, View.GONE)
            }
            DownloadStatus.ERROR -> {
                setButton(R.string.settings_retry_install_stardb, true, View.GONE)
            }
        }
    }
}
