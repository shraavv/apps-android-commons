package fr.free.nrw.commons.upload.worker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.multidex.BuildConfig
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.android.ContributesAndroidInjector
import fr.free.nrw.commons.BuildConfig.HOME_URL
import fr.free.nrw.commons.CommonsApplication
import fr.free.nrw.commons.Media
import fr.free.nrw.commons.R
import fr.free.nrw.commons.auth.SessionManager
import fr.free.nrw.commons.auth.csrf.CsrfTokenClient
import fr.free.nrw.commons.contributions.ChunkInfo
import fr.free.nrw.commons.contributions.Contribution
import fr.free.nrw.commons.contributions.ContributionDao
import fr.free.nrw.commons.contributions.MainActivity
import fr.free.nrw.commons.customselector.database.UploadedStatus
import fr.free.nrw.commons.customselector.database.UploadedStatusDao
import fr.free.nrw.commons.di.ApplicationlessInjection
import fr.free.nrw.commons.media.MediaClient
import fr.free.nrw.commons.nearby.PlacesRepository
import fr.free.nrw.commons.theme.BaseActivity
import fr.free.nrw.commons.upload.FileUtilsWrapper
import fr.free.nrw.commons.upload.StashUploadResult
import fr.free.nrw.commons.upload.StashUploadState
import fr.free.nrw.commons.upload.UploadClient
import fr.free.nrw.commons.upload.UploadProgressActivity
import fr.free.nrw.commons.upload.UploadResult
import fr.free.nrw.commons.wikidata.WikidataEditService
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Date
import java.util.Random
import java.util.regex.Pattern
import javax.inject.Inject

class UploadWorker(
    private var appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private var notificationManager: NotificationManagerCompat? = null

    @Inject
    lateinit var wikidataEditService: WikidataEditService

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var contributionDao: ContributionDao

    @Inject
    lateinit var uploadedStatusDao: UploadedStatusDao

    @Inject
    lateinit var uploadClient: UploadClient

    @Inject
    lateinit var mediaClient: MediaClient

    @Inject
    lateinit var fileUtilsWrapper: FileUtilsWrapper

    @Inject
    lateinit var placesRepository: PlacesRepository

    private val processingUploadsNotificationTag = BuildConfig.APPLICATION_ID + " : upload_tag"

    private val processingUploadsNotificationId = 101

    // Attributes of the current-upload notification
    private var currentNotificationID: Int = -1 // lateinit is not allowed with primitives
    private lateinit var currentNotificationTag: String
    private var currentNotification: NotificationCompat.Builder

    private val statesToProcess = ArrayList<Int>()

    private val stashErrorCodes =
        listOf(
            "uploadstash-file-not-found",
            "stashfailed",
            "verification-error",
            "chunk-too-small",
        )

    init {
        ApplicationlessInjection
            .getInstance(appContext)
            .commonsApplicationComponent
            .inject(this)
        currentNotification =
            getNotificationBuilder(CommonsApplication.NOTIFICATION_CHANNEL_ID_ALL)!!

        statesToProcess.add(Contribution.STATE_QUEUED)
    }

    @dagger.Module
    interface Module {
        @ContributesAndroidInjector
        fun worker(): UploadWorker
    }

    open inner class NotificationUpdateProgressListener(
        private var notificationFinishingTitle: String?,
        var contribution: Contribution?,
    ) {
        @SuppressLint("MissingPermission")
        fun onProgress(
            transferred: Long,
            total: Long,
        ) {
            if (transferred == total) {
                // Completed!
                currentNotification
                    .setContentTitle(notificationFinishingTitle)
                    .setProgress(0, 100, true)
            } else {
                currentNotification
                    .setProgress(
                        100,
                        (transferred.toDouble() / total.toDouble() * 100).toInt(),
                        false,
                    )
            }
            notificationManager?.cancel(
                processingUploadsNotificationTag,
                processingUploadsNotificationId,
            )
            notificationManager?.notify(
                currentNotificationTag,
                currentNotificationID,
                currentNotification.build(),
            )
            contribution!!.transferred = transferred
            contributionDao.update(contribution!!).blockingAwait()
        }

        open fun onChunkUploaded(
            contribution: Contribution,
            chunkInfo: ChunkInfo?,
        ) {
            contribution.chunkInfo = chunkInfo
            contributionDao.update(contribution).blockingAwait()
        }
    }

    private fun getNotificationBuilder(channelId: String): NotificationCompat.Builder? =
        NotificationCompat
            .Builder(appContext, channelId)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_launcher)
            .setLargeIcon(
                BitmapFactory.decodeResource(
                    appContext.resources,
                    R.drawable.ic_launcher,
                ),
            ).setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
            .setOngoing(true)

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        try {
            var totalUploadsStarted = 0
            // Start a foreground service
            setForeground(createForegroundInfo())
            notificationManager = NotificationManagerCompat.from(appContext)
            val processingUploads =
                getNotificationBuilder(
                    CommonsApplication.NOTIFICATION_CHANNEL_ID_ALL,
                )!!
            withContext(Dispatchers.IO) {
                while (contributionDao
                        .getContribution(statesToProcess)
                        .blockingGet()
                        .size > 0 &&
                    contributionDao
                        .getContribution(
                            arrayListOf(
                                Contribution.STATE_IN_PROGRESS,
                            ),
                        ).blockingGet()
                        .size == 0
                ) {
                    /*
                    queuedContributions receives the results from a one-shot query.
                    This means that once the list has been fetched from the database,
                    it does not get updated even if some changes (insertions, deletions, etc.)
                    are made to the contribution table afterwards.

                    Related issues (fixed):
                    https://github.com/commons-app/apps-android-commons/issues/5136
                    https://github.com/commons-app/apps-android-commons/issues/5346
                     */
                    val queuedContributions =
                        contributionDao
                            .getContribution(statesToProcess)
                            .blockingGet()
                    // Showing initial notification for the number of uploads being processed

                    processingUploads.setContentTitle(appContext.getString(R.string.starting_uploads))
                    processingUploads.setContentText(
                        appContext.resources.getQuantityString(
                            R.plurals.starting_multiple_uploads,
                            queuedContributions.size,
                            queuedContributions.size,
                        ),
                    )
                    notificationManager?.notify(
                        processingUploadsNotificationTag,
                        processingUploadsNotificationId,
                        processingUploads.build(),
                    )

                    val sortedQueuedContributionsList: List<Contribution> =
                        queuedContributions.sortedBy { it.dateUploadStartedInMillis() }

                    var contribution = sortedQueuedContributionsList.first()

                    if (contributionDao.getContribution(contribution.pageId) != null) {
                        contribution.transferred = 0
                        contribution.state = Contribution.STATE_IN_PROGRESS
                        contributionDao.saveSynchronous(contribution)
                        setProgressAsync(Data.Builder().putInt("progress", totalUploadsStarted).build())
                        totalUploadsStarted++
                        uploadContribution(contribution = contribution)
                    }
                }
                // Dismiss the global notification
                notificationManager?.cancel(
                    processingUploadsNotificationTag,
                    processingUploadsNotificationId,
                )
            }
            // Trigger WorkManager to process any new contributions that may have been added to the queue
            val updatedContributionQueue =
                withContext(Dispatchers.IO) {
                    contributionDao.getContribution(statesToProcess).blockingGet()
                }
            if (updatedContributionQueue.isNotEmpty()) {
                return Result.retry()
            }

            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "UploadWorker encountered an error.")
            return Result.failure()
        } finally {
            WorkRequestHelper.markUploadWorkerAsStopped()
        }
    }

    /**
     * Create new notification for foreground service
     */
    private fun createForegroundInfo(): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                1,
                createNotificationForForegroundService(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(
                1,
                createNotificationForForegroundService(),
            )
        }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createNotificationForForegroundService(): Notification {
        // TODO: Improve notification for foreground service
        return getNotificationBuilder(
            CommonsApplication.NOTIFICATION_CHANNEL_ID_ALL,
        )!!
            .setContentTitle(appContext.getString(R.string.upload_in_progress))
            .build()
    }

    /**
     * Upload the contribution
     * @param contribution
     */
    @SuppressLint("StringFormatInvalid", "CheckResult", "MissingPermission")
    private suspend fun uploadContribution(contribution: Contribution) {
        if (contribution.localUri == null || contribution.localUri.path == null) {
            Timber.e("""upload: ${contribution.media.filename} failed, file path is null""")
        }

        val media = contribution.media
        val displayTitle = contribution.media.displayTitle

        currentNotificationTag = contribution.localUri.toString()
        currentNotificationID =
            (contribution.localUri.toString() + contribution.media.filename).hashCode()

        currentNotification
        getNotificationBuilder(CommonsApplication.NOTIFICATION_CHANNEL_ID_ALL)!!
        currentNotification.setContentTitle(
            appContext.getString(
                R.string.upload_progress_notification_title_start,
                displayTitle,
            ),
        )

        notificationManager?.notify(
            currentNotificationTag,
            currentNotificationID,
            currentNotification.build(),
        )

        val filename = media.filename

        val notificationProgressUpdater =
            NotificationUpdateProgressListener(
                appContext.getString(
                    R.string.upload_progress_notification_title_finishing,
                    displayTitle,
                ),
                contribution,
            )

        try {
            // Upload the file to stash
            val stashUploadResult =
                uploadClient
                    .uploadFileToStash(
                        filename!!,
                        contribution,
                        notificationProgressUpdater,
                    ).onErrorReturn {
                        return@onErrorReturn StashUploadResult(
                            StashUploadState.FAILED,
                            fileKey = null,
                            errorMessage = it.message,
                        )
                    }.blockingSingle()
            when (stashUploadResult.state) {
                StashUploadState.SUCCESS -> {
                    // If the stash upload succeeds
                    Timber.d("Upload to stash success for fileName: $filename")
                    Timber.d("Ensure uniqueness of filename")
                    val uniqueFileName = findUniqueFileName(filename)

                    try {
                        // Upload the file from stash
                        val uploadResult =
                            uploadClient
                                .uploadFileFromStash(
                                    contribution,
                                    uniqueFileName,
                                    stashUploadResult.fileKey,
                                ).onErrorReturn {
                                    return@onErrorReturn null
                                }.blockingSingle()

                        if (null != uploadResult && uploadResult.isSuccessful()) {
                            Timber.d(
                                "Stash Upload success..proceeding to make wikidata edit",
                            )

                            wikidataEditService
                                .addDepictionsAndCaptions(uploadResult, contribution)
                                .blockingSubscribe()
                            if (contribution.wikidataPlace == null) {
                                Timber.d(
                                    "WikiDataEdit not required, upload success",
                                )
                                saveCompletedContribution(contribution, uploadResult)
                            } else {
                                Timber.d(
                                    "WikiDataEdit required, making wikidata edit",
                                )
                                makeWikiDataEdit(uploadResult, contribution)
                            }
                            showSuccessNotification(contribution)
                        } else {
                            Timber.e("Stash Upload failed")
                            showFailedNotification(contribution)
                            contribution.state = Contribution.STATE_FAILED
                            contribution.chunkInfo = null
                            contributionDao.save(contribution).blockingAwait()
                        }
                    } catch (exception: Exception) {
                        Timber.e(exception)
                        Timber.e("Upload from stash failed for contribution : $filename")
                        showFailedNotification(contribution)
                        contribution.state = Contribution.STATE_FAILED
                        contributionDao.saveSynchronous(contribution)
                        if (stashErrorCodes.contains(exception.message)) {
                            clearChunks(contribution)
                        }
                    }
                }
                StashUploadState.PAUSED -> {
                    showPausedNotification(contribution)
                    contribution.state = Contribution.STATE_PAUSED
                    contributionDao.saveSynchronous(contribution)
                }
                StashUploadState.CANCELLED -> {
                    showCancelledNotification(contribution)
                }
                else -> {
                    Timber.e("""upload file to stash failed with status: ${stashUploadResult.state}""")

                    contribution.state = Contribution.STATE_FAILED
                    contribution.chunkInfo = null
                    contribution.errorInfo = stashUploadResult.errorMessage
                    showErrorNotification(contribution)
                    contributionDao.saveSynchronous(contribution)
                    if (stashUploadResult.errorMessage.equals(
                            CsrfTokenClient.INVALID_TOKEN_ERROR_MESSAGE,
                        )
                    ) {
                        Timber.e("Invalid Login, logging out")
                        showInvalidLoginNotification(contribution)
                        val username = sessionManager.userName
                        var logoutListener =
                            CommonsApplication.BaseLogoutListener(
                                appContext,
                                appContext.getString(R.string.invalid_login_message),
                                username,
                            )
                        CommonsApplication
                            .instance
                            .clearApplicationData(appContext, logoutListener)
                    }
                }
            }
        } catch (exception: Exception) {
            Timber.e(exception)
            Timber.e("Stash upload failed for contribution: $filename")
            showFailedNotification(contribution)
            contribution.errorInfo = exception.message
            contribution.state = Contribution.STATE_FAILED
            clearChunks(contribution)
        }
    }

    private fun clearChunks(contribution: Contribution) {
        contribution.chunkInfo = null
        contributionDao.saveSynchronous(contribution)
    }

    /**
     * Make the WikiData Edit, if applicable
     */
    private suspend fun makeWikiDataEdit(
        uploadResult: UploadResult,
        contribution: Contribution,
    ) {
        val wikiDataPlace = contribution.wikidataPlace
        if (wikiDataPlace != null) {
            if (!contribution.hasInvalidLocation()) {
                var revisionID: Long? = null
                val p18WasSkipped = !wikiDataPlace.imageValue.isNullOrBlank()
                try {
                    if (!p18WasSkipped) {
                    // Only set P18 if the place does not already have a picture
                    revisionID =
                        wikidataEditService.createClaim(
                            wikiDataPlace,
                            uploadResult.filename,
                            contribution.media.captions,
                        )
                    if (null != revisionID) {
                        withContext(Dispatchers.IO) {
                            val place = placesRepository.fetchPlace(wikiDataPlace.id)
                            place.name = wikiDataPlace.name
                            place.pic = HOME_URL + uploadResult.createCanonicalFileName()
                            placesRepository
                                .save(place)
                                .subscribeOn(Schedulers.io())
                                .blockingAwait()
                            Timber.d("Updated WikiItem place ${place.name} with image ${place.pic}")
                            }
                        }
                    }
                    // Always show success notification, whether P18 was set or skipped
                    showSuccessNotification(contribution)
                } catch (exception: Exception) {
                    Timber.e(exception)
                }

                withContext(Dispatchers.Main) {
                    wikidataEditService.handleImageClaimResult(
                        contribution.wikidataPlace!!,
                        revisionID,
                        p18WasSkipped = p18WasSkipped
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    wikidataEditService.handleImageClaimResult(
                        contribution.wikidataPlace!!,
                        null,
                    )
                }
            }
        }
        saveCompletedContribution(contribution, uploadResult)
    }

    private fun saveCompletedContribution(
        contribution: Contribution,
        uploadResult: UploadResult,
    ) {
        val contributionFromUpload =
            mediaClient
                .getMedia("File:" + uploadResult.filename)
                .map { media: Media? -> contribution.completeWith(media!!) }
                .blockingGet()
        contributionFromUpload.dateModified = Date()
        contributionDao.deleteAndSaveContribution(contribution, contributionFromUpload)

        // Upload success, save to uploaded status.
        saveIntoUploadedStatus(contribution)
    }

    /**
     * Save to uploadedStatusDao.
     */
    private fun saveIntoUploadedStatus(contribution: Contribution) {
        contribution.contentUri?.let {
            val imageSha1 = contribution.imageSHA1.toString()
            val modifiedSha1 = fileUtilsWrapper.getSHA1(fileUtilsWrapper.getFileInputStream(contribution.localUri?.path))
            CoroutineScope(Dispatchers.IO).launch {
                uploadedStatusDao.insertUploaded(
                    UploadedStatus(
                        imageSha1,
                        modifiedSha1,
                        imageSha1 == modifiedSha1,
                        true,
                    ),
                )
            }
        }
    }

    private fun findUniqueFileName(fileName: String): String {
        var sequenceFileName: String? = fileName
        val random = Random()

        // Loops until sequenceFileName does not match any existing file names
        while (mediaClient
                .checkPageExistsUsingTitle(
                    String.format(
                        "File:%s",
                        sequenceFileName,
                    ),
                ).blockingGet()) {

            // Generate a random 5-character alphanumeric string
            val randomHash = (random.nextInt(90000) + 10000).toString()

            sequenceFileName =
                if (fileName.indexOf('.') == -1) {
                    // Append the random hash in parentheses if no file extension is present
                    "$fileName ($randomHash)"
                } else {
                    val regex =
                        Pattern.compile("^(.*)(\\..+?)$")
                    val regexMatcher = regex.matcher(fileName)
                    // Append the random hash in parentheses before the file extension
                    if (regexMatcher.find()) {
                        "${regexMatcher.group(1)} ($randomHash)${regexMatcher.group(2)}"
                    } else {
                        "$fileName ($randomHash)"
                    }
                }
        }
        return sequenceFileName!!
    }

    /**
     * Notify that the current upload has succeeded
     * @param contribution
     */
    @SuppressLint("StringFormatInvalid", "MissingPermission")
    private fun showSuccessNotification(contribution: Contribution) {
        val displayTitle = contribution.media.displayTitle
        contribution.state = Contribution.STATE_COMPLETED
        currentNotification.setContentIntent(getPendingIntent(MainActivity::class.java))
        currentNotification
            .setContentTitle(
                appContext.getString(
                    R.string.upload_completed_notification_title,
                    displayTitle,
                ),
            ).setContentText(appContext.getString(R.string.upload_completed_notification_text))
            .setProgress(0, 0, false)
            .setOngoing(false)
        notificationManager?.notify(
            currentNotificationTag,
            currentNotificationID,
            currentNotification.build(),
        )
    }

    /**
     * Notify that the current upload has failed
     * @param contribution
     */
    @SuppressLint("StringFormatInvalid", "MissingPermission")
    private fun showFailedNotification(contribution: Contribution) {
        val displayTitle = contribution.media.displayTitle
        currentNotification.setContentIntent(getPendingIntent(UploadProgressActivity::class.java))
        currentNotification
            .setContentTitle(
                appContext.getString(
                    R.string.upload_failed_notification_title,
                    displayTitle,
                ),
            ).setContentText(appContext.getString(R.string.upload_failed_notification_subtitle))
            .setProgress(0, 0, false)
            .setOngoing(false)
        notificationManager?.notify(
            currentNotificationTag,
            currentNotificationID,
            currentNotification.build(),
        )
    }

    @SuppressLint("StringFormatInvalid", "MissingPermission")
    private fun showInvalidLoginNotification(contribution: Contribution) {
        val displayTitle = contribution.media.displayTitle
        currentNotification
            .setContentTitle(
                appContext.getString(
                    R.string.upload_failed_notification_title,
                    displayTitle,
                ),
            ).setContentText(appContext.getString(R.string.invalid_login_message))
            .setProgress(0, 0, false)
            .setOngoing(false)
        notificationManager?.notify(
            currentNotificationTag,
            currentNotificationID,
            currentNotification.build(),
        )
    }

    /**
     * Shows a notification for a failed contribution upload.
     */
    @SuppressLint("StringFormatInvalid", "MissingPermission")
    private fun showErrorNotification(contribution: Contribution) {
        val displayTitle = contribution.media.displayTitle
        currentNotification
            .setContentTitle(
                appContext.getString(
                    R.string.upload_failed_notification_title,
                    displayTitle,
                ),
            ).setContentText(contribution.errorInfo)
            .setProgress(0, 0, false)
            .setOngoing(false)
        notificationManager?.notify(
            currentNotificationTag,
            currentNotificationID,
            currentNotification.build(),
        )
    }

    /**
     * Notify that the current upload is paused
     * @param contribution
     */
    @SuppressLint("MissingPermission")
    private fun showPausedNotification(contribution: Contribution) {
        val displayTitle = contribution.media.displayTitle

        currentNotification.setContentIntent(getPendingIntent(UploadProgressActivity::class.java))
        currentNotification
            .setContentTitle(
                appContext.getString(
                    R.string.upload_paused_notification_title,
                    displayTitle,
                ),
            ).setContentText(appContext.getString(R.string.upload_paused_notification_subtitle))
            .setProgress(0, 0, false)
            .setOngoing(false)
        notificationManager!!.notify(
            currentNotificationTag,
            currentNotificationID,
            currentNotification.build(),
        )
    }

    /**
     * Notify that the current upload is cancelled
     * @param contribution
     */
    @SuppressLint("MissingPermission")
    private fun showCancelledNotification(contribution: Contribution) {
        val displayTitle = contribution.media.displayTitle
        currentNotification.setContentIntent(getPendingIntent(UploadProgressActivity::class.java))
        currentNotification
            .setContentTitle(
                displayTitle,
            ).setContentText("Upload has been cancelled!")
            .setProgress(0, 0, false)
            .setOngoing(false)
        notificationManager!!.notify(
            currentNotificationTag,
            currentNotificationID,
            currentNotification.build(),
        )
    }

    /**
     * Method used to get Pending intent for opening different screen after clicking on notification
     * @param toClass
     */
    private fun getPendingIntent(toClass: Class<out BaseActivity>): PendingIntent {
        val intent = Intent(appContext, toClass)
        return TaskStackBuilder.create(appContext).run {
            addNextIntentWithParentStack(intent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getPendingIntent(
                    0,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
            }
        }
    }
}
