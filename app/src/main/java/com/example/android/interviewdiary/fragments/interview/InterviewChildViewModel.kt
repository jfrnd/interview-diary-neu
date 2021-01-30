package com.example.android.interviewdiary.fragments.interview

import androidx.hilt.Assisted
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.android.interviewdiary.model.*
import com.example.android.interviewdiary.other.Constants.CURRENT_NOTE
import com.example.android.interviewdiary.other.Constants.CURRENT_VALUES
import com.example.android.interviewdiary.other.Constants.DATE
import com.example.android.interviewdiary.other.Constants.TRACKER
import com.example.android.interviewdiary.other.utils.ConverterUtil.toLocalDate
import com.example.android.interviewdiary.repositories.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import javax.inject.Inject


enum class InputStatus { INPUT_OK_AND_OR_SYNC_TO_DATABASE_VALUES, INPUT_ASYNC_TO_DATABASE_VALUES, WAITING_FOR_INPUT }

/**
 * Will be newly instantiated for each question in an interview session.
 */
@HiltViewModel
class InterviewChildViewModel @Inject constructor(
    private val repo: AppRepository,
    private val state: SavedStateHandle
) : ViewModel() {

    val date: LocalDate = state.get<String>(DATE)!!.toLocalDate()

    var tracker = state.get<Tracker>(TRACKER)!!
        private set

    private val childEventChannel = Channel<ChildEvent>()
    val recordEvent = childEventChannel.receiveAsFlow()

    private var databaseRecord: Record? = null
    private fun streamDatabaseRecord() = repo.streamRecord(tracker.trackerId, date)

    /**
     * note which is currently selected by the user, but has not been saved to the database yet
     */
    private val currentNote = MutableStateFlow(state.get(CURRENT_NOTE) ?: "")
    fun streamCurrentNote() = currentNote.asStateFlow()


    /**
     * values which are currently selected by the user, but have not been saved to the database yet
     */
    private val currentValues = MutableStateFlow<List<Float>>(state.get(CURRENT_VALUES) ?: listOf())
    fun streamCurrentValues() = currentValues.asStateFlow()

    init {
        viewModelScope.launch {
            streamDatabaseRecord().collect { record ->
                databaseRecord = record
                currentValues.value = when (tracker.type) {
                    TrackerType.MULTIPLE_CHOICE -> record?.values ?: emptyList()
                    TrackerType.TIME -> record?.values ?: tracker.configValues
                    TrackerType.NUMERIC -> record?.values ?: listOf(tracker.configValues[0])
                    TrackerType.YES_NO -> record?.values ?: emptyList()
                }
                currentNote.value = databaseRecord?.note ?: ""
                state.set(CURRENT_NOTE, currentNote.value)
                state.set(CURRENT_VALUES, currentValues.value)
            }
        }
    }

    fun createAdapterItemList(): Flow<List<InterviewMultipleChoiceAdapter.Item>> {
        return combine(
            streamDatabaseRecord(),
            streamCurrentValues(),
            streamCurrentNote(),
        ) { dbRecord, curValues, curNote ->
            Triple(dbRecord, curValues, curNote)
        }.flatMapLatest { (dbRecord, curValues, curNote) ->
            flowOf(
                listOf(
                    InterviewMultipleChoiceAdapter.Item.Header(
                        0,
                        tracker.question,
                        tracker.imageUri,
                        curNote,
                        tracker.enabledFeatures.contains(Feature.NOTES)
                    )
                ) + tracker.configValues.map { answerId ->
                    InterviewMultipleChoiceAdapter.Item.Answer(
                        multiSelectionEnabled = tracker.enabledFeatures.contains(Feature.MULTI_SELECTION),
                        answerId = answerId.toInt(),
                        text = tracker.answerOptions[answerId.toInt()]
                            ?: error("answer option with no text"),
                        isSelectedInDb = dbRecord?.values?.contains(answerId) ?: false,
                        isSelectedAsCurVal = curValues.contains(answerId)
                    )
                })
        }
    }

    fun streamSelectionStatus(): Flow<InputStatus> {
        return combine(streamDatabaseRecord(), streamCurrentValues()) { record, curValues ->
            Pair(record?.values, curValues)
        }.flatMapLatest { (dbValues, curValues) ->
            when {
                curValues.isNullOrEmpty() -> flowOf(InputStatus.WAITING_FOR_INPUT)
                dbValues == null || dbValues == curValues -> flowOf(InputStatus.INPUT_OK_AND_OR_SYNC_TO_DATABASE_VALUES)
                else -> flowOf(InputStatus.INPUT_ASYNC_TO_DATABASE_VALUES)
            }
        }
    }

    fun onEditNoteConfirmClick(input: String) {
        updateCurrentNote(input)
    }

    fun onEditNoteConfirmAndSaveClick(input: String) {
        updateCurrentNote(input)
        saveRecord()
    }

    fun onAnswerClick(answerId: Float, forceNoteInputInCurrentSession: Boolean) {
        updateMultipleChoiceSelection(answerId)
        if (!tracker.enabledFeatures.contains(Feature.MULTI_SELECTION))
            onConfirmClick(forceNoteInputInCurrentSession)
    }

    fun onNumericTimeValueChanged(index: Int, newValue: Float) {
        val value = if (tracker.enabledFeatures.contains(Feature.DECIMAL))
            newValue
        else newValue.toInt().toFloat()
        updateNumericTimeValue(index, value)
    }

    fun onCancelClick() {
        resetToDBValue()
    }

    fun onBackClick() {
        navigateBack()
    }

    fun onSkipNoteClick() {
        saveRecord()
    }

    private fun navigateBack() {
        viewModelScope.launch {
            childEventChannel.send(ChildEvent.NavigateBack)
        }
    }

    fun onConfirmClick(forceNoteInputInCurrentSession: Boolean) {
        if (forceNoteInputInCurrentSession && tracker.enabledFeatures.contains(Feature.NOTES) && currentNote.value.isBlank())
            forceOpenNoteDialog()
        else {
            saveRecord()
            navigateForward()
        }
    }

    private fun updateMultipleChoiceSelection(clickedAnswerId: Float) {
        currentValues.value = when {
            currentValues.value.contains(clickedAnswerId) && tracker.enabledFeatures.contains(
                Feature.MULTI_SELECTION
            ) ->
                currentValues.value.filter { it != clickedAnswerId }
            !currentValues.value.contains(clickedAnswerId) && tracker.enabledFeatures.contains(
                Feature.MULTI_SELECTION
            ) ->
                currentValues.value.plus(clickedAnswerId)
            currentValues.value.contains(clickedAnswerId) && !tracker.enabledFeatures.contains(
                Feature.MULTI_SELECTION
            ) ->
                currentValues.value
            else -> arrayListOf(clickedAnswerId)
        }
        state.set(CURRENT_VALUES, currentValues.value)
    }

    private fun updateNumericTimeValue(index: Int, newValue: Float) {
        currentValues.value = ArrayList(currentValues.value.mapIndexed { idx, value ->
            if (idx == index) newValue else value
        })
        state.set(CURRENT_VALUES, currentValues.value)
    }

    private fun updateCurrentNote(input: String) {
        if (input.isNotEmpty())
            currentNote.value = input
        state.set(CURRENT_NOTE, input)
    }

    private fun navigateForward() {
        viewModelScope.launch {
            childEventChannel.send(
                ChildEvent.NavigateForward
            )
        }
    }

    private fun forceOpenNoteDialog() {
        viewModelScope.launch {
            childEventChannel.send(
                ChildEvent.ForceOpenNoteDialog
            )
        }
    }

    private fun resetToDBValue() {
        if (databaseRecord?.values != null) {
            currentValues.value = databaseRecord!!.values
            state.set(CURRENT_VALUES, currentValues.value)
        }
    }

    private fun saveRecord() {
        if (databaseRecord != null) {
            updateRecord()
        } else {
            val newRecord =
                Record(
                    note = currentNote.value,
                    trackerId = tracker.trackerId,
                    date = date,
                    values = currentValues.value
                )
            createRecord(newRecord)
        }
    }

    private fun createRecord(record: Record) =
        viewModelScope.launch(Dispatchers.IO) {
            repo.insertRecord(record)
        }

    private fun updateRecord() {
        val updatedRecord = databaseRecord!!.copy(
            note = currentNote.value,
            values = currentValues.value
        )

        viewModelScope.launch(Dispatchers.IO) {
            repo.updateRecord(updatedRecord)
        }
    }

    fun onYesClick(forceNoteInputInCurrentSession: Boolean) {
        updateYesNoSelection(true)
        onConfirmClick(forceNoteInputInCurrentSession)
    }

    fun onNoClick(forceNoteInputInCurrentSession: Boolean) {
        updateYesNoSelection(false)
        onConfirmClick(forceNoteInputInCurrentSession)
    }

    private fun updateYesNoSelection(yes: Boolean) {
        if (yes) currentValues.value = listOf(1f)
        else currentValues.value = listOf(0f)
        state.set(CURRENT_VALUES, currentValues.value)
    }

    sealed class ChildEvent {
        object ForceOpenNoteDialog : ChildEvent()
        object NavigateForward : ChildEvent()
        object NavigateBack : ChildEvent()
    }
}

