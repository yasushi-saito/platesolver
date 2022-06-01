package com.yasushisaito.platesolver

import android.app.Activity
import android.content.Intent
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
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.atan
import kotlin.math.tan

// Convert the lens focal length (FX) to
// the field of view of the image
fun focalLengthToFov(lensmm: Double): Double {
    val rad = 2 * atan(36.0 / (2 * lensmm))
    return rad * 180 / Math.PI
}

fun fovToFocalLength(deg: Double): Double {
    val rad = deg / (180 / Math.PI)
    return 18.0 / tan(rad / 2)
}

private fun isValidFovDeg(fovDeg: Double): Boolean {
    return fovDeg > 0 && fovDeg < 90.0
}

private fun setOnChangeListener(edit: EditText, cb: (edit: EditText) -> Unit) {
    edit.setOnEditorActionListener { _, actionId, event ->
        if (actionId == 0) cb(edit)
        false
    }
    edit.setOnFocusChangeListener { v, hasFocus ->
        if (!hasFocus) cb(edit)
    }
}


class RunAstapFragment : Fragment() {
    companion object {
        const val TAG = "SetupFragment"
        const val DEFAULT_FOV_DEG = 2.0
        const val BUNDLE_KEY_FOV_DEG = "fovDeg"

        const val EVENT_MESSAGE = 0
        const val EVENT_ERROR = 2
        const val EVENT_SHOW_SOLUTION = 3
        const val EVENT_SHOW_DIALOG = 4
        const val EVENT_DISMISS_DIALOG = 5
        const val EVENT_WELLKNOWNDSO_LOADED = 6
    }

    // Setup parameters
    private var imageUri: Uri? = null
    private var fovDeg: Double = DEFAULT_FOV_DEG
    private var startSearch: WellKnownDso? = null

    private fun newLauncher(cb: (Intent) -> Unit): ActivityResultLauncher<Intent> {
        val launch =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
                if (result?.resultCode != Activity.RESULT_OK) {
                    println("launch activity failed: $result")
                    return@registerForActivityResult
                }
                cb(result.data!!)
            }
        return launch
    }

    private val pickFileLauncher = newLauncher { intent: Intent ->
        val uri: Uri = intent.data ?: return@newLauncher
        imageUri = uri
        updateView()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (savedInstanceState != null) {
            fovDeg = savedInstanceState.getDouble(BUNDLE_KEY_FOV_DEG, DEFAULT_FOV_DEG)
        }
        WellKnownDsoSet.registerOnSingletonLoaded { sendMessage(EVENT_WELLKNOWNDSO_LOADED, "" as Any) }
        return inflater.inflate(R.layout.fragment_run_astap, container, false)
    }

    private var thisView: View? = null

    override fun onDestroyView() {
        super.onDestroyView()
        thisView = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        thisView = view

        // Handle FOV degree changes
        setOnChangeListener(view.findViewById<EditText>(R.id.text_astap_fov_deg)) { edit ->
            var deg = 0.0
            try {
                deg = edit.getText().toString().toDouble()
            } catch (e: Exception) {
            }
            if (isValidFovDeg(deg)) {
                fovDeg = deg
            }
            updateView()
        }
        // Handle FOV lens focal length changes
        setOnChangeListener(view.findViewById<EditText>(R.id.text_astap_fov_lens)) { edit ->
            var mm = 0.0
            try {
                mm = edit.getText().toString().toDouble()
            } catch (e: Exception) {
            }
            if (mm > 0 || mm < 10000) {
                val deg = focalLengthToFov(mm)
                if (isValidFovDeg(deg)) {
                    fovDeg = deg
                }
            }
            updateView()
        }
        val pickFileButton = view.findViewById<Button>(R.id.astap_pick_file)
        pickFileButton.setOnClickListener({
            val intent = Intent()
                .setType("*/*")
                .setAction(Intent.ACTION_GET_CONTENT)
            pickFileLauncher.launch(intent)
        })
        val runButton = view.findViewById<Button>(R.id.setup_run)
        runButton.setOnClickListener({
            onRunAstap()
        })
        updateView()
    }

    // List of DSO names extracted from WellKnownDsoSet.
    private var wellKnownDsoArray: ArrayAdapter<String>? = null
    // Maps DSO names in the above -> WellKnownDso objects
    private val wellKnownDsoNameMap = HashMap<String, WellKnownDso>()

    private fun updateView() {
        val view: View = thisView ?: return

        // Set autocompletion list for well-known deep sky objects.
        // Note that the list is loaded in a separate thread, so we need to
        // delay filling the list until dsoSet becomes non-null.
        // Once dsoSet becomes nonnull, its value never changes.
        val dsoSet = WellKnownDsoSet.getSingleton()
        if (dsoSet != null && wellKnownDsoArray == null) {
            val dsos = ArrayList<String>()
            for (e in dsoSet.entries) {
                for (name in e.names) {
                    dsos.add(name)
                    wellKnownDsoNameMap[name] = e
                }
            }
            wellKnownDsoArray = ArrayAdapter<String>(
                requireActivity(),
                android.R.layout.simple_dropdown_item_1line,
                dsos)
            val dsoView = view.findViewById<AutoCompleteTextView>(R.id.autocomplete_astap_searchstart)
            dsoView.setAdapter(wellKnownDsoArray)
            dsoView.setDropDownWidth(400)
            setOnChangeListener(dsoView) {
                startSearch = wellKnownDsoNameMap.get(dsoView.getText().toString())
                Log.d(TAG, "dsoview selected $startSearch")
                val raDecView = view.findViewById<TextView>(R.id.text_setup_searchstart_ra_dec)
                if (startSearch == null) {
                    dsoView.setText("")
                    raDecView.setText("Auto")
                } else {
                    raDecView.setText(startSearch!!.cel.toDisplayString())
                }
            }
        }

        val degEdit = view.findViewById<EditText>(R.id.text_astap_fov_deg)
        degEdit.setText("%.2f".format(fovDeg))

        val focalLengthEdit = view.findViewById<EditText>(R.id.text_astap_fov_lens)
        focalLengthEdit.setText("%d".format(fovToFocalLength(fovDeg).toInt()))

        val imageNameText = view.findViewById<TextView>(R.id.text_setup_imagename)
        if (imageUri != null) {
            imageNameText.setText(getUriFilename(    requireContext().contentResolver, imageUri!!))
        } else {
            imageNameText.setText("")
        }
        val runButton = view.findViewById<Button>(R.id.setup_run)
        runButton.setEnabled(isValidFovDeg(fovDeg) && imageUri != null && WellKnownDsoSet.getSingleton() != null)
    }

    var dialog: AstapDialogFragment? = null

    val eventHandler = Handler(Looper.getMainLooper()) { msg: Message ->
        println("EVENTHANDLER: what=${msg.what} msg=${msg.obj}")

        println("HANDLER $msg dialog=$dialog")
        when (msg.what) {
            EVENT_MESSAGE -> {
                dialog?.setMessage(msg.obj as String)
            }
            EVENT_ERROR -> {
                dialog?.setError(msg.obj as String)
            }
            EVENT_SHOW_SOLUTION -> {
                dialog = null
                startSolutionFragment(msg.obj as String)
            }
            EVENT_WELLKNOWNDSO_LOADED -> {
                Log.d(TAG, "wellknowndso loaded")
                updateView()
            }
            EVENT_SHOW_DIALOG -> {
                dialog?.show(childFragmentManager, null)
            }
            EVENT_DISMISS_DIALOG -> {
                dialog?.dismiss()
                dialog = null
            }
            else -> throw Error("Invalid message $msg")
        }
        true
    }

    private fun sendMessage(what: Int, message: Any) {
        eventHandler.sendMessage(
            Message.obtain(eventHandler, what, message)
        )
    }
    private fun onRunAstap() {
        if (!isValidFovDeg(fovDeg) || imageUri == null) {
            throw Error("invalid args")
        }

        val activity = requireActivity()
        val astapRunnerMu = ReentrantLock()
        var astapRunner: AstapRunner? = null

        val thisImageUri = imageUri!!
        val thisFovDeg = fovDeg
        val thisStartSearchDso = startSearch

        dialog = AstapDialogFragment(
            onAbort= {
                astapRunnerMu.withLock {
                    astapRunner?.abort()
                }
            },
            fovDeg = thisFovDeg,
            imageName= getUriFilename(activity.contentResolver, thisImageUri),
            searchOrigin = thisStartSearchDso?.cel
        )
        Thread(Runnable {
                val resolver = activity.contentResolver
                val ext = getUriMimeType(resolver, thisImageUri)
                val inputStream = resolver.openInputStream(thisImageUri)
                if (inputStream == null) {
                    Log.e(TAG, "could not open $thisImageUri")
                    return@Runnable
                }
                val sha256 = inputStream.use {
                    inputStreamDigest(inputStream)
                }
                val imagePath = File(activity.getExternalFilesDir(null), "${sha256}.$ext")
                val solverParams = SolverParameters(imagePath.absolutePath, thisFovDeg, thisStartSearchDso?.cel)
                val solutionJsonPath = File(getSolutionDir(activity), "${solverParams.hashString()}.json")
                var solution: Solution? = null
                try {
                    solution = readSolution(solutionJsonPath)
                } catch (e: Exception) {
                    Log.d(TAG,"readSolution: could not read cached solution in solutionJsonPath: $e: Running astap")
                }

                if (solution == null) {
                    astapRunnerMu.withLock {
                        astapRunner = AstapRunner(
                            context = requireContext(),
                            onError = { message: String ->
                                sendMessage(
                                    EVENT_ERROR,
                                    message as Any
                                )
                            },
                            onMessage = { message: String ->
                                sendMessage(
                                    EVENT_MESSAGE,
                                    message as Any
                                )
                            },
                            solutionJsonPath = solutionJsonPath,
                            solverParams = solverParams,
                            imageName = getUriFilename(requireContext().contentResolver, thisImageUri)
                        )
                    }
                    sendMessage(EVENT_SHOW_DIALOG, "" as Any)
                    if (!imagePath.exists()) {
                        copyUriTo(activity.contentResolver, thisImageUri, imagePath)
                        sendMessage(EVENT_MESSAGE, "copied file" as Any)
                    } else {
                        sendMessage(EVENT_MESSAGE, "file already exists; skipping copying" as Any)
                    }
                    astapRunner!!.run()
                    sendMessage(EVENT_DISMISS_DIALOG, "" as Any)
                }
                if (!solutionJsonPath.exists()) {
                    throw Error("could not build solution")
                }
                sendMessage(EVENT_SHOW_SOLUTION, solutionJsonPath.absolutePath as Any)
        }).start()
    }

    fun startSolutionFragment(solutionJsonPath: String) {
        val bundle = Bundle()
        bundle.putString(ResultFragment.BUNDLE_KEY_SOLUTION_JSON_PATH, solutionJsonPath)
        val fragment = ResultFragment()
        fragment.arguments = bundle
        val ft = parentFragmentManager.beginTransaction()
        ft.replace(R.id.content_frame, fragment)
        ft.commit()
    }
}