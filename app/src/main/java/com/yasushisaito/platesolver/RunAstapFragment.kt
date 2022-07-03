package com.yasushisaito.platesolver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import kotlin.math.atan
import kotlin.math.tan

// Convert the lens focal length (FX) to
// the field of view of the image
fun focalLengthToFov(lensMm: Double): Double {
    val rad = 2 * atan(36.0 / (2 * lensMm))
    return rad * 180 / Math.PI
}

fun fovToFocalLength(deg: Double): Double {
    val rad = deg / (180 / Math.PI)
    return 18.0 / tan(rad / 2)
}

private fun isValidFovDeg(fovDeg: Double): Boolean {
    return fovDeg > 0 && fovDeg < 90.0
}

// https://stackoverflow.com/questions/9385081/how-can-i-change-the-edittext-text-without-triggering-the-text-watcher
private fun updateEditText(edit: EditText, value: String) {
    val focused = edit.hasFocus()
    if (focused) edit.clearFocus()
    edit.setText(value)
    if (focused) edit.requestFocus()
}

private fun setOnChangeListener(edit: EditText, cb: (value: String) -> Unit) {
    edit.setOnEditorActionListener { p0, p1, p2 ->
        val value = edit.text.toString()
        Log.d("EditText", "action p1=$p1 val=$value")
        cb(value)
        false
    }
    edit.onFocusChangeListener = View.OnFocusChangeListener { p0, p1 ->
        val value = edit.text.toString()
        Log.d("EditText", "focus $p1 val=$value")
        if (!p1) cb(value)
    }
}

private fun getDbTypeFromFov(context: Context, fovDeg: Double) : String {
    var dbType = ""
    if (getStarDbReadyPath(context, STARDB_H18).exists()) {
        if (fovDeg <= 5.0) return STARDB_H18
        dbType = STARDB_H18
    }
    if (getStarDbReadyPath(context, STARDB_H17).exists()) {
        if (fovDeg <= 5.0) return STARDB_H17
        if (dbType == "") dbType = STARDB_H17
    }
    if (getStarDbReadyPath(context, STARDB_V17).exists()) {
        if (fovDeg <= 20.0) return STARDB_V17
        if (dbType == "") dbType = STARDB_V17
    }
    if (getStarDbReadyPath(context, STARDB_W08).exists()) {
        if (dbType == "") dbType = STARDB_W08
    }
    if (dbType == "") {
        throw Exception("No star database installed")
    }
    return dbType
}

class RunAstapFragment : Fragment() {
    companion object {
        const val TAG = "RunAstapFragment"
        const val DEFAULT_FOV_DEG = 2.0
        const val BUNDLE_KEY_FOV_DEG = "FOV_DEG"
        const val BUNDLE_KEY_SEARCH_ORIGIN = "SEARCH_ORIGIN"
        const val BUNDLE_KEY_IMAGE_PATH = "IMAGE_PATH"
        const val BUNDLE_KEY_IMAGE_FILENAME = "IMAGE_FILENAME"

        const val EVENT_MESSAGE = 0
        const val EVENT_ERROR_MESSAGE = 2
        const val EVENT_SHOW_SOLUTION = 3
        const val EVENT_SHOW_DIALOG = 4
        const val EVENT_WELLKNOWNDSO_LOADED = 6
        const val EVENT_SUSPEND_DIALOG = 7
        const val EVENT_IMAGE_LOADED = 8
    }

    // Setup parameters

    // The image to analyze. The URI specified by the user is copied to the application storage,
    // and imagePath points to a ile in the the application storage.
    private var imagePath: File? = null
    // The user-specified filename. Its different from the last part of imagePath, since
    // the latter is a SHA of the contents.
    //
    // INVARIANT: imageFilename is set iff. imagePath is set.
    private var imageFilename: String? = null

    private var fovDeg: Double = DEFAULT_FOV_DEG
    private var startSearch: WellKnownDso? = null
    private var startSearchName: String? = null
    private var dbType: String? = null

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
        val activity = requireActivity()
        Thread {
            val resolver = activity.contentResolver
            val ext = getUriMimeType(resolver, uri)
            val inputStream = resolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "could not open $uri")
                return@Thread
            }
            val sha256 = inputStream.use {
                inputStreamDigest(inputStream)
            }
            val imagePath = File(activity.getExternalFilesDir(null), "${sha256}.$ext")
            if (!imagePath.exists()) {
                copyUriTo(resolver, uri, imagePath)
            }
            Log.d(TAG, "copied $uri to $imagePath")
            sendMessage(EVENT_IMAGE_LOADED, LoadedImage(
                imagePath=imagePath,
                imageFilename = getUriFilename(resolver, uri)))
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDouble(BUNDLE_KEY_FOV_DEG, fovDeg)
        startSearch?.let {
            outState.putString(BUNDLE_KEY_SEARCH_ORIGIN, it.names[0])
        }
        imagePath?.let {
            outState.putString(BUNDLE_KEY_IMAGE_PATH, it.absolutePath)
        }
        imageFilename.let {
            outState.putString(BUNDLE_KEY_IMAGE_FILENAME, it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        state: Bundle?
    ): View {
        Log.d(TAG, "oncreateview")
        WellKnownDsoSet.registerOnSingletonLoaded {
            sendMessage(
                EVENT_WELLKNOWNDSO_LOADED,
                "" as Any
            )
        }
        return inflater.inflate(R.layout.fragment_run_astap, container, false)
    }

    private var thisView: View? = null
    private lateinit var imageView: AnnotatedImageView

    override fun onDestroyView() {
        super.onDestroyView()
        thisView = null
    }

    private lateinit var fovDegEdit: EditText
    private lateinit var fovLensEdit: EditText
    private lateinit var runButton: FloatingActionButton
    private lateinit var searchStartEdit: AutoCompleteTextView
    private lateinit var searchStartRaDecView: TextView
    private lateinit var dbTypeSpinner: Spinner

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        thisView = view
        imageView = view.findViewById(R.id.view_astap_image)
        fovDegEdit = view.findViewById(R.id.text_astap_fov_deg)
        fovLensEdit = view.findViewById(R.id.text_astap_fov_lens)
        searchStartEdit = view.findViewById(R.id.autocomplete_astap_search_origin)
        searchStartRaDecView = view.findViewById(R.id.text_setup_searchstart_ra_dec)
        val dbDescriptions = ArrayList<String>() // Human-readable descriptions of star databases
        val dbTypes = ArrayList<String?>() // STARDB_H18, STARDB_V17, etc. null means autodetect.

        dbDescriptions.add("Auto")
        dbTypes.add(null)
        if (getStarDbReadyPath(requireContext(), STARDB_H18).exists()) {
            dbDescriptions.add(requireContext().getString(R.string.h18))
            dbTypes.add(STARDB_H18)
        }
        if (getStarDbReadyPath(requireContext(), STARDB_H17).exists()) {
            dbDescriptions.add(requireContext().getString(R.string.h17))
            dbTypes.add(STARDB_H17)
        }
        if (getStarDbReadyPath(requireContext(), STARDB_V17).exists()) {
            dbDescriptions.add(requireContext().getString(R.string.v17))
            dbTypes.add(STARDB_V17)
        }
        if (getStarDbReadyPath(requireContext(), STARDB_W08).exists()) {
            dbDescriptions.add(requireContext().getString(R.string.w08))
            dbTypes.add(STARDB_W08)
        }
        dbTypeSpinner = view.findViewById(R.id.spinner_astap_db_type)
        dbTypeSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, dbDescriptions)
        dbTypeSpinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                Log.d(TAG, "Selected: pos=${pos} id=${id}")
                dbType = dbTypes[pos]
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        runButton = view.findViewById(R.id.fab_astap_run)
        runButton.setOnClickListener {
            onRunAstap()
        }

        if (state != null) {
            fovDeg = state.getDouble(BUNDLE_KEY_FOV_DEG, DEFAULT_FOV_DEG)
            startSearchName = state.getString(BUNDLE_KEY_SEARCH_ORIGIN)
            state.getString(BUNDLE_KEY_IMAGE_PATH)?.let {
                imagePath = File(it)
                imageView.setImage(it)
            }
            imageFilename = state.getString(BUNDLE_KEY_IMAGE_FILENAME)
        }

        // Handle FOV degree changes
        setOnChangeListener(fovDegEdit) { value ->
            try {
                Log.d(TAG, "FovDeg: $value")
                val deg = value.toDouble()
                Log.d(TAG, "FovDeg: $deg $value")
                if (isValidFovDeg(deg)) {
                    fovDeg = deg
                }
                updateView()
            } catch (ex: Exception) {
            }
        }
        // Handle FOV lens focal length changes
        setOnChangeListener(fovLensEdit) { value ->
            try {
                Log.d(TAG, "FovMm: $value")
                val mm = value.toDouble()
                Log.d(TAG, "FovMm: $mm $value")
                if (mm > 0 || mm < 10000) {
                    val deg = focalLengthToFov(mm)
                    if (isValidFovDeg(deg)) {
                        fovDeg = deg
                        updateView()
                    }
                }
            } catch (e: Exception) {
            }
        }
        val fileButton = view.findViewById<Button>(R.id.button_astap_pick_file)
        fileButton.setOnClickListener {
            val intent = Intent()
                .setType("*/*")
                .setAction(Intent.ACTION_GET_CONTENT)
            pickFileLauncher.launch(intent)
        }
        updateView()
    }

    // List of DSO names extracted from WellKnownDsoSet.
    private var wellKnownDsoArray: ArrayAdapter<String>? = null

    // Maps DSO names in the above -> WellKnownDso objects
    private val wellKnownDsoNameMap = HashMap<String, WellKnownDso>()

    @SuppressLint("SetTextI18n")
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
                dsos
            )
            searchStartEdit.setAdapter(wellKnownDsoArray)
            searchStartEdit.dropDownWidth = 400
            setOnChangeListener(searchStartEdit) {
                startSearch = wellKnownDsoNameMap[it]
                updateView()
            }
        }

        updateEditText(fovDegEdit, "%.2f".format(fovDeg))

        val focalLengthEdit = view.findViewById<EditText>(R.id.text_astap_fov_lens)
        updateEditText(focalLengthEdit, "%d".format(fovToFocalLength(fovDeg).toInt()))

        val setEditable = fun(enabled: Boolean) {
            fovDegEdit.isEnabled = enabled
            fovLensEdit.isEnabled = enabled
            searchStartEdit.isEnabled = enabled
            dbTypeSpinner.isEnabled = enabled
            // https://stackoverflow.com/questions/7641879/how-do-i-make-a-spinners-disabled-state-look-disabled#:~:text=Since%20Android%20doesn't%20gray,out%20the%20text%20within%20it.
            dbTypeSpinner.alpha = if (enabled) 1.0f else 0.4f
        }
        val imageNameText = view.findViewById<TextView>(R.id.text_setup_imagename)
        if (imageFilename != null) {
            imageNameText.text = imageFilename
            setEditable(true)
        } else {
            imageNameText.text = ""
            setEditable(false)
        }

        if (startSearch == null) {
            updateEditText(searchStartEdit, "")
            searchStartRaDecView.text = ""
        } else {
            searchStartRaDecView.text = startSearch!!.cel.toDisplayString()
        }

        runButton.isEnabled =
            isValidFovDeg(fovDeg) && imagePath != null && WellKnownDsoSet.getSingleton() != null
    }

    private data class LoadedImage(val imagePath: File, val imageFilename: String)
    private data class DialogParams(val params: SolverParameters, val astapRunner: AstapRunner)

    private var dialog: AstapRunnerDialogFragment? = null

    private val eventHandler = Handler(Looper.getMainLooper()) { msg: Message ->
        Log.d(TAG, "event msg=$msg")

        when (msg.what) {
            EVENT_MESSAGE -> {
                dialog?.setMessage(msg.obj as String)
            }
            EVENT_ERROR_MESSAGE -> {
                dialog?.setError(msg.obj as String)
            }
            EVENT_SHOW_SOLUTION -> {
                dialog?.dismiss()
                dialog = null
                val activity = requireActivity() as? MainActivity
                activity!!.refreshSolutionMenuItems()
                startSolutionFragment(msg.obj as String)
            }
            EVENT_WELLKNOWNDSO_LOADED -> {
                Log.d(TAG, "wellknowndso loaded")
                startSearchName?.let {
                    startSearch = WellKnownDsoSet.getSingleton()!!.findByName(it)
                }
                updateView()
            }
            EVENT_SHOW_DIALOG -> {
                dialog?.dismiss()
                val params = msg.obj as DialogParams
                dialog = AstapRunnerDialogFragment(
                    onAbort = {
                        params.astapRunner.abort()
                    },
                    fovDeg = params.params.fovDeg,
                    imageName = params.params.imageFilename,
                    searchOrigin = params.params.startSearch,
                )
                dialog!!.show(childFragmentManager, null)
            }
            EVENT_SUSPEND_DIALOG -> {
                dialog?.setError((msg.obj as? String)!!)
                dialog?.suspend()
            }
            EVENT_IMAGE_LOADED -> {
                val params = msg.obj as LoadedImage
                imagePath = params.imagePath
                imageFilename = params.imageFilename
                imageView.setImage(params.imagePath.absolutePath)
                updateView()
            }
            else -> throw Exception("Invalid message $msg")
        }
        true
    }

    private fun sendMessage(what: Int, message: Any) {
        eventHandler.sendMessage(
            Message.obtain(eventHandler, what, message)
        )
    }

    private fun onRunAstap() {
        assert(
            isValidFovDeg(fovDeg) && imagePath != null
        ) { Log.e(TAG, "fovDeg=$fovDeg, imagePath=$imagePath") }

        val activity = requireActivity()

        val solverParams = SolverParameters(
            imagePath = imagePath!!.absolutePath,
            imageFilename = imageFilename!!,
            fovDeg = fovDeg,
            startSearch = startSearch?.cel,
            dbName = dbType ?: getDbTypeFromFov(requireContext(), fovDeg))

        Thread(Runnable {
            val solutionJsonPath =
                File(getSolutionDir(activity), "${solverParams.hashString()}.json")

            // Try reading the json file. Note that readSolution will raise exception on any error
            try {
                readSolution(solutionJsonPath)
                sendMessage(EVENT_SHOW_SOLUTION, solutionJsonPath.absolutePath as Any)
                return@Runnable
            } catch (ex: Exception) {
                Log.d(TAG, "Could not read $solutionJsonPath: $ex; Running astap")
            }
            try {
                sendMessage(EVENT_MESSAGE, "Running astap...")
                // The solution json doesn't exist yet. Run astap_cli.
                val astapRunner = AstapRunner(
                    context = requireContext(),
                    solutionJsonPath = solutionJsonPath,
                    solverParams = solverParams,
                    onMessage = { message -> sendMessage(EVENT_MESSAGE, message) }
                )
                sendMessage(EVENT_SHOW_DIALOG, DialogParams(params=solverParams, astapRunner=astapRunner))
                sendMessage(EVENT_MESSAGE, "running Astap...")
                val result = astapRunner.run()
                Log.d(TAG, "astap finished: exitCode=${result.exitCode} error=${result.error}")
                val errorMessage: String? = when {
                    result.error != "" -> result.error
                    result.exitCode == 128 + 9 || result.exitCode == 128 + 15 -> {
                        // SIGTERM or SIGKILL
                        "Astap aborted"
                    }
                    result.exitCode != 0 -> {
                        val stdout = String(result.stdout)
                        val stderr = String(result.stderr)
                        "Astap failed with outputs: $stdout\nstderr: $stderr"
                    }
                    else -> null
                }
                if (errorMessage != null) {
                    sendMessage(EVENT_SUSPEND_DIALOG,  errorMessage)
                    Log.d(TAG, errorMessage)
                    return@Runnable
                }
                if (!solutionJsonPath.exists()) {
                    // This shouldn't happen
                    sendMessage(EVENT_SUSPEND_DIALOG, "Astap finished successfully, but it did not create a solution file")
                    return@Runnable
                }
                sendMessage(EVENT_SHOW_SOLUTION, solutionJsonPath.absolutePath as Any)
            } catch (ex: Exception) {
                val message = "Could not run astap_cli: $ex"
                sendMessage(EVENT_SUSPEND_DIALOG,  message)
                Log.d(TAG, message)
            }
        }).start()
    }

    private fun startSolutionFragment(solutionJsonPath: String) {
        val bundle = Bundle()
        bundle.putString(FRAGMENT_ARG_SOLUTION_JSON_PATH, solutionJsonPath)
        val frag = findOrCreateFragment(parentFragmentManager, FragmentType.Result, bundle)
        val fragName = getFragmentType(frag).name
        parentFragmentManager.commit {
            replace(R.id.content_frame, frag, fragName)
            setReorderingAllowed(true)
            addToBackStack(fragName)
        }
    }
}
